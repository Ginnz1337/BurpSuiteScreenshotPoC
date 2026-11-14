package burp.screenshot.engine;

import burp.screenshot.model.HighlightRule;
import burp.screenshot.model.RedactionRule;
import burp.screenshot.model.ScopeTarget;
import burp.screenshot.model.TemplateConfig;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The template's highlight and redaction rules, with their patterns already compiled.
 *
 * <p>Extracted from the card renderer, which owned this while the only view was a rendered
 * image. The on-screen text view needs the same rules and the same matching, so the matching
 * lives here and both callers go through it: a rule that hides a token in the view hides the
 * same characters the export would have blurred.
 *
 * <p>Compilation happens once per rebuild, not once per line. A long message would otherwise
 * recompile the same regex hundreds of times, which is what made the previous implementation
 * slow on large responses.
 */
public final class Rules {

    /** A highlight or redaction rule with its pattern already compiled. */
    public static final class Rule {
        public final Pattern regex;
        public final String literal;
        public final int captureGroup;
        public final ScopeTarget target;
        public final Color color;
        /** A redaction hides what it matches; a highlight washes a color behind it. */
        public final boolean redaction;
        /**
         * True when the match leaves the message, false when it is only painted over.
         *
         * <p>Only meaningful for a redaction. A hidden match is replaced by a marker before the
         * line is ever drawn, so nothing downstream sees the characters.
         */
        public final boolean hide;
        /** The pattern as the user wrote it, so the menu can match a rule back to its text. */
        public final String source;

        Rule(String source, Pattern regex, String literal, int captureGroup,
             ScopeTarget target, Color color, boolean redaction, boolean hide) {
            this.source = source;
            this.regex = regex;
            this.literal = literal;
            this.captureGroup = captureGroup;
            this.target = target;
            this.color = color;
            this.redaction = redaction;
            this.hide = hide;
        }

        public boolean appliesTo(boolean request) {
            return request ? target.appliesToRequest() : target.appliesToResponse();
        }

        /** True when this rule takes its match out of the message instead of painting over it. */
        public boolean isHide() {
            return redaction && hide;
        }

        /**
         * True when the literal runs over more than one line.
         *
         * <p>The view can select a block — a JSON object, a form body — and the rule made from
         * it is a literal carrying the line breaks between the lines it covered. Only a literal
         * can be one: a regex is still tried one line at a time, because a pattern written for a
         * line has no second line to match on.
         */
        public boolean isBlock() {
            return regex == null && literal != null && literal.indexOf('\n') >= 0;
        }

        /** The lines a block literal covers, in order. Empty for anything that is not a block. */
        public String[] blockLines() {
            return isBlock() ? literal.split("\n", -1) : new String[0];
        }

