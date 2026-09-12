package burp.screenshot.design;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Immutable design tokens. Two instances exist: {@link #DARK} and {@link #LIGHT}.
 *
 * All UI colors and fonts come from here. Nothing in the extension may read
 * {@code UIManager} for colors, because the extension shares a JVM with Burp Suite
 * and Burp's Look and Feel values are not the extension's design language.
 */
public final class Tokens {

    // ---------------------------------------------------------------- scales

    public static final int XS = 4;
    public static final int SM = 6;
    public static final int MD = 10;
    public static final int LG = 16;
    public static final int XL = 24;

    public static final int R_SM = 4;
    public static final int R_MD = 6;
    public static final int R_LG = 10;

    // ---------------------------------------------------------------- fonts

    private static final Set<String> AVAILABLE = new HashSet<>(Arrays.asList(
            GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));

    /**
     * Resolves the first installed family. Falls back to a logical family so a machine
     * without the preferred fonts never gets a proportional font where a monospaced one
     * is required (that silently breaks right-aligned gutter numbers in the renderer).
     */
    private static String pick(String logicalFallback, String... candidates) {
        for (String c : candidates) {
            if (AVAILABLE.contains(c)) return c;
        }
        return logicalFallback;
    }

    public static final String MONO_FAMILY = pick(Font.MONOSPACED,
            "JetBrains Mono", "Cascadia Mono", "Consolas", "Menlo", "DejaVu Sans Mono", "Courier New");

    public static final String UI_FAMILY = pick(Font.SANS_SERIF,
            "Segoe UI", "Inter", "SF Pro Text", "Roboto", "Noto Sans", "Helvetica Neue");

    public static Font mono(int size) { return new Font(MONO_FAMILY, Font.PLAIN, size); }
    public static Font monoBold(int size) { return new Font(MONO_FAMILY, Font.BOLD, size); }
    public static Font monoItalic(int size) { return new Font(MONO_FAMILY, Font.ITALIC, size); }
    public static Font sans(int size) { return new Font(UI_FAMILY, Font.PLAIN, size); }
    public static Font sansBold(int size) { return new Font(UI_FAMILY, Font.BOLD, size); }

    /** True when the monospaced family resolved to a real fixed-width font. */
    public static boolean isMonospaced(Font f) {
        return f != null && "monospaced".equalsIgnoreCase(f.getFamily());
    }

    // ---------------------------------------------------------------- colors

    public final Color bgApp;
    public final Color bgPanel;
    public final Color bgCard;
    public final Color bgInput;
    public final Color bgHover;
    public final Color bgActive;
    /** Left margin behind the line numbers in the rendered card. */
    public final Color bgGutter;
    public final Color border;
    public final Color borderStrong;
    public final Color textPrimary;
    public final Color textSecondary;
    public final Color textMuted;
    public final Color textInverse;
    public final Color accent;
    public final Color accentHover;
    public final Color accentPressed;
    public final Color accentSoft;
    public final Color success;
    public final Color danger;
    public final Color warn;

    // ---------------------------------------------------------------- fonts

    public final Font ui;
    public final Font uiBold;
    public final Font uiSmall;
    public final Font uiSmallBold;
    public final Font title;
    public final Font code;
    public final Font codeBold;
    public final Font codeItalic;

    private Tokens(Builder b) {
        this.bgApp = b.bgApp;
        this.bgPanel = b.bgPanel;
        this.bgCard = b.bgCard;
        this.bgInput = b.bgInput;
        this.bgHover = b.bgHover;
        this.bgActive = b.bgActive;
        this.bgGutter = b.bgGutter;
        this.border = b.border;
        this.borderStrong = b.borderStrong;
        this.textPrimary = b.textPrimary;
        this.textSecondary = b.textSecondary;
        this.textMuted = b.textMuted;
        this.textInverse = b.textInverse;
        this.accent = b.accent;
        this.accentHover = b.accentHover;
        this.accentPressed = b.accentPressed;
        this.accentSoft = b.accentSoft;
        this.success = b.success;
        this.danger = b.danger;
        this.warn = b.warn;

        this.ui = sans(12);
        this.uiBold = sansBold(12);
        this.uiSmall = sans(11);
        this.uiSmallBold = sansBold(11);
        this.title = sansBold(13);
        this.code = mono(12);
        this.codeBold = monoBold(12);
        this.codeItalic = monoItalic(12);
    }

    public static final Tokens DARK = new Builder()
            .bgApp(0x11111b)
            .bgPanel(0x181825)
            .bgCard(0x1e1e2e)
            .bgInput(0x181825)
            .bgHover(0x2a2a3c)
            .bgActive(0x313244)
            .bgGutter(0x181825)
            .border(0x313244)
            .borderStrong(0x45475a)
            .textPrimary(0xcdd6f4)
            .textSecondary(0xa6adc8)
            .textMuted(0x6c7086)
            .textInverse(0xffffff)
            .accent(0xb91c3e)
            .accentHover(0xd9264d)
            .accentPressed(0x8f1530)
            .accentSoft(0x3a1a24)
            .success(0x40a02b)
            .danger(0xe64553)
            .warn(0xdf8e1d)
            .build();

    public static final Tokens LIGHT = new Builder()
            .bgApp(0xf2f4f7)
            .bgPanel(0xffffff)
            .bgCard(0xffffff)
            .bgInput(0xffffff)
            .bgHover(0xeef0f3)
            .bgActive(0xe4e7eb)
            .bgGutter(0xf6f8fa)
            .border(0xd8dee4)
            .borderStrong(0xc3cad3)
            .textPrimary(0x1f2328)
            .textSecondary(0x57606a)
            .textMuted(0x8c959f)
            .textInverse(0xffffff)
            .accent(0xb91c3e)
            .accentHover(0xa01a37)
            .accentPressed(0x8f1530)
            .accentSoft(0xfbeef1)
            .success(0x1a7f37)
            .danger(0xcf222e)
            .warn(0x9a6700)
            .build();

    // ---------------------------------------------------------------- helper

    /** Opaque-or-alpha variant of a color. */
    public static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }

    /** Blend two colors, {@code ratio} 0 returns {@code a}, 1 returns {@code b}. */
    public static Color mix(Color a, Color b, double ratio) {
        double r = Math.max(0, Math.min(1, ratio));
        return new Color(
                (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * r),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * r),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * r));
    }

    public static Color hex(String hex) {
        try {
            return Color.decode(hex);
        } catch (Exception e) {
            return null;
        }
    }

    public static String toHex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** Picks black or white, whichever reads better on the given background. */
    public static Color readableOn(Color bg) {
        double lum = (bg.getRed() * 299 + bg.getGreen() * 587 + bg.getBlue() * 114) / 1000.0;
        return lum > 140 ? new Color(0x1f2328) : Color.WHITE;
    }

    private static final class Builder {
        Color bgApp, bgPanel, bgCard, bgInput, bgHover, bgActive, bgGutter;
        Color border, borderStrong;
        Color textPrimary, textSecondary, textMuted, textInverse;
        Color accent, accentHover, accentPressed, accentSoft;
        Color success, danger, warn;

        Builder bgApp(int v) { this.bgApp = new Color(v); return this; }
        Builder bgPanel(int v) { this.bgPanel = new Color(v); return this; }
        Builder bgCard(int v) { this.bgCard = new Color(v); return this; }
        Builder bgInput(int v) { this.bgInput = new Color(v); return this; }
        Builder bgHover(int v) { this.bgHover = new Color(v); return this; }
        Builder bgActive(int v) { this.bgActive = new Color(v); return this; }
        Builder bgGutter(int v) { this.bgGutter = new Color(v); return this; }
        Builder border(int v) { this.border = new Color(v); return this; }
        Builder borderStrong(int v) { this.borderStrong = new Color(v); return this; }
        Builder textPrimary(int v) { this.textPrimary = new Color(v); return this; }
        Builder textSecondary(int v) { this.textSecondary = new Color(v); return this; }
        Builder textMuted(int v) { this.textMuted = new Color(v); return this; }
        Builder textInverse(int v) { this.textInverse = new Color(v); return this; }
        Builder accent(int v) { this.accent = new Color(v); return this; }
        Builder accentHover(int v) { this.accentHover = new Color(v); return this; }
        Builder accentPressed(int v) { this.accentPressed = new Color(v); return this; }
        Builder accentSoft(int v) { this.accentSoft = new Color(v); return this; }
        Builder success(int v) { this.success = new Color(v); return this; }
        Builder danger(int v) { this.danger = new Color(v); return this; }
        Builder warn(int v) { this.warn = new Color(v); return this; }

        Tokens build() { return new Tokens(this); }
    }
}
