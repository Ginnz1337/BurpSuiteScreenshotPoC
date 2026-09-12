package burp.screenshot.engine;

import burp.screenshot.design.SyntaxPalette;
import burp.screenshot.design.Theme;
import burp.screenshot.model.HttpExchangeData;
import burp.screenshot.model.LayoutMode;
import burp.screenshot.model.TemplateConfig;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders an HTTP exchange as a shareable card.
 *
 * <p>Three entry points and one invariant: {@link #render} is exactly {@link #measure}
 * followed by {@link #paint} onto a fresh image. The invariant is what lets the preview paint
 * the card straight onto a Swing component instead of rasterising it, which is where its speed
 * comes from: measuring costs no pixels, and painting costs only the part that is looked at.
 * The test suite asserts the equality pixel by pixel.
 *
 * <p>Measurement happens on a scratch {@link Graphics2D} carrying the same rendering hints as
 * every painting surface, so the two cannot disagree about how wide a string is.
 *
 * <p>Highlights are painted before redactions. Redaction is the stronger statement, so a
 * blackout must be able to cover a highlight rather than the other way round.
 */
public class ScreenshotRenderer {

    /**
     * Scratch surface for {@link java.awt.FontMetrics}.
     *
     * <p>Built once. The metrics depend on the font and the hints, not on the canvas, so
     * rebuilding the surface per render was pure waste on the hot path.
     */
    private static final BufferedImage MEASURE_IMAGE =
            new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

    /** Narrowest a pane may get when the request/response divider is dragged. */
    public static final int MIN_PANE_WIDTH = 220;

    private ScreenshotRenderer() {}

    // ------------------------------------------------------------------ measure

    /** Lays the card out at its full content height. */
    public static CardScene measure(HttpExchangeData exchange, TemplateConfig config,
                                    int baseWidth, boolean dark) {
        return measure(exchange, config, baseWidth, dark, 0);
    }

    /**
     * Lays the card out, optionally showing only the top {@code cropCardHeight} of it.
     *
     * <p>The crop shortens the card itself rather than clipping the picture of it, so the
     * bottom edge, the rounded corners and the shadow all land where a shorter card would put
     * them. The URL bar and the footer are never cropped: they are the card's frame, and the
     * footer is where the timestamp and the response size live. What the crop takes is the
     * middle, the part a reader skips.
     *
     * <p>A layout pass allocates no raster and recompiles no pattern, so it is cheap enough to
     * run on every mouse move of a drag.
     */
    public static CardScene measure(HttpExchangeData exchange, TemplateConfig config,
                                    int baseWidth, boolean dark, int cropCardHeight) {
        if (exchange == null) exchange = new HttpExchangeData();
        if (config == null) config = new TemplateConfig();

        Graphics2D measure = MEASURE_IMAGE.createGraphics();
        RenderContext.applyHints(measure);
        RenderContext ctx = RenderContext.create(exchange, config, dark, measure);
        measure.dispose();

        TextProcessor.ProcessedHttp req = TextProcessor.processRequest(exchange.getRawRequest(), config);
        TextProcessor.ProcessedHttp res = TextProcessor.processResponse(exchange.getRawResponse(), config);

        // ---------------------------------------------------------------- layout

        int cardWidth = Math.max(600, baseWidth - ctx.shadowMargin * 2);
        boolean sideBySide = config.getLayoutMode() == LayoutMode.SIDE_BY_SIDE;
        int colWidth = sideBySide ? columnWidth(config, cardWidth) : cardWidth;

        int reqGutter = SectionPainter.gutterWidth(ctx, SectionPainter.maxLineNumber(req));
        int resGutter = SectionPainter.gutterWidth(ctx, SectionPainter.maxLineNumber(res));

        int reqAvail;
        int resAvail;
        if (sideBySide) {
            reqAvail = Math.max(80, colWidth - reqGutter - 24);
            resAvail = Math.max(80, (cardWidth - colWidth - 1) - resGutter - 24);
        } else {
            reqAvail = Math.max(80, cardWidth - reqGutter - 24);
            resAvail = Math.max(80, cardWidth - resGutter - 24);
        }

        List<SectionPainter.Row> reqRows =
                SectionPainter.layout(ctx, req, reqAvail, config.isWrapText());
        List<SectionPainter.Row> resRows =
                SectionPainter.layout(ctx, res, resAvail, config.isWrapText());

        // Measured here, not at paint time: the address wraps, and the number of lines it takes
        // is part of the card height fixed below.
        CardChrome.UrlLayout urlLayout = config.isShowUrl() ? CardChrome.urlLayout(ctx, cardWidth) : null;
        int urlBarHeight = urlLayout != null ? urlLayout.height() : 0;
        int footerHeight = (config.isShowTimestamp() || config.isShowResponseInfo())
                ? CardChrome.FOOTER_HEIGHT : 0;

        int reqHeight = CardChrome.sectionHeight(ctx, Math.max(1, reqRows.size()));
        int resHeight = CardChrome.sectionHeight(ctx, Math.max(1, resRows.size()));

        int naturalBodyHeight = sideBySide
                ? Math.max(reqHeight, resHeight)
                : reqHeight + 1 + resHeight;
        int naturalCardHeight = urlBarHeight + naturalBodyHeight + footerHeight;

        // The chrome is never cropped, so the smallest useful card is the frame on its own.
        int cardHeight = cropCardHeight > 0
                ? Math.max(urlBarHeight + footerHeight, Math.min(naturalCardHeight, cropCardHeight))
                : naturalCardHeight;
        int bodyHeight = Math.max(0, cardHeight - urlBarHeight - footerHeight);

        int bodyTop = ctx.shadowMargin + urlBarHeight;

        CardScene.Pane reqPane = new CardScene.Pane(true, reqRows,
                ctx.shadowMargin, bodyTop,
                sideBySide ? colWidth : cardWidth,
                sideBySide ? bodyHeight : reqHeight,
                reqGutter);
        CardScene.Pane resPane = new CardScene.Pane(false, resRows,
                sideBySide ? ctx.shadowMargin + colWidth + 1 : ctx.shadowMargin,
                sideBySide ? bodyTop : bodyTop + reqHeight + 1,
                sideBySide ? cardWidth - colWidth - 1 : cardWidth,
                sideBySide ? bodyHeight : resHeight,
                resGutter);

        return new CardScene(ctx, exchange, config, cardWidth, cardHeight, naturalCardHeight,
                urlLayout, urlBarHeight, bodyHeight, sideBySide, colWidth, reqPane, resPane);
    }

    /**
     * Request pane width in side-by-side mode.
     *
     * <p>Clamped so neither pane can be dragged away: a divider pushed to the far edge would
     * leave a section with no room for a single glyph, which reads as a rendering fault rather
     * than as a layout choice.
     */
    private static int columnWidth(TemplateConfig config, int cardWidth) {
        int wanted = (int) Math.round((cardWidth - 1) * config.getSplitRatio());
        int limit = cardWidth - 1 - MIN_PANE_WIDTH;
        if (limit < MIN_PANE_WIDTH) return (cardWidth - 1) / 2;
        return Math.max(MIN_PANE_WIDTH, Math.min(limit, wanted));
    }

    // ------------------------------------------------------------------ paint

    /**
     * Paints the whole scene onto a surface whose user space is in unscaled card units.
     *
     * <p>Callers that only look at part of the card should pass {@code visible}: it limits the
     * shadow blur to that rectangle and lets the section painter skip rows outside it, which
     * is what keeps a long response cheap to scroll.
     */
    public static void paint(Graphics2D g, CardScene scene, double scale) {
        paint(g, scene, scale, null);
    }

    public static void paint(Graphics2D g, CardScene scene, double scale, Rectangle visible) {
        paint(g, scene, scale, visible, null, true);
    }

    /**
     * @param visible      part of the card being looked at, or {@code null} for all of it
     * @param target       image to blur redactions into, or {@code null} to paint them as tiles
     * @param blurEnabled  false while painting one of those tiles, which would otherwise recurse
     */
    private static void paint(Graphics2D g, CardScene scene, double scale, Rectangle visible,
                              BufferedImage target, boolean blurEnabled) {
        RenderContext ctx = scene.ctx;

        RenderContext.applyHints(g);
        g.scale(scale, scale);

        Shape outerClip = g.getClip();
        if (visible != null) g.clipRect(visible.x, visible.y, visible.width, visible.height);

        // ---------------------------------------------------------------- chrome

        CardChrome.paintShadow(g, ctx, scene.totalWidth, scene.totalHeight,
                scene.cardX, scene.cardY, scene.cardWidth, scene.cardHeight, visible);
        CardChrome.paintCard(g, ctx, scene.cardShape);

        g.clip(scene.cardShape);

        int curY = scene.cardY;
        CardChrome.paintUrlBar(g, ctx, scene.cardX, curY, scene.cardWidth, scene.urlLayout);
        curY += scene.urlBarHeight;

        // ---------------------------------------------------------------- sections

        List<SectionPainter.Mark> marks = new ArrayList<>();

        if (scene.bodyHeight > 0) {
            // The body is clipped on its own, not just by the card: with a crop the footer
            // tucks up over where the sections would otherwise continue, and a footer drawn
            // over live glyphs is unreadable.
            Shape cardClip = g.getClip();
            g.clipRect(scene.cardX, scene.bodyTop, scene.cardWidth, scene.bodyHeight);

            paintPane(g, ctx, scene.request, marks);
            if (scene.sideBySide) {
                g.setColor(ctx.tokens.border);
                g.fillRect(scene.splitX(), scene.bodyTop, 1, scene.bodyHeight);
                CardChrome.handle(g, ctx, scene.splitX(), scene.bodyTop + scene.bodyHeight / 2);
                paintPane(g, ctx, scene.response, marks);
            } else {
                g.setColor(ctx.tokens.border);
                g.fillRect(scene.cardX, scene.response.secY - 1, scene.cardWidth, 1);
                paintPane(g, ctx, scene.response, marks);
            }

            CardChrome.paintWatermark(g, ctx, scene.cardX, scene.bodyTop,
                    scene.cardWidth, scene.bodyHeight);

            // No filtering against the visible rectangle here: a mark only exists because the
            // row it belongs to was drawn, and rows outside the rectangle were skipped.
            paintHighlights(g, ctx, marks);
            paintRedactions(g, scene, scale, marks, target, blurEnabled);

            g.setClip(cardClip);
        }

        // ---------------------------------------------------------------- footer

        curY = scene.bodyTop + scene.bodyHeight;
        CardChrome.paintFooter(g, ctx, scene.cardX, curY, scene.cardWidth,
                (int) Math.min(Integer.MAX_VALUE, scene.exchange.getResponseSizeBytes()),
                scene.exchange.getResponseDurationMs());

        g.setClip(outerClip);
    }

    private static void paintPane(Graphics2D g, RenderContext ctx,
                                  CardScene.Pane pane, List<SectionPainter.Mark> marks) {
        SectionPainter.paint(g, ctx, pane.isRequest ? "Request" : "Response", pane.rows,
                pane.secX, pane.secY, pane.secW, pane.secH, pane.gutterW,
                pane.isRequest, marks);
    }

    private static void paintHighlights(Graphics2D g, RenderContext ctx, List<SectionPainter.Mark> marks) {
        for (SectionPainter.Mark mark : marks) {
            if (mark.redaction) continue;
            Color c = mark.color != null ? mark.color : new Color(0xf5be28);
            // The wash carries the highlight and the outline only supports it. At a low alpha the
            // wash disappears against the dark card and what is left is a box around the value,
            // which reads as a mistake rather than as emphasis.
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 80));
            g.fillRoundRect(mark.rect.x - 2, mark.rect.y, mark.rect.width + 4, mark.rect.height, 4, 4);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 150));
            g.drawRoundRect(mark.rect.x - 2, mark.rect.y, mark.rect.width + 4, mark.rect.height, 4, 4);
        }
    }

    /**
     * Blur first, then blackout, so a blackout is never smeared by a neighbouring blur. The
     * radius is deliberately generous: a redaction that can still be read is worse than no
     * redaction, because the author believes the secret is hidden.
     */
    private static void paintRedactions(Graphics2D g, CardScene scene, double scale,
                                        List<SectionPainter.Mark> marks,
                                        BufferedImage target, boolean blurEnabled) {
        int radius = Math.max(4, (int) Math.round(9 * scale));

        if (blurEnabled) {
            if (target != null) {
                for (SectionPainter.Mark mark : marks) {
                    if (!mark.redaction || !mark.blur) continue;
                    blurMark(target, mark, scale, radius);
                }
            } else if (hasBlur(marks)) {
                paintBlurBand(g, scene, scale, marks, radius);
            }
        }

        g.setColor(scene.dark ? new Color(0x0d0d14) : new Color(0x24292f));
        for (SectionPainter.Mark mark : marks) {
            if (!mark.redaction || mark.blur) continue;
            g.fillRoundRect(mark.rect.x - 2, mark.rect.y, mark.rect.width + 4, mark.rect.height, 4, 4);
        }

        g.setColor(scene.ctx.tokens.border);
        for (SectionPainter.Mark mark : marks) {
            if (!mark.redaction || mark.blur) continue;
            g.drawRoundRect(mark.rect.x - 2, mark.rect.y, mark.rect.width + 4, mark.rect.height, 4, 4);
        }
    }

    private static boolean hasBlur(List<SectionPainter.Mark> marks) {
        for (SectionPainter.Mark mark : marks) {
            if (mark.redaction && mark.blur) return true;
        }
        return false;
    }

    private static void blurMark(BufferedImage image, SectionPainter.Mark mark, double scale, int radius) {
        int x = (int) Math.floor(mark.rect.x * scale) - 2;
        int y = (int) Math.floor(mark.rect.y * scale) - 1;
        int w = (int) Math.ceil(mark.rect.width * scale) + 4;
        int h = (int) Math.ceil(mark.rect.height * scale) + 2;

        // Adding a pixel of slack on each side keeps the rounded edge of the neighbouring
        // glyphs from being eaten, while the blur itself is sampled from a wider patch.
        BlurFilter.blurRegion(image, x, y, w, h, radius + 2, radius);
    }

    /**
     * Blurred redactions without an image to blur in place.
     *
     * <p>One raster covers every blur mark's patch, painted by this same method with blurring
     * switched off, and then the marks are blurred into it in order. Because that raster holds
     * exactly the pixels the full image holds over the same rectangle, and every patch it is
     * asked to blur lies inside it, the result is the same as blurring the full image. Drawing
     * one raster rather than one per mark also means a mark blurred later sees an earlier
     * mark's blur, which is what the image path does.
     */
    private static void paintBlurBand(Graphics2D g, CardScene scene, double scale,
                                      List<SectionPainter.Mark> marks, int radius) {
        int imageWidth = Math.max(1, (int) Math.round(scene.totalWidth * scale));
        int imageHeight = Math.max(1, (int) Math.round(scene.totalHeight * scale));
        int pad = radius + 2;

        // Union of the patches BlurFilter.blurRegion would copy, in device pixels.
        int ux0 = Integer.MAX_VALUE;
        int uy0 = Integer.MAX_VALUE;
        int ux1 = Integer.MIN_VALUE;
        int uy1 = Integer.MIN_VALUE;
        for (SectionPainter.Mark mark : marks) {
            if (!mark.redaction || !mark.blur) continue;
            int x = (int) Math.floor(mark.rect.x * scale) - 2;
            int y = (int) Math.floor(mark.rect.y * scale) - 1;
            int w = (int) Math.ceil(mark.rect.width * scale) + 4;
            int h = (int) Math.ceil(mark.rect.height * scale) + 2;
            ux0 = Math.min(ux0, Math.max(0, x - pad));
            uy0 = Math.min(uy0, Math.max(0, y - pad));
            ux1 = Math.max(ux1, Math.min(imageWidth, x + w + pad));
            uy1 = Math.max(uy1, Math.min(imageHeight, y + h + pad));
        }
        if (ux1 <= ux0 || uy1 <= uy0) return;

        int lx0 = (int) Math.floor(ux0 / scale);
        int ly0 = (int) Math.floor(uy0 / scale);
        // Ceil, not round: the raster has to reach the far edge of the union even when that
        // edge falls a fraction of a pixel short of the next whole one.
        int lx1 = (int) Math.ceil(ux1 / scale);
        int ly1 = (int) Math.ceil(uy1 / scale);

        int rx0 = (int) Math.floor(lx0 * scale);
        int ry0 = (int) Math.floor(ly0 * scale);
        int rw = Math.max(1, (int) Math.ceil(lx1 * scale) - rx0);
        int rh = Math.max(1, (int) Math.ceil(ly1 * scale) - ry0);

        BufferedImage raster = new BufferedImage(rw, rh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D rg = raster.createGraphics();
        rg.translate(-rx0, -ry0);
        paint(rg, scene, scale, new Rectangle(lx0, ly0, lx1 - lx0, ly1 - ly0), null, false);
        rg.dispose();

        for (SectionPainter.Mark mark : marks) {
            if (!mark.redaction || !mark.blur) continue;
            blurMark(raster, new SectionPainter.Mark(
                            new Rectangle(mark.rect.x - lx0, mark.rect.y - ly0,
                                    mark.rect.width, mark.rect.height),
                            mark.color, mark.redaction, mark.blur),
                    scale, radius);
        }

        Graphics2D ig = (Graphics2D) g.create();
        ig.setTransform(new AffineTransform());
        ig.drawImage(raster, rx0, ry0, null);
        ig.dispose();
    }

    /** Exposed so the preview and the test can render a sample without a Burp instance. */
    public static BufferedImage renderSample(TemplateConfig config, int width, double scale, boolean dark) {
        return render(HttpExchangeData.createSampleData(), config, width, scale, dark);
    }

    /** Convenience for callers that only want the palette for the current theme. */
    public static SyntaxPalette palette() {
        return Theme.isDark() ? SyntaxPalette.DARK : SyntaxPalette.LIGHT;
    }

    // ------------------------------------------------------------------ raster

    /** Renders the whole card at the template's scale factor. Save and Copy use this. */
    public static BufferedImage render(HttpExchangeData exchange, TemplateConfig config,
                                       int baseWidth, double scale, boolean dark) {
        return render(exchange, config, baseWidth, scale, dark, 0);
    }

    /**
     * Rasterises the card, optionally cropped to {@code cropCardHeight}.
     *
     * <p>This is the only path that allocates a full canvas. Everything the preview shows is
     * painted directly, which is the difference between a drag that tracks the pointer and a
     * drag that lags behind it.
     */
    public static BufferedImage render(HttpExchangeData exchange, TemplateConfig config,
                                       int baseWidth, double scale, boolean dark,
                                       int cropCardHeight) {
        CardScene scene = measure(exchange, config, baseWidth, dark, cropCardHeight);
        double factor = scale > 0 ? scale : 1.0;

        int imageWidth = Math.max(1, (int) Math.round(scene.totalWidth * factor));
        int imageHeight = Math.max(1, (int) Math.round(scene.totalHeight * factor));

        BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        paint(g, scene, factor, null, image, true);
        g.dispose();
        return image;
    }
}
