package burp.screenshot.model;

public enum RedactionMode {
    BLUR("Blur"),
    BLACKOUT("Blackout"),
    MASK("Mask");

    private final String displayName;

    RedactionMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
