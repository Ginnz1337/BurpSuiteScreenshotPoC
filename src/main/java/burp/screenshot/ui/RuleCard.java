package burp.screenshot.ui;

import burp.screenshot.design.Icons;
import burp.screenshot.design.Theme;
import burp.screenshot.design.Tokens;
import burp.screenshot.engine.Rules;
import burp.screenshot.model.HighlightRule;
import burp.screenshot.model.RedactionRule;
import burp.screenshot.model.ScopeTarget;
import burp.screenshot.ui.components.Buttons;
import burp.screenshot.ui.components.Fields;
import burp.screenshot.ui.components.SegmentedControl;
import burp.screenshot.ui.components.SwitchToggle;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One highlight or redaction rule, as an editable card.
 *
 * <p>Laid out as stacked rows rather than a grid of label/value pairs: the inspector is only
 * about 340px wide, and a two-column grid that fits English labels truncates the Vietnamese
 * ones. Each row keeps one control, so nothing has to shrink to fit.
 */
public class RuleCard extends JPanel {

    /** Callbacks into the owning list. */
    public interface Listener {
        void changed();

        void deleted();
    }

    private static final Color FALLBACK_HIGHLIGHT = new Color(0xf5be28);

    /** The two redaction styles, as the picker writes them. See {@link RedactionRule}. */
    public static final String BLUR = "Blur";
    public static final String HIDE = "Hide";

    private final Listener listener;
    private final JTextField patternField;
    private final JTextField nameField;

    private HighlightRule highlight;
    private RedactionRule redaction;

