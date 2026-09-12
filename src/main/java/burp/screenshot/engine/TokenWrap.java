package burp.screenshot.engine;

import burp.screenshot.design.TokenType;

import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.List;

/**
 * Greedy token wrapping, shared by the body sections and the URL bar.
 *
 * <p>Char advances are accumulated rather than re-measuring every candidate substring, which
 * keeps a minified single-line body from costing quadratic time.
 */
public final class TokenWrap {

    /** Characters a chunk may break after, when a natural break is available. */
    public static final String BREAK_CHARS = " ,;&?/|=+-";

    private TokenWrap() {}

    /** Total advance of {@code tokens} at the metrics their own type resolves to. */
    public static int width(RenderContext ctx, List<SyntaxHighlighter.Token> tokens) {
        int w = 0;
        for (SyntaxHighlighter.Token t : tokens) {
            w += ctx.metricsFor(t.type).stringWidth(t.text);
        }
        return w;
    }

    /** Wraps to as many lines as it takes. */
    public static List<List<SyntaxHighlighter.Token>> wrap(
            RenderContext ctx, List<SyntaxHighlighter.Token> tokens, int availWidth) {
        return wrap(ctx, tokens, availWidth, Integer.MAX_VALUE);
    }

    /**
     * Wraps to at most {@code maxLines} lines.
     *
     * <p>When the text does not fit, the last line holds the remainder whole: it is deliberately
     * allowed to be wider than {@code availWidth} so the caller can decide how to shorten it,
     * rather than having the overflow silently dropped here.
     */
    public static List<List<SyntaxHighlighter.Token>> wrap(
            RenderContext ctx, List<SyntaxHighlighter.Token> tokens, int availWidth, int maxLines) {

        // Flatten to characters tagged with the type they came from.
        StringBuilder text = new StringBuilder();
        List<TokenType> types = new ArrayList<>();
        for (SyntaxHighlighter.Token t : tokens) {
            for (int i = 0; i < t.text.length(); i++) {
                text.append(t.text.charAt(i));
                types.add(t.type);
            }
        }

        List<List<SyntaxHighlighter.Token>> chunks = new ArrayList<>();
        int len = text.length();
        int start = 0;

        while (start < len) {
            // The budget is spent. Hand back everything left as one over-wide line.
            if (chunks.size() == maxLines - 1) {
                chunks.add(slice(tokens, start, len));
                break;
            }

            int width = 0;
            int end = start;
            int lastBreak = -1;

            while (end < len) {
                TokenType type = types.get(end);
                FontMetrics fm = ctx.metricsFor(type);
                int advance = fm.charWidth(text.charAt(end));
                if (width + advance > availWidth && end > start) break;
                width += advance;
                if (BREAK_CHARS.indexOf(text.charAt(end)) >= 0) lastBreak = end + 1;
                end++;
            }

            if (end >= len) {
                chunks.add(slice(tokens, start, len));
                break;
            }

            // Prefer a natural break, but never one so early that the line is mostly empty.
            int minBreak = start + Math.max(1, (end - start) * 2 / 3);
            int breakAt = (lastBreak >= minBreak && lastBreak <= end) ? lastBreak : end;
            chunks.add(slice(tokens, start, breakAt));
            start = breakAt;
        }

        if (chunks.isEmpty()) chunks.add(new ArrayList<>(tokens));
        return chunks;
    }

    /** Slices the flattened character range back into tokens, preserving each type. */
    public static List<SyntaxHighlighter.Token> slice(List<SyntaxHighlighter.Token> tokens,
                                                     int start, int end) {
        List<SyntaxHighlighter.Token> out = new ArrayList<>();
        int cursor = 0;
        for (SyntaxHighlighter.Token t : tokens) {
            int len = t.text.length();
            int from = Math.max(start, cursor);
            int to = Math.min(end, cursor + len);
            if (from < to) {
                out.add(new SyntaxHighlighter.Token(t.text.substring(from - cursor, to - cursor), t.type));
            }
            cursor += len;
            if (cursor >= end) break;
        }
        if (out.isEmpty()) out.add(new SyntaxHighlighter.Token("", TokenType.TEXT));
        return out;
    }
}
