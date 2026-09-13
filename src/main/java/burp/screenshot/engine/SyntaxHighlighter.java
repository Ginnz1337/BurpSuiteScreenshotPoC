package burp.screenshot.engine;

import burp.screenshot.design.SyntaxPalette;
import burp.screenshot.design.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits one HTTP line into typed tokens.
 *
 * <p>Colours are not attached here. A token carries only its {@link TokenType}, and the
 * renderer and the on-screen editor both resolve the colour through the same
 * {@link SyntaxPalette}. That keeps the exported image and the preview from drifting apart.
 */
public final class SyntaxHighlighter {

    /** Which grammar applies to a line. */
    public enum LineKind { REQUEST_LINE, STATUS_LINE, HEADER, BODY, BLANK, OMISSION }

    /** A run of characters sharing one {@link TokenType}. */
    public static final class Token {
        public final String text;
        public final TokenType type;

        public Token(String text, TokenType type) {
            this.text = text == null ? "" : text;
            this.type = type == null ? TokenType.TEXT : type;
        }

        @Override public String toString() { return type + "(" + text + ")"; }
    }

    private SyntaxHighlighter() {}

    // ------------------------------------------------------------------ dispatch

    public static List<Token> tokenize(String line, LineKind kind) {
        List<Token> out = new ArrayList<>();
        if (line == null || line.isEmpty()) {
            out.add(new Token("", TokenType.TEXT));
            return out;
        }
        switch (kind) {
            case REQUEST_LINE: return requestLine(line);
            case STATUS_LINE: return statusLine(line);
            case HEADER: return header(line);
            case OMISSION: out.add(new Token(line, TokenType.OMISSION)); return out;
            case BLANK: out.add(new Token(line, TokenType.TEXT)); return out;
            case BODY:
            default: return body(line);
        }
    }

    // ------------------------------------------------------------------ start lines

    private static List<Token> requestLine(String line) {
        List<Token> out = new ArrayList<>();
        int sp1 = line.indexOf(' ');
        if (sp1 <= 0) return body(line);

        String method = line.substring(0, sp1);
        if (!isMethod(method)) return body(line);

        out.add(new Token(method, TokenType.METHOD));
        out.add(new Token(" ", TokenType.TEXT));

        int rest = sp1 + 1;
        int sp2 = line.indexOf(' ', rest);
        String target = sp2 < 0 ? line.substring(rest) : line.substring(rest, sp2);
        out.addAll(targetTokens(target));

        if (sp2 < 0) return out;

        out.add(new Token(" ", TokenType.TEXT));
        int sp3 = line.indexOf(' ', sp2 + 1);
        if (sp3 < 0) {
            out.add(new Token(line.substring(sp2 + 1), TokenType.PROTOCOL));
        } else {
            out.add(new Token(line.substring(sp2 + 1, sp3), TokenType.PROTOCOL));
            // Anything past the protocol is not valid HTTP but must not be dropped.
            out.add(new Token(line.substring(sp3), TokenType.TEXT));
        }
        return out;
    }

    private static List<Token> statusLine(String line) {
        List<Token> out = new ArrayList<>();
        int sp1 = line.indexOf(' ');
        if (sp1 <= 0) return body(line);

        String version = line.substring(0, sp1);
        if (!version.regionMatches(true, 0, "HTTP/", 0, 5)) return body(line);

        out.add(new Token(version, TokenType.PROTOCOL));
        out.add(new Token(" ", TokenType.TEXT));

        int rest = sp1 + 1;
        int sp2 = line.indexOf(' ', rest);
        String code = sp2 < 0 ? line.substring(rest) : line.substring(rest, sp2);

        out.add(new Token(code, statusType(code)));

        if (sp2 < 0) return out;
        out.add(new Token(" ", TokenType.TEXT));
        out.add(new Token(line.substring(sp2 + 1), TokenType.STATUS_TEXT));
        return out;
    }

