package burp.screenshot.engine;

import burp.screenshot.design.Tokens;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.util.List;

/**
 * Draws the parts of the card that are not code: shadow, background, URL bar and footer.
 *
 * <p>Section labels are text over a hairline rather than a filled band, which is the
 * "clean" card style chosen for the output image.
 */
public final class CardChrome {

    public static final Color MAC_RED = new Color(0xff5f56);
    public static final Color MAC_YELLOW = new Color(0xffbd2e);
    public static final Color MAC_GREEN = new Color(0x27c93f);

    public static final int URL_BAR_HEIGHT = 40;
    public static final int FOOTER_HEIGHT = 30;
    public static final int SECTION_HEADER_HEIGHT = 30;
    /** Space between the section hairline and the first row's box. */
    public static final int SECTION_PADDING_TOP = 6;
    /** Bottom padding inside a section, below the last code line. */
    public static final int SECTION_PADDING_BOTTOM = 10;
    /** Space between the gutter's right edge and the first glyph of a row. */
    public static final int TEXT_INSET = 12;

    /** Vertical padding inside the URL bar, above the address box and below it. */
    private static final int URL_BAR_PADDING = 6;
    /** Lines the address box may grow to before the last line is shortened instead. */
    public static final int MAX_URL_LINES = 3;

    private CardChrome() {}

    /**
     * Height of a section holding {@code rows} lines.
     *
     * <p>Shared with the hit test. A row sits at
     * {@code secY + SECTION_HEADER_HEIGHT + SECTION_PADDING_TOP + index * lineHeight}, and if
     * that arithmetic lived in two places the click target would drift from the glyphs with
     * nothing to report it.
     */
    public static int sectionHeight(RenderContext ctx, int rows) {
        return SECTION_HEADER_HEIGHT + SECTION_PADDING_TOP
                + rows * ctx.lineHeight + SECTION_PADDING_BOTTOM;
    }

    /**
     * A wrapped address, measured once so the card height and the paint agree.
     *
     * <p>The height has to be known before the card is sized, and the wrap count is what
     * determines it, so the two cannot be computed in separate passes.
     */
    public record UrlLayout(List<List<SyntaxHighlighter.Token>> lines, int height, int lineHeight,
                            int innerWidth) {}

    public static Shape cardShape(RenderContext ctx, int x, int y, int w, int h) {
        return new RoundRectangle2D.Float(x, y, w, h, ctx.cardRadius, ctx.cardRadius);
    }

    /**
     * Paints the drop shadow. Called before the card so it sits behind everything.
     *
     * <p>Takes the canvas size as numbers rather than as an image, so the preview can paint
     * onto a component without one. {@code wanted} limits the blur to the part of the canvas
     * that will be looked at; {@code null} produces the whole shadow.
     */
    public static void paintShadow(Graphics2D g, RenderContext ctx,
                                   int canvasWidth, int canvasHeight,
                                   int cardX, int cardY, int cardW, int cardH,
                                   Rectangle wanted) {
        if (!ctx.config.isDropShadow() || ctx.shadowMargin <= 0) return;

        // Dark cards need a heavier shadow to read against a dark background.
        float peak = ctx.dark ? 0.55f : 0.28f;
        Shape spread = new RoundRectangle2D.Float(
                cardX, cardY, cardW, cardH, ctx.cardRadius, ctx.cardRadius);

        BlurFilter.Shadow shadow = BlurFilter.softShadow(canvasWidth, canvasHeight,
                spread, ctx.shadowMargin, peak, wanted);
        if (shadow != null) g.drawImage(shadow.image(), shadow.x(), shadow.y(), null);
    }

    public static void paintCard(Graphics2D g, RenderContext ctx, Shape shape) {
        g.setColor(ctx.tokens.bgCard);
        g.fill(shape);
        g.setColor(ctx.tokens.border);
        g.setStroke(new BasicStroke(1f));
        g.draw(shape);
    }

