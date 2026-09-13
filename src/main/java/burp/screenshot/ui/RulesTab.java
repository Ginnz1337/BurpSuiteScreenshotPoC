package burp.screenshot.ui;

import burp.screenshot.design.Icons;
import burp.screenshot.design.Theme;
import burp.screenshot.design.Tokens;
import burp.screenshot.model.HighlightRule;
import burp.screenshot.model.RedactionRule;
import burp.screenshot.model.ScopeTarget;
import burp.screenshot.model.TemplateConfig;
import burp.screenshot.ui.components.Buttons;
import burp.screenshot.ui.components.Fields;
import burp.screenshot.ui.components.SlimScrollBarUI;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;

/**
 * The Rules tab: the highlight and redaction rules, and the ways to add one.
 *
 * <p>A class of its own rather than methods of {@link SettingsDialog}, for the reason
 * {@link HeadersTab} is: a dialog cannot be built without a display, and this is the tab whose
 * layout has to be measured. Extracted, the verification run can build it, lay it out at the
 * width the dialog can actually be, and read where every control ended up.
 *
 * <p>The toolbar is three controls, not four. {@code Add highlight} and {@code Add redaction}
 * were separate buttons and the second was laid out past the right edge of the tab, so the only
 * way to add a rule by hand was off screen. One {@code Add custom rule} button opens a menu with
 * both kinds on it, which is shorter than either pair of labels and says what the button does.
 */
public final class RulesTab extends JPanel {

    private final TemplateConfig config;

    /** Called while the user types: the apply is debounced, because this fires per keystroke. */
    private final Runnable schedule;

    /** Called when a control that commits in one step changed something. */
    private final Runnable apply;

    private final JPanel ruleList = new JPanel();

    /**
     * The line above the toolbar. It explains the presets until something is added, and says what
     * happened afterwards.
     *
     * <p>One label doing both jobs rather than a second status line: the tab has no width to
     * spare for both, and the outcome of pressing a button is read where the button is.
     */
    private final JLabel status = Fields.muted(HINT);

    public RulesTab(TemplateConfig config, Runnable schedule, Runnable apply) {
        super(new BorderLayout());
        this.config = config;
        this.schedule = schedule;
        this.apply = apply;

        setOpaque(true);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(true);
        top.setBorder(BorderFactory.createEmptyBorder(Tokens.SM, Tokens.SM, 0, Tokens.SM));
        top.add(caption(status));
        top.add(Box.createVerticalStrut(Tokens.SM));
        top.add(buildToolbar());

        status.setToolTipText(HINT_DETAIL);

        add(top, BorderLayout.NORTH);
        add(buildList(), BorderLayout.CENTER);

        rebuildRules();
    }

    // ------------------------------------------------------------------ status

    /** What the line above the toolbar says, so a check can read the outcome of a press. */
    public String statusText() {
        return status.getText();
    }

    private void say(String message) {
        status.setText(message);
    }

    private void sayHint() {
        say(HINT);
    }

    /**
     * Whether a rule matching this pattern and scope is already in the list.
     *
     * <p>The whole point of the list is to be searched by pattern, and a pattern added twice
     * makes that search return two identical rows with no way to tell which one is in force.
     * Adding is refused rather than the duplicate dropped afterwards, so the user is told at the
     * moment they press the button.
     */
    private boolean alreadyThere(String pattern, ScopeTarget scope, boolean highlight) {
        if (highlight) {
            for (HighlightRule h : config.getHighlights()) {
                if (scope == h.getTarget() && pattern.equals(h.getPattern())) return true;
            }
            return false;
        }
        for (RedactionRule r : config.getRedactions()) {
            if (scope == r.getTarget() && pattern.equals(r.getPattern())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ header

    /**
     * What the presets do, in one line.
     *
     * <p>The names of the presets are not a sentence, so the list has to say which of them blur
     * and which highlight. Short enough to fit the tab at its narrowest, with the full list in
     * the tooltip: a caption wider than the panel is clipped rather than wrapped.
     */
    private static final String HINT = "Presets apply a ready rule in one click. "
            + "Credentials blur, payloads highlight.";

    private static final String HINT_DETAIL = "The redaction presets are Authorization, Cookie, "
            + "Set-Cookie, password, api_key, session and JWT; each blurs the value and leaves the "
            + "header name on screen. The highlight presets are SQLi, XSS and Admin.";

    private JComponent buildToolbar() {
        JComboBox<RuleCard.Preset> presets = Fields.combo(RuleCard.Preset.values());
        presets.setPreferredSize(new Dimension(200, 26));
        presets.setMaximumSize(new Dimension(200, 26));

        JButton addPreset = Buttons.secondary("Add preset", Icons.plus(null, 14));
        addPreset.setToolTipText("Add the rule selected in the list, ready to use.");
        addPreset.addActionListener(e -> {
            RuleCard.Preset preset = (RuleCard.Preset) presets.getSelectedItem();
            if (preset != null) addPreset(preset);
        });

        JButton addCustom = Buttons.secondary("Add custom rule", Icons.plus(null, 14));
        addCustom.setToolTipText("Add a rule with an empty pattern and type it yourself.");
        addCustom.addActionListener(e -> buildCustomMenu().show(addCustom, 0, addCustom.getHeight()));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SM, 0));
        toolbar.setOpaque(false);
        toolbar.setAlignmentX(Component.LEFT_ALIGNMENT);
        toolbar.add(presets);
        toolbar.add(addPreset);
        toolbar.add(addCustom);
        return toolbar;
    }

    /**
     * The two kinds of rule, offered in one place rather than as two buttons.
     *
     * <p>Public, and built fresh per call, because a display-less JVM cannot open a popup: this
     * is the seam the verification run uses to reach the entries without showing them.
     */
    public JPopupMenu buildCustomMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.setFont(Theme.tokens().ui);

        JMenuItem highlight = new JMenuItem("Highlight rule", Icons.palette(null, 14));
        highlight.setFont(Theme.tokens().ui);
        highlight.addActionListener(e -> addCustom(true));
        menu.add(highlight);

        JMenuItem redaction = new JMenuItem("Blur or hide rule", Icons.trash(null, 14));
        redaction.setFont(Theme.tokens().ui);
        redaction.addActionListener(e -> addCustom(false));
        menu.add(redaction);
        return menu;
    }

