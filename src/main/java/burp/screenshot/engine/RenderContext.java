package burp.screenshot.engine;

import burp.screenshot.design.SyntaxPalette;
import burp.screenshot.design.Theme;
import burp.screenshot.design.TokenType;
import burp.screenshot.design.Tokens;
import burp.screenshot.model.HighlightRule;
import burp.screenshot.model.HttpExchangeData;
import burp.screenshot.model.RedactionMode;
import burp.screenshot.model.RedactionRule;
import burp.screenshot.model.ScopeTarget;
import burp.screenshot.model.TemplateConfig;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything the renderer needs, resolved once per image.
 *
 * <p>The point of this object is that {@link FontMetrics} is measured from a
 * {@link Graphics2D} that carries the same rendering hints as the one used to paint, and
 * that every rule pattern is compiled once rather than once per line. Both were sources of
 * visible error in the previous renderer: text drifted after a bold token, and a long
 * message recompiled the same regex hundreds of times.
 */
public final class RenderContext {

    /** A highlight or redaction rule with its pattern already compiled. */
    public static final class Rule {
        public final Pattern regex;
        public final String literal;
        public final int captureGroup;
        public final ScopeTarget target;
        public final Color color;
        public final RedactionMode mode;
        public final String source;

        Rule(String source, Pattern regex, String literal, int captureGroup,
             ScopeTarget target, Color color, RedactionMode mode) {
            this.source = source;
            this.regex = regex;
            this.literal = literal;
            this.captureGroup = captureGroup;
            this.target = target;
            this.color = color;
            this.mode = mode;
        }

        public boolean appliesTo(boolean request) {
            return request ? target.appliesToRequest() : target.appliesToResponse();
        }

        /** Visits every match in {@code line} as a half-open character range. */
        public void find(String line, RangeSink sink) {
            if (line == null || line.isEmpty()) return;
            if (regex == null) {
                if (literal == null || literal.isEmpty()) return;
                int from = 0;
                while (from <= line.length() - literal.length()) {
                    int at = line.indexOf(literal, from);
                    if (at < 0) return;
                    sink.accept(at, at + literal.length());
                    from = at + literal.length();
                }
                return;
            }

            Matcher m = regex.matcher(line);
            while (m.find()) {
                int start = m.start();
                int end = m.end();
                if (captureGroup > 0 && captureGroup <= m.groupCount()) {
                    start = m.start(captureGroup);
                    end = m.end(captureGroup);
                }
                if (start >= 0 && end > start) sink.accept(start, end);
            }
        }
    }

    public interface RangeSink {
        void accept(int start, int end);
    }

    // ------------------------------------------------------------------ state

    public final HttpExchangeData exchange;
    public final TemplateConfig config;
    public final boolean dark;
    public final Tokens tokens;
    public final SyntaxPalette palette;

    public final Font code;
    public final Font codeBold;
    public final Font codeItalic;
    public final Font ui;
    public final Font uiBold;

    public final FontMetrics fmCode;
    public final FontMetrics fmCodeBold;
    public final FontMetrics fmCodeItalic;
    public final FontMetrics fmUi;
    public final FontMetrics fmUiBold;

    public final int lineHeight;
    public final int lineAscent;

    public final List<Rule> redactions = new ArrayList<>();
    public final List<Rule> highlights = new ArrayList<>();

    public final int shadowMargin;
    public final int cardRadius;

