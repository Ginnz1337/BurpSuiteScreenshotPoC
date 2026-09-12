package burp.screenshot.model;

import java.util.UUID;

public class HighlightRule {
    private String id;
    private String pattern;
    private boolean regex;
    private ScopeTarget target;
    private String colorHex;
    private boolean enabled;

    public HighlightRule() {
        this.id = UUID.randomUUID().toString();
        this.pattern = "";
        this.regex = true;
        this.target = ScopeTarget.REQUEST;
        this.colorHex = "#e5c07b"; // Default warm yellow
        this.enabled = true;
    }

    public HighlightRule(String pattern, boolean regex, ScopeTarget target, String colorHex) {
        this.id = UUID.randomUUID().toString();
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
