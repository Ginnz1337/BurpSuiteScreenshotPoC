package burp.screenshot.design;

import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central theme state.
 *
 * <p>Burp's Montoya API exposes {@code UserInterface.currentTheme()} but fires no event when
 * the user switches theme, so {@link #poll()} runs a lightweight timer while at least one
 * listener is registered.
 *
 * <p>Components must read {@link #tokens()} at paint time rather than caching colors in
 * their constructors. Caching at construction time is what made the previous
 * implementation ignore theme changes entirely.
 */
public final class Theme {

    public enum Mode {
        AUTO("Match Burp"),
        DARK("Dark"),
        LIGHT("Light");

        private final String label;
        Mode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    /** Supplies theme state from the host. Implemented by BurpExtender against Montoya. */
    public interface Source {
        boolean isDark();
        Font editorFont();
        Font displayFont();
    }

    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    /**
     * How many times in a row the host source may fail before it is left alone.
     *
     * <p>Montoya logs every exception its own proxies throw, before the caller ever sees it, so
     * a poll timer that keeps asking a host which is not answering is a timer that fills Burp's
     * error output once a second. Three attempts ride out a momentary failure; after that the
     * Look and Feel colours answer for the rest of the session, and the next
     * {@link #setSource} starts the count over.
     */
    private static final int MAX_SOURCE_FAILURES = 3;

    private static Source source;
    private static Mode mode = Mode.AUTO;
    private static boolean dark = true;
    private static Timer poller;

    /** Consecutive {@link Source} failures. Reset by {@link #setSource} and {@link #dispose}. */
    private static int sourceFailures;

    private Theme() {}

    public static void setSource(Source s) {
        source = s;
        sourceFailures = 0;
        refresh(true);
    }

    /**
     * Drops the host source, the listeners and the poll timer.
     *
     * <p>Called when Burp unloads the extension, which is the only moment the host stops being
     * real. Everything here is static, so without this the timer outlives the extension and
     * keeps calling a Montoya proxy Burp has already torn down: {@code currentTheme()} is then
     * reported to throw a {@code NullPointerException} from inside Burp itself, once a second,
     * for as long as the JVM lives. The listener list is emptied for the same reason, one step
     * further out: each entry holds a panel, and a panel holds the whole component tree behind
     * it, so the extension's UI would stay reachable after it was unloaded.
     */
    public static synchronized void dispose() {
        source = null;
        sourceFailures = 0;
        LISTENERS.clear();
        stopPolling();
    }

    /** Whether the poll timer is running. Read by the verification suite. */
    public static synchronized boolean isPolling() {
        return poller != null;
    }

    public static void setMode(Mode m) {
        if (m == null || m == mode) return;
        mode = m;
        refresh(true);
    }

    public static Mode getMode() { return mode; }

    public static boolean isDark() { return dark; }

    public static Tokens tokens() { return dark ? Tokens.DARK : Tokens.LIGHT; }

    /**
     * Registers a re-theme callback. The returned handle removes it, so a window that
     * registers on open and closes on dispose cannot leak into the static list.
     */
    public static AutoCloseable addListener(Runnable r) {
        LISTENERS.add(r);
        startPolling();
        return () -> {
            LISTENERS.remove(r);
            if (LISTENERS.isEmpty()) stopPolling();
        };
    }

    /** Re-evaluates the theme. Use after a manual override or a host theme change. */
    public static void refresh(boolean force) {
        boolean next = resolveDark();
        if (!force && next == dark) return;
        dark = next;
        for (Runnable r : LISTENERS) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // One broken listener must not stop the others.
            }
        }
    }

    // ------------------------------------------------------------- internals

    private static boolean resolveDark() {
        if (mode == Mode.DARK) return true;
        if (mode == Mode.LIGHT) return false;

        Source current = source;
        if (current != null && sourceFailures < MAX_SOURCE_FAILURES) {
            try {
                boolean next = current.isDark();
                sourceFailures = 0;
                return next;
            } catch (RuntimeException | LinkageError e) {
                // A host that is not ready yet, or one that has been unloaded while a timer was
                // still running. Both arrive as a NullPointerException thrown from inside Burp's
                // own implementation of currentTheme(). Burp has already written that failure to
                // its error output by the time it lands here, so counting it is all this can do:
                // once the count is up the source is left alone and the Look and Feel answers.
                if (++sourceFailures == MAX_SOURCE_FAILURES) {
                    System.err.println("[PoC view] Burp's theme API failed " + MAX_SOURCE_FAILURES
                            + " times in a row; using the Look and Feel colours until the "
                            + "extension is reloaded.");
                }
            }
        }
        return guessDarkFromLookAndFeel();
    }

    /**
     * Fallback used when no {@link Source} is installed (headless tests, a host that fails to
     * report its theme, or one that has just been unloaded). Never used to *set* colors in Burp.
     *
     * <p>Burp swaps its Look and Feel when the theme changes, so the panel colour read here
     * moves with the theme as well, which is what lets this stand in for the API.
     */
    private static boolean guessDarkFromLookAndFeel() {
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null) bg = UIManager.getColor("control");
        if (bg == null) return true;
        double brightness = (bg.getRed() * 299 + bg.getGreen() * 587 + bg.getBlue() * 114) / 1000.0;
        return brightness < 128;
    }

    private static synchronized void startPolling() {
        if (poller != null) return;
        poller = new Timer(1000, e -> refresh(false));
        poller.setRepeats(true);
        poller.start();
    }

    private static synchronized void stopPolling() {
        if (poller == null) return;
        poller.stop();
        poller = null;
    }

    /** Editor font from the host, at the given size, falling back to the token font. */
    public static Font editorFont(int size) {
        Font f = safeFont(true);
        return f != null ? f.deriveFont((float) size) : Tokens.mono(size);
    }

    /** Display font from the host, at the given size, falling back to the token font. */
    public static Font displayFont(int size) {
        Font f = safeFont(false);
        return f != null ? f.deriveFont((float) size) : Tokens.sans(size);
    }

    private static Font safeFont(boolean editor) {
        if (source == null) return null;
        try {
            return editor ? source.editorFont() : source.displayFont();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
