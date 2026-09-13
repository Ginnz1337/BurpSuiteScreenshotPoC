package burp.screenshot.ui.components;

import burp.screenshot.design.Theme;
import burp.screenshot.design.Tokens;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Pill-shaped radio group. Replaces the four pairs of toggle buttons in the old sidebar,
 * which stretched under {@code BoxLayout} because no maximum size was set.
 *
 * <p>The selected pill animates horizontally over ~110 ms so a change of mode is visible
 * even when the two labels look similar.
 */
public class SegmentedControl<T> extends JComponent {

    private final List<T> values;
    private final Function<T, String> labeler;
    private final Consumer<T> onSelect;

    private int selectedIndex;
    private float pillX;
    private int hoverIndex = -1;
    private Timer animator;

    public SegmentedControl(List<T> values, Function<T, String> labeler, T selected, Consumer<T> onSelect) {
        this.values = values;
        this.labeler = labeler;
        this.onSelect = onSelect;
        this.selectedIndex = Math.max(0, values.indexOf(selected));

        setOpaque(false);
        setFont(Theme.tokens().uiSmall);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        addMouseListener(new MouseAdapter() {
            @Override public void mouseExited(MouseEvent e) { hoverIndex = -1; repaint(); }
            @Override public void mouseClicked(MouseEvent e) { clickAt(e.getX()); }
        });
        addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int i = indexAt(e.getX());
                if (i != hoverIndex) { hoverIndex = i; repaint(); }
            }
        });
    }

    public T getSelected() { return values.get(selectedIndex); }

    public void setSelected(T value) { setSelected(value, false); }

    /** {@code silent} suppresses the callback, used when loading a template into the UI. */
    public void setSelected(T value, boolean silent) {
        int i = values.indexOf(value);
        if (i < 0 || i == selectedIndex) return;
        selectedIndex = i;
        animateTo(segmentWidth() * i);
        if (!silent && onSelect != null) onSelect.accept(value);
    }

    private void clickAt(int x) {
        int i = indexAt(x);
        if (i < 0 || i == selectedIndex) return;
        selectedIndex = i;
        animateTo(segmentWidth() * i);
        if (onSelect != null) onSelect.accept(values.get(i));
    }

    private void animateTo(int target) {
        if (animator != null && animator.isRunning()) animator.stop();
        animator = new Timer(16, e -> {
            float delta = target - pillX;
            if (Math.abs(delta) < 1f) {
                pillX = target;
                animator.stop();
            } else {
                pillX += delta * 0.4f;
            }
            repaint();
        });
        animator.start();
    }

    private int segmentWidth() {
        int n = Math.max(1, values.size());
        return Math.max(1, (getWidth() - 4) / n);
    }

    private int indexAt(int x) {
        int sw = segmentWidth();
        if (sw <= 0) return -1;
        int i = (x - 2) / sw;
        return (i >= 0 && i < values.size()) ? i : -1;
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        int widest = 0;
        for (T v : values) widest = Math.max(widest, fm.stringWidth(labeler.apply(v)));
        int w = (widest + Tokens.MD * 2) * values.size() + 4;
        return new Dimension(w, 26);
    }

    @Override
    public Dimension getMinimumSize() { return getPreferredSize(); }

    @Override
    public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, 26); }

    @Override
    protected void paintComponent(Graphics g) {
        Tokens t = Theme.tokens();
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        g2.setColor(t.bgApp);
        g2.fillRoundRect(0, 0, w, h, h, h);
        g2.setColor(t.border);
        g2.drawRoundRect(0, 0, w - 1, h - 1, h, h);

        int sw = segmentWidth();
        int pill = Math.round(pillX);

        g2.setColor(t.bgCard);
        g2.fillRoundRect(2 + pill, 2, sw, h - 4, h - 4, h - 4);
        g2.setColor(t.borderStrong);
        g2.drawRoundRect(2 + pill, 2, sw - 1, h - 5, h - 4, h - 4);

        FontMetrics fm = g2.getFontMetrics(getFont());
        int baseline = (h - fm.getHeight()) / 2 + fm.getAscent();

        for (int i = 0; i < values.size(); i++) {
            String label = labeler.apply(values.get(i));
            int x = 2 + sw * i + (sw - fm.stringWidth(label)) / 2;

            if (i == selectedIndex) {
                g2.setColor(t.textPrimary);
                g2.setFont(Theme.tokens().uiSmallBold);
            } else {
                g2.setColor(i == hoverIndex ? t.textSecondary : t.textMuted);
                g2.setFont(getFont());
            }
            g2.drawString(label, x, baseline);
        }

        g2.dispose();
    }

    /** Convenience for building the pill row over an enum. */
    public static <E extends Enum<E>> SegmentedControl<E> of(
            Class<E> type, Function<E, String> labeler, E selected, Consumer<E> onSelect) {
        return new SegmentedControl<>(List.of(type.getEnumConstants()), labeler, selected, onSelect);
    }
}
