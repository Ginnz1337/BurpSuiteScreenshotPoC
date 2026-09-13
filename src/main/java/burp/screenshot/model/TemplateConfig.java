package burp.screenshot.model;

import burp.screenshot.design.SyntaxPalette;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the Screenshot PoC needs to draw a message.
 *
 * <p>The settings that only made sense while the extension produced a PNG, such as the card
 * width, the drop shadow and the scale factor, are gone rather than kept unused: the view is
 * plain text on Burp's own background now, and a field nothing reads is a field that lies
 * about what the extension does.
 */
public class TemplateConfig {

    /** Warm yellow, the color the default {@code Date} highlight uses. */
    public static final String DEFAULT_HIGHLIGHT = "#e5c07b";

    /**
     * The shipped {@code Date} rule: the whole line, name and value.
     *
     * <p>The value is the part a reader is looking for, and an anchored rule that stopped at the
     * colon left it unmarked. The rule is compiled per line, so {@code .*} reaches the end of
     * that line without needing {@code MULTILINE}.
     */
    public static final String DEFAULT_DATE_PATTERN = "^Date:.*";

    /** What the shipped {@code Date} rule is called in the rule list. */
    public static final String DEFAULT_DATE_NAME = "Date header";

    /** What the shipped rule said before it was widened. Recognised so it can be upgraded. */
    public static final String LEGACY_DATE_PATTERN = "^Date:";

    /**
     * Which set of shipped rules this config has been brought up to. Zero means not yet.
     *
     * <p>A number rather than a check for the rules themselves. A config that simply lacks the
     * Cookie rule could be one written before that rule existed or one where the user deleted it
     * on purpose, and only the first should get it back. Bumping this and comparing it is what
     * lets the deletion stick.
     *
     * <p>Zero is what a config read from a file carries until {@code TemplateManager.normalize}
     * has run on it, and that is deliberate. Gson fills a field only when the file names it, so a
     * settings file written before this field existed would otherwise keep whatever the
     * constructor put there, the comparison would never be true, and the rules added in a later
     * release would never reach an existing install. Leaving the constructor at zero makes a
     * missing field mean exactly what it should: not reconciled yet.
     */
    public static final int DEFAULTS_VERSION = 1;

    /**
     * The secrets a PoC must not carry unless the user says otherwise.
     *
     * <p>On by default in the other direction from the Date highlight: a reader wants to see the
     * Date, and nobody wants a live session cookie pasted into a report. Over-redacting is the
     * failure that costs a paragraph of explanation; under-redacting is the one that leaks a
     * credential, so the defaults lean towards blurring.
     *
     * <p>Each one is an ordinary rule. They show up in Settings, they can be switched off, and
     * they stay off once they are switched off.
     */
    public static List<RedactionRule> defaultRedactions() {
        List<RedactionRule> out = new ArrayList<>();
        out.add(named(new RedactionRule("(?i)^(Proxy-)?Authorization:\\s*(.+)$", true,
                ScopeTarget.BOTH, 2), "Authorization header"));
        out.add(named(new RedactionRule("(?i)^Cookie:\\s*(.+)$", true, ScopeTarget.BOTH, 1),
                "Cookie"));
        out.add(named(new RedactionRule("(?i)^Set-Cookie:\\s*(.+)$", true, ScopeTarget.BOTH, 1),
                "Set-Cookie"));
        out.add(named(new RedactionRule(
                "(?i)(password|passwd|pwd)[\"']?\\s*[:=]\\s*[\"']?([^\"'&;\\s]+)",
                true, ScopeTarget.BOTH, 2), "password"));
        out.add(named(new RedactionRule(
                "(?i)(api[_-]?key|apikey|access[_-]?token)[\"']?\\s*[:=]\\s*[\"']?([^\"'&;\\s]+)",
                true, ScopeTarget.BOTH, 2), "api_key"));
        out.add(named(new RedactionRule(
                "(?i)session[_-]?id[\"']?\\s*[:=]\\s*[\"']?([^\"'&;\\s]+)",
                true, ScopeTarget.BOTH, 1), "session_id"));
        out.add(named(new RedactionRule("Bearer\\s+[A-Za-z0-9._~+/-]+=*", true,
                ScopeTarget.BOTH, 0), "Bearer token"));
        return out;
    }

