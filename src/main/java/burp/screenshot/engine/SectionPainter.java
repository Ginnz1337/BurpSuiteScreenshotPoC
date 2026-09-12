package burp.screenshot.engine;

import burp.screenshot.design.TokenType;
import burp.screenshot.design.Tokens;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.List;

/**
 * Lays out and draws one Request or Response block.
 *
 * <p>Every advance is measured in the font the text is actually drawn with. The previous
 * renderer drew bold tokens with the bold face but advanced the pen by the regular face's
 * metrics, so every token after a bold one drifted right and the highlight rectangles that
 * followed drifted with it.
 *
 * <p>Highlight and redaction rectangles are produced during drawing, from the same prefix
 * widths used to place the glyphs, so they cannot disagree with the text.
 */
public final class SectionPainter {

    /** A highlight or redaction to paint, in unscaled card coordinates. */
    public static final class Mark {
        public final Rectangle rect;
        public final Color color;
        public final boolean redaction;
        public final boolean blur;

        Mark(Rectangle rect, Color color, boolean redaction, boolean blur) {
            this.rect = rect;
            this.color = color;
            this.redaction = redaction;
            this.blur = blur;
        }
    }

    /** One rendered line, after wrapping. */
    public static final class Row {
        public final int lineNumber;      // -1 on a wrapped continuation
        public final boolean omission;
        public final List<SyntaxHighlighter.Token> tokens;
        public final String text;         // concatenation of tokens, for rule matching
        public final int charOffset;      // offset of this chunk inside the source line
        public final String sourceLine;   // the unwrapped line, for rule matching

        Row(int lineNumber, boolean omission, List<SyntaxHighlighter.Token> tokens,
            int charOffset, String sourceLine) {
            this.lineNumber = lineNumber;
            this.omission = omission;
            this.tokens = tokens;
            this.charOffset = charOffset;
            this.sourceLine = sourceLine;
            this.text = SyntaxHighlighter.join(tokens);
        }
    }

    private SectionPainter() {}

    // ------------------------------------------------------------------ layout

    public static int gutterWidth(RenderContext ctx, int maxLineNumber) {
        String digits = String.valueOf(Math.max(100, maxLineNumber));
        return Math.max(34, ctx.fmCode.stringWidth(digits) + 20);
    }

    public static int maxLineNumber(TextProcessor.ProcessedHttp data) {
        int max = 1;
        if (data == null || data.lines == null) return max;
        for (TextProcessor.LineItem item : data.lines) {
            if (item.originalLineNumber > max) max = item.originalLineNumber;
        }
        return max;
    }

    public static List<Row> layout(RenderContext ctx, TextProcessor.ProcessedHttp data,
                                   int availWidth, boolean wrap) {
        List<Row> rows = new ArrayList<>();
        if (data == null || data.lines == null) return rows;

        for (TextProcessor.LineItem item : data.lines) {
            String line = item.text != null ? item.text : "";

            if (item.isOmission) {
                List<SyntaxHighlighter.Token> t = new ArrayList<>();
                t.add(new SyntaxHighlighter.Token(line, TokenType.OMISSION));
                rows.add(new Row(-1, true, t, 0, line));
                continue;
            }

            List<SyntaxHighlighter.Token> tokens =
                    SyntaxHighlighter.tokenize(line, item.kind);

            if (!wrap || measure(ctx, tokens) <= availWidth) {
                rows.add(new Row(item.originalLineNumber, false, tokens, 0, line));
                continue;
            }

            List<List<SyntaxHighlighter.Token>> chunks =
                    wrapTokens(ctx, tokens, availWidth);
            int offset = 0;
            boolean first = true;
            for (List<SyntaxHighlighter.Token> chunk : chunks) {
                rows.add(new Row(first ? item.originalLineNumber : -1, false, chunk, offset, line));
                offset += SyntaxHighlighter.join(chunk).length();
                first = false;
            }
        }
        return rows;
    }

    private static int measure(RenderContext ctx, List<SyntaxHighlighter.Token> tokens) {
        return TokenWrap.width(ctx, tokens);
    }

