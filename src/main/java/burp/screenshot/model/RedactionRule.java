package burp.screenshot.model;

import java.util.UUID;

public class RedactionRule {
    private String id;
    private String pattern;
    private boolean regex;
    private ScopeTarget target;
    private RedactionMode mode;
    private int captureGroup; // 0 = entire match, 1 = group 1, etc.
    private boolean enabled;

    public RedactionRule() {
        this.id = UUID.randomUUID().toString();
        this.pattern = "";
        this.regex = true;
        this.target = ScopeTarget.REQUEST;
        this.mode = RedactionMode.BLUR;
        this.captureGroup = 0;
        this.enabled = true;
    }

    public RedactionRule(String pattern, boolean regex, ScopeTarget target, RedactionMode mode, int captureGroup) {
        this.id = UUID.randomUUID().toString();
        this.pattern = pattern;
        this.regex = regex;
        this.target = target;
        this.mode = mode;
        this.captureGroup = captureGroup;
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

    public RedactionMode getMode() {
        return mode;
    }

    public void setMode(RedactionMode mode) {
        this.mode = mode;
    }

    public int getCaptureGroup() {
        return captureGroup;
    }

    public void setCaptureGroup(int captureGroup) {
        this.captureGroup = captureGroup;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
