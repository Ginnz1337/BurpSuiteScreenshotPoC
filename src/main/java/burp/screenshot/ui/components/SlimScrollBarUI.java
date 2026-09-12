package burp.screenshot.ui.components;

import burp.screenshot.design.Theme;
import burp.screenshot.design.Tokens;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/**
 * Thin, borderless scrollbar that follows the extension theme.
 *
 * <p>Applied per scroll pane via {@link #install}. It is never registered through
 * {@code UIManager.put}: the extension runs in Burp's JVM, so a global default would
 * restyle Burp's own Proxy, Repeater and Intruder scrollbars as well.
 */
public class SlimScrollBarUI extends BasicScrollBarUI {

    private static final int THICKNESS = 10;

    public static void install(JScrollPane pane) {
        if (pane == null) return;
        style(pane.getVerticalScrollBar(), THICKNESS);
        style(pane.getHorizontalScrollBar(), THICKNESS);
        pane.setBorder(null);
        // The viewport stays transparent so the host panel's own ground shows through. Opaque
        // here would paint the look and feel's panel colour in the strip beside a view that is
        // narrower than the viewport, which is a light band in a dark theme.
        pane.setOpaque(false);
        pane.getViewport().setOpaque(false);
    }

    public static void style(JScrollBar bar, int thickness) {
        if (bar == null) return;
        bar.setUI(new SlimScrollBarUI());
        bar.setPreferredSize(new Dimension(thickness, thickness));
        bar.setUnitIncrement(16);
        bar.setOpaque(false);
        bar.setBorder(null);
    }

    @Override
    protected void configureScrollBarColors() {
        // Colors are resolved per paint from the theme, not configured once.
    }

    @Override
    protected JButton createDecreaseButton(int orientation) { return zeroButton(); }

    @Override
    protected JButton createIncreaseButton(int orientation) { return zeroButton(); }

    private JButton zeroButton() {
        JButton b = new JButton();
        Dimension zero = new Dimension(0, 0);
        b.setPreferredSize(zero);
        b.setMinimumSize(zero);
        b.setMaximumSize(zero);
        b.setBorder(null);
        b.setFocusable(false);
        return b;
    }

    @Override
    protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
        // The track stays fully transparent so the panel background shows through.
    }

    @Override
    protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
        if (r.width <= 0 || r.height <= 0 || !c.isEnabled()) return;

        Tokens t = Theme.tokens();
        boolean active = isThumbRollover() || isDragging;
        Color thumb = active ? t.borderStrong : Tokens.alpha(t.textMuted, 110);

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(thumb);

        int inset = 2;
        int w = Math.max(4, r.width - inset * 2);
        int h = Math.max(4, r.height - inset * 2);
        g2.fillRoundRect(r.x + inset, r.y + inset, w, h, w, w);
        g2.dispose();
    }
}
