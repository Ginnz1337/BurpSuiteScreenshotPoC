package burp.screenshot.engine;

import burp.screenshot.model.ScopeTarget;
import burp.screenshot.model.TemplateConfig;

import java.util.*;

public class TextProcessor {

    public static class LineItem {
        public String text;
        public int originalLineNumber; // 1-based original line number, or -1 for omission marker
        public boolean isOmission;
        /** Grammar the highlighter should apply to this line. */
        public SyntaxHighlighter.LineKind kind = SyntaxHighlighter.LineKind.BODY;

        public LineItem(String text, int originalLineNumber, boolean isOmission) {
            this.text = text;
            this.originalLineNumber = originalLineNumber;
            this.isOmission = isOmission;
            if (isOmission) this.kind = SyntaxHighlighter.LineKind.OMISSION;
        }

        public LineItem(String text, int originalLineNumber, SyntaxHighlighter.LineKind kind) {
            this.text = text;
            this.originalLineNumber = originalLineNumber;
            this.isOmission = false;
            this.kind = kind;
        }
    }

    public static class ProcessedHttp {
        public List<LineItem> lines = new ArrayList<>();

        /**
         * Lines the ranges took out that would otherwise have been on screen.
         *
         * <p>Counted after header hiding, because that is the number that matches what the user
         * sees change. A line the ranges cover but the header list had already removed is not a
         * line anyone can watch disappear, and reporting it would make the count disagree with
         * the view.
         */
        public int removedLineCount;

        /** Entries in a line-ranges field that could not be read, kept verbatim to report back. */
        public final List<String> ignoredRangeEntries = new ArrayList<>();
    }

    public static ProcessedHttp processRequest(String rawRequest, TemplateConfig config) {
        boolean shouldFilterHeaders = config.getHeaderScope() == ScopeTarget.BOTH ||
                                      config.getHeaderScope() == ScopeTarget.REQUEST;
        return process(rawRequest, config.getHeadersToHide(), shouldFilterHeaders,
                config.getRequestLineRanges(), false, Rules.hiddenForSide(config, true));
    }

    public static ProcessedHttp processResponse(String rawResponse, TemplateConfig config) {
        boolean shouldFilterHeaders = config.getHeaderScope() == ScopeTarget.BOTH ||
                                      config.getHeaderScope() == ScopeTarget.RESPONSE;
        return process(rawResponse, config.getHeadersToHide(), shouldFilterHeaders,
                config.getResponseLineRanges(), true, Rules.hiddenForSide(config, false));
    }

    /**
     * The header names of a raw message, in the order they appear, without duplicates.
     *
     * <p>Used to offer a list of names to hide, so the user picks one instead of typing it and
     * getting the spelling wrong. The rule for what counts as a header is the same one the
     * filter uses: a colon past the first character, before the blank line.
     */
    public static List<String> headerNames(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;

        String[] lines = raw.split("\r?\n", -1);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim();
            if (name.isEmpty()) continue;
            boolean seen = false;
            for (String existing : out) {
                if (existing.equalsIgnoreCase(name)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) out.add(name);
        }
        return out;
    }

