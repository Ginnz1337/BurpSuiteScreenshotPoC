package burp.screenshot.ui;

import burp.screenshot.design.Icons;
import burp.screenshot.design.Theme;
import burp.screenshot.design.TokenType;
import burp.screenshot.design.Tokens;
import burp.screenshot.engine.TextProcessor;
import burp.screenshot.model.HighlightRule;
import burp.screenshot.model.RedactionRule;
import burp.screenshot.model.ScopeTarget;
import burp.screenshot.model.TemplateConfig;

import javax.swing.Icon;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * The right-click menu of the Screenshot PoC.
 *
 * <p>This is where a rule is made: select a token, pick a color or a redaction, and the view
 * redraws with it. It exists because the studio is gone, so a rule can no longer be typed into
 * a form and there has to be a way back out: every entry that adds a rule has a matching entry
 * that removes it.
 *
 * <p>Rules added here are literals, not regexes. The user pointed at a value and meant that
 * value; turning it into a pattern would need escaping and would quietly match more than what
 * was selected.
 */
public final class MessageRuleMenu {

    /** Colors offered for a highlight, the default first. */
    private static final String[][] COLORS = {
            {"#e5c07b", "Yellow"},
            {"#7dcfff", "Cyan"},
            {"#9ece6a", "Green"},
            {"#f7768e", "Red"},
            {"#bb9af7", "Purple"},
    };

    /** What the menu acts on. */
    public record Target(String text, TokenType type, String headerName, boolean isRequest) {
    }

    private MessageRuleMenu() {}

    /**
     * Builds the menu for a target.
     *
     * @param onApplied run after the menu changed the config, so the view can redraw and the
     *                  host can persist the template
     */
    public static JPopupMenu build(TemplateConfig config, Target target, Runnable onApplied) {
        JPopupMenu menu = new JPopupMenu();
        Font ui = Theme.tokens().ui;
        menu.setFont(ui);

        ScopeTarget scope = target.isRequest() ? ScopeTarget.REQUEST : ScopeTarget.RESPONSE;
        String text = target.text();
        String preview = text.length() > 28 ? text.substring(0, 28) + "..." : text;

        // A rule is matched against one line at a time, so a selection that spans several lines
        // can never match anything. Every entry here is disabled rather than offered and quietly
        // doing nothing, which is the failure the gutter menu exists to avoid: a drag that ends
        // past the end of a line is a normal thing to do, and the answer is to take those lines
        // out by number instead.
        boolean oneLine = text.indexOf('\n') < 0 && text.indexOf('\r') < 0;
        String spanning = "A rule matches within one line. Select inside a line, "
                + "or right-click the line numbers to remove whole lines.";
        boolean usable = !text.isEmpty() && oneLine;

        JMenu highlight = new JMenu("Highlight");
        highlight.setFont(ui);
        highlight.setIcon(Icons.palette(null, 14));
        for (String[] entry : COLORS) {
            String hex = entry[0];
            JMenuItem item = new JMenuItem(entry[1], Icons.swatch(Tokens.hex(hex), 12));
            item.setFont(ui);
            item.addActionListener(e -> {
                if (addHighlight(config, text, scope, hex)) onApplied.run();
            });
            highlight.add(item);
        }
        highlight.setEnabled(usable);
        if (!oneLine) highlight.setToolTipText(spanning);
        menu.add(highlight);

        JMenuItem blur = new JMenuItem("Blur \"" + preview + "\"");
        blur.setFont(ui);
        blur.setToolTipText(oneLine
                ? "Dims the value. The characters stay in the message."
                : spanning);
        blur.addActionListener(e -> {
            if (addRedaction(config, text, scope, false)) onApplied.run();
        });
        blur.setEnabled(usable && !hasRedaction(config, text, scope, false));
        menu.add(blur);

        // The count is what makes this entry usable on a payload inside a very long line: the
        // preview is 28 characters of what may be thousands, and "did I catch the whole thing"
        // is the question a selection that long raises.
        JMenuItem hideValue = new JMenuItem("Hide \"" + preview + "\"" + lengthSuffix(text));
        hideValue.setFont(ui);
        hideValue.setToolTipText(oneLine
                ? "Takes the value out of the message and leaves a marker. "
                        + "Unlike blur, the characters are gone."
                : spanning);
        hideValue.addActionListener(e -> {
            if (addRedaction(config, text, scope, true)) onApplied.run();
        });
        hideValue.setEnabled(usable && !hasRedaction(config, text, scope, true));
        menu.add(hideValue);

        if (target.headerName() != null) {
            menu.addSeparator();
            JMenuItem hide = new JMenuItem("Hide header \"" + target.headerName() + "\"");
            hide.setFont(ui);
            hide.addActionListener(e -> {
                config.addHeaderToHide(target.headerName());
                onApplied.run();
            });
            menu.add(hide);
        }

        List<Runnable> removers = removersFor(config, text, scope);
        if (!removers.isEmpty()) {
            menu.addSeparator();
            JMenuItem remove = new JMenuItem(removers.size() == 1
                    ? "Remove the rule for \"" + preview + "\""
                    : "Remove " + removers.size() + " rules for \"" + preview + "\"");
            remove.setFont(ui);
            remove.setIcon(Icons.trash(null, 14));
            remove.addActionListener(e -> {
                for (Runnable remover : removers) remover.run();
                onApplied.run();
            });
            menu.add(remove);
        }

        return menu;
    }

