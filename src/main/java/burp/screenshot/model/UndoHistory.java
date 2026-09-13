package burp.screenshot.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The settings states a tab has been through, so the last change can be taken back.
 *
 * <p>States rather than edits. A rule added from the right-click menu, a mode switched, a header
 * name typed into the hide list and a colour picked in the settings dialog are four different
 * kinds of change, and recording the inverse of each would mean four places to get wrong. A copy
 * of the settings before the change is one thing to get right, and it also covers the change
 * nobody thought to write an undo for.
 *
 * <p>Snapshots are taken before an edit begins, not after it ends. That means opening the
 * right-click menu and picking nothing leaves a duplicate of the current state on the stack;
 * {@link #undo} walks past those, so a state that says the same thing as the one on screen is
 * never handed back as a change.
 */
public final class UndoHistory {

    /** How many states are kept. Each is a copy of a small settings object, not of the message. */
    public static final int LIMIT = 50;

    private final Deque<TemplateConfig> past = new ArrayDeque<>();
    private final int limit;

    public UndoHistory() {
        this(LIMIT);
    }

    public UndoHistory(int limit) {
        this.limit = Math.max(1, limit);
    }

    /**
     * Remembers a state, to be returned to if the change about to happen is not wanted.
     *
     * <p>Called before the edit. The oldest state is dropped once the stack is full: a user who
     * has made fifty changes is not going to walk back through all of them, and the alternative
     * is a stack that grows with the session.
     */
    public void remember(TemplateConfig state) {
        if (state == null) return;
        past.push(state.copy());
        while (past.size() > limit) past.removeLast();
    }

    /** How many states are held. */
    public int size() {
        return past.size();
    }

    public void clear() {
        past.clear();
    }

    /**
     * The state to go back to, or null when there is nothing left to undo.
     *
     * <p>States that say the same thing as {@code current} are dropped rather than returned:
     * they come from menus that were opened and dismissed, and handing one back would make
     * Ctrl+Z look broken, since nothing on screen would change.
     */
    public TemplateConfig undo(TemplateConfig current) {
        while (!past.isEmpty()) {
            TemplateConfig candidate = past.pop();
            if (!same(candidate, current)) return candidate;
        }
        return null;
    }

    // ------------------------------------------------------------------ comparison

    /**
     * Whether two states would draw the same thing.
     *
     * <p>Field by field, and deliberately not the rule ids: a copy carries fresh ids, so
     * comparing them would call every state different and undo would hand back the state that is
     * already on screen.
     */
    private static boolean same(TemplateConfig a, TemplateConfig b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return eq(a.getName(), b.getName())
                && eq(a.getHeadersToHide(), b.getHeadersToHide())
                && a.getHeaderScope() == b.getHeaderScope()
                && eq(a.getRequestLineRanges(), b.getRequestLineRanges())
                && eq(a.getResponseLineRanges(), b.getResponseLineRanges())
                && a.isWrapText() == b.isWrapText()
                && a.getSyntaxColors().equals(b.getSyntaxColors())
                && highlightsOf(a).equals(highlightsOf(b))
                && redactionsOf(a).equals(redactionsOf(b));
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * A rule as a value, so two rules compare on what they do and not on their fresh id.
     *
     * <p>{@code color} is empty for a redaction. The style is part of the value: switching a rule
     * from blur to hide changes what the screenshot shows, so it has to count as a change worth
     * an undo step rather than one the comparison calls identical.
     */
    private record RuleKey(String name, String pattern, boolean regex, ScopeTarget target,
                           String color, int captureGroup, boolean enabled, boolean hide) { }

    private static List<RuleKey> highlightsOf(TemplateConfig c) {
        List<RuleKey> out = new ArrayList<>();
        for (HighlightRule h : c.getHighlights()) {
            out.add(new RuleKey(h.getName(), h.getPattern(), h.isRegex(), h.getTarget(),
                    h.getColorHex(), 0, h.isEnabled(), false));
        }
        return out;
    }

    private static List<RuleKey> redactionsOf(TemplateConfig c) {
        List<RuleKey> out = new ArrayList<>();
        for (RedactionRule r : c.getRedactions()) {
            out.add(new RuleKey(r.getName(), r.getPattern(), r.isRegex(), r.getTarget(), "",
                    r.getCaptureGroup(), r.isEnabled(), r.isHide()));
        }
        return out;
    }
}