    private static class Range {
        int start;
        int end;
        Range(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    /**
     * The lines a ranges field asked to leave out.
     *
     * <p>Empty and {@code all} both mean nothing is removed. {@code all} has to keep meaning
     * "keep everything": a field that took the whole message away on a stray word would be a
     * trap, and it is the value that was in the box before this field meant removal.
     */
    private static final class Removals {

        final List<Range> ranges = new ArrayList<>();
        final List<String> ignored = new ArrayList<>();

        boolean covers(int lineNumber) {
            for (Range r : ranges) {
                if (lineNumber >= r.start && lineNumber <= r.end) return true;
            }
            return false;
        }
    }

    private static ProcessedHttp process(String raw, String headersToHideText, boolean filterHeaders,
                                         String lineRangesText, boolean isResponse,
                                         List<Rules.Rule> hidden) {
        ProcessedHttp result = new ProcessedHttp();
        if (raw == null || raw.isEmpty()) {
            return result;
        }

        // Parse hide/show lists
        Set<String> alwaysShow = new HashSet<>();
        List<String> hideRules = new ArrayList<>();

        if (filterHeaders && headersToHideText != null) {
            String[] lines = headersToHideText.split("\r?\n");
            for (String l : lines) {
                String trimmed = l.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.startsWith("!")) {
                    alwaysShow.add(trimmed.substring(1).trim().toLowerCase());
                } else {
                    hideRules.add(trimmed.toLowerCase());
                }
            }
        }

        // Split raw into lines preserving empty lines
        String[] allLines = raw.split("\r?\n", -1);

        // Find header section boundary (first blank line)
        int headerEndIndex = -1;
        for (int i = 0; i < allLines.length; i++) {
            if (allLines[i].trim().isEmpty()) {
                headerEndIndex = i;
                break;
            }
        }
        if (headerEndIndex == -1) {
            headerEndIndex = allLines.length;
        }

        // Every line of the message is walked in its own order, so the numbers in a ranges field
        // are the numbers the gutter shows, and a hidden header leaves the same silent gap it
        // always has.
        Removals removals = parseRemovals(lineRangesText, allLines.length);
        result.ignoredRangeEntries.addAll(removals.ignored);

        SyntaxHighlighter.LineKind firstLineKind = isResponse
                ? SyntaxHighlighter.LineKind.STATUS_LINE
                : SyntaxHighlighter.LineKind.REQUEST_LINE;

        int pendingRemoval = 0;

        for (int i = 0; i < allLines.length; i++) {
            String line = allLines[i];
            int originalLineNum = i + 1;

            // Headers section, but the first line is the Request-Line or Status-Line.
            if (i > 0 && i < headerEndIndex && filterHeaders) {
                int colonPos = line.indexOf(':');
                if (colonPos > 0) {
                    String headerName = line.substring(0, colonPos).trim().toLowerCase();
                    if (!alwaysShow.contains(headerName)) {
                        boolean shouldHide = false;
                        for (String rule : hideRules) {
                            if (rule.endsWith("*")) {
                                String prefix = rule.substring(0, rule.length() - 1);
                                if (headerName.startsWith(prefix)) {
                                    shouldHide = true;
                                    break;
                                }
                            } else if (headerName.equalsIgnoreCase(rule)) {
                                shouldHide = true;
                                break;
                            }
                        }
                        if (shouldHide) {
                            continue; // Skip this header, leaving its number out of the gutter
                        }
                    }
                }
            }

            if (removals.covers(originalLineNum)) {
                pendingRemoval++;
                result.removedLineCount++;
                continue;
            }
            if (pendingRemoval > 0) {
                result.lines.add(new LineItem(
                        "··· [" + pendingRemoval + " lines omitted] ···", -1, true));
                pendingRemoval = 0;
            }

            if (i == 0) {
                result.lines.add(new LineItem(hideSpans(line, hidden), originalLineNum, firstLineKind));
            } else if (i < headerEndIndex) {
                result.lines.add(new LineItem(hideSpans(line, hidden), originalLineNum,
                        SyntaxHighlighter.LineKind.HEADER));
            } else {
                // Body section: the separating blank line then the payload.
                SyntaxHighlighter.LineKind bodyKind = line.trim().isEmpty()
                        ? SyntaxHighlighter.LineKind.BLANK
                        : SyntaxHighlighter.LineKind.BODY;
                result.lines.add(new LineItem(hideSpans(line, hidden), originalLineNum, bodyKind));
            }
        }

        if (pendingRemoval > 0) {
            result.lines.add(new LineItem(
                    "··· [" + pendingRemoval + " lines omitted] ···", -1, true));
        }

        // Remove trailing blank lines
        while (result.lines.size() > 1
                && !result.lines.get(result.lines.size() - 1).isOmission
                && result.lines.get(result.lines.size() - 1).text.trim().isEmpty()) {
            result.lines.remove(result.lines.size() - 1);
        }

        return result;
    }

    /** What a hidden span leaves behind, in the same voice as the omitted-lines marker. */
    public static String hiddenMarker(int chars) {
        return "[" + chars + " chars hidden]";
    }

