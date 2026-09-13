package burp.screenshot.model;

public enum ScopeTarget {
    BOTH("Both"),
    REQUEST("Request"),
    RESPONSE("Response");

    private final String displayName;

    ScopeTarget(String displayName) {
        this.displayName = displayName;
    }

    public boolean appliesToRequest() {
        return this == BOTH || this == REQUEST;
    }

    public boolean appliesToResponse() {
        return this == BOTH || this == RESPONSE;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