    /** An empty rule, ready to type into. Also the case the duplicate guard exists for: pressing
     * the button twice used to leave two blank rows and no way to tell them apart. */
    private void addCustom(boolean highlight) {
        if (alreadyThere("", ScopeTarget.BOTH, highlight)) {
            say("A blank " + (highlight ? "highlight" : "redaction")
                    + " rule is already in the list. Use it, or delete it first.");
            return;
        }
        if (highlight) {
            config.getHighlights().add(new HighlightRule("",
                    true, ScopeTarget.BOTH, TemplateConfig.DEFAULT_HIGHLIGHT));
        } else {
            config.getRedactions().add(new RedactionRule("", true, ScopeTarget.BOTH, 0));
        }
        rebuildRules();
        apply.run();
        sayHint();
    }

    private void addPreset(RuleCard.Preset preset) {
        if (alreadyThere(preset.pattern(), ScopeTarget.BOTH, preset.isHighlight())) {
            say(preset.label() + " is already in the list.");
            return;
        }
        if (preset.isHighlight()) {
            config.getHighlights().add(new HighlightRule(preset.pattern(),
                    true, ScopeTarget.BOTH, preset.colorHex()));
        } else {
            RedactionRule rule = new RedactionRule(preset.pattern(),
                    true, ScopeTarget.BOTH, preset.captureGroup());
            // A preset arrives with a pattern the user did not write, so it arrives with the
            // name it was listed under. That is the name they will look for in the list.
            rule.setName(preset.label());
            config.getRedactions().add(rule);
        }
        rebuildRules();
        apply.run();
        sayHint();
    }

    // ------------------------------------------------------------------ the list

    private JComponent buildList() {
        ruleList.setLayout(new BoxLayout(ruleList, BoxLayout.Y_AXIS));
        ruleList.setOpaque(true);
        ruleList.setBorder(BorderFactory.createEmptyBorder(Tokens.SM, Tokens.SM, Tokens.SM, Tokens.SM));

        JPanel content = new JPanel(new BorderLayout());
        content.setOpaque(false);
        content.add(ruleList, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        SlimScrollBarUI.install(scroll);
        return scroll;
    }

    /** Rebuilds the rule column from the config, after an add or a delete. */
    private void rebuildRules() {
        ruleList.removeAll();

        for (HighlightRule rule : config.getHighlights()) {
            ruleList.add(RuleCard.forHighlight(rule, new RuleCard.Listener() {
                @Override public void changed() { schedule.run(); }
                @Override public void deleted() {
                    config.getHighlights().remove(rule);
                    rebuildRules();
                    apply.run();
                }
            }));
            ruleList.add(Box.createVerticalStrut(Tokens.SM));
        }

        for (RedactionRule rule : config.getRedactions()) {
            ruleList.add(RuleCard.forRedaction(rule, new RuleCard.Listener() {
                @Override public void changed() { schedule.run(); }
                @Override public void deleted() {
                    config.getRedactions().remove(rule);
                    rebuildRules();
                    apply.run();
                }
            }));
            ruleList.add(Box.createVerticalStrut(Tokens.SM));
        }

        if (config.getHighlights().isEmpty() && config.getRedactions().isEmpty()) {
            ruleList.add(caption(Fields.muted("No rules. Right-click a token in the "
                    + "ScreenshotPoC tab to make one, or add a preset above.")));
        }

        ruleList.revalidate();
        ruleList.repaint();
    }

    // ------------------------------------------------------------------ plumbing

    private JComponent caption(JLabel label) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(label, BorderLayout.WEST);
        return row;
    }

    /** The ground the dialog resolves, so the tab does not paint Burp's panel colour. */
    @Override
    public java.awt.Color getBackground() { return Theme.tokens().bgPanel; }
}