    /**
     * The line with every hidden span taken out and a marker left in its place.
     *
     * <p>This is where hide differs from blur, and it has to happen here rather than at paint
     * time. A blurred value keeps its characters and is painted over, so the text under the wash
     * is still there to select and to copy. A hidden value must not be, or the screenshot would
     * be the only place it was gone from. So the replacement happens before the line exists, and
     * every consumer of a {@link LineItem} sees the marker and never the payload.
     *
     * <p>The whole line is scanned against the original text first and the substitutions are made
     * afterwards in one pass. Replacing as matches are found would shift the offsets of every
     * match behind it, so a line carrying two payloads would hide the wrong characters.
     */
    private static String hideSpans(String line, List<Rules.Rule> hidden) {
        if (line == null || line.isEmpty() || hidden == null || hidden.isEmpty()) return line;

        List<int[]> marks = new ArrayList<>();
        for (Rules.Rule rule : hidden) {
            rule.find(line, (start, end) -> marks.add(new int[]{start, end}));
        }
        if (marks.isEmpty()) return line;

        marks.sort(Comparator.comparingInt(m -> m[0]));

        StringBuilder out = new StringBuilder(line.length());
        int cursor = 0;
        int i = 0;
        while (i < marks.size()) {
            int start = marks.get(i)[0];
            int end = marks.get(i)[1];
            // Two rules can cover the same characters, and a run that overlaps is one omission.
            // Reporting it twice would count characters that only went away once.
            int j = i + 1;
            while (j < marks.size() && marks.get(j)[0] <= end) {
                end = Math.max(end, marks.get(j)[1]);
                j++;
            }
            i = j;
            if (start < cursor || end <= start) continue;
            out.append(line, cursor, start);
            out.append(hiddenMarker(end - start));
            cursor = end;
        }
        out.append(line, cursor, line.length());
        return out.toString();
    }

    /**
     * How many of the lines in a span a ranges field already takes out.
     *
     * <p>The gutter menu uses this to disable itself when the whole span is gone. A field like
     * {@code 17-19,22-24} covers both ends of {@code 17-24} but not its middle, so looking only
     * at the ends would call a span removed while two of its lines are still on screen.
     */
    public static int removedBetween(String rangesText, int first, int last) {
        Removals removals = parseRemovals(rangesText, Integer.MAX_VALUE);
        int count = 0;
        for (int line = first; line <= last; line++) {
            if (removals.covers(line)) count++;
        }
        return count;
    }

    /**
     * Reads a line-ranges field as the set of lines to leave out.
     *
     * <p>Numbered against the whole message, which is what the gutter prints. An entry that
     * cannot be read is collected rather than swallowed, so the settings dialog can say which
     * one it did not understand instead of leaving the user to guess why nothing happened.
     */
    private static Removals parseRemovals(String input, int maxLines) {
        Removals out = new Removals();
        if (input == null) return out;

        String whole = input.trim();
        if (whole.isEmpty() || "all".equalsIgnoreCase(whole)) return out;

        for (String part : input.split("[,;]")) {
            String token = part.trim();
            if (token.isEmpty()) continue;

            int dash = token.indexOf('-');
            try {
                int start;
                int end;
                if (dash > 0) {
                    start = Integer.parseInt(token.substring(0, dash).trim());
                    end = Integer.parseInt(token.substring(dash + 1).trim());
                } else {
                    start = end = Integer.parseInt(token);
                }
                start = Math.max(1, start);
                end = Math.min(maxLines, end);
                if (start > end) {
                    out.ignored.add(token); // Reversed, or past the end of the message.
                    continue;
                }
                out.ranges.add(new Range(start, end));
            } catch (NumberFormatException e) {
                out.ignored.add(token);
            }
        }

        out.ranges.sort(Comparator.comparingInt(r -> r.start));

        List<Range> merged = new ArrayList<>();
        for (Range next : out.ranges) {
            if (!merged.isEmpty() && next.start <= merged.get(merged.size() - 1).end + 1) {
                Range last = merged.get(merged.size() - 1);
                last.end = Math.max(last.end, next.end);
            } else {
                merged.add(next);
            }
        }
        out.ranges.clear();
        out.ranges.addAll(merged);

        return out;
    }
}
