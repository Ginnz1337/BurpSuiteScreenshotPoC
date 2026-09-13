package burp.screenshot.model;

import java.util.UUID;

/**
 * One value to take out of a screenshot, and how far to take it out.
 *
 * <p>Two styles, one flag. A blurred value keeps its characters and is painted over, so a
 * reader can see that something was there without reading it. A hidden value is replaced by a
 * marker and is not in the document at all, which is what the line-removal feature does to a
 * whole line and what a payload spanning a single long line needs.
 *
 * <p>A boolean rather than an enum, because the settings file has to stay readable by a build
 * that predates it: a new field defaults to false on load, which is the blur that the file
 * already meant.
 */
public class RedactionRule {
    private String id;
    /** What the user calls this rule, for telling one from another at a glance. */
    private String name;
    private String pattern;
    private boolean regex;
    private ScopeTarget target;
    private int captureGroup; // 0 = entire match, 1 = group 1, etc.
    private boolean enabled;
    /** False blurs the match, true takes it out and leaves a marker. */
    private boolean hide;

    public RedactionRule() {
        this.id = UUID.randomUUID().toString();
        this.name = "";
        this.pattern = "";
        this.regex = true;
        this.target = ScopeTarget.REQUEST;
        this.captureGroup = 0;
        this.enabled = true;
    }

    public RedactionRule(String pattern, boolean regex, ScopeTarget target, int captureGroup) {
        this(pattern, regex, target, captureGroup, false);
    }

    public RedactionRule(String pattern, boolean regex, ScopeTarget target, int captureGroup,
                         boolean hide) {
        this.id = UUID.randomUUID().toString();
        this.name = "";
        this.pattern = pattern;
        this.regex = regex;
        this.target = target;
        this.captureGroup = captureGroup;
        this.enabled = true;
        this.hide = hide;
    }

    /** Never null; a rule without a name reads as an empty field, not as a missing one. */
    public String getName() {
        return name == null ? "" : name;
    }

    public void setName(String name) {
        this.name = name;
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

    /** True when the match is taken out of the message, false when it is only painted over. */
    public boolean isHide() {
        return hide;
    }

    public void setHide(boolean hide) {
        this.hide = hide;
    }
}
