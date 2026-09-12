package burp.screenshot.ui.components;

import burp.screenshot.design.Theme;
import burp.screenshot.design.Tokens;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.ComboPopup;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;

/**
 * Text, area and combo factories that read theme colors at paint time.
 *
 * <p>Swing paints a text field's background from {@code getBackground()} before
 * {@code paintComponent}, so these override the getter rather than calling
 * {@code setBackground} once. That is what makes a theme switch take effect without
 * rebuilding the inspector.
 */
public final class Fields {

    private Fields() {}

    // ------------------------------------------------------------------ labels

    /** Label whose color is resolved per paint, so it re-themes in place. */
    public static class ThemedLabel extends JLabel {
        private final ColorResolver resolver;

        public interface ColorResolver { Color resolve(Tokens t); }

        public ThemedLabel(String text, ColorResolver resolver) {
            super(text);
            this.resolver = resolver;
            setFont(Theme.tokens().uiSmall);
        }

        /**
         * Never returns null, including from {@code JLabel}'s own constructor.
         *
         * <p>{@code JLabel.updateUI} calls {@code getForeground()} through the look and feel
         * before this subclass's fields are assigned, so a plain dereference of
         * {@link #resolver} throws on every construction. The fallback only covers that
         * window; it is not a colour any label keeps.
         */
        @Override
        public Color getForeground() {
            return resolver == null ? Theme.tokens().textSecondary : resolver.resolve(Theme.tokens());
        }
    }

    public static JLabel muted(String text) {
        return new ThemedLabel(text, t -> t.textMuted);
    }

    public static JLabel secondary(String text) {
        return new ThemedLabel(text, t -> t.textSecondary);
    }

    public static JLabel primary(String text) {
        ThemedLabel l = new ThemedLabel(text, t -> t.textPrimary);
        l.setFont(Theme.tokens().ui);
        return l;
    }

    // ------------------------------------------------------------------ inputs

    public static JTextField text(String value) {
        JTextField f = new JTextField(value == null ? "" : value) {
            @Override public Color getBackground() { return Theme.tokens().bgInput; }
            @Override public Color getForeground() { return Theme.tokens().textPrimary; }
            @Override public Color getCaretColor() { return Theme.tokens().accent; }
            @Override public Color getSelectionColor() { return Tokens.alpha(Theme.tokens().accent, 110); }
            @Override public Color getSelectedTextColor() { return Theme.tokens().textPrimary; }
        };
        style(f);
        return f;
    }

    /** Text field with a hint that shows while the field is empty. */
    public static JTextField text(String value, String placeholder) {
        JTextField f = text(value);
        if (placeholder != null && !placeholder.isBlank()) {
            f.setBorder(new PlaceholderBorder(placeholder));
        }
        return f;
    }

    public static JPasswordField password() {
        JPasswordField f = new JPasswordField() {
            @Override public Color getBackground() { return Theme.tokens().bgInput; }
            @Override public Color getForeground() { return Theme.tokens().textPrimary; }
            @Override public Color getCaretColor() { return Theme.tokens().accent; }
            @Override public Color getSelectionColor() { return Tokens.alpha(Theme.tokens().accent, 110); }
        };
        f.setEchoChar('•');
        style(f);
        return f;
    }

    public static JTextArea area(int rows) {
        JTextArea a = new JTextArea(rows, 10) {
            @Override public Color getBackground() { return Theme.tokens().bgInput; }
            @Override public Color getForeground() { return Theme.tokens().textPrimary; }
            @Override public Color getCaretColor() { return Theme.tokens().accent; }
            @Override public Color getSelectionColor() { return Tokens.alpha(Theme.tokens().accent, 110); }
            @Override public Color getSelectedTextColor() { return Theme.tokens().textPrimary; }
        };
        a.setFont(Theme.tokens().code);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setBorder(BorderFactory.createEmptyBorder(Tokens.SM, Tokens.SM, Tokens.SM, Tokens.SM));
        return a;
    }

