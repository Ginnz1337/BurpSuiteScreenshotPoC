package burp.screenshot.ui.components;

import burp.screenshot.design.Theme;
import burp.screenshot.design.Tokens;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

/**
 * Transient message shown over the bottom-right corner of a frame.
 *
 * <p>Replaces the {@code JOptionPane} calls in {@code quickCopy}: a modal dialog steals
 * focus from Burp, which is disruptive for an action the user repeats often.
 */
public final class Toast {

    public enum Kind { INFO, SUCCESS, ERROR }

    private static final String CLIENT_KEY = "poc.toast";
    private static final int LIFETIME_MS = 2500;
    private static final int FADE_MS = 220;

    private final JRootPane root;
    private final String message;
    private final Kind kind;

    private float alpha = 0f;
    private Timer fadeIn;
    private Timer fadeOut;
    private Timer dismiss;

    private Toast(JRootPane root, String message, Kind kind) {
        this.root = root;
        this.message = message;
        this.kind = kind;
    }

    public static void show(JRootPane root, String message) {
        show(root, message, Kind.INFO);
    }

    public static void success(JRootPane root, String message) {
        show(root, message, Kind.SUCCESS);
    }

    public static void error(JRootPane root, String message) {
        show(root, message, Kind.ERROR);
    }

    public static void show(JRootPane root, String message, Kind kind) {
        if (root == null || message == null || message.isBlank()) return;

        // One toast per frame: a burst of saves must not stack up overlays.
        Object existing = root.getClientProperty(CLIENT_KEY);
        if (existing instanceof Toast) ((Toast) existing).dismissNow();

        Toast toast = new Toast(root, message, kind);
        root.putClientProperty(CLIENT_KEY, toast);
        toast.install();
    }

    private Panel panel;

    private void install() {
        panel = new Panel();
        panel.setOpaque(false);

        JLayeredPane layers = root.getLayeredPane();
        layers.add(panel, JLayeredPane.POPUP_LAYER);
        panel.setSize(panel.getPreferredSize());
        reposition();
        layers.repaint();

        fadeIn = new Timer(16, e -> {
            alpha = Math.min(1f, alpha + 0.14f);
            if (alpha >= 1f) fadeIn.stop();
            panel.repaint();
        });
        fadeIn.start();

        dismiss = new Timer(LIFETIME_MS, e -> dismissNow());
        dismiss.setRepeats(false);
        dismiss.start();

        root.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                if (panel.getParent() != null) reposition();
            }
        });
    }

    private void reposition() {
        Dimension d = panel.getPreferredSize();
        panel.setSize(d);
        int margin = Tokens.LG;
        Insets insets = root.getInsets();
        int x = root.getWidth() - d.width - margin + insets.left;
        int y = root.getHeight() - d.height - margin + insets.top;
        panel.setLocation(Math.max(margin, x), Math.max(margin, y));
    }

    private void dismissNow() {
        if (fadeIn != null) fadeIn.stop();
        if (dismiss != null) dismiss.stop();
        if (panel == null || panel.getParent() == null) return;

        if (fadeOut != null && fadeOut.isRunning()) return;
        fadeOut = new Timer(16, e -> {
            alpha -= 0.12f;
            if (alpha <= 0f) {
                alpha = 0f;
                fadeOut.stop();
                JLayeredPane layers = root.getLayeredPane();
                layers.remove(panel);
                layers.repaint();
                if (root.getClientProperty(CLIENT_KEY) == this) root.putClientProperty(CLIENT_KEY, null);
            } else {
                panel.repaint();
            }
        });
        fadeOut.start();
    }

    private final class Panel extends JComponent {

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(Theme.tokens().ui);
            int text = fm.stringWidth(message);
            int w = Tokens.MD + 14 + Tokens.SM + text + Tokens.MD;
            int h = Math.max(32, fm.getHeight() + Tokens.MD * 2);
            return new Dimension(w, h);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, alpha)));

            Tokens t = Theme.tokens();
            int w = getWidth();
            int h = getHeight();
            int arc = Tokens.R_MD;

            g2.setColor(Tokens.alpha(java.awt.Color.BLACK, 70));
            g2.fillRoundRect(0, 2, w, h - 2, arc, arc);

            g2.setColor(t.bgCard);
            g2.fillRoundRect(0, 0, w, h - 2, arc, arc);

            java.awt.Color edge = switch (kind) {
                case SUCCESS -> t.success;
                case ERROR -> t.danger;
                case INFO -> t.borderStrong;
            };
            g2.setColor(edge);
            g2.drawRoundRect(0, 0, w - 1, h - 3, arc, arc);

            java.awt.Color fg = switch (kind) {
                case SUCCESS -> t.success;
                case ERROR -> t.danger;
                case INFO -> t.textSecondary;
            };
            Icon icon = switch (kind) {
                case SUCCESS -> burp.screenshot.design.Icons.check(fg, 14);
                case ERROR -> burp.screenshot.design.Icons.close(fg, 14);
                case INFO -> burp.screenshot.design.Icons.image(fg, 14);
            };
            icon.paintIcon(this, g2, Tokens.MD, (h - 2 - icon.getIconHeight()) / 2);

            g2.setFont(t.ui);
            g2.setColor(t.textPrimary);
            FontMetrics fm = g2.getFontMetrics();
            int baseline = (h - 2 - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(message, Tokens.MD + 14 + Tokens.SM, baseline);

            g2.dispose();
        }
    }

    /** Convenience for a component that only knows itself. */
    public static void showFor(JComponent any, String message) {
        if (any == null) return;
        JRootPane root = javax.swing.SwingUtilities.getRootPane(any);
        show(root, message);
    }
}
