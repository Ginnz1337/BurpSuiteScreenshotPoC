package burp.screenshot.ui;

import burp.screenshot.design.Icons;
import burp.screenshot.design.SyntaxPalette;
import burp.screenshot.design.Theme;
import burp.screenshot.design.TokenType;
import burp.screenshot.design.Tokens;
import burp.screenshot.engine.SyntaxHighlighter;
import burp.screenshot.model.TemplateConfig;
import burp.screenshot.ui.components.AccordionSection;
import burp.screenshot.ui.components.Buttons;
import burp.screenshot.ui.components.Fields;
import burp.screenshot.ui.components.SlimScrollBarUI;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;

/**
 * Lets the user recolor any part of the message without rebuilding the extension.
 *
 * <p>The colors shipped in {@link SyntaxPalette} were read off Burp Repeater screenshots, so
 * they carry some error. This panel is the correction mechanism: every token shows its
 * current swatch and hex, and a click replaces it.
 *
 * <p>Overrides live in {@code TemplateConfig.syntaxColors} as hex strings and apply to both
 * themes. The built-in palettes remain the source for whichever theme is active, so a
 * template with no overrides still follows a theme switch.
 */
public class SyntaxColorEditor extends JPanel {

    private final JPanel grid = new JPanel(new GridLayout(0, 1, 0, 2));
    private final Preview preview = new Preview();
    private final JLabel overrideCount = Fields.muted("");
    private final JButton resetAll = Buttons.secondary("Reset all", Icons.refresh(null, 14));

    private TemplateConfig config;
    private Runnable onChange;
    private AutoCloseable themeHandle;