    // ------------------------------------------------------------------ edits

    private static boolean addHighlight(TemplateConfig config, String text, ScopeTarget scope, String hex) {
        if (text.isEmpty()) return false;
        for (HighlightRule h : config.getHighlights()) {
            if (h.getPattern().equals(text) && h.getTarget() == scope && h.isEnabled()) return false;
        }
        // Added at the end and enabled: the user just asked for it, so it has to be visible.
        config.getHighlights().add(new HighlightRule(text, false, scope, hex));
        return true;
    }

    /** How many characters the selection was, once the preview has stopped showing all of it. */
    private static String lengthSuffix(String text) {
        return text.length() > 28 ? "  (" + text.length() + " chars)" : "";
    }

    /**
     * Blurs or hides one value. A value carries one redaction per style, so a repeat adds nothing.
     *
     * <p>The two styles are counted apart. Blurring a value the user then decides to hide is a
     * decision either way, and a single guard would leave the second entry greyed out.
     */
    private static boolean addRedaction(TemplateConfig config, String text, ScopeTarget scope,
                                        boolean hide) {
        if (text.isEmpty()) return false;
        if (hasRedaction(config, text, scope, hide)) return false;
        config.getRedactions().add(new RedactionRule(text, false, scope, 0, hide));
        return true;
    }

    private static boolean hasRedaction(TemplateConfig config, String text, ScopeTarget scope,
                                        boolean hide) {
        for (RedactionRule r : config.getRedactions()) {
            if (r.getPattern().equals(text) && r.getTarget() == scope && r.isEnabled()
                    && r.isHide() == hide) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the menu for a run of line numbers picked in the gutter.
     *
     * <p>One entry. A hide entry sat beside it and was dropped, then the feature behind it went
     * too: the two did nearly the same thing from the same selection, and the one that leaves an
     * {@code [N lines omitted]} marker is the one that says what happened to the PoC. A line that
     * vanishes with the numbering closing over it reads as a message that never had it.
     */
    public static JPopupMenu buildForLines(TemplateConfig config, boolean isRequest,
                                           int first, int last, Runnable onApplied) {
        JPopupMenu menu = new JPopupMenu();
        menu.setFont(Theme.tokens().ui);
        menu.add(lineItem("Remove", "removed", "from the PoC", Icons.trash(null, 14),
                config, isRequest, first, last,
                isRequest ? config.getRequestLineRanges() : config.getResponseLineRanges(),
                onApplied));
        return menu;
    }

    /**
     * One entry: the action when the span is still there, a refusal when it is already gone.
     *
     * <p>The refusal is the whole count rather than the two ends. A field like {@code 17-19,22-24}
     * covers both ends of {@code 17-24} but not its middle, and offering an action for a span that
     * is half gone would quietly do nothing to the half that is left.
     */
    private static JMenuItem lineItem(String verb, String done, String suffix, Icon icon,
                                      TemplateConfig config, boolean isRequest,
                                      int first, int last, String field, Runnable onApplied) {
        String span = first == last ? String.valueOf(first) : first + "-" + last;
        String noun = first == last ? " line " : " lines ";
        boolean alreadyGone = TextProcessor.removedBetween(field, first, last) == last - first + 1;

        JMenuItem item = new JMenuItem(alreadyGone
                ? "Lines " + span + " already " + done
                : verb + noun + span + (suffix.isEmpty() ? "" : " " + suffix));
        item.setFont(Theme.tokens().ui);
        item.setIcon(icon);
        item.setEnabled(!alreadyGone);
        item.addActionListener(e -> {
            if (appendSpan(config, isRequest, span)) onApplied.run();
        });
        return item;
    }

    /**
     * Appends a span to the field for that side.
     *
     * <p>{@code all} is replaced rather than appended to. It means "nothing here", and
     * {@code all,17-24} is not a field anything can read.
     */
    private static boolean appendSpan(TemplateConfig config, boolean isRequest, String span) {
        String current = isRequest ? config.getRequestLineRanges() : config.getResponseLineRanges();
        String trimmed = current == null ? "" : current.trim();
        String next = trimmed.isEmpty() || "all".equalsIgnoreCase(trimmed)
                ? span
                : trimmed + "," + span;

        if (isRequest) config.setRequestLineRanges(next);
        else config.setResponseLineRanges(next);
        return true;
    }

    /**
     * The edits that would undo this target.
     *
     * <p>Matched on the stored pattern rather than on compiled matches: a rule added from this
     * menu carries the exact text that was selected, so an exact comparison finds it and never
     * removes a rule the user wrote by hand and meant to keep.
     */
    private static List<Runnable> removersFor(TemplateConfig config, String text, ScopeTarget scope) {
        List<Runnable> out = new ArrayList<>();
        if (text.isEmpty()) return out;

        for (HighlightRule h : List.copyOf(config.getHighlights())) {
            if (h.getPattern().equals(text) && h.getTarget() == scope) {
                out.add(() -> config.getHighlights().remove(h));
            }
        }
        for (RedactionRule r : List.copyOf(config.getRedactions())) {
            if (r.getPattern().equals(text) && r.getTarget() == scope) {
                out.add(() -> config.getRedactions().remove(r));
            }
        }
        return out;
    }

    /** The colors on offer, so a test can check the default is among them. */
    public static List<String> offeredColors() {
        List<String> out = new ArrayList<>();
        for (String[] entry : COLORS) out.add(entry[0]);
        return out;
    }
}