    /**
     * Greedy wrap that prefers breaking after a separator in the last third of the chunk.
     *
     * <p>Lives in {@link TokenWrap} because the URL bar wraps by the same rules.
     */
    private static List<List<SyntaxHighlighter.Token>> wrapTokens(
            RenderContext ctx, List<SyntaxHighlighter.Token> tokens, int availWidth) {
        return TokenWrap.wrap(ctx, tokens, availWidth);
    }

    // ------------------------------------------------------------------ width helpers

    /** Advance from the start of {@code tokens} to character {@code offset}. */
    public static int widthAt(RenderContext ctx, List<SyntaxHighlighter.Token> tokens, int offset) {
        int width = 0;
        int remaining = offset;
        for (SyntaxHighlighter.Token t : tokens) {
            int len = t.text.length();
            if (remaining <= 0) break;
            if (remaining >= len) {
                width += ctx.metricsFor(t.type).stringWidth(t.text);
                remaining -= len;
            } else {
                width += ctx.metricsFor(t.type).stringWidth(t.text.substring(0, remaining));
                remaining = 0;
            }
        }
        return width;
    }

    // ------------------------------------------------------------------ paint

    public static int paint(Graphics2D g, RenderContext ctx,
                            String title, List<Row> rows,
                            int secX, int secY, int secW, int secH, int gutterW,
                            boolean isRequest, List<Mark> marks) {

        Tokens t = ctx.tokens;

        // Header label and hairline instead of a filled band: the clean card style.
        g.setFont(ctx.uiBold);
        g.setColor(t.textPrimary);
        FontMetrics fmUi = ctx.fmUiBold;
        g.drawString(title, secX + gutterW + CardChrome.TEXT_INSET,
                secY + (CardChrome.SECTION_HEADER_HEIGHT - fmUi.getHeight()) / 2 + fmUi.getAscent());

        g.setColor(t.border);
        g.fillRect(secX, secY + CardChrome.SECTION_HEADER_HEIGHT - 1, secW, 1);

        int codeTop = secY + CardChrome.SECTION_HEADER_HEIGHT;

        g.setColor(t.bgGutter);
        g.fillRect(secX, codeTop, gutterW, secH - CardChrome.SECTION_HEADER_HEIGHT);
        g.setColor(t.border);
        g.fillRect(secX + gutterW - 1, codeTop, 1, secH - CardChrome.SECTION_HEADER_HEIGHT);

        int textX = secX + gutterW + CardChrome.TEXT_INSET;
        int maxRight = secX + secW - 8;
        int lineY = codeTop + CardChrome.SECTION_PADDING_TOP;

        // The clip starts at secX, not at the text column: the line numbers live inside the
        // gutter and would otherwise be clipped away entirely.
        Shape savedClip = g.getClip();
        Shape textClip = new Rectangle(secX, codeTop, secW, secH - CardChrome.SECTION_HEADER_HEIGHT);
        g.clip(textClip);
        Rectangle visible = g.getClipBounds();

        for (Row row : rows) {
            int baseline = lineY + ctx.lineAscent;

            // Java2D would discard these pixels anyway, but it still walks every draw call to
            // do it, and a long response is thousands of calls per frame. The clip is already
            // the visible area: the card shape on the export path, the viewport on screen.
            if (visible != null
                    && (baseline + ctx.fmCode.getDescent() < visible.y
                        || baseline - ctx.lineAscent > visible.y + visible.height)) {
                lineY += ctx.lineHeight;
                continue;
            }

            if (row.omission) {
                g.setFont(ctx.codeItalic);
                g.setColor(ctx.color(TokenType.OMISSION));
                g.drawString(row.text, textX, baseline);
            } else {
                if (row.lineNumber > 0) {
                    g.setFont(ctx.code);
                    g.setColor(ctx.color(TokenType.LINE_NUMBER));
                    String number = String.valueOf(row.lineNumber);
                    g.drawString(number, secX + gutterW - 8 - ctx.fmCode.stringWidth(number), baseline);
                }
                drawRow(g, ctx, row, textX, baseline, maxRight, marks, isRequest);
            }

            lineY += ctx.lineHeight;
        }

        g.setClip(savedClip);
        return lineY;
    }

