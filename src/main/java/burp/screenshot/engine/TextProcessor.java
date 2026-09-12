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
    }

    public static ProcessedHttp processRequest(String rawRequest, TemplateConfig config) {
        boolean shouldFilterHeaders = config.getHeaderScope() == ScopeTarget.BOTH ||
                                      config.getHeaderScope() == ScopeTarget.REQUEST;
        return process(rawRequest, config.getHeadersToHide(), shouldFilterHeaders,
                config.getRequestLineRanges(), false);
    }

    public static ProcessedHttp processResponse(String rawResponse, TemplateConfig config) {
        boolean shouldFilterHeaders = config.getHeaderScope() == ScopeTarget.BOTH ||
                                      config.getHeaderScope() == ScopeTarget.RESPONSE;
        return process(rawResponse, config.getHeadersToHide(), shouldFilterHeaders,
                config.getResponseLineRanges(), true);
    }

    private static class Range {
        int start;
        int end;
        Range(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private static ProcessedHttp process(String raw, String headersToHideText, boolean filterHeaders,
                                         String lineRangesText, boolean isResponse) {
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

        // Filter headers while preserving original line numbers (1-based)
        List<LineItem> keptLines = new ArrayList<>();

        SyntaxHighlighter.LineKind firstLineKind = isResponse
                ? SyntaxHighlighter.LineKind.STATUS_LINE
                : SyntaxHighlighter.LineKind.REQUEST_LINE;

        for (int i = 0; i < allLines.length; i++) {
            String line = allLines[i];
            int originalLineNum = i + 1;

            // Line 0 is Request-Line or Status-Line, always preserve
            if (i == 0) {
                keptLines.add(new LineItem(line, originalLineNum, firstLineKind));
                continue;
            }

            // Headers section
            if (i < headerEndIndex) {
                if (filterHeaders) {
                    int colonPos = line.indexOf(':');
                    if (colonPos > 0) {
                        String headerName = line.substring(0, colonPos).trim().toLowerCase();
                        if (alwaysShow.contains(headerName)) {
                            keptLines.add(new LineItem(line, originalLineNum, SyntaxHighlighter.LineKind.HEADER));
                            continue;
                        }

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
                            continue; // Skip this header
                        }
                    }
                }
                keptLines.add(new LineItem(line, originalLineNum, SyntaxHighlighter.LineKind.HEADER));
            } else {
                // Body section: the separating blank line then the payload.
                SyntaxHighlighter.LineKind bodyKind = line.trim().isEmpty()
                        ? SyntaxHighlighter.LineKind.BLANK
                        : SyntaxHighlighter.LineKind.BODY;
                keptLines.add(new LineItem(line, originalLineNum, bodyKind));
            }
        }

        // Remove trailing blank lines
        while (keptLines.size() > 1 && keptLines.get(keptLines.size() - 1).text.trim().isEmpty()) {
            keptLines.remove(keptLines.size() - 1);
        }

        int total = keptLines.size();
        if (total == 0) return result;

        // Parse Multi-range line selection (e.g. "1-5, 20-35, 100-110")
        List<Range> parsedRanges = parseRanges(lineRangesText, total);

        // Build final list with omission separators between disconnected ranges
        for (int rIdx = 0; rIdx < parsedRanges.size(); rIdx++) {
            Range r = parsedRanges.get(rIdx);

            // Add omission marker if there was a gap
            if (rIdx > 0) {
                Range prev = parsedRanges.get(rIdx - 1);
                int omittedCount = r.start - prev.end - 1;
                if (omittedCount > 0) {
                    result.lines.add(new LineItem("··· [" + omittedCount + " lines omitted] ···", -1, true));
                }
            }

            for (int i = r.start - 1; i < r.end && i < total; i++) {
                result.lines.add(keptLines.get(i));
            }
        }

        return result;
    }

    private static List<Range> parseRanges(String input, int maxLines) {
        List<Range> list = new ArrayList<>();
        if (input == null || input.trim().isEmpty() || "all".equalsIgnoreCase(input.trim())) {
            list.add(new Range(1, maxLines));
            return list;
        }

        String[] parts = input.split("[,;]");
        for (String p : parts) {
            String trimmed = p.trim();
            if (trimmed.isEmpty()) continue;

            try {
                if (trimmed.contains("-")) {
                    String[] bounds = trimmed.split("-", 2);
                    int s = Integer.parseInt(bounds[0].trim());
                    int e = Integer.parseInt(bounds[1].trim());
                    s = Math.max(1, Math.min(s, maxLines));
                    e = Math.max(1, Math.min(e, maxLines));
                    if (s <= e) {
                        list.add(new Range(s, e));
                    }
                } else {
                    int num = Integer.parseInt(trimmed);
                    num = Math.max(1, Math.min(num, maxLines));
                    list.add(new Range(num, num));
                }
            } catch (Exception ignored) {}
        }

        if (list.isEmpty()) {
            list.add(new Range(1, maxLines));
            return list;
        }

        // Sort by start line
        list.sort(Comparator.comparingInt(r -> r.start));

        // Merge overlapping ranges
        List<Range> merged = new ArrayList<>();
        Range cur = list.get(0);

        for (int i = 1; i < list.size(); i++) {
            Range next = list.get(i);
            if (next.start <= cur.end + 1) {
                cur.end = Math.max(cur.end, next.end);
            } else {
                merged.add(cur);
                cur = next;
            }
        }
        merged.add(cur);

        return merged;
    }
}
