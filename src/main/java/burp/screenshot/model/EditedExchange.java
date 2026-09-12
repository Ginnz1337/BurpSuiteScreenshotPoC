package burp.screenshot.model;

import java.util.Locale;

/**
 * The exchange as the user has edited it in the text pane.
 *
 * <p>Edits are kept beside the original rather than written into it, so Reset can always get
 * Burp's own bytes back, and so a highlight or redaction rule that matched the original can be
 * re-run against the edited text without the two being confused.
 *
 * <p>{@link HttpExchangeData} carries the URL, method, version and status as fields of their own
 * next to the raw text, filled in from the Burp API when the studio opened. Editing the text
 * therefore has to re-read those fields from the edited start line, or the card would show an
 * edited request under the old URL.
 */
public class EditedExchange {

    private final HttpExchangeData original;

    private String requestOverride;
    private String responseOverride;

    public EditedExchange(HttpExchangeData original) {
        this.original = original;
    }

    public HttpExchangeData getOriginal() { return original; }

    public void setRequestText(String text) {
        requestOverride = differs(original.getRawRequest(), text) ? text : null;
    }

    public void setResponseText(String text) {
        responseOverride = differs(original.getRawResponse(), text) ? text : null;
    }

    public void clearRequest() { requestOverride = null; }

    public void clearResponse() { responseOverride = null; }

    public void clear() {
        requestOverride = null;
        responseOverride = null;
    }

    public boolean isRequestModified() { return requestOverride != null; }

    public boolean isResponseModified() { return responseOverride != null; }

    public boolean isModified() { return isRequestModified() || isResponseModified(); }

    /** The original with the edits applied and every derived field re-read from the new text. */
    public HttpExchangeData effective() {
        HttpExchangeData out = copy(original);

        if (requestOverride != null) {
            out.setRawRequest(requestOverride);
            applyRequestLine(out, requestOverride);
        }
        if (responseOverride != null) {
            out.setRawResponse(responseOverride);
            applyStatusLine(out, responseOverride);
            out.setResponseSizeBytes(responseOverride.getBytes().length);
        }
        return out;
    }

    private static boolean differs(String original, String edited) {
        if (edited == null) return original != null && !original.isEmpty();
        return !edited.equals(original);
    }

    // ------------------------------------------------------------------ derived fields

    /** {@code METHOD TARGET VERSION} sets the method, the version and the URL. */
    private static void applyRequestLine(HttpExchangeData out, String raw) {
        String line = firstLine(raw);
        String[] parts = line.split("\\s+");

        if (parts.length > 0 && !parts[0].isBlank()) {
            out.setHttpMethod(parts[0]);
        }
        if (parts.length > 1) {
            out.setHttpVersion(parts[parts.length - 1].startsWith("HTTP/")
                    ? parts[parts.length - 1]
                    : out.getHttpVersion());
        }
        if (parts.length < 2) return;

        // Everything between the method and the version is the target.
        String target = String.join(" ",
                java.util.Arrays.copyOfRange(parts, 1, Math.max(1, parts.length - 1)));
        if (target.isBlank()) return;

        if (target.startsWith("http://") || target.startsWith("https://")) {
            out.setUrl(target);
            return;
        }

        String host = headerValue(raw, "Host");
        if (host == null || host.isBlank()) return;

        out.setUrl(schemeOfUrl(out.getUrl()) + "://" + host + target);
    }

    /** {@code HTTP/1.1 200 OK} sets the status code and the reason phrase. */
    private static void applyStatusLine(HttpExchangeData out, String raw) {
        String line = firstLine(raw);
        String[] parts = line.split("\\s+", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) return;

        try {
            out.setStatusCode(Integer.parseInt(parts[1]));
        } catch (NumberFormatException notACode) {
            // A half-typed status line is normal while editing; keep the previous code.
        }
        if (parts.length > 2) out.setStatusReason(parts[2]);
    }

    /**
     * The scheme the previous URL used, so editing a request does not silently turn an https
     * URL into http.
     */
    private static String schemeOfUrl(String url) {
        if (url == null) return "https";
        int marker = url.indexOf("://");
        return marker > 0 ? url.substring(0, marker).toLowerCase(Locale.ROOT) : "https";
    }

    private static String firstLine(String raw) {
        if (raw == null) return "";
        int end = raw.indexOf('\n');
        String line = end < 0 ? raw : raw.substring(0, end);
        return line.stripTrailing();
    }

    /** Case-insensitive value of a header in the edited text, or null. */
    private static String headerValue(String raw, String name) {
        if (raw == null) return null;
        String[] lines = raw.split("\r?\n", -1);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            if (line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static HttpExchangeData copy(HttpExchangeData src) {
        HttpExchangeData d = new HttpExchangeData();
        d.setUrl(src.getUrl());
        d.setRawRequest(src.getRawRequest());
        d.setRawResponse(src.getRawResponse());
        d.setHttpMethod(src.getHttpMethod());
        d.setHttpVersion(src.getHttpVersion());
        d.setStatusCode(src.getStatusCode());
        d.setStatusReason(src.getStatusReason());
        d.setTimestampFormatted(src.getTimestampFormatted());
        d.setResponseSizeBytes(src.getResponseSizeBytes());
        d.setResponseDurationMs(src.getResponseDurationMs());
        return d;
    }
}
