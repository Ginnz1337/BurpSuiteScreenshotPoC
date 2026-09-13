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

    private static Source source;
    private static Mode mode = Mode.AUTO;
    private static boolean dark = true;
    private static Timer poller;

    private Theme() {}

    public static void setSource(Source s) {
        source = s;
        refresh(true);
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

        if (source != null) {
            try {
                return source.isDark();
            } catch (RuntimeException ignored) {
                // Fall through to the Look and Feel heuristic below.
            }
        }
        return guessDarkFromLookAndFeel();
    }

    /**
     * Fallback used only when no {@link Source} is installed (headless tests, or a host
     * that fails to report its theme). Never used to *set* colors in Burp.
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