    private static void style(JTextField f) {
        f.setFont(Theme.tokens().ui);
        f.setBorder(FOCUS_RING);
        f.setOpaque(true);
        Dimension size = new Dimension(10, 28);
        f.setPreferredSize(size);
        f.setMinimumSize(size);
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        f.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) { f.repaint(); }
            @Override public void focusLost(java.awt.event.FocusEvent e) { f.repaint(); }
        });
    }

    /**
     * Flags a field as holding an invalid value, so the border turns red.
     *
     * <p>Used by the rule editor for a regex that will not compile. The renderer drops such a
     * rule silently, so the field has to say so.
     */
    public static void markInvalid(JTextField f, boolean invalid) {
        f.putClientProperty(INVALID_KEY, invalid);
        f.repaint();
    }

    private static final String INVALID_KEY = "poc.field.invalid";

    /**
     * Focus ring that resolves its colour at paint time.
     *
     * <p>A {@code LineBorder} captures the colour when it is constructed, so every field
     * outline would keep the previous theme's colour after a theme switch. The insets match
     * the old compound border, so gaining focus does not shift the layout.
     */
    private static final class FocusRingBorder extends javax.swing.border.AbstractBorder {

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(3, Tokens.SM + 1, 3, Tokens.SM + 1);
        }

        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(3, Tokens.SM + 1, 3, Tokens.SM + 1);
            return insets;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Tokens t = Theme.tokens();
            boolean invalid = Boolean.TRUE.equals(((JComponent) c).getClientProperty(INVALID_KEY));
            g.setColor(invalid ? t.danger : (c.isFocusOwner() ? t.accent : t.border));
            g.drawRect(x, y, w - 1, h - 1);
        }
    }

    // Typed as the concrete border, not as Border: the placeholder delegates the two-argument
    // getBorderInsets, which the Border interface does not declare.
    private static final FocusRingBorder FOCUS_RING = new FocusRingBorder();

    /**
     * Hint text painted inside an empty field.
     *
     * <p>A border rather than an overlay label: the border is already the component that knows
     * the field's insets, and it repaints with the field, so the hint cannot drift out of
     * alignment or be left behind by a theme switch.
     *
     * <p>The hint is hidden while the field has focus, because {@code paintBorder} runs after
     * {@code paintComponent} and would otherwise paint over the caret.
     */
    private static final class PlaceholderBorder extends javax.swing.border.AbstractBorder {

        private final String text;

        PlaceholderBorder(String text) {
            this.text = text;
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return FOCUS_RING.getBorderInsets(c);
        }

        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            return FOCUS_RING.getBorderInsets(c, insets);
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            FOCUS_RING.paintBorder(c, g, x, y, w, h);
            if (!(c instanceof JTextField field) || field.isFocusOwner()) return;
            if (!field.getText().isEmpty()) return;

            Insets in = getBorderInsets(c);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(field.getFont());
                g2.setColor(Theme.tokens().textMuted);

                FontMetrics fm = g2.getFontMetrics();
                g2.clipRect(x + in.left, y, Math.max(0, w - in.left - in.right), h);
                g2.drawString(text, x + in.left, y + (h - fm.getHeight()) / 2 + fm.getAscent());
            } finally {
                g2.dispose();
            }
        }
    }

    // ------------------------------------------------------------------ combo

    public static <T> JComboBox<T> combo(T[] items) {
        JComboBox<T> c = new JComboBox<>(items);
        c.setFont(Theme.tokens().ui);
        c.setForeground(Theme.tokens().textPrimary);
        c.setBackground(Theme.tokens().bgInput);
        c.setFocusable(false);
        c.setBorder(BorderFactory.createEmptyBorder(0, Tokens.XS, 0, 0));

        c.setUI(new BasicComboBoxUI() {
            @Override
            protected javax.swing.JButton createArrowButton() {
                return new javax.swing.JButton() {
                    @Override public void paint(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(Theme.tokens().bgInput);
                        g2.fillRect(0, 0, getWidth(), getHeight());
                        g2.setColor(Theme.tokens().textMuted);
                        int w = getWidth();
                        int h = getHeight();
                        g2.drawLine(w / 2 - 4, h / 2 - 2, w / 2, h / 2 + 2);
                        g2.drawLine(w / 2, h / 2 + 2, w / 2 + 4, h / 2 - 2);
                        g2.dispose();
                    }
                };
            }

            @Override
            protected ComboPopup createPopup() {
                BasicComboPopup popup = new BasicComboPopup(comboBox) {
                    @Override protected JScrollPane createScroller() {
                        JScrollPane sp = super.createScroller();
                        SlimScrollBarUI.install(sp);
                        return sp;
                    }
                };
                popup.setBorder(BorderFactory.createLineBorder(Theme.tokens().borderStrong));
                return popup;
            }

            @Override
            public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
                g.setColor(Theme.tokens().bgInput);
                g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            }
        });

        c.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focus) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                l.setFont(Theme.tokens().ui);
                l.setBorder(BorderFactory.createEmptyBorder(Tokens.XS, Tokens.SM, Tokens.XS, Tokens.SM));
                l.setOpaque(true);
                l.setBackground(selected ? Theme.tokens().bgActive : Theme.tokens().bgCard);
                l.setForeground(selected ? Theme.tokens().textPrimary : Theme.tokens().textSecondary);
                return l;
            }
        });

        // Width follows the widest entry so long labels are never clipped.
        FontMetrics fm = c.getFontMetrics(Theme.tokens().ui);
        int widest = 0;
        for (T item : items) widest = Math.max(widest, fm.stringWidth(String.valueOf(item)));
        int w = widest + Tokens.XL + Tokens.LG;
        c.setPreferredSize(new Dimension(w, 28));
        c.setMinimumSize(new Dimension(60, 28));
        return c;
    }

    // ------------------------------------------------------------------ layout

    /** Label above control, the standard inspector row. */
    public static JPanel row(String label, JComponent control) {
        JPanel p = new JPanel(new BorderLayout(0, Tokens.XS));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(muted(label), BorderLayout.NORTH);
        p.add(control, BorderLayout.CENTER);
        return p;
    }

    /** Label left, control right, used for compact numeric rows. */
    public static JPanel inline(String label, JComponent control) {
        JPanel p = new JPanel(new BorderLayout(Tokens.SM, 0));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(muted(label), BorderLayout.WEST);
        p.add(control, BorderLayout.EAST);
        return p;
    }

    /** Scroll pane with the slim scrollbar already installed. */
    public static JScrollPane scroll(Component view) {
        JScrollPane sp = new JScrollPane(view);
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        SlimScrollBarUI.install(sp);
        return sp;
    }

    /** Spacer of the given height for BoxLayout stacks. */
    public static JComponent gap(int height) {
        return (JComponent) javax.swing.Box.createVerticalStrut(height);
    }

    /**
     * Caps a component's height at its preferred height, for use inside a vertical box.
     *
     * <p>A Swing container reports an unbounded maximum size unless one is set, and
     * {@code BoxLayout} grows a child up to that maximum using the leftover space. The result
     * is a toggle stretched into an oval or a label floating in a tall gap. Rows are sized once
     * when they are built, so measuring the preferred height here is accurate.
     */
    public static <T extends JComponent> T capHeight(T c) {
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }
}
