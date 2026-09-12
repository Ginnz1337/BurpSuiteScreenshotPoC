package burp.screenshot.model;

import burp.screenshot.design.SyntaxPalette;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TemplateConfig {
    private String name;
    private String headersToHide;
    private ScopeTarget headerScope;
    private LayoutMode layoutMode;
    private String contentWidth;
    private boolean showUrl;
    private boolean showTimestamp;
    private boolean showResponseInfo;
    private String requestLineRanges;
    private String responseLineRanges;
    private boolean wrapText;
    private String windowStyle; // "Caido" or "Mac"
    private List<HighlightRule> highlights;
    private List<RedactionRule> redactions;
    private double scaleFactor;
    /**
     * Share of the card width given to the Request pane in the side-by-side layout.
     *
     * <p>A ratio rather than a pixel width: the card is resizable, and a width would have to be
     * re-clamped on every resize, losing the user's intent at the first narrow render.
     */
    private double splitRatio;
    private boolean roundedCorners;
    private boolean dropShadow;
    private String watermarkText;
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
                "Priority"
        );
        this.headerScope = ScopeTarget.BOTH;
        this.layoutMode = LayoutMode.SIDE_BY_SIDE;
        this.contentWidth = "Medium (1000px)";
        this.showUrl = true;
        this.showTimestamp = true;
        this.showResponseInfo = true;
        this.requestLineRanges = "";
        this.responseLineRanges = "";
        this.wrapText = true;
        this.windowStyle = "Caido";
        this.highlights = new ArrayList<>();
        this.redactions = new ArrayList<>();
        this.scaleFactor = 2.0;
        this.splitRatio = 0.5;
        this.roundedCorners = true;
        this.dropShadow = true;
        this.watermarkText = "";
        this.syntaxColors = new LinkedHashMap<>();
    }

    public TemplateConfig(String name) {
        this();
        this.name = name;
    }

    public TemplateConfig copy() {
        TemplateConfig c = new TemplateConfig();
        c.name = this.name;
        c.headersToHide = this.headersToHide;
        c.headerScope = this.headerScope;
        c.layoutMode = this.layoutMode;
        c.contentWidth = this.contentWidth;
        c.showUrl = this.showUrl;
        c.showTimestamp = this.showTimestamp;
        c.showResponseInfo = this.showResponseInfo;
        c.requestLineRanges = this.requestLineRanges;
        c.responseLineRanges = this.responseLineRanges;
        c.wrapText = this.wrapText;
        c.windowStyle = this.windowStyle;
        c.scaleFactor = this.scaleFactor;
        c.splitRatio = this.splitRatio;
        c.roundedCorners = this.roundedCorners;
        c.dropShadow = this.dropShadow;
        c.watermarkText = this.watermarkText;
        c.syntaxColors = this.syntaxColors != null ? new LinkedHashMap<>(this.syntaxColors) : new LinkedHashMap<>();
        c.highlights = new ArrayList<>();
        for (HighlightRule h : this.highlights) {
            c.highlights.add(new HighlightRule(h.getPattern(), h.isRegex(), h.getTarget(), h.getColorHex()));
        }
        c.redactions = new ArrayList<>();
        for (RedactionRule r : this.redactions) {
            c.redactions.add(new RedactionRule(r.getPattern(), r.isRegex(), r.getTarget(), r.getMode(), r.getCaptureGroup()));
        }
        return c;
    }

    // Getters and setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getHeadersToHide() { return headersToHide; }
    public void setHeadersToHide(String headersToHide) { this.headersToHide = headersToHide; }

    public ScopeTarget getHeaderScope() { return headerScope; }
    public void setHeaderScope(ScopeTarget headerScope) { this.headerScope = headerScope; }

    public LayoutMode getLayoutMode() { return layoutMode; }
    public void setLayoutMode(LayoutMode layoutMode) { this.layoutMode = layoutMode; }

    public String getContentWidth() { return contentWidth; }
    public void setContentWidth(String contentWidth) { this.contentWidth = contentWidth; }

    public boolean isShowUrl() { return showUrl; }
    public void setShowUrl(boolean showUrl) { this.showUrl = showUrl; }

    public boolean isShowTimestamp() { return showTimestamp; }
    public void setShowTimestamp(boolean showTimestamp) { this.showTimestamp = showTimestamp; }

    public boolean isShowResponseInfo() { return showResponseInfo; }
    public void setShowResponseInfo(boolean showResponseInfo) { this.showResponseInfo = showResponseInfo; }

    public String getRequestLineRanges() { return requestLineRanges; }
    public void setRequestLineRanges(String requestLineRanges) { this.requestLineRanges = requestLineRanges; }

    public String getResponseLineRanges() { return responseLineRanges; }
    public void setResponseLineRanges(String responseLineRanges) { this.responseLineRanges = responseLineRanges; }

    public boolean isWrapText() { return wrapText; }
    public void setWrapText(boolean wrapText) { this.wrapText = wrapText; }

    public String getWindowStyle() { return windowStyle != null ? windowStyle : "Caido"; }
    public void setWindowStyle(String windowStyle) { this.windowStyle = windowStyle; }

    public List<HighlightRule> getHighlights() { return highlights; }
    public void setHighlights(List<HighlightRule> highlights) { this.highlights = highlights; }

    public List<RedactionRule> getRedactions() { return redactions; }
    public void setRedactions(List<RedactionRule> redactions) { this.redactions = redactions; }

    public double getScaleFactor() { return scaleFactor; }
    public void setScaleFactor(double scaleFactor) { this.scaleFactor = scaleFactor; }

    public double getSplitRatio() { return splitRatio; }
    public void setSplitRatio(double splitRatio) { this.splitRatio = splitRatio; }

    public boolean isRoundedCorners() { return roundedCorners; }
    public void setRoundedCorners(boolean roundedCorners) { this.roundedCorners = roundedCorners; }

    public boolean isDropShadow() { return dropShadow; }
    public void setDropShadow(boolean dropShadow) { this.dropShadow = dropShadow; }

    public String getWatermarkText() { return watermarkText; }
    public void setWatermarkText(String watermarkText) { this.watermarkText = watermarkText; }

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
}
