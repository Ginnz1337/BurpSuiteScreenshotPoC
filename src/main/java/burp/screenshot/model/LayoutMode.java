package burp.screenshot.model;

public enum LayoutMode {
    SIDE_BY_SIDE("Side by Side"),
    STACKED("Stacked");

    private final String displayName;

    LayoutMode(String displayName) {
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
