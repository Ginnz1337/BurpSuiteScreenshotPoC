package burp.screenshot.engine;

import burp.screenshot.design.TokenType;
import burp.screenshot.model.HttpExchangeData;
import burp.screenshot.model.TemplateConfig;

import java.awt.Shape;
import java.util.List;

/**
 * A card laid out and ready to paint, with nothing rasterised yet.
 *
 * <p>The previous renderer measured and painted in one pass and always produced a
 * {@link java.awt.image.BufferedImage} on the way, so every config change, every keystroke and
 * every drag step paid for a full-canvas allocation and a card-sized shadow blur. Measuring
 * produces this instead, and {@link ScreenshotRenderer#paint} draws it onto whatever surface
 * the caller has. The preview paints the visible part of a component; Save and Copy allocate
 * an image and paint the whole thing.
 *
 * <p>Everything here is derived from the exchange and the config. Nothing is derived from a
 * raster, which is why {@code render} can be proven equal to measure-then-paint.
 *
 * <p>Coordinate system: unscaled card units, origin at the canvas top-left. The shadow margin
 * is included, so the card itself starts at {@code cardX == cardY == shadowMargin}.
 */
public final class CardScene {

    /** One pane of the card, and everything needed to paint or hit-test it. */
    public static final class Pane {
        public final boolean isRequest;
        public final List<SectionPainter.Row> rows;
        public final int secX;
        public final int secY;
        public final int secW;
        public final int secH;
        public final int gutterW;

        Pane(boolean isRequest, List<SectionPainter.Row> rows,
             int secX, int secY, int secW, int secH, int gutterW) {
            this.isRequest = isRequest;
            this.rows = rows;
            this.secX = secX;
            this.secY = secY;
            this.secW = secW;
            this.secH = secH;
            this.gutterW = gutterW;
        }

        /** Left edge of the first glyph of a row. */
        public int textX() {
            return secX + gutterW + CardChrome.TEXT_INSET;
        }
    }

    /** The token under a point, in unscaled card units. */
    public record Hit(boolean request, String text, TokenType type) {}

    public final RenderContext ctx;
    public final HttpExchangeData exchange;
    public final TemplateConfig config;
    public final boolean dark;

    public final int cardX;
    public final int cardY;
    public final int cardWidth;
    /** Card height after the crop, so the bottom edge, corners and shadow are all correct. */
    public final int cardHeight;
    /** Card height the content would need. Equal to {@link #cardHeight} when nothing is cropped. */
    public final int naturalCardHeight;
    public final Shape cardShape;

    public final CardChrome.UrlLayout urlLayout;
    public final int urlBarHeight;
    public final int footerHeight;
    /** Top of the section area, below the URL bar. */
    public final int bodyTop;
    /** Height of the section area after the crop. The crop shortens this, not the card chrome. */
    public final int bodyHeight;

    public final boolean sideBySide;
    /** Width of the request pane in side-by-side mode. Unused when stacked. */
    public final int colWidth;
    public final Pane request;
    public final Pane response;

    public final int totalWidth;
    public final int totalHeight;

    CardScene(RenderContext ctx, HttpExchangeData exchange, TemplateConfig config,
              int cardWidth, int cardHeight, int naturalCardHeight,
              CardChrome.UrlLayout urlLayout, int urlBarHeight, int bodyHeight,
              boolean sideBySide, int colWidth, Pane request, Pane response) {
        this.ctx = ctx;
        this.exchange = exchange;
        this.config = config;
        this.dark = ctx.dark;

        this.cardX = ctx.shadowMargin;
        this.cardY = ctx.shadowMargin;
        this.cardWidth = cardWidth;
        this.cardHeight = cardHeight;
        this.naturalCardHeight = naturalCardHeight;
        this.cardShape = CardChrome.cardShape(ctx, cardX, cardY, cardWidth, cardHeight);

        this.urlLayout = urlLayout;
        this.urlBarHeight = urlBarHeight;
        this.footerHeight = (config.isShowTimestamp() || config.isShowResponseInfo())
                ? CardChrome.FOOTER_HEIGHT : 0;
        this.bodyTop = cardY + urlBarHeight;
        this.bodyHeight = bodyHeight;

        this.sideBySide = sideBySide;
        this.colWidth = colWidth;
        this.request = request;
        this.response = response;

        this.totalWidth = cardWidth + ctx.shadowMargin * 2;
        this.totalHeight = cardHeight + ctx.shadowMargin * 2;
    }

    /** True when the card is showing less than its full content height. */
    public boolean isCropped() {
        return cardHeight < naturalCardHeight;
    }

    /** Bottom edge of the visible body, in card units. */
    public int bodyBottom() {
        return bodyTop + bodyHeight;
    }

    /**
     * X of the divider between the panes, or {@code -1} when the panes are stacked.
     *
     * <p>The divider is one pixel wide and sits at the end of the request pane.
     */
    public int splitX() {
        return sideBySide ? cardX + colWidth : -1;
    }

    /**
     * The token at a point, or {@code null} when the point is not on a glyph.
     *
     * <p>Walks the row's tokens with the same metrics {@code SectionPainter.drawRow} advances
     * by, so the token reported here is the token drawn there.
     */
    public Hit hitTest(int x, int y) {
        if (y < bodyTop || y >= bodyBottom()) return null;

        Pane pane = paneAt(x);
        int codeTop = pane.secY + CardChrome.SECTION_HEADER_HEIGHT;
        int rel = y - (codeTop + CardChrome.SECTION_PADDING_TOP);
        if (rel < 0) return null;

        int index = rel / Math.max(1, ctx.lineHeight);
        if (index >= pane.rows.size()) return null;

        SectionPainter.Row row = pane.rows.get(index);
        if (row.omission) return null;

        int textX = pane.textX();
        if (x < textX) return null;

        int cursor = textX;
        for (SyntaxHighlighter.Token token : row.tokens) {
            int width = ctx.metricsFor(token.type).stringWidth(token.text);
            if (x < cursor + width) return new Hit(pane.isRequest, token.text, token.type);
            cursor += width;
        }
        return null;
    }

    private Pane paneAt(int x) {
        if (!sideBySide) return request;
        return x < cardX + colWidth + 1 ? request : response;
    }

    /**
     * The line range still visible after a crop, as {@code "1-12"}, or {@code null} when the
     * crop does not cut into this pane.
     *
     * <p>Rows are laid out from the top down, so the visible set is always a prefix and one
     * range is enough. The first line is not assumed to be 1: a template with its own line
     * ranges starts wherever it starts.
     */
    public String visibleLineRange(boolean requestSide) {
        if (!isCropped()) return null;

        Pane pane = requestSide ? request : response;
        int codeTop = pane.secY + CardChrome.SECTION_HEADER_HEIGHT + CardChrome.SECTION_PADDING_TOP;

        int first = -1;
        int last = -1;
        for (int i = 0; i < pane.rows.size(); i++) {
            // A row only counts when it is wholly above the cut: half a glyph in a saved
            // screenshot reads as a mistake.
            if (codeTop + (i + 1) * ctx.lineHeight > bodyBottom()) break;
            int number = pane.rows.get(i).lineNumber;
            if (number <= 0) continue;
            if (first < 0) first = number;
            last = number;
        }

        if (first < 0) return null;
        return first == last ? String.valueOf(first) : first + "-" + last;
    }
}
