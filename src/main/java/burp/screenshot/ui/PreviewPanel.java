package burp.screenshot.ui;

import burp.screenshot.design.Icons;
import burp.screenshot.design.Theme;
import burp.screenshot.design.Tokens;
import burp.screenshot.engine.ScreenshotRenderer;
import burp.screenshot.model.HttpExchangeData;
import burp.screenshot.model.TemplateConfig;

import javax.swing.JPanel;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;

/**
 * The centre of the studio: the rendered card, at a zoom the user controls.
 *
 * <p>Zoom re-renders at the target scale instead of stretching a 1x bitmap. Stretching looks
 * acceptable at 100% and blurry at 200%, which is exactly where a screenshot needs to be
 * sharp. Rendering below 1x would put the fonts into sub-pixel sizes and break the metrics,
 * so zooming out scales the 1x bitmap down instead.
 *
 * <p>The checkerboard behind the image is painted here rather than baked into the image.
 * Baking it would fill the transparent margin around the drop shadow and the exported PNG
 * would no longer composite onto an arbitrary background.
 */
public class PreviewPanel extends JPanel {

    /** Empty space kept around the card so the drop shadow is never against the edge. */
    private static final int MARGIN = 20;
    private static final int MIN_CARD_WIDTH = 550;
    private static final int MAX_CARD_WIDTH = 2400;
    private static final int DEBOUNCE_MS = 120;
    /** A drag only re-renders once the width has moved this far. */
    private static final int DRAG_STEP = 12;
    private static final double MIN_ZOOM = 0.25;
    private static final double MAX_ZOOM = 4.0;

    /** Notified after every render so the host can update its status bar. */
    public interface StatusListener {
        void status(String text);
    }

    private HttpExchangeData exchangeData;
    private TemplateConfig config;

    private BufferedImage rendered;
    /** Scale the current {@link #rendered} was produced at, so painting knows the ratio. */
    private double renderedScale = 1.0;

    /** User-chosen card width. {@code -1} means "follow the template's width preset". */
    private int customWidth = -1;
    private double zoom = 1.0;

    private int lastRenderedWidth = -1;

    private int dragEdge;              // 0 none, -1 left, +1 right
    private int dragStartX;
    private int dragStartWidth;
    private int hoverEdge;

    private final Timer debounce;
    private StatusListener statusListener;

    /** Removed on {@code removeNotify}, so a closed studio does not keep re-rendering. */
    private AutoCloseable themeHandle;