    private RenderContext(HttpExchangeData exchange, TemplateConfig config, boolean dark,
                          Graphics2D measure) {
        this.exchange = exchange;
        this.config = config;
        this.dark = dark;
        this.tokens = Theme.tokensFor(dark);
        // Start from the built-in palette for this theme, then lay the template's
        // overrides on top. A template with no overrides therefore follows the theme.
        this.palette = dark ? SyntaxPalette.DARK.copy() : SyntaxPalette.LIGHT.copy();
        this.palette.applyHexMap(config.getSyntaxColors());

        this.code = Tokens.mono(13);
        this.codeBold = Tokens.monoBold(13);
        this.codeItalic = Tokens.monoItalic(13);
        this.ui = Tokens.sans(12);
        this.uiBold = Tokens.sansBold(12);

        this.fmCode = measure.getFontMetrics(code);
        this.fmCodeBold = measure.getFontMetrics(codeBold);
        this.fmCodeItalic = measure.getFontMetrics(codeItalic);
        this.fmUi = measure.getFontMetrics(ui);
        this.fmUiBold = measure.getFontMetrics(uiBold);

        this.lineHeight = Math.max(fmCode.getHeight(), fmCodeBold.getHeight()) + 5;
        this.lineAscent = Math.max(fmCode.getAscent(), fmCodeBold.getAscent());

        this.shadowMargin = config.isDropShadow() ? 16 : 0;
        this.cardRadius = config.isRoundedCorners() ? 12 : 0;

        compileRules();
    }

    /**
     * Resolves a context for one theme.
     *
     * <p>Carries no scale: the rasterisation scale is a property of the surface being painted
     * onto, not of the measurements, so a zoom change repaints without re-measuring.
     */
    public static RenderContext create(HttpExchangeData exchange, TemplateConfig config,
                                       boolean dark, Graphics2D measure) {
        return new RenderContext(exchange, config, dark, measure);
    }

    /** Applies the hint set shared by measurement and painting. They must not diverge. */
    public static void applyHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Grayscale, not LCD subpixel: LCD antialiasing fringes glyphs on a transparent ARGB
        // canvas, which is exactly what the exported PNG is.
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    // ------------------------------------------------------------------ tokens

    public Color color(TokenType type) {
        return palette.color(type);
    }

    /** Bold tokens are drawn with the bold face, so they must also advance by its metrics. */
    public Font fontFor(TokenType type) {
        return SyntaxPalette.isBold(type) ? codeBold : code;
    }

    public FontMetrics metricsFor(TokenType type) {
        return SyntaxPalette.isBold(type) ? fmCodeBold : fmCode;
    }

    public FontMetrics metricsFor(Font font) {
        if (font == codeBold) return fmCodeBold;
        if (font == codeItalic) return fmCodeItalic;
        if (font == code) return fmCode;
        if (font == ui) return fmUi;
        if (font == uiBold) return fmUiBold;
        return fmCode;
    }

    // ------------------------------------------------------------------ rules

    private void compileRules() {
        if (config.getRedactions() != null) {
            for (RedactionRule r : config.getRedactions()) {
                if (!r.isEnabled()) continue;
                Rule rule = compile(r.getPattern(), r.isRegex(), r.getCaptureGroup(),
                        r.getTarget(), null, r.getMode());
                if (rule != null) redactions.add(rule);
            }
        }
        if (config.getHighlights() != null) {
            for (HighlightRule h : config.getHighlights()) {
                if (!h.isEnabled()) continue;
                Color c = parseColor(h.getColorHex(), new Color(0xf5be28));
                Rule rule = compile(h.getPattern(), h.isRegex(), 0, h.getTarget(), c, null);
                if (rule != null) highlights.add(rule);
            }
        }
    }

    private static Rule compile(String pattern, boolean isRegex, int group, ScopeTarget target,
                                Color color, RedactionMode mode) {
        if (pattern == null || pattern.isEmpty()) return null;
        if (!isRegex) return new Rule(pattern, null, pattern, 0, target, color, mode);
        try {
            return new Rule(pattern, Pattern.compile(pattern), null, group, target, color, mode);
        } catch (Exception e) {
            // A malformed pattern must not abort the render; the rule simply does not apply.
            return null;
        }
    }

    public static Color parseColor(String hex, Color fallback) {
        if (hex == null || hex.isBlank()) return fallback;
        try {
            return Color.decode(hex.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
