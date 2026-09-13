package burp.screenshot.model;

import java.util.UUID;

public class HighlightRule {
    private String id;
    /**
     * What the user calls this rule, for telling one from another at a glance.
     *
     * <p>Free text and optional. A list of patterns is a list of regexes to read one by one, and
     * nothing else on the card says which of them is the one for the session cookie.
     */
    private String name;
    private String pattern;
    private boolean regex;
    private ScopeTarget target;
    private String colorHex;
    private boolean enabled;

    public HighlightRule() {
        this.id = UUID.randomUUID().toString();
        this.name = "";
        this.pattern = "";
        this.regex = true;
        this.target = ScopeTarget.REQUEST;
        this.colorHex = "#e5c07b"; // Default warm yellow
        this.enabled = true;
    }

    public HighlightRule(String pattern, boolean regex, ScopeTarget target, String colorHex) {
        this.id = UUID.randomUUID().toString();
        this.name = "";
        this.pattern = pattern;
        this.regex = regex;
        this.target = target;
        this.colorHex = colorHex;
        this.enabled = true;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /** Never null; a rule without a name reads as an empty field, not as a missing one. */
    public String getName() {
        return name == null ? "" : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public boolean isRegex() {
        return regex;
    }

    public void setRegex(boolean regex) {
        this.regex = regex;
    }

    public ScopeTarget getTarget() {
        return target;
    }

    public void setTarget(ScopeTarget target) {
        this.target = target;
    }

    public String getColorHex() {
        return colorHex;
    }

    public void setColorHex(String colorHex) {
        this.colorHex = colorHex;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
