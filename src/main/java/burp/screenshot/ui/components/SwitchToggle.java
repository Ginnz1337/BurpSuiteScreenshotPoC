package burp.screenshot.ui.components;

import burp.screenshot.design.Theme;
import burp.screenshot.design.Tokens;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * Pill switch replacing {@code JCheckBox}. The knob slides over ~120 ms rather than
 * snapping, which is the only motion in the UI and keeps state changes legible.
 */
public class SwitchToggle extends JComponent {

    private static final int W = 34;
    private static final int H = 18;

    private final Consumer<Boolean> onChange;
    private boolean selected;
    private float position;
    private boolean hover;
    private Timer animator;

    public SwitchToggle(boolean selected, Consumer<Boolean> onChange) {
        this.selected = selected;
        this.onChange = onChange;
        this.position = selected ? 1f : 0f;

        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFocusable(true);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
            @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            @Override public void mouseClicked(MouseEvent e) { toggle(); }
        });
    }

    public void setSelected(boolean value) {
        setSelected(value, false);
    }

    /** {@code silent} suppresses the callback, used when loading a template into the UI. */
    public void setSelected(boolean value, boolean silent) {
        if (this.selected == value) return;
        this.selected = value;
        animateTo(value ? 1f : 0f);
        if (!silent && onChange != null) onChange.accept(value);
    }

    private void toggle() { setSelected(!selected); }

    private void animateTo(float target) {
        if (animator != null && animator.isRunning()) animator.stop();
        animator = new Timer(16, e -> {
            float delta = target - position;
            if (Math.abs(delta) < 0.03f) {
                position = target;
                animator.stop();
            } else {
                position += delta * 0.35f;
            }
            repaint();
        });
        animator.start();
    }

    @Override
    public Dimension getPreferredSize() { return new Dimension(W, H); }

    @Override
    public Dimension getMinimumSize() { return getPreferredSize(); }

    @Override
    public Dimension getMaximumSize() { return getPreferredSize(); }

    @Override
    protected void paintComponent(Graphics g) {
        Tokens t = Theme.tokens();
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();
        int r = h;

        boolean on = position > 0.5f;
        java.awt.Color track = on
                ? (hover ? t.accentHover : t.accent)
                : (hover ? t.borderStrong : t.border);
        g2.setColor(track);
        g2.fillRoundRect(0, 0, w, h, r, r);

        if (hasFocus()) {
            g2.setColor(Tokens.alpha(t.accent, 90));
            g2.drawRoundRect(-1, -1, w + 1, h + 1, r + 2, r + 2);
        }

        int knob = h - 4;
        int travel = w - knob - 4;
        int kx = 2 + Math.round(travel * position);

        g2.setColor(Tokens.alpha(java.awt.Color.BLACK, on ? 40 : 18));
        g2.fillOval(kx, 3, knob, knob);
        g2.setColor(on ? java.awt.Color.WHITE : t.bgCard);
        g2.fillOval(kx, 2, knob, knob);

        g2.dispose();
    }
}