    public PreviewPanel() {
        setOpaque(true);
        setFocusable(true);

        debounce = new Timer(DEBOUNCE_MS, e -> renderNow());
        debounce.setRepeats(false);

        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) { updateHover(e.getX(), e.getY()); }
            @Override public void mouseExited(MouseEvent e) { setHoverEdge(0); }
            @Override public void mousePressed(MouseEvent e) { beginDrag(e); }
            @Override public void mouseDragged(MouseEvent e) { continueDrag(e); }
            @Override public void mouseReleased(MouseEvent e) { endDrag(); }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) resetWidth();
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                // A wheel listener here would swallow the event before the enclosing scroll
                // pane sees it, so a plain wheel is forwarded up by hand.
                if (!e.isControlDown()) {
                    if (getParent() != null) {
                        getParent().dispatchEvent(SwingUtilities.convertMouseEvent(
                                PreviewPanel.this, e, getParent()));
                    }
                    return;
                }
                e.consume();
                zoomBy(e.getWheelRotation() < 0 ? 1.1 : 1 / 1.1);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);

        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                if (isFullWidth() && customWidth <= 0) schedule();
            }
        });
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (themeHandle == null) {
            // A theme switch changes every colour in the image, so the cache must be dropped.
            themeHandle = Theme.addListener(() -> SwingUtilities.invokeLater(this::refresh));
        }
    }

    @Override
    public void removeNotify() {
        if (themeHandle != null) {
            try {
                themeHandle.close();
            } catch (Exception ignored) {
                // Nothing useful to do; the listener is already gone.
            }
            themeHandle = null;
        }
        debounce.stop();
        super.removeNotify();
    }

    // ------------------------------------------------------------------ data

    public void setData(HttpExchangeData data, TemplateConfig templateConfig) {
        this.exchangeData = data;
        this.config = templateConfig;
        this.customWidth = -1;
        this.lastRenderedWidth = -1;
        renderNow();
    }

    public void setStatusListener(StatusListener l) {
        this.statusListener = l;
    }

    /** Debounced rebuild, for edits that arrive one keystroke at a time. */
    public void refresh() {
        schedule();
    }

    /** Immediate rebuild, for structural changes the user expects to see at once. */
    public void refreshNow() {
        debounce.stop();
        renderNow();
    }

    private void schedule() {
        debounce.restart();
    }

    // ------------------------------------------------------------------ zoom

    public double getZoom() { return zoom; }

    public void setZoom(double value) {
        double next = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value));
        if (Math.abs(next - zoom) < 0.001) return;
        zoom = next;
        renderNow();
    }

    public void zoomBy(double factor) { setZoom(zoom * factor); }

    /** Fits the card's width into the viewport, never magnifying past 100%. */
    public void fitToWindow() {
        int available = visibleWidth() - MARGIN * 2 - 8;
        if (available <= 0 || rendered == null) {
            setZoom(1.0);
            return;
        }
        double fit = (double) available / Math.max(1, rendered.getWidth());
        setZoom(Math.min(1.0, fit));
    }

    /**
     * Width the user can actually see.
     *
     * <p>Not {@code getWidth()}: the preferred size of this panel is derived from the card's
     * own width, so inside a scroll pane the panel is at least as wide as the image it holds.
     * Measuring it would make "fit to window" preserve the current zoom instead of choosing
     * one, and would make the "Full Width" preset grow on every render.
     */
    private int visibleWidth() {
        JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
        if (viewport != null && viewport.getWidth() > 0) return viewport.getWidth();
        return getWidth();
    }

    public void resetZoom() { setZoom(1.0); }

    // ------------------------------------------------------------------ width

    /** The base width the card is rendered at, before zoom. */
    public int getCardWidth() {
        if (customWidth > 0) return customWidth;
        return presetWidth();
    }

    /** Where the drawn image sits inside this panel, at the current zoom. */
    public java.awt.Rectangle getImageBounds() {
        return new java.awt.Rectangle(imageX(), imageY(), drawnWidth(), drawnHeight());
    }

    public void resetWidth() {
        if (customWidth == -1) return;
        customWidth = -1;
        refreshNow();
        announce("Card width back to the default");
    }

    private int presetWidth() {
        String option = config != null ? config.getContentWidth() : null;
        if ("Compact (800px)".equalsIgnoreCase(option)) return 800;
        if ("Wide (1200px)".equalsIgnoreCase(option)) return 1200;
        if ("Full Width".equalsIgnoreCase(option)) return Math.max(650, visibleWidth() - MARGIN * 2);
        if ("Medium (1000px)".equalsIgnoreCase(option)) return 1000;
        return 1000;
    }

    private boolean isFullWidth() {
        return config != null && "Full Width".equalsIgnoreCase(config.getContentWidth());
    }

    // ------------------------------------------------------------------ export

    /** Renders at the template's scale factor. Only Save and Copy call this. */
    public BufferedImage imageForExport() {
        if (exchangeData == null || config == null) return null;
        double scale = config.getScaleFactor() > 0 ? config.getScaleFactor() : 2.0;
        return ScreenshotRenderer.render(exchangeData, config, getCardWidth(), scale, Theme.isDark());
    }

    // ------------------------------------------------------------------ render

    private void renderNow() {
        if (exchangeData == null || config == null) {
            rendered = null;
            repaint();
            return;
        }

        int width = getCardWidth();
        // Above 100% the image is produced at the zoomed scale so glyph edges stay crisp.
        // Below 100% the 1x image is shrunk instead, which keeps the fonts in whole pixels.
        renderedScale = Math.max(1.0, zoom);

        rendered = ScreenshotRenderer.render(exchangeData, config, width, renderedScale, Theme.isDark());
        lastRenderedWidth = width;

        updatePreferredSize();
        repaint();
        announce(String.format("%,d × %,d px · %d%%", width, rendered.getHeight(), Math.round(zoom * 100)));
    }

    private void updatePreferredSize() {
        if (rendered == null) return;
        Dimension size = new Dimension(drawnWidth() + MARGIN * 2, drawnHeight() + MARGIN * 2);
        if (!size.equals(getPreferredSize())) {
            setPreferredSize(size);
            revalidate();
        }
    }

    private void announce(String text) {
        if (statusListener != null) statusListener.status(text);
    }

    // ------------------------------------------------------------------ geometry

    /** Ratio between the drawn image and the rendered image. */
    private double drawScale() {
        return rendered == null ? 1.0 : zoom / renderedScale;
    }

    private int drawnWidth() {
        return rendered == null ? 0 : (int) Math.round(rendered.getWidth() * drawScale());
    }

    private int drawnHeight() {
        return rendered == null ? 0 : (int) Math.round(rendered.getHeight() * drawScale());
    }

    private int imageX() {
        return Math.max(MARGIN, (getWidth() - drawnWidth()) / 2);
    }

    private int imageY() {
        return MARGIN;
    }

    /**
     * Tolerance for grabbing an edge, in panel pixels.
     *
     * <p>Grown with the zoom: at 200% a fixed 8px band is a quarter of the card's own margin
     * and the user has to aim far more precisely than the pixels on screen suggest.
     */
    private int hitTolerance() {
        double scale = Math.max(0.5, Math.min(2.5, drawScale()));
        return (int) Math.round(8 * scale);
    }

    private int edgeAt(int mx, int my) {
        if (rendered == null) return 0;
        int top = imageY();
        int bottom = top + drawnHeight();
        if (my < top - 8 || my > bottom + 8) return 0;

        int tolerance = hitTolerance();
        int left = imageX();
        int right = left + drawnWidth();
        if (Math.abs(mx - right) <= tolerance) return 1;
        if (Math.abs(mx - left) <= tolerance) return -1;
        return 0;
    }

    private void updateHover(int mx, int my) {
        setHoverEdge(edgeAt(mx, my));
        int edge = hoverEdge;
        if (edge == 0) {
            setCursor(Cursor.getDefaultCursor());
        } else {
            setCursor(Cursor.getPredefinedCursor(
                    edge > 0 ? Cursor.E_RESIZE_CURSOR : Cursor.W_RESIZE_CURSOR));
        }
    }

    private void setHoverEdge(int edge) {
        if (hoverEdge == edge) return;
        hoverEdge = edge;
        repaint();
    }

    private void beginDrag(MouseEvent e) {
        int edge = edgeAt(e.getX(), e.getY());
        if (edge == 0) return;
        dragEdge = edge;
        dragStartX = e.getX();
        dragStartWidth = getCardWidth();
    }

    private void continueDrag(MouseEvent e) {
        if (dragEdge == 0) return;

        // The pointer moves in panel pixels; the card grows in its own pixels. Dividing by
        // the draw scale keeps the edge under the cursor at every zoom level.
        double scale = drawScale() <= 0 ? 1 : drawScale();
        int delta = (int) Math.round((e.getX() - dragStartX) / scale) * dragEdge;
        int next = Math.max(MIN_CARD_WIDTH, Math.min(MAX_CARD_WIDTH, dragStartWidth + delta));

        // Re-render only on a visible step: a render per mouse event drops frames.
        if (Math.abs(next - lastRenderedWidth) < DRAG_STEP) return;

        customWidth = next;
        debounce.stop();
        renderNow();
    }

    private void endDrag() {
        if (dragEdge == 0) return;
        dragEdge = 0;
        setCursor(Cursor.getDefaultCursor());
        repaint();
    }

    // ------------------------------------------------------------------ paint

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        Tokens t = Theme.tokens();

        g2.setColor(t.bgApp);
        g2.fillRect(0, 0, getWidth(), getHeight());

        if (rendered == null) {
            paintEmptyState(g2, t);
            g2.dispose();
            return;
        }

        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        int x = imageX();
        int y = imageY();
        int w = drawnWidth();
        int h = drawnHeight();

        paintCheckerboard(g2, t, x, y, w, h);
        g2.drawImage(rendered, x, y, w, h, null);

        if (hoverEdge != 0 || dragEdge != 0) paintEdgeHint(g2, t, x, y, w, h);
        paintEdgeGrips(g2, t, x, y, w, h);
        paintBadge(g2, t, x, y, w, h);

        g2.dispose();
    }

    private void paintEmptyState(Graphics2D g2, Tokens t) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int cx = getWidth() / 2;
        int cy = Math.max(60, getHeight() / 2 - 20);

        Icons.image(t.textMuted, 40).paintIcon(this, g2, cx - 20, cy - 46);

        g2.setFont(t.ui);
        g2.setColor(t.textSecondary);
        String line1 = "No HTTP data to show yet";
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(line1, cx - fm.stringWidth(line1) / 2, cy + 20);

        g2.setColor(t.textMuted);
        g2.setFont(t.uiSmall);
        String line2 = "Open the studio from the right-click menu on a request in Burp";
        FontMetrics fm2 = g2.getFontMetrics();
        g2.drawString(line2, cx - fm2.stringWidth(line2) / 2, cy + 42);
    }

    /**
     * Faint checkerboard behind the image.
     *
     * <p>Both tones are derived from the panel background so the pattern stays subtle in
     * either theme. Without it the transparent margin around the drop shadow is
     * indistinguishable from the viewport.
     */
    private void paintCheckerboard(Graphics2D g2, Tokens t, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) return;
        int cell = 10;

        Color light = Tokens.mix(t.bgApp, t.textPrimary, 0.030);
        g2.setColor(light);
        g2.fillRect(x, y, w, h);

        g2.setColor(Tokens.mix(t.bgApp, t.textPrimary, 0.065));
        Shape saved = g2.getClip();
        g2.clipRect(x, y, w, h);
        for (int row = 0; row * cell < h; row++) {
            for (int col = 0; col * cell < w; col++) {
                if (((row + col) & 1) == 0) continue;
                g2.fillRect(x + col * cell, y + row * cell, cell, cell);
            }
        }
        g2.setClip(saved);
    }

    private void paintEdgeHint(Graphics2D g2, Tokens t, int x, int y, int w, int h) {
        int edgeX = dragEdge != 0 ? dragEdge : hoverEdge;
        int lineX = edgeX > 0 ? x + w : x;

        g2.setColor(t.accent);
        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                1f, new float[]{5f, 5f}, 0f));
        g2.drawLine(lineX, y, lineX, y + h);
    }

    /**
     * The drag target, drawn on both card edges all the time.
     *
     * <p>The dashed line only appears once the pointer is already on the edge, so it can never
     * tell anyone the edge is draggable. This is the affordance that does.
     */
    private void paintEdgeGrips(Graphics2D g2, Tokens t, int x, int y, int w, int h) {
        if (h < 60 || w <= 0) return;

        // Kept inside the viewport: on an image taller than the pane the vertical centre is
        // scrolled out of sight, and a grip nobody can see is no better than no grip.
        java.awt.Rectangle visible = getVisibleRect();
        int cy = y + h / 2;
        cy = Math.max(visible.y + 24, Math.min(cy, visible.y + visible.height - 24));

        int alpha = (hoverEdge != 0 || dragEdge != 0) ? 235 : 145;
        paintGrip(g2, t, x, cy, alpha);
        paintGrip(g2, t, x + w, cy, alpha);
    }

    private void paintGrip(Graphics2D g2, Tokens t, int edgeX, int cy, int alpha) {
        int gw = 5;
        int gh = 44;
        int left = edgeX - gw / 2;

        Composite savedComposite = g2.getComposite();
        Object savedAA = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha / 255f));

        g2.setColor(t.accent);
        g2.fillRoundRect(left, cy - gh / 2, gw, gh, gw, gw);

        // Punched in the panel colour so the grip reads as a handle on either card theme.
        g2.setColor(Tokens.alpha(t.bgApp, 210));
        for (int i = -1; i <= 1; i++) {
            g2.fillOval(edgeX - 1, cy + i * 7 - 1, 2, 2);
        }

        g2.setComposite(savedComposite);
        if (savedAA != null) g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, savedAA);
    }

    private void paintBadge(Graphics2D g2, Tokens t, int x, int y, int w, int h) {
        boolean dragging = dragEdge != 0;
        if (!dragging && hoverEdge == 0) return;

        String text = getCardWidth() + " px" + (dragging ? "  ·  drag to resize" : "");
        g2.setFont(t.uiSmall);
        FontMetrics fm = g2.getFontMetrics();
        int bw = fm.stringWidth(text) + Tokens.MD * 2;
        int bh = 22;
        int bx = Math.max(4, x + w / 2 - bw / 2);
        int by = Math.max(4, y + h / 2 - bh / 2);

        g2.setColor(t.bgCard);
        g2.fillRoundRect(bx, by, bw, bh, Tokens.R_MD, Tokens.R_MD);
        g2.setColor(t.borderStrong);
        g2.drawRoundRect(bx, by, bw, bh, Tokens.R_MD, Tokens.R_MD);

        g2.setColor(t.textPrimary);
        g2.drawString(text, bx + Tokens.MD,
                by + (bh - fm.getHeight()) / 2 + fm.getAscent());
    }
}