    /** Non-numeric codes such as the {@code 0} Burp writes for a dropped connection. */
    private static TokenType statusType(String code) {
        int value;
        try {
            value = Integer.parseInt(code.trim());
        } catch (NumberFormatException e) {
            return TokenType.STATUS_TEXT;
        }
        if (value >= 500) return TokenType.STATUS_5XX;
        if (value >= 400) return TokenType.STATUS_4XX;
        if (value >= 300) return TokenType.STATUS_3XX;
        if (value >= 200) return TokenType.STATUS_2XX;
        return TokenType.STATUS_TEXT;
    }

    private static boolean isMethod(String s) {
        if (s.isEmpty() || s.length() > 12) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 'A' || c > 'Z') return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ headers

    private static List<Token> header(String line) {
        List<Token> out = new ArrayList<>();

        // A folded continuation line starts with whitespace and belongs to the header above.
        if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
            out.add(new Token(line, TokenType.HEADER_VALUE));
            return out;
        }

        int colon = line.indexOf(':');
        if (colon <= 0) return body(line);

        out.add(new Token(line.substring(0, colon), TokenType.HEADER_NAME));
        out.add(new Token(":", TokenType.HEADER_COLON));
        out.add(new Token(line.substring(colon + 1), TokenType.HEADER_VALUE));
        return out;
    }

    // ------------------------------------------------------------------ target and URL

    /**
     * Splits an absolute URL or an origin-form request target.
     *
     * <p>Both forms land here so the URL bar in the card and the request line use one
     * grammar and therefore one set of colours.
     */
    public static List<Token> targetTokens(String target) {
        List<Token> out = new ArrayList<>();
        if (target == null || target.isEmpty()) return out;

        String rest = target;
        int schemeEnd = schemeEnd(target);
        if (schemeEnd > 0) {
            out.add(new Token(target.substring(0, schemeEnd), TokenType.URL_SCHEME));
            out.add(new Token("://", TokenType.URL_QUERY_SEP));
            rest = target.substring(schemeEnd + 3);

            int slash = rest.indexOf('/');
            int cut = slash < 0 ? rest.length() : slash;
            String authority = rest.substring(0, cut);
            rest = rest.substring(cut);

            int at = authority.lastIndexOf('@');
            if (at >= 0) {
                out.add(new Token(authority.substring(0, at + 1), TokenType.MUTED));
                authority = authority.substring(at + 1);
            }

            int colon = authority.lastIndexOf(':');
            if (colon > 0 && isAllDigits(authority.substring(colon + 1))) {
                out.add(new Token(authority.substring(0, colon), TokenType.URL_HOST));
                out.add(new Token(authority.substring(colon), TokenType.URL_PORT));
            } else if (!authority.isEmpty()) {
                out.add(new Token(authority, TokenType.URL_HOST));
            }
        }

        int hash = rest.indexOf('#');
        String fragment = "";
        if (hash >= 0) {
            fragment = rest.substring(hash);
            rest = rest.substring(0, hash);
        }

        int q = rest.indexOf('?');
        String path = q < 0 ? rest : rest.substring(0, q);
        String query = q < 0 ? "" : rest.substring(q);

        if (!path.isEmpty()) out.add(new Token(path, TokenType.URL_PATH));
        if (!query.isEmpty()) {
            out.add(new Token("?", TokenType.URL_QUERY_SEP));
            out.addAll(queryTokens(query.substring(1)));
        }
        if (!fragment.isEmpty()) {
            out.add(new Token("#", TokenType.URL_QUERY_SEP));
            out.add(new Token(fragment.substring(1), TokenType.URL_FRAGMENT));
        }
        return out;
    }

    /** Query string without the leading {@code ?}. */
    private static List<Token> queryTokens(String query) {
        List<Token> out = new ArrayList<>();
        if (query.isEmpty()) return out;

        int i = 0;
        int len = query.length();
        while (i < len) {
            int amp = query.indexOf('&', i);
            int end = amp < 0 ? len : amp;
            String pair = query.substring(i, end);

            int eq = pair.indexOf('=');
            if (eq < 0) {
                if (!pair.isEmpty()) out.add(new Token(pair, TokenType.URL_QUERY_KEY));
            } else {
                if (eq > 0) out.add(new Token(pair.substring(0, eq), TokenType.URL_QUERY_KEY));
                out.add(new Token("=", TokenType.URL_QUERY_SEP));
                if (eq + 1 < pair.length()) {
                    out.add(new Token(pair.substring(eq + 1), TokenType.URL_QUERY_VALUE));
                }
            }

            if (amp < 0) break;
            out.add(new Token("&", TokenType.URL_QUERY_SEP));
            i = amp + 1;
        }
        return out;
    }

    private static int schemeEnd(String s) {
        int colon = s.indexOf("://");
        if (colon <= 0 || colon > 10) return -1;
        for (int i = 0; i < colon; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (i > 0 && ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.'));
            if (!ok) return -1;
        }
        return colon;
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty() || s.length() > 5) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ body

    public static List<Token> body(String line) {
        List<Token> out = new ArrayList<>();
        if (line.isEmpty()) {
            out.add(new Token("", TokenType.TEXT));
            return out;
        }

        String trimmed = line.trim();
        if (trimmed.startsWith("//") || trimmed.startsWith("#") && !trimmed.startsWith("#!")) {
            out.add(new Token(line, TokenType.MUTED));
            return out;
        }
        if (looksLikeHtml(trimmed)) return htmlLine(line);
        if (looksLikeForm(trimmed)) return formLine(line);
        return jsonLine(line);
    }

    private static boolean looksLikeHtml(String trimmed) {
        return trimmed.startsWith("<") && trimmed.indexOf('>') > 1;
    }

    private static boolean looksLikeForm(String trimmed) {
        int eq = trimmed.indexOf('=');
        if (eq <= 0) return false;
        if (trimmed.indexOf('"') >= 0 || trimmed.indexOf('{') >= 0) return false;
        return trimmed.indexOf('&') >= 0 || trimmed.indexOf(' ') < 0;
    }

    private static List<Token> jsonLine(String line) {
        List<Token> out = new ArrayList<>();
        int len = line.length();
        int i = 0;

        while (i < len) {
            char c = line.charAt(i);

            if (c == '"') {
                int close = closingQuote(line, i);
                if (close > i) {
                    String literal = line.substring(i, close + 1);
                    if (nextNonSpace(line, close + 1) == ':') {
                        out.add(new Token(literal, TokenType.JSON_KEY));
                    } else {
                        out.add(new Token(literal, TokenType.JSON_STRING));
                    }
                    i = close + 1;
                    continue;
                }
            }

            if (isNumberStart(line, i)) {
                int end = i;
                while (end < len && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '.')) end++;
                out.add(new Token(line.substring(i, end), TokenType.JSON_NUMBER));
                i = end;
                continue;
            }

            if (isLiteralAt(line, i, "true") || isLiteralAt(line, i, "false") || isLiteralAt(line, i, "null")) {
                int end = i + (line.charAt(i) == 'n' ? 4 : (line.charAt(i) == 't' ? 4 : 5));
                out.add(new Token(line.substring(i, end), TokenType.JSON_LITERAL));
                i = end;
                continue;
            }

            int next = i + 1;
            while (next < len && !startsJsonToken(line, next)) next++;
            out.add(new Token(line.substring(i, next), TokenType.TEXT));
            i = next;
        }

        if (out.isEmpty()) out.add(new Token(line, TokenType.TEXT));
        return out;
    }

    private static boolean startsJsonToken(String line, int i) {
        char c = line.charAt(i);
        return c == '"' || isNumberStart(line, i) || isLiteralAt(line, i, "true")
                || isLiteralAt(line, i, "false") || isLiteralAt(line, i, "null");
    }

    private static boolean isLiteralAt(String line, int i, String literal) {
        if (i + literal.length() > line.length()) return false;
        if (!line.regionMatches(i, literal, 0, literal.length())) return false;
        int after = i + literal.length();
        return after >= line.length() || !Character.isLetterOrDigit(line.charAt(after));
    }

    private static boolean isNumberStart(String line, int i) {
        char c = line.charAt(i);
        if (c == '-' ) c = i + 1 < line.length() ? line.charAt(i + 1) : ' ';
        if (!Character.isDigit(c)) return false;
        if (i == 0) return true;
        char prev = line.charAt(i - 1);
        return !Character.isLetterOrDigit(prev) && prev != '_';
    }

    private static int closingQuote(String line, int open) {
        for (int j = open + 1; j < line.length(); j++) {
            char c = line.charAt(j);
            if (c == '\\') { j++; continue; }
            if (c == '"') return j;
        }
        return -1;
    }

    private static int nextNonSpace(String line, int from) {
        for (int i = from; i < line.length(); i++) {
            if (!Character.isWhitespace(line.charAt(i))) return line.charAt(i);
        }
        return -1;
    }

    private static List<Token> htmlLine(String line) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int len = line.length();

        while (i < len) {
            int lt = line.indexOf('<', i);
            if (lt < 0) {
                out.add(new Token(line.substring(i), TokenType.TEXT));
                break;
            }
            if (lt > i) out.add(new Token(line.substring(i, lt), TokenType.TEXT));

            int gt = line.indexOf('>', lt);
            if (gt < 0) {
                out.add(new Token(line.substring(lt), TokenType.HTML_TAG));
                break;
            }
            out.addAll(htmlTag(line.substring(lt, gt + 1)));
            i = gt + 1;
        }

        if (out.isEmpty()) out.add(new Token(line, TokenType.TEXT));
        return out;
    }

    private static List<Token> htmlTag(String tag) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int len = tag.length();
        int nameEnd = 1;
        if (nameEnd < len && tag.charAt(nameEnd) == '/') nameEnd++;
        while (nameEnd < len && !Character.isWhitespace(tag.charAt(nameEnd)) && tag.charAt(nameEnd) != '>') nameEnd++;

        out.add(new Token(tag.substring(0, Math.min(nameEnd, len)), TokenType.HTML_TAG));
        i = nameEnd;

        while (i < len) {
            char c = tag.charAt(i);
            if (Character.isWhitespace(c)) {
                int j = i;
                while (j < len && Character.isWhitespace(tag.charAt(j))) j++;
                out.add(new Token(tag.substring(i, j), TokenType.TEXT));
                i = j;
                continue;
            }
            if (c == '>') {
                out.add(new Token(">", TokenType.HTML_TAG));
                i++;
                continue;
            }
            if (c == '=') {
                out.add(new Token("=", TokenType.MUTED));
                i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                int close = tag.indexOf(c, i + 1);
                if (close < 0) close = len - 1;
                out.add(new Token(tag.substring(i, Math.min(close + 1, len)), TokenType.HTML_VALUE));
                i = close + 1;
                continue;
            }
            int j = i;
            while (j < len && !Character.isWhitespace(tag.charAt(j)) && tag.charAt(j) != '='
                    && tag.charAt(j) != '>' && tag.charAt(j) != '"' && tag.charAt(j) != '\'') j++;
            out.add(new Token(tag.substring(i, j), TokenType.HTML_ATTR));
            i = j;
        }
        return out;
    }

    private static List<Token> formLine(String line) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int len = line.length();

        while (i < len) {
            int amp = line.indexOf('&', i);
            int end = amp < 0 ? len : amp;
            String pair = line.substring(i, end);
            int eq = pair.indexOf('=');

            if (eq < 0) {
                if (!pair.isEmpty()) out.add(new Token(pair, TokenType.FORM_KEY));
            } else {
                if (eq > 0) out.add(new Token(pair.substring(0, eq), TokenType.FORM_KEY));
                out.add(new Token("=", TokenType.MUTED));
                if (eq + 1 < pair.length()) {
                    out.add(new Token(pair.substring(eq + 1), TokenType.FORM_VALUE));
                }
            }

            if (amp < 0) break;
            out.add(new Token("&", TokenType.MUTED));
            i = amp + 1;
        }

        if (out.isEmpty()) out.add(new Token(line, TokenType.TEXT));
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /** Concatenated text of a token list, used to verify that tokenizing never loses input. */
    public static String join(List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        for (Token t : tokens) sb.append(t.text);
        return sb.toString();
    }
}