        /** Visits every match in {@code line} as a half-open character range. */
        public void find(String line, RangeSink sink) {
            if (line == null || line.isEmpty()) return;
            // A block rule carries the breaks between its lines, so it can never match inside
            // one. blockRuns walks the run of lines it covers instead.
            if (isBlock()) return;
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

    /**
     * One run of lines a block rule covers.
     *
     * <p>{@code start} and {@code end} are document offsets, and are -1 when the caller asked for
     * line numbers only. The text processor asks that way: it is deciding which lines to leave
     * out, not which characters to paint.
     */
    public record BlockRun(int firstLine, int lastLine, int start, int end) { }

    /**
     * Where a block rule matches in a run of lines.
     *
     * <p>The selection that made the rule began somewhere in its first line and stopped somewhere
     * in its last, so those two are matched by suffix and by prefix and the lines between them
     * have to be exactly the ones the literal carries. That holds whether the reader selected
     * whole lines or only part of the first and of the last.
     *
     * <p>Matched on content rather than on line numbers, because the two callers do not agree on
     * them: the view counts the lines it drew, which is after the header list and the ranges have
     * taken theirs out, and the processor counts the lines of the raw message.
     *
     * @param starts the document offset of each line, or null when only the line numbers are
     *               wanted
     */
    public static List<BlockRun> blockRuns(Rule rule, List<String> lines, int[] starts) {
        List<BlockRun> out = new ArrayList<>();
        if (rule == null || !rule.isBlock() || lines == null || lines.isEmpty()) return out;

        String[] segments = rule.blockLines();
        int count = segments.length;
        // A selection that stopped on the line break itself carries a trailing empty segment; the
        // block ends with the line before it rather than with a line that is not there.
        if (count > 1 && segments[count - 1].isEmpty()) count--;
        if (count < 2 || segments[0].isEmpty() || segments[count - 1].isEmpty()) return out;

        String head = segments[0];
        String tail = segments[count - 1];

        for (int i = 0; i + count <= lines.size(); i++) {
            String first = lines.get(i);
            if (!first.endsWith(head)) continue;

            boolean middle = true;
            for (int k = 1; k < count - 1; k++) {
                if (!lines.get(i + k).equals(segments[k])) {
                    middle = false;
                    break;
                }
            }
            if (!middle) continue;
            String last = lines.get(i + count - 1);
            if (!last.startsWith(tail)) continue;

            int start = starts == null ? -1 : starts[i] + first.length() - head.length();
            int end = starts == null ? -1 : starts[i + count - 1] + tail.length();
            out.add(new BlockRun(i, i + count - 1, start, end));
        }
        return out;
    }

    /** The compiled rules of one template, split by what they do. */
    public record Result(List<Rule> highlights, List<Rule> redactions) {

        public static Result empty() {
            return new Result(List.of(), List.of());
        }

        /**
         * The rules that apply to one side of the exchange.
         *
         * <p>Filtered once per rebuild rather than once per line: a scope check inside the
         * per-line loop is the kind of work that only shows up on a large response.
         */
        public Result forSide(boolean request) {
            return new Result(applicable(highlights, request), applicable(redactions, request));
        }

        private static List<Rule> applicable(List<Rule> rules, boolean request) {
            List<Rule> out = new ArrayList<>(rules.size());
            for (Rule r : rules) {
                if (r.appliesTo(request)) out.add(r);
            }
            return out;
        }

        /**
         * The redactions that take their match out of the message.
         *
         * <p>Separate from {@link #redactions()} because the two are applied in different places:
         * a hidden match is replaced before the line is built, a blurred one is painted after.
         */
        public List<Rule> hidden() {
            return withHide(redactions, true);
        }

        /** The redactions that stay painted over the text they match. */
        public List<Rule> blurred() {
            return withHide(redactions, false);
        }

        private static List<Rule> withHide(List<Rule> rules, boolean hide) {
            List<Rule> out = new ArrayList<>(rules.size());
            for (Rule r : rules) {
                if (r.isHide() == hide) out.add(r);
            }
            return out;
        }

    }

    private Rules() {}

    /**
     * Compiles a template's rules. A rule with an unparsable regex is dropped, not thrown.
     *
     * <p>A malformed pattern is a typo in a settings field, and refusing to draw the message
     * over it would leave the user with no screen and no way back to the field.
     */
    public static Result compile(TemplateConfig config) {
        if (config == null) return Result.empty();

        List<Rule> highlights = new ArrayList<>();
        List<Rule> redactions = new ArrayList<>();

        if (config.getRedactions() != null) {
            for (RedactionRule r : config.getRedactions()) {
                if (r == null || !r.isEnabled()) continue;
                Rule rule = compile(r.getPattern(), r.isRegex(), r.getCaptureGroup(),
                        r.getTarget(), null, true, r.isHide());
                if (rule != null) redactions.add(rule);
            }
        }
        if (config.getHighlights() != null) {
            for (HighlightRule h : config.getHighlights()) {
                if (h == null || !h.isEnabled()) continue;
                Color c = parseColor(h.getColorHex(), new Color(0xf5be28));
                Rule rule = compile(h.getPattern(), h.isRegex(), 0, h.getTarget(), c, false, false);
                if (rule != null) highlights.add(rule);
            }
        }

        return new Result(highlights, redactions);
    }

    /**
     * The rules that take text out of one side of the exchange.
     *
     * <p>For the text processor, which needs them before a line exists rather than after it is
     * drawn, and has no use for the highlights or the blurs.
     */
    public static List<Rule> hiddenForSide(TemplateConfig config, boolean request) {
        return compile(config).forSide(request).hidden();
    }

    private static Rule compile(String pattern, boolean isRegex, int group, ScopeTarget target,
                                Color color, boolean redaction, boolean hide) {
        if (pattern == null || pattern.isEmpty()) return null;
        if (!isRegex) return new Rule(pattern, null, pattern, 0, target, color, redaction, hide);
        try {
            return new Rule(pattern, Pattern.compile(pattern), null, group, target, color,
                    redaction, hide);
        } catch (Exception e) {
            // A malformed pattern must not abort the view; the rule simply does not apply.
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