    /** Left edge of the address box: past the Mac dots, or past the {@code URL} label. */
    private static int urlTextLeft(RenderContext ctx, int cardX) {
        return "Mac".equalsIgnoreCase(ctx.config.getWindowStyle()) ? cardX + 80 : cardX + 54;
    }

    /**
     * Wraps the address into at most {@link #MAX_URL_LINES} lines and reports the bar height.
     *
     * <p>Called during layout: the card height depends on this, and the paint pass reuses the
     * returned lines rather than wrapping a second time, so the two cannot disagree.
     */
    public static UrlLayout urlLayout(RenderContext ctx, int width) {
        int textLeft = urlTextLeft(ctx, 0);
        int boxW = Math.max(20, width - textLeft - 16);
        int innerW = Math.max(20, boxW - 20);

        String url = ctx.exchange.getUrl() != null ? ctx.exchange.getUrl() : "";
        List<SyntaxHighlighter.Token> tokens = SyntaxHighlighter.targetTokens(url);

        int lineHeight = Math.max(1, ctx.fmCode.getHeight());
        List<List<SyntaxHighlighter.Token>> lines =
                TokenWrap.wrap(ctx, tokens, innerW, MAX_URL_LINES);

        int height = Math.max(URL_BAR_HEIGHT, lines.size() * lineHeight + URL_BAR_PADDING * 2);
        return new UrlLayout(lines, height, lineHeight, innerW);
    }

    /**
     * URL bar with the address broken into coloured tokens.
     *
     * <p>The address wraps rather than running off the card. A long URL is the normal case in
     * a PoC, and a single line cut in the middle hides the parameter that identifies the
     * request.
     */
    public static void paintUrlBar(Graphics2D g, RenderContext ctx, int x, int y, int width,
                                   UrlLayout layout) {
        if (!ctx.config.isShowUrl() || layout == null) return;

        Tokens t = ctx.tokens;
        int height = layout.height();
        int lineHeight = layout.lineHeight();

        g.setColor(t.bgPanel);
        g.fillRect(x, y, width, height);

        int textLeft = urlTextLeft(ctx, x);
        int boxX = textLeft;
        int boxY = y + URL_BAR_PADDING;
        int boxW = Math.max(20, width - textLeft - 16);
        int boxH = height - URL_BAR_PADDING * 2;

        // The first baseline sits where the single-line bar always put it, so a short URL
        // renders exactly as before; extra lines grow downwards from there.
        int firstBaseline = boxY + (boxH - layout.lines().size() * lineHeight) / 2
                + ctx.fmCode.getAscent();
        int innerX = boxX + 10;
        int innerW = Math.max(20, boxW - 20);

        if ("Mac".equalsIgnoreCase(ctx.config.getWindowStyle())) {
            int dotY = firstBaseline - ctx.fmCode.getAscent() + (lineHeight - 12) / 2;
            g.setColor(MAC_RED);
            g.fillOval(x + 16, dotY, 12, 12);
            g.setColor(MAC_YELLOW);
            g.fillOval(x + 36, dotY, 12, 12);
            g.setColor(MAC_GREEN);
            g.fillOval(x + 56, dotY, 12, 12);
        } else {
            g.setFont(ctx.uiBold);
            g.setColor(t.textMuted);
            FontMetrics fm = ctx.fmUiBold;
            g.drawString("URL", x + 16,
                    firstBaseline - ctx.fmCode.getAscent() + (lineHeight - fm.getHeight()) / 2
                            + fm.getAscent());
        }

        g.setColor(t.bgInput);
        g.fillRoundRect(boxX, boxY, boxW, boxH, 6, 6);
        g.setColor(t.border);
        g.drawRoundRect(boxX, boxY, boxW - 1, boxH - 1, 6, 6);

        List<List<SyntaxHighlighter.Token>> lines = layout.lines();
        for (int i = 0; i < lines.size(); i++) {
            List<SyntaxHighlighter.Token> line = lines.get(i);
            int baseline = firstBaseline + i * lineHeight;
            if (tokenWidth(ctx, line) <= innerW) {
                drawTokens(g, ctx, line, innerX, baseline, Integer.MAX_VALUE);
            } else {
                drawMiddleEllipsis(g, ctx, line, innerX, baseline, innerW, lineHeight);
            }
        }

        g.setColor(t.border);
        g.fillRect(x, y + height - 1, width, 1);
    }

