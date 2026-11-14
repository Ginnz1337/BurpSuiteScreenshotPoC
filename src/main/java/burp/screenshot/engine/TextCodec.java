package burp.screenshot.engine;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Turns the bytes of an HTTP message into the text the view draws.
 *
 * <p>Montoya's {@code toString} reads a message one byte per character. That is byte-transparent,
 * which is right for a tool that edits, and wrong for a view that is meant to be photographed: a
 * body of UTF-8 JSON arrives as {@code Viettel mÃ´ rá»ng} where the reader wrote
 * {@code Viettel mở rộng}, and a report built from that screenshot is unreadable.
 *
 * <p>So the bytes are decoded here instead, with the charset the {@code Content-Type} names and
 * UTF-8 when it names none. Burp's own editor falls back the same way, which is why that editor
 * shows the body correctly while this view did not.
 */
public final class TextCodec {

    private TextCodec() {}

    /**
     * Decodes one message.
     *
     * @param raw         the whole message: head, blank line, body
     * @param bodyOffset  where the body starts, in bytes, or 0 when the host cannot say
     * @param contentType the header value that may name a charset, or null
     */
    public static String decode(byte[] raw, int bodyOffset, String contentType) {
        if (raw == null || raw.length == 0) return "";

        Charset body = charsetOf(contentType);

        // No usable boundary: decode the lot with the body's charset. The headers are ASCII in
        // every message this extension is pointed at, so nothing is lost by it.
        if (bodyOffset <= 0) return new String(raw, body);

        // Headers are read one byte per character. They are ASCII in practice, and a header
        // carrying a stray high byte must not be able to swallow the byte after it as the second
        // half of a character that is not there.
        if (bodyOffset >= raw.length) return new String(raw, StandardCharsets.ISO_8859_1);

        return new String(raw, 0, bodyOffset, StandardCharsets.ISO_8859_1)
                + new String(raw, bodyOffset, raw.length - bodyOffset, body);
    }

    /**
     * The charset a {@code Content-Type} names, or UTF-8.
     *
     * <p>A charset that is missing, blank, quoted, or not on this JVM all answer UTF-8 rather than
     * throwing: a body that cannot be named is still a body the reader wants to see, and the
     * decoding of the rest of the message must not depend on it.
     */
    public static Charset charsetOf(String contentType) {
        if (contentType == null || contentType.isEmpty()) return StandardCharsets.UTF_8;

        String lower = contentType.toLowerCase(Locale.ROOT);
        int at = lower.indexOf("charset");
        if (at < 0) return StandardCharsets.UTF_8;

        int equals = lower.indexOf('=', at);
        if (equals < 0) return StandardCharsets.UTF_8;

        String name = contentType.substring(equals + 1);
        int end = name.indexOf(';');
        if (end >= 0) name = name.substring(0, end);
        name = name.trim().replace("\"", "").replace("'", "").trim();
        if (name.isEmpty()) return StandardCharsets.UTF_8;

        try {
            return Charset.forName(name);
        } catch (RuntimeException e) {
            // IllegalCharsetNameException and UnsupportedCharsetException are both RuntimeExceptions.
            return StandardCharsets.UTF_8;
        }
    }
}