    /**
     * The name a shipped rule carries in the list.
     *
     * <p>A pattern is not a name. Seven rows of regex tell the user nothing about which is the
     * one for the session cookie, and {@code TemplateManager} uses these to label a settings file
     * written before rules had names.
     */
    private static RedactionRule named(RedactionRule rule, String name) {
        rule.setName(name);
        return rule;
    }

    private String name;
    private String headersToHide;
    private ScopeTarget headerScope;
    private String requestLineRanges;
    private String responseLineRanges;
    private boolean wrapText;
    private List<HighlightRule> highlights;
    private List<RedactionRule> redactions;

    /**
     * The shipped-rule set this config has been brought up to. See {@link #DEFAULTS_VERSION}.
     *
     * <p>Left at zero by the constructor on purpose. A config built in memory already carries the
     * current rules, and one read from a file has to be reconciled before it can say so.
     */
    private int defaultsVersion;
    /**
     * User overrides for the syntax colors, keyed by {@code TokenType} name.
     *
     * <p>Stored as plain hex strings rather than as {@code SyntaxPalette} because Gson
     * writes {@code java.awt.Color} reflectively, which fails on a modular JDK. An empty map
     * means "use the palette built in for the current theme".
     */
    private Map<String, String> syntaxColors;

    public TemplateConfig() {
        this.name = "Default";
        this.headersToHide = String.join("\n",
                "Accept",
                "Accept-Encoding",
                "Accept-Language",
                "Accept-Ranges",
                "Sec-Ch-Ua",
                "Sec-Ch-Ua-Mobile",
                "Sec-Ch-Ua-Platform",
                "Sec-Fetch-Dest",
                "Sec-Fetch-Mode",
                "Sec-Fetch-Site",
                "Sec-Fetch-User",
                "Upgrade-Insecure-Requests",
                "Connection",
                "Priority",
                "X-PwnFox-Color"
        );
        this.headerScope = ScopeTarget.BOTH;
        this.requestLineRanges = "";
        this.responseLineRanges = "";
        this.wrapText = true;
        this.redactions = defaultRedactions();
        this.syntaxColors = new LinkedHashMap<>();

        // A response's Date is the one header a reader always looks for, so it is pointed at
        // out of the box. It is an ordinary rule: visible in Settings, recolorable, deletable.
        this.highlights = new ArrayList<>();
        HighlightRule date = new HighlightRule(DEFAULT_DATE_PATTERN, true, ScopeTarget.RESPONSE,
                DEFAULT_HIGHLIGHT);
        date.setName(DEFAULT_DATE_NAME);
        this.highlights.add(date);
    }

    public TemplateConfig(String name) {
        this();
        this.name = name;
    }

    public TemplateConfig copy() {
        TemplateConfig c = new TemplateConfig();
        c.copyFrom(this);
        return c;
    }

