package burp.screenshot.design;

import java.awt.Color;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps every {@link TokenType} to a color.
 *
 * <p>Default values are measured from Burp Repeater, not estimated by eye. The hex values come
 * from pixel probes of two real Repeater screenshots; the probe scripts and the full table are in
 * {@code reference/README.md}. In summary:
 *
 * <ul>
 *   <li>Header names and URL paths are <em>different</em> blues, in both themes. Light:
 *       {@code #000075} against {@code #0000c0}. Dark: {@code #d1e8f9} against {@code #bbcdff}.
 *   <li>Values are neutral: {@code #202020} on light, {@code #bababa} on dark.
 *   <li>Repeater gives a path and a query key the same color; so does this palette. Header, URL
 *       and body still land in three different hues, which is what keeps a screenshot readable.
 *   <li>Status colors are the one deliberate departure. Repeater draws the whole status line in
 *       one color, which would make a 500 look like a 200 in a PoC image. The four classes keep
 *       their meaning but borrow the measured hues.
 * </ul>
 *
 * <p>The palette is user-editable and round-trips through {@code TemplateConfig}, so a
 * mismatch against a real Repeater screenshot can be corrected in the app.
 */
public final class SyntaxPalette {

    private final EnumMap<TokenType, Color> colors;

    private SyntaxPalette(EnumMap<TokenType, Color> colors) {
        this.colors = colors;
    }

    public static final SyntaxPalette DARK = defaults(true);
    public static final SyntaxPalette LIGHT = defaults(false);

    /**
     * A palette holding no entries, used as the stored form of "no user overrides".
     *
     * <p>Persisting only the entries the user actually changed means a template written in
     * dark mode still gets the built-in light palette when the host theme is light.
     */
    public static SyntaxPalette empty() {
        return new SyntaxPalette(new EnumMap<>(TokenType.class));
    }

    public Color color(TokenType t) {
        Color c = colors.get(t);
        return c != null ? c : colors.get(TokenType.TEXT);
    }

    public void set(TokenType t, Color c) {
        if (t != null && c != null) colors.put(t, c);
    }

    public SyntaxPalette copy() {
        return new SyntaxPalette(new EnumMap<>(colors));
    }

    public boolean isEmpty() { return colors.isEmpty(); }

    // ------------------------------------------------------------ persistence

    public Map<String, String> toHexMap() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<TokenType, Color> e : colors.entrySet()) {
            out.put(e.getKey().name(), Tokens.toHex(e.getValue()));
        }
        return out;
    }

    /** Applies a stored map. Unknown keys are ignored, missing keys keep the fallback. */
    public void applyHexMap(Map<String, String> stored) {
        if (stored == null) return;
        for (Map.Entry<String, String> e : stored.entrySet()) {
            try {
                TokenType t = TokenType.valueOf(e.getKey());
                Color c = Tokens.hex(e.getValue());
                if (c != null) colors.put(t, c);
            } catch (IllegalArgumentException ignored) {
                // Key from a newer or older version; skip.
            }
        }
    }

    public static SyntaxPalette defaults(boolean dark) {
        EnumMap<TokenType, Color> m = new EnumMap<>(TokenType.class);

        if (dark) {
            // Probe: reference/burp-repeater/repeater-dark.png, card background #20232b.
            Color text = new Color(0xbababa);      // header value, measured
            Color ink = new Color(0xd1e8f9);       // header name / method / separator, measured
            Color path = new Color(0xbbcdff);      // path, query key, attribute name, measured
            Color string = new Color(0xa5c35b);    // query value, attribute value, measured
            Color tag = new Color(0xe9c063);       // HTML tag name, measured
            Color decl = new Color(0xa3baba);      // doctype, measured
            Color dim = new Color(0x8a8a8a);
            Color lineNo = new Color(0xa0a0a0);    // measured
            Color pale = new Color(0x9fb3d9);      // scheme and port, one step down from path

            m.put(TokenType.TEXT, text);
            m.put(TokenType.MUTED, dim);
            m.put(TokenType.LINE_NUMBER, lineNo);
            m.put(TokenType.OMISSION, new Color(0x6e7681));

            m.put(TokenType.METHOD, ink);
            m.put(TokenType.TARGET, path);
            m.put(TokenType.PROTOCOL, ink);

            m.put(TokenType.URL_SCHEME, pale);
            m.put(TokenType.URL_HOST, path);
            m.put(TokenType.URL_PORT, pale);
            m.put(TokenType.URL_PATH, path);
            m.put(TokenType.URL_QUERY_KEY, path);
            m.put(TokenType.URL_QUERY_VALUE, string);
            m.put(TokenType.URL_QUERY_SEP, ink);
            m.put(TokenType.URL_FRAGMENT, decl);

            m.put(TokenType.HEADER_NAME, ink);
            m.put(TokenType.HEADER_COLON, text);
            m.put(TokenType.HEADER_VALUE, text);

            // Deliberate departure: Repeater paints the whole status line one color.
            m.put(TokenType.STATUS_2XX, string);
            m.put(TokenType.STATUS_3XX, path);
            m.put(TokenType.STATUS_4XX, tag);
            m.put(TokenType.STATUS_5XX, new Color(0xf07178));
            m.put(TokenType.STATUS_TEXT, ink);

            m.put(TokenType.JSON_KEY, path);
            m.put(TokenType.JSON_STRING, string);
            m.put(TokenType.JSON_NUMBER, tag);
            m.put(TokenType.JSON_LITERAL, new Color(0xf07178));
            m.put(TokenType.HTML_TAG, tag);
            m.put(TokenType.HTML_ATTR, path);
            m.put(TokenType.HTML_VALUE, string);
            m.put(TokenType.FORM_KEY, path);
            m.put(TokenType.FORM_VALUE, string);
        } else {
            // Probe: reference/burp-repeater/repeater-light.png, card background white.
            Color text = new Color(0x202020);      // header value, measured
            Color ink = new Color(0x141414);       // method, separator, measured
            Color name = new Color(0x000075);      // header name, measured
            Color path = new Color(0x0000c0);      // path, query key, attribute name, measured
            Color string = new Color(0xa01010);    // query value, attribute value, measured
            Color tag = new Color(0xb000c0);       // HTML tag name, measured
            Color decl = new Color(0x005a00);      // doctype, measured
            Color dim = new Color(0x767676);
            Color lineNo = new Color(0x787878);    // measured
            Color pale = new Color(0x5a5a9e);      // scheme and port, one step down from path

            m.put(TokenType.TEXT, text);
            m.put(TokenType.MUTED, dim);
            m.put(TokenType.LINE_NUMBER, lineNo);
            m.put(TokenType.OMISSION, new Color(0x8c959f));

            m.put(TokenType.METHOD, ink);
            m.put(TokenType.TARGET, path);
            m.put(TokenType.PROTOCOL, ink);

            m.put(TokenType.URL_SCHEME, pale);
            m.put(TokenType.URL_HOST, path);
            m.put(TokenType.URL_PORT, pale);
            m.put(TokenType.URL_PATH, path);
            m.put(TokenType.URL_QUERY_KEY, path);
            m.put(TokenType.URL_QUERY_VALUE, string);
            m.put(TokenType.URL_QUERY_SEP, ink);
            m.put(TokenType.URL_FRAGMENT, decl);

            m.put(TokenType.HEADER_NAME, name);
            m.put(TokenType.HEADER_COLON, text);
            m.put(TokenType.HEADER_VALUE, text);

            // Deliberate departure: Repeater paints the whole status line one color.
            m.put(TokenType.STATUS_2XX, decl);
            m.put(TokenType.STATUS_3XX, path);
            m.put(TokenType.STATUS_4XX, new Color(0x953800));
            m.put(TokenType.STATUS_5XX, string);
            m.put(TokenType.STATUS_TEXT, ink);

            m.put(TokenType.JSON_KEY, path);
            m.put(TokenType.JSON_STRING, string);
            m.put(TokenType.JSON_NUMBER, decl);
            m.put(TokenType.JSON_LITERAL, tag);
            m.put(TokenType.HTML_TAG, tag);
            m.put(TokenType.HTML_ATTR, path);
            m.put(TokenType.HTML_VALUE, string);
            m.put(TokenType.FORM_KEY, path);
            m.put(TokenType.FORM_VALUE, string);
        }

        return new SyntaxPalette(m);
    }

    /** Tokens that carry bold weight in addition to their color. */
    public static boolean isBold(TokenType t) {
        return t == TokenType.METHOD
                || t == TokenType.STATUS_2XX
                || t == TokenType.STATUS_3XX
                || t == TokenType.STATUS_4XX
                || t == TokenType.STATUS_5XX;
    }
}
