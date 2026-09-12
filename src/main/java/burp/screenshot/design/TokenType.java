package burp.screenshot.design;

/**
 * Every distinct piece of an HTTP message that gets its own color.
 *
 * <p>The set is deliberately fine-grained: the request line splits into method, target and
 * protocol; the target splits into scheme, host, path, query and fragment; the body splits
 * by format. Each part staying visually distinct is the point.
 */
public enum TokenType {

    // Request line
    METHOD("Method", "Bold, for GET / POST / PUT ..."),
    TARGET("Request target", "The path part of the request line"),
    PROTOCOL("Protocol", "HTTP/1.1, HTTP/2"),

    // URL components
    URL_SCHEME("URL scheme", "https, http, ws"),
    URL_HOST("URL host", "Domain name or IP"),
    URL_PORT("URL port", "The port, such as :8080"),
    URL_PATH("URL path", "The path part"),
    URL_QUERY_KEY("Query key", "Parameter name after the ?"),
    URL_QUERY_VALUE("Query value", "The parameter's value"),
    URL_QUERY_SEP("Query separators", "? & ="),
    URL_FRAGMENT("URL fragment", "Everything after the #"),

    // Headers
    HEADER_NAME("Header name", "Host, User-Agent, Cookie ..."),
    HEADER_COLON("Header colon", "The separating :"),
    HEADER_VALUE("Header value", "Everything right of the :"),

    // Status line
    STATUS_2XX("Status 2xx", "Success"),
    STATUS_3XX("Status 3xx", "Redirect"),
    STATUS_4XX("Status 4xx", "Client error"),
    STATUS_5XX("Status 5xx", "Server error"),
    STATUS_TEXT("Status reason", "OK, Not Found ..."),

    // Body
    JSON_KEY("JSON key", "A field name in JSON"),
    JSON_STRING("JSON string", "A string value"),
    JSON_NUMBER("JSON number", "A numeric value"),
    JSON_LITERAL("JSON literal", "true, false, null"),
    HTML_TAG("HTML tag", "Tag names and angle brackets"),
    HTML_ATTR("HTML attribute", "An attribute name inside a tag"),
    HTML_VALUE("HTML value", "An attribute value"),
    FORM_KEY("Form key", "Field name in form-urlencoded or multipart"),
    FORM_VALUE("Form value", "A form field's value"),

    // Structural
    TEXT("Plain text", "Anything that fits no other group"),
    MUTED("Muted", "Omitted lines, comments, boundaries"),
    LINE_NUMBER("Line number", "The number in the left gutter"),
    OMISSION("Omission marker", "The ··· marker where lines were cut");

    private final String label;
    private final String hint;

    TokenType(String label, String hint) {
        this.label = label;
        this.hint = hint;
    }

    public String label() { return label; }

    public String hint() { return hint; }
}