    private static void drawRow(Graphics2D g, RenderContext ctx, Row row,
                                int textX, int baseline, int maxRight,
                                List<Mark> marks, boolean isRequest) {

        int cursor = textX;
        int drawnWidth = 0;

        for (SyntaxHighlighter.Token token : row.tokens) {
            Font font = ctx.fontFor(token.type);
            FontMetrics fm = ctx.metricsFor(token.type);
            g.setFont(font);
            g.setColor(ctx.color(token.type));
            g.drawString(token.text, cursor, baseline);
            cursor += fm.stringWidth(token.text);
            drawnWidth += fm.stringWidth(token.text);
        }

        collectMarks(ctx, row, textX, maxRight, baseline, drawnWidth, marks, isRequest);
    }

    /**
     * Produces highlight and redaction rectangles for a row.
     *
     * <p>Ranges are resolved against the unwrapped source line, then shifted by the chunk's
     * character offset, so a match that spans a wrap boundary still lands on the right glyphs
     * on both rows.
     */
    private static void collectMarks(RenderContext ctx, Row row, int textX, int maxRight,
                                     int baseline, int drawnWidth,
                                     List<Mark> marks, boolean isRequest) {
        if (row.sourceLine == null || row.sourceLine.isEmpty()) return;

        int chunkStart = row.charOffset;
        int chunkEnd = chunkStart + row.text.length();
        int top = baseline - ctx.lineAscent - 1;
        int height = ctx.lineHeight - 3;

        for (RenderContext.Rule rule : ctx.highlights) {
            if (!rule.appliesTo(isRequest)) continue;
            rule.find(row.sourceLine, (start, end) ->
                    addMark(ctx, row, marks, start, end, chunkStart, chunkEnd,
                            textX, maxRight, drawnWidth, top, height, rule.color, false, false));
        }

        for (RenderContext.Rule rule : ctx.redactions) {
            if (!rule.appliesTo(isRequest)) continue;
            boolean blur = rule.mode == burp.screenshot.model.RedactionMode.BLUR;
            rule.find(row.sourceLine, (start, end) ->
                    addMark(ctx, row, marks, start, end, chunkStart, chunkEnd,
                            textX, maxRight, drawnWidth, top, height, null, true, blur));
        }
    }

    private static void addMark(RenderContext ctx, Row row, List<Mark> marks,
                                int matchStart, int matchEnd, int chunkStart, int chunkEnd,
                                int textX, int maxRight, int drawnWidth,
                                int top, int height, Color color,
                                boolean redaction, boolean blur) {

        int overlapStart = Math.max(matchStart, chunkStart);
        int overlapEnd = Math.min(matchEnd, chunkEnd);
        if (overlapStart >= overlapEnd) return;

        int from = overlapStart - chunkStart;
        int to = overlapEnd - chunkStart;

        int startWidth = widthAt(ctx, row.tokens, from);
        int endWidth = widthAt(ctx, row.tokens, to);

        // A match that ends in spaces would otherwise draw a box past the visible glyphs.
        int boundedTo = Math.min(to, row.text.length());
        int trailing = stripTrailingSpaces(row.text.substring(Math.max(0, from), boundedTo));
        if (trailing > 0) endWidth -= widthAt(ctx, row.tokens, to) - widthAt(ctx, row.tokens, to - trailing);

        int x = textX + startWidth;
        int w = endWidth - startWidth;

        if (w <= 0) return;
        x = Math.max(x, textX);
        w = Math.min(w, Math.min(maxRight, textX + drawnWidth) - x);
        if (w <= 0) return;

        marks.add(new Mark(new Rectangle(x, top, w, height), color, redaction, blur));
    }

    private static int stripTrailingSpaces(String s) {
        int n = 0;
        for (int i = s.length() - 1; i >= 0 && s.charAt(i) == ' '; i--) n++;
        return n;
    }
}