    /**
     * Makes this instance say what {@code other} says, keeping the instance itself.
     *
     * <p>Copied into rather than replaced because the view and the settings dialog hold a
     * reference to this object, and swapping it for another one would leave them drawing the
     * settings the user just rejected. Every list is copied, so the two do not share entries.
     */
    public void copyFrom(TemplateConfig other) {
        if (other == null) return;
        this.name = other.name;
        this.headersToHide = other.headersToHide;
        this.headerScope = other.headerScope;
        this.requestLineRanges = other.requestLineRanges;
        this.responseLineRanges = other.responseLineRanges;
        this.wrapText = other.wrapText;
        this.defaultsVersion = other.defaultsVersion;
        this.syntaxColors = other.syntaxColors != null
                ? new LinkedHashMap<>(other.syntaxColors) : new LinkedHashMap<>();
        this.highlights = new ArrayList<>();
        for (HighlightRule h : other.highlights) {
            HighlightRule copy = new HighlightRule(h.getPattern(), h.isRegex(), h.getTarget(),
                    h.getColorHex());
            copy.setName(h.getName());
            copy.setEnabled(h.isEnabled());
            this.highlights.add(copy);
        }
        this.redactions = new ArrayList<>();
        for (RedactionRule r : other.redactions) {
            RedactionRule copy = new RedactionRule(r.getPattern(), r.isRegex(), r.getTarget(),
                    r.getCaptureGroup(), r.isHide());
            copy.setName(r.getName());
            copy.setEnabled(r.isEnabled());
            this.redactions.add(copy);
        }
    }

    // Getters and setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getHeadersToHide() { return headersToHide; }
    public void setHeadersToHide(String headersToHide) { this.headersToHide = headersToHide; }

    public ScopeTarget getHeaderScope() { return headerScope; }
    public void setHeaderScope(ScopeTarget headerScope) { this.headerScope = headerScope; }

    public String getRequestLineRanges() { return requestLineRanges; }
    public void setRequestLineRanges(String requestLineRanges) { this.requestLineRanges = requestLineRanges; }

    public String getResponseLineRanges() { return responseLineRanges; }
    public void setResponseLineRanges(String responseLineRanges) { this.responseLineRanges = responseLineRanges; }

    public boolean isWrapText() { return wrapText; }
    public void setWrapText(boolean wrapText) { this.wrapText = wrapText; }

    public int getDefaultsVersion() { return defaultsVersion; }
    public void setDefaultsVersion(int defaultsVersion) { this.defaultsVersion = defaultsVersion; }

    public List<HighlightRule> getHighlights() { return highlights; }
    public void setHighlights(List<HighlightRule> highlights) { this.highlights = highlights; }

    public List<RedactionRule> getRedactions() { return redactions; }
    public void setRedactions(List<RedactionRule> redactions) { this.redactions = redactions; }

    /** Never null; an empty map means the built-in colors for the current theme apply. */
    public Map<String, String> getSyntaxColors() {
        if (syntaxColors == null) syntaxColors = new LinkedHashMap<>();
        return syntaxColors;
    }

    public void setSyntaxColors(Map<String, String> syntaxColors) {
        this.syntaxColors = syntaxColors != null ? new LinkedHashMap<>(syntaxColors) : new LinkedHashMap<>();
    }

    /** The stored overrides as a palette object, for the color editor. */
    public SyntaxPalette getSyntaxPalette() {
        SyntaxPalette p = SyntaxPalette.empty();
        p.applyHexMap(getSyntaxColors());
        return p;
    }

    /** Stores every entry of {@code p} as an override, making the template self-contained. */
    public void setSyntaxPalette(SyntaxPalette p) {
        setSyntaxColors(p != null ? p.toHexMap() : null);
    }

    public boolean hasSyntaxOverrides() {
        return !getSyntaxColors().isEmpty();
    }

    /** Header names as they are stored, one per entry, blanks dropped. */
    public List<String> headerList() {
        List<String> out = new ArrayList<>();
        String raw = getHeadersToHide();
        if (raw == null) return out;
        for (String line : raw.split("\r?\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    /** Adds a header name to the hide list, ignoring case and keeping the list sorted. */
    public void addHeaderToHide(String headerName) {
        if (headerName == null) return;
        String name = headerName.trim();
        if (name.isEmpty()) return;

        List<String> list = headerList();
        for (String existing : list) {
            if (existing.equalsIgnoreCase(name)) return;
        }
        list.add(name);
        list.sort(String.CASE_INSENSITIVE_ORDER);
        setHeadersToHide(String.join("\n", list));
    }
}
