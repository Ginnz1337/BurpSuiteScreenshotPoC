package burp.screenshot.ui.components;

import burp.screenshot.design.Theme;
import burp.screenshot.design.Tokens;

import javax.swing.Icon;
import javax.swing.JButton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Flat buttons that resolve their colors at paint time, so a theme switch repaints them
 * correctly without rebuilding the component tree.
 */
public final class Buttons {

    public enum Kind { PRIMARY, SECONDARY, GHOST, DANGER }

    private static final Color TRANSPARENT = new Color(0, 0, 0, 0);

    private Buttons() {}

    public static JButton primary(String text, Icon icon) { return new FlatButton(text, icon, Kind.PRIMARY); }
    public static JButton secondary(String text, Icon icon) { return new FlatButton(text, icon, Kind.SECONDARY); }
    public static JButton danger(String text, Icon icon) { return new FlatButton(text, icon, Kind.DANGER); }

    /**
     * Square button carrying only an icon.
     *
     * <p>Shorter than a labelled button on purpose. A row of text beside a row of icons is as
     * tall as the taller of the two, and an icon has no descender to leave room for: a 32-pixel
     * button in a toolbar of 24-pixel fields is what makes a one-line strip three pixels taller
     * than the text it sits above.
     */
    public static JButton iconOnly(Icon icon, String tooltip) {
        FlatButton b = new FlatButton("", icon, Kind.GHOST);
        b.setToolTipText(tooltip);
        b.setPadding(Tokens.SM, 0);
        b.setMinHeight(24);
        return b;
    }

    public static final class FlatButton extends JButton {

        private final Kind kind;
        private boolean hover;
        private boolean pressed;
        private int padX = Tokens.MD + 2;
        private int padY = Tokens.SM;
        private int minHeight = 26;

        FlatButton(String text, Icon icon, Kind kind) {
            super(text);
            this.kind = kind;

            if (icon != null) {
                setIcon(icon);
                setIconTextGap(Tokens.SM);
            }

            setFont(Theme.tokens().uiBold);
            setFocusable(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setRolloverEnabled(true);

            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; pressed = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { pressed = true; repaint(); }
                @Override public void mouseReleased(MouseEvent e) { pressed = false; repaint(); }
            });
        }

        void setPadding(int x, int y) {
            this.padX = x;
            this.padY = y;
            revalidate();
        }

        /** Floor for the height, before the vertical padding is added. */
        void setMinHeight(int height) {
            this.minHeight = height;
            revalidate();
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            d.width += padX * 2;
            d.height = Math.max(d.height, minHeight) + padY;
            return d;
        }

        @Override
        public Dimension getMinimumSize() { return getPreferredSize(); }

        @Override
        public Dimension getMaximumSize() { return getPreferredSize(); }

        /**
         * The {@code kind == null} branches below cover one window: {@code JButton}'s own
         * constructor runs the look and feel's {@code installColors}, which calls these
         * getters before this subclass's fields exist. Returning a real colour there also
         * stops the look and feel from installing one of its own.
         */
        @Override
        public Color getForeground() {
            Tokens t = Theme.tokens();
            if (kind == null) return t.textPrimary;
            if (!isEnabled()) return t.textMuted;
            switch (kind) {
                case PRIMARY:
                case DANGER:
                    return t.textInverse;
                default:
                    return t.textPrimary;
            }
        }

        @Override
        public Color getBackground() {
            Tokens t = Theme.tokens();
            if (kind == null) return t.bgCard;
            if (!isEnabled()) return t.bgApp;
            Color base;
            switch (kind) {
                case PRIMARY: base = t.accent; break;
                case DANGER: base = t.danger; break;
                case SECONDARY: base = t.bgCard; break;
                default: base = null; break;
            }
            if (base == null) {
                if (pressed) return t.bgActive;
                return hover ? t.bgHover : TRANSPARENT;
            }
            if (pressed) return kind == Kind.PRIMARY ? t.accentPressed : base.darker();
            return hover ? (kind == Kind.PRIMARY ? t.accentHover : Tokens.mix(base, t.textPrimary, 0.08)) : base;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Tokens t = Theme.tokens();
            Color bg = getBackground();

            if (bg != null) {
                int h = getHeight() - (pressed ? 1 : 0);
                g2.setColor(bg);
                g2.fillRoundRect(0, pressed ? 1 : 0, getWidth(), h, Tokens.R_MD, Tokens.R_MD);
            }

            if (kind == Kind.SECONDARY) {
                g2.setColor(hover ? t.borderStrong : t.border);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tokens.R_MD, Tokens.R_MD);
            }

            g2.dispose();
            super.paintComponent(g);
        }
    }
}