    public SyntaxColorEditor() {
        super(new BorderLayout(0, Tokens.SM));
        setOpaque(true);

        grid.setOpaque(false);
        resetAll.addActionListener(e -> resetAll());

        JPanel scrollContent = new JPanel(new BorderLayout());
        scrollContent.setOpaque(false);
        scrollContent.add(grid, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(scrollContent);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        SlimScrollBarUI.install(scroll);

        add(buildHeader(), BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout(0, Tokens.SM));
        header.setOpaque(false);

        JPanel actions = new JPanel(new BorderLayout(Tokens.SM, 0));
        actions.setOpaque(false);
        actions.add(overrideCount, BorderLayout.CENTER);
        actions.add(resetAll, BorderLayout.EAST);

        header.add(preview, BorderLayout.NORTH);
        header.add(AccordionSection.caption("Colors apply to both themes. "
                + "Click a swatch to change it, click the arrow to reset one entry."), BorderLayout.CENTER);
        header.add(actions, BorderLayout.SOUTH);
        return header;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (themeHandle == null) {
            // The swatches show the effective color, which depends on the active theme.
            themeHandle = Theme.addListener(() -> SwingUtilities.invokeLater(this::reload));
        }
    }

    @Override
    public void removeNotify() {
        if (themeHandle != null) {
            try {
                themeHandle.close();
            } catch (Exception ignored) {
                // Already removed.
            }
            themeHandle = null;
        }
        super.removeNotify();
    }

    // ------------------------------------------------------------------ api

    public void setConfig(TemplateConfig value) {
        this.config = value;
        reload();
    }

    public void setOnChange(Runnable r) {
        this.onChange = r;
    }

    // ------------------------------------------------------------------ state

    /** Built-in colors for the active theme, with the template's overrides on top. */
    private SyntaxPalette effectivePalette() {
        SyntaxPalette p = Theme.isDark() ? SyntaxPalette.DARK.copy() : SyntaxPalette.LIGHT.copy();
        if (config != null) p.applyHexMap(config.getSyntaxColors());
        return p;
    }

    private boolean isOverridden(TokenType t) {
        return config != null && config.getSyntaxColors().containsKey(t.name());
    }

    private void setColor(TokenType type, Color color) {
        if (config == null || color == null) return;
        config.getSyntaxColors().put(type.name(), Tokens.toHex(color));
        reload();
        fireChange();
    }

    private void revert(TokenType type) {
        if (config == null) return;
        if (config.getSyntaxColors().remove(type.name()) == null) return;
        reload();
        fireChange();
    }

    private void resetAll() {
        if (config == null || config.getSyntaxColors().isEmpty()) return;
        config.getSyntaxColors().clear();
        reload();
        fireChange();
    }

    private void fireChange() {
        if (onChange != null) onChange.run();
    }

    // ------------------------------------------------------------------ build

    private void reload() {
        SyntaxPalette palette = effectivePalette();

        grid.removeAll();
        for (TokenType type : TokenType.values()) {
            grid.add(new TokenRow(type, palette.color(type), isOverridden(type)));
        }

        int overrides = config == null ? 0 : config.getSyntaxColors().size();
        overrideCount.setText(overrides == 0
                ? "Using the built-in palette"
                : overrides + " overridden");
        resetAll.setEnabled(overrides > 0);

        preview.setPalette(palette);
        grid.revalidate();
        grid.repaint();
        revalidate();
        repaint();
    }

    // ------------------------------------------------------------------ rows

    private final class TokenRow extends JPanel {

        private final TokenType type;

        TokenRow(TokenType type, Color color, boolean overridden) {
            super(new BorderLayout(Tokens.SM, 0));
            this.type = type;

            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
            setToolTipText(type.hint());

            JButton swatch = new Swatch(color);
            swatch.addActionListener(e -> pick());

            JPanel labels = new JPanel(new BorderLayout(Tokens.SM, 0));
            labels.setOpaque(false);
            labels.add(Fields.secondary(type.label()), BorderLayout.WEST);

            JLabel hex = Fields.muted(Tokens.toHex(color));
            labels.add(hex, BorderLayout.EAST);

            if (overridden) {
                labels.add(dot(), BorderLayout.CENTER);
            }

            JButton revert = Buttons.iconOnly(Icons.refresh(null, 12), "Reset to the default color");
            revert.setEnabled(overridden);
            revert.addActionListener(e -> SyntaxColorEditor.this.revert(type));

            add(swatch, BorderLayout.WEST);
            add(labels, BorderLayout.CENTER);
            add(revert, BorderLayout.EAST);
        }

        /** Marks a row the user has changed, so the list is scannable. */
        private JComponent dot() {
            JComponent c = new JComponent() {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(Theme.tokens().accent);
                    g2.fillOval(getWidth() / 2 - 3, getHeight() / 2 - 3, 6, 6);
                    g2.dispose();
                }
            };
            c.setPreferredSize(new Dimension(12, 12));
            return c;
        }

        private void pick() {
            Color current = effectivePalette().color(type);
            Color chosen = JColorChooser.showDialog(SwingUtilities.getWindowAncestor(this),
                    "Color for " + type.label(), current);
            setColor(type, chosen);
        }

        @Override
        public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, 26); }
    }

    /** A clickable color chip. */
    private static final class Swatch extends JButton {
        private final Color color;

        Swatch(Color color) {
            this.color = color == null ? Color.GRAY : color;
            setPreferredSize(new Dimension(30, 20));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Click to pick a color");
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

    // ------------------------------------------------------------------ preview

    /** Two sample lines drawn with the palette being edited. */
    private static final class Preview extends JComponent {

        private static final String LINE_ONE = "GET /api/v1/users?id=1337 HTTP/1.1";
        private static final String LINE_TWO = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9";
        private static final String LINE_THREE = "{\"role\": \"admin\", \"active\": true}";

        private SyntaxPalette palette = SyntaxPalette.DARK;

        Preview() {
            setOpaque(true);
            setPreferredSize(new Dimension(10, 76));
        }

        void setPalette(SyntaxPalette p) {
            if (p != null) palette = p;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Tokens t = Theme.tokens();
            g2.setColor(t.bgCard);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), Tokens.R_MD, Tokens.R_MD);
            g2.setColor(t.border);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tokens.R_MD, Tokens.R_MD);

            drawLine(g2, t, LINE_ONE, SyntaxHighlighter.LineKind.REQUEST_LINE, 10, 8);
            drawLine(g2, t, LINE_TWO, SyntaxHighlighter.LineKind.HEADER, 10, 30);
            drawLine(g2, t, LINE_THREE, SyntaxHighlighter.LineKind.BODY, 10, 52);

            g2.dispose();
        }

        private void drawLine(Graphics2D g2, Tokens t, String text,
                              SyntaxHighlighter.LineKind kind, int x, int top) {
            FontMetrics fm = g2.getFontMetrics(t.code);
            int baseline = top + fm.getAscent();
            int cursor = x;

            g2.setFont(t.code);
            for (SyntaxHighlighter.Token token : SyntaxHighlighter.tokenize(text, kind)) {
                g2.setFont(SyntaxPalette.isBold(token.type) ? t.codeBold : t.code);
                g2.setColor(palette.color(token.type));
                g2.drawString(token.text, cursor, baseline);
                cursor += g2.getFontMetrics().stringWidth(token.text);
            }
        }

        @Override
        public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, 76); }
    }
}