    /** Timestamp on the left, response statistics on the right. */
    public static int paintFooter(Graphics2D g, RenderContext ctx, int x, int y, int width,
                                  int responseSizeBytes, long durationMs) {
        if (!ctx.config.isShowTimestamp() && !ctx.config.isShowResponseInfo()) return 0;

        Tokens t = ctx.tokens;
        g.setColor(t.border);
        g.fillRect(x, y, width, 1);

        g.setFont(ctx.ui);
        g.setColor(t.textMuted);

        int baseline = y + (FOOTER_HEIGHT - ctx.fmUi.getHeight()) / 2 + ctx.fmUi.getAscent();

        if (ctx.config.isShowTimestamp() && ctx.exchange.getTimestampFormatted() != null) {
            g.drawString(ctx.exchange.getTimestampFormatted(), x + 16, baseline);
        }

        if (ctx.config.isShowResponseInfo()) {
            String stats = String.format("%,d bytes  |  %,d ms", responseSizeBytes, durationMs);
            int w = ctx.fmUi.stringWidth(stats);
            g.drawString(stats, x + width - w - 16, baseline);
        }

        return FOOTER_HEIGHT;
    }

    /** Faint diagonal text over the body, drawn after the code so it overlays it. */
    public static void paintWatermark(Graphics2D g, RenderContext ctx,
                                      int x, int y, int w, int h) {
        String text = ctx.config.getWatermarkText();
        if (text == null || text.isBlank() || h <= 0) return;

        Font font = Tokens.sansBold(Math.max(18, Math.min(46, h / 8)));
        AffineTransform saved = g.getTransform();
        Composite savedComposite = g.getComposite();

        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, ctx.dark ? 0.10f : 0.13f));
        g.setFont(font);
        g.setColor(ctx.dark ? Color.WHITE : Color.BLACK);
        g.rotate(Math.toRadians(-24), x + w / 2.0, y + h / 2.0);

        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(text);
        g.drawString(text, x + w / 2 - tw / 2, y + h / 2 + fm.getAscent() / 2);

        g.setComposite(savedComposite);
        g.setTransform(saved);
    }

    // ------------------------------------------------------------------ token layout

    public static int tokenWidth(RenderContext ctx, List<SyntaxHighlighter.Token> tokens) {
        int width = 0;
        for (SyntaxHighlighter.Token token : tokens) {
            width += ctx.metricsFor(token.type).stringWidth(token.text);
        }
        return width;
    }

    /**
     * Draws tokens left to right, clipping the run at {@code maxWidth}.
     *
     * @return the x position after the last token drawn
     */
    public static int drawTokens(Graphics2D g, RenderContext ctx,
                                 List<SyntaxHighlighter.Token> tokens,
                                 int x, int baseline, int maxWidth) {
        Shape saved = g.getClip();
        int cursor = x;
        int limit = maxWidth == Integer.MAX_VALUE ? Integer.MAX_VALUE : x + maxWidth;

        for (SyntaxHighlighter.Token token : tokens) {
            Font font = ctx.fontFor(token.type);
            FontMetrics fm = ctx.metricsFor(token.type);
            int w = fm.stringWidth(token.text);

            if (cursor + w <= limit) {
                g.setFont(font);
                g.setColor(ctx.color(token.type));
                g.drawString(token.text, cursor, baseline);
                cursor += w;
                continue;
            }

            int room = limit - cursor;
            if (room > 0) {
                g.setFont(font);
                g.setColor(ctx.color(token.type));
                g.clipRect(cursor, baseline - fm.getAscent() - 4, room, fm.getHeight() + 8);
                g.drawString(token.text, cursor, baseline);
                g.setClip(saved);
                cursor += room;
            }
            break;
        }
        g.setClip(saved);
        return cursor;
    }

    /**
     * Truncates in the middle, which keeps the scheme, host and the tail of the path
     * visible. Truncating the tail instead hides the part of the URL that identifies the
     * request.
     *
     * <p>Head, ellipsis and tail get exclusive budgets. Sharing one budget is what made a long
     * URL paint over itself: the tail was placed from the right edge without subtracting the
     * ellipsis or the head, and nothing clipped the result.
     */
    private static void drawMiddleEllipsis(Graphics2D g, RenderContext ctx,
                                           List<SyntaxHighlighter.Token> tokens,
                                           int x, int baseline, int maxWidth, int lineHeight) {
        String ellipsis = "…";
        int ellipsisW = ctx.fmCode.stringWidth(ellipsis);
        int ascent = ctx.fmCode.getAscent();

        int headBudget = Math.max(10, (maxWidth - ellipsisW) / 2);
        int tailBudget = Math.max(0, maxWidth - headBudget - ellipsisW);

        int consumed = 0;
        int headEnd = 0;
        for (SyntaxHighlighter.Token token : tokens) {
            int w = ctx.metricsFor(token.type).stringWidth(token.text);
            if (consumed + w > headBudget) break;
            consumed += w;
            headEnd++;
        }
        // One token can be wider than the whole head budget, which on a wrapped URL is the
        // normal case: the remainder of a path segment arrives as a single token. Drawing it
        // clipped still shows the start of it, where drawing nothing shows a bare ellipsis.
        if (headEnd == 0 && !tokens.isEmpty()) {
            headEnd = 1;
            consumed = Math.min(headBudget, ctx.metricsFor(tokens.get(0).type)
                    .stringWidth(tokens.get(0).text));
        }
        int headWidth = consumed;

        int tailStart = tokens.size();
        int tailWidth = 0;
        for (int i = tokens.size() - 1; i >= headEnd; i--) {
            int w = ctx.metricsFor(tokens.get(i).type).stringWidth(tokens.get(i).text);
            if (tailWidth + w > tailBudget) break;
            tailWidth += w;
            tailStart = i;
        }
        // The head drew tokens up to headEnd exclusive, so a tail starting at headEnd adds no
        // duplicate. Requiring it to start past headEnd is what dropped the tail on a wrapped
        // URL, where the head stops inside the first remaining token.
        boolean drawTail = tailStart >= headEnd && tailWidth > 0 && headEnd < tokens.size();

        // Everything is confined to the box, so even a rounding error cannot spill past it.
        Shape saved = g.getClip();
        g.clipRect(x, baseline - ascent - 4, maxWidth, lineHeight + 8);

        int cursor = headEnd > 0
                ? drawTokens(g, ctx, tokens.subList(0, headEnd), x, baseline, headWidth)
                : x;

        g.setFont(ctx.code);
        g.setColor(ctx.tokens.textMuted);
        g.drawString(ellipsis, cursor, baseline);

        if (drawTail) {
            int tailX = x + maxWidth - tailWidth;
            drawTokens(g, ctx, tokens.subList(tailStart, tokens.size()), tailX, baseline, tailWidth);
        }

        g.setClip(saved);
    }

    /** Rounded hairline used to separate stacked sections. */
    public static void divider(Graphics2D g, RenderContext ctx, int x, int y, int width) {
        g.setColor(ctx.tokens.border);
        g.fillRect(x, y, width, 1);
    }

    /** Small grab handle drawn on the vertical divider of the side-by-side layout. */
    public static void handle(Graphics2D g, RenderContext ctx, int centerX, int centerY) {
        int w = 4;
        int h = 26;
        g.setColor(ctx.tokens.borderStrong);
        g.fillRoundRect(centerX - w / 2, centerY - h / 2, w, h, 3, 3);
    }
}