    private RuleCard(Listener listener) {
        super();
        this.listener = listener;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(true);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.tokens().border),
                BorderFactory.createEmptyBorder(Tokens.SM, Tokens.SM, Tokens.SM, Tokens.SM)));

        // The name is the first thing on the card and the pattern the second, so a list of rules
        // reads as a list of names. Scanning the patterns instead means reading a regex per card
        // to find the one for the session cookie.
        nameField = Fields.text("", "Name this rule");
        nameField.setToolTipText("Optional. Shown at the top of the card, so the list can be "
                + "read without opening a pattern.");
        nameField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onNameEdited(); }
            @Override public void removeUpdate(DocumentEvent e) { onNameEdited(); }
            @Override public void changedUpdate(DocumentEvent e) { onNameEdited(); }
        });

        patternField = Fields.text("", "Regex or literal, e.g. Bearer\\s+([A-Za-z0-9._-]+)");
        patternField.setToolTipText("Pattern to find. Regex is used when the rule has it enabled.");
        patternField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onPatternEdited(); }
            @Override public void removeUpdate(DocumentEvent e) { onPatternEdited(); }
            @Override public void changedUpdate(DocumentEvent e) { onPatternEdited(); }
        });
    }

    // ------------------------------------------------------------------ factories

    public static RuleCard forHighlight(HighlightRule rule, Listener listener) {
        RuleCard card = new RuleCard(listener);
        card.highlight = rule;
        card.buildHighlight(rule);
        return card;
    }

    public static RuleCard forRedaction(RedactionRule rule, Listener listener) {
        RuleCard card = new RuleCard(listener);
        card.redaction = rule;
        card.buildRedaction(rule);
        return card;
    }

    /**
     * Keeps the card at its natural height inside the inspector's vertical box.
     *
     * <p>A Swing container reports an unbounded maximum size, and a {@code BoxLayout} grows its
     * children up to that maximum; without this the last card in a list would absorb all the
     * slack and stretch to the bottom of the panel.
     */
    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    // ------------------------------------------------------------------ build

    private void buildHighlight(HighlightRule rule) {
        SwitchToggle enabled = new SwitchToggle(rule.isEnabled(), on -> {
            rule.setEnabled(on);
            listener.changed();
        });

        nameField.setText(rule.getName());
        add(headerRow(enabled));

        patternField.setText(rule.getPattern());
        add(patternRow());

        add(gap());

        SwitchToggle regex = new SwitchToggle(rule.isRegex(), on -> {
            rule.setRegex(on);
            validatePattern();
            listener.changed();
        });
        add(pairedRow(Fields.muted("Regex"), regex,
                Fields.muted("Scope"), scopeControl(rule.getTarget(), t -> {
                    rule.setTarget(t);
                    listener.changed();
                })));

        add(gap());

        SwatchButton swatch = new SwatchButton(Rules.parseColor(rule.getColorHex(), FALLBACK_HIGHLIGHT));
        swatch.addActionListener(e -> {
            Color chosen = JColorChooser.showDialog(SwingUtilities.getWindowAncestor(this),
                    "Highlight color", Rules.parseColor(rule.getColorHex(), FALLBACK_HIGHLIGHT));
            if (chosen == null) return;
            rule.setColorHex(Tokens.toHex(chosen));
            swatch.setColor(chosen);
            listener.changed();
        });
        add(pairedRow(Fields.muted("Color"), swatch, null, null));

        validatePattern();
    }

    private void buildRedaction(RedactionRule rule) {
        SwitchToggle enabled = new SwitchToggle(rule.isEnabled(), on -> {
            rule.setEnabled(on);
            listener.changed();
        });

        nameField.setText(rule.getName());
        add(headerRow(enabled));

        patternField.setText(rule.getPattern());
        add(patternRow());

        add(gap());

        SwitchToggle regex = new SwitchToggle(rule.isRegex(), on -> {
            rule.setRegex(on);
            validatePattern();
            listener.changed();
        });
        add(pairedRow(Fields.muted("Regex"), regex,
                Fields.muted("Scope"), scopeControl(rule.getTarget(), t -> {
                    rule.setTarget(t);
                    listener.changed();
                })));

        add(gap());

        JSpinner group = new JSpinner(new SpinnerNumberModel(rule.getCaptureGroup(), 0, 9, 1));
        group.setFont(Theme.tokens().ui);
        group.setPreferredSize(new Dimension(58, 24));
        group.setMaximumSize(new Dimension(58, 24));
        group.setToolTipText("0 = the whole match, 1..9 = a regex capture group");
        group.addChangeListener(e -> {
            rule.setCaptureGroup(((Number) group.getValue()).intValue());
            listener.changed();
        });

        // The card says what the rule does rather than leaving it to be remembered, and it is
        // where a rule made from the right-click menu turns up: without a control here, a hidden
        // rule would read as "Blur" and there would be no way to tell them apart or change one.
        SegmentedControl<String> style = new SegmentedControl<>(
                List.of(BLUR, HIDE), s -> s, rule.isHide() ? HIDE : BLUR, chosen -> {
                    rule.setHide(HIDE.equals(chosen));
                    listener.changed();
                });
        style.setToolTipText("Blur dims the value, hide takes it out and leaves a marker.");
        add(pairedRow(Fields.muted("Group"), group, Fields.muted("Style"), style));

        validatePattern();
    }

    /** The name, the on/off switch and the delete button: what the rule is called and whether
     * it is on. The pattern gets the row below to itself, where it has the width to be read. */
    private JComponent headerRow(SwitchToggle enabled) {
        JButton delete = Buttons.iconOnly(Icons.trash(null, 13), "Delete this rule");
        delete.addActionListener(e -> listener.deleted());

        JPanel row = new JPanel(new BorderLayout(Tokens.SM, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(enabled, BorderLayout.WEST);
        row.add(nameField, BorderLayout.CENTER);
        row.add(delete, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        return row;
    }

    private JComponent patternRow() {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(patternField, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        return row;
    }

    /** Two label/control pairs on one line, used where both sides are small. */
    private JComponent pairedRow(JComponent leftLabel, JComponent leftControl,
                                 JComponent rightLabel, JComponent rightControl) {
        JPanel row = new JPanel(new BorderLayout(Tokens.SM, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        if (leftLabel != null && leftControl != null) {
            row.add(inlinePair(leftLabel, leftControl), BorderLayout.WEST);
        }
        if (rightLabel != null && rightControl != null) {
            row.add(inlinePair(rightLabel, rightControl), BorderLayout.EAST);
        }

        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        return row;
    }

    private JComponent inlinePair(JComponent label, JComponent control) {
        JPanel p = new JPanel(new BorderLayout(Tokens.XS, 0));
        p.setOpaque(false);
        p.add(label, BorderLayout.WEST);
        p.add(control, BorderLayout.EAST);
        return p;
    }

    private SegmentedControl<ScopeTarget> scopeControl(ScopeTarget current,
                                                       Consumer<ScopeTarget> onSelect) {
        return new SegmentedControl<>(
                List.of(ScopeTarget.REQUEST, ScopeTarget.RESPONSE, ScopeTarget.BOTH),
                RuleCard::shortScope, current, onSelect);
    }

    private static String shortScope(ScopeTarget t) {
        switch (t) {
            case REQUEST: return "Req";
            case RESPONSE: return "Res";
            default: return "Both";
        }
    }

    private JComponent gap() {
        return (JComponent) Box.createVerticalStrut(Tokens.XS);
    }

    // ------------------------------------------------------------------ validation

    private void onNameEdited() {
        String name = nameField.getText();
        if (highlight != null) highlight.setName(name);
        if (redaction != null) redaction.setName(name);
        listener.changed();
    }

    private void onPatternEdited() {
        String pattern = patternField.getText();
        if (highlight != null) highlight.setPattern(pattern);
        if (redaction != null) redaction.setPattern(pattern);
        validatePattern();
        listener.changed();
    }

    /**
     * Flags a regex that will not compile.
     *
     * <p>The renderer silently drops an unparsable rule, so without this the user would see a
     * rule that simply never matches and no reason why.
     */
    private void validatePattern() {
        boolean regex = highlight != null ? highlight.isRegex() : redaction.isRegex();
        String pattern = patternField.getText();
        boolean bad = regex && pattern != null && !pattern.isEmpty() && !compiles(pattern);

        patternField.setToolTipText(bad
                ? "Invalid regex, this rule is skipped when rendering"
                : "What to look for. Turn on Regex to use a regular expression.");
        Fields.markInvalid(patternField, bad);
    }

    private static boolean compiles(String pattern) {
        try {
            Pattern.compile(pattern);
            return true;
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ swatch

    /** A clickable colour chip that shows the highlight's current colour. */
    private static final class SwatchButton extends JButton {

        private Color color;

        SwatchButton(Color color) {
            this.color = color;
            setPreferredSize(new Dimension(46, 22));
            setMaximumSize(new Dimension(46, 22));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Click to change the color");
        }

        void setColor(Color c) {
            this.color = c;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), Tokens.R_SM, Tokens.R_SM);
            g2.setColor(Theme.tokens().borderStrong);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tokens.R_SM, Tokens.R_SM);
            g2.dispose();
        }
    }

    /**
     * Preset rules offered by the inspector, so a common case is one click away.
     *
     * <p>Each entry carries a {@code highlight} flag: the two groups are not interchangeable.
     * A SQLi payload is worth pointing at, a bearer token is worth hiding, and offering them
     * in one flat list would let the user redact the very thing they meant to emphasise.
     */
    public enum Preset {
        // Redaction: values that must not leave the machine. Every one of these blurs, which is
        // the style a credential wants: the reader sees that a token was there without reading
        // it. A preset that hid instead would be a header name with an unexplained gap after it,
        // so the style stays changeable on the card rather than baked into the preset.
        //
        // The header presets capture only the value and leave the name on screen. A reader of the
        // PoC still needs to see that the exchange carried a Cookie or an Authorization; it is the
        // secret beside it that has to go.
        AUTHORIZATION("Authorization header", "(?i)^(Proxy-)?Authorization:\\s*(.+)$",
                false, 2, null),
        COOKIE("Cookie", "(?i)^Cookie:\\s*(.+)$", false, 1, null),
        SET_COOKIE("Set-Cookie", "(?i)^Set-Cookie:\\s*(.+)$", false, 1, null),
        PASSWORD("password", "(?i)(password|passwd|pwd)[\"']?\\s*[:=]\\s*[\"']?([^\"'&;\\s]+)",
                false, 2, null),
        API_KEY("api_key", "(?i)(api[_-]?key|apikey|access[_-]?token)[\"']?\\s*[:=]\\s*[\"']?([^\"'&;\\s]+)",
                false, 2, null),
        SESSION("session_id", "(?i)session[_-]?id[\"']?\\s*[:=]\\s*[\"']?([^\"'&;\\s]+)",
                false, 1, null),
        BEARER("Bearer token", "Bearer\\s+[A-Za-z0-9._~+/-]+=*", false, 0, null),
        JWT("JWT", "(?i)\\b(eyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)",
                false, 1, null),

        // Highlights: payloads worth pointing at, not hiding.
        SQLI("SQLi", "(?i)(union\\s+select|or\\s+1=1|'\\s*or\\s*'|sleep\\s*\\(|benchmark\\s*\\()",
                true, 0, "#f5be28"),
        XSS("XSS", "(?i)(<script|javascript:|onerror\\s*=|onload\\s*=)",
                true, 0, "#f5be28"),
        ADMIN("Admin", "(?i)\\b(superadmin|administrator|is_?admin)\\b",
                true, 0, "#f5be28");

        private final String label;
        private final String pattern;
        private final boolean highlight;
        private final int captureGroup;
        private final String colorHex;

        Preset(String label, String pattern, boolean highlight, int captureGroup, String colorHex) {
            this.label = label;
            this.pattern = pattern;
            this.highlight = highlight;
            this.captureGroup = captureGroup;
            this.colorHex = colorHex;
        }

        public String label() { return label; }
        public String pattern() { return pattern; }
        /** True for a highlight preset, false for a redaction preset. */
        public boolean isHighlight() { return highlight; }
        /** 0 means the whole match; the renderer falls back to 0 for an out-of-range group. */
        public int captureGroup() { return captureGroup; }
        /** Only meaningful when {@link #isHighlight()} is true. */
        public String colorHex() { return colorHex; }

        @Override public String toString() { return label; }
    }
}
