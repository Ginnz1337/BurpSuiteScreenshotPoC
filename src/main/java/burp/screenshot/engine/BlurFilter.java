package burp.screenshot.engine;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

/**
 * Box and shadow blurs built on an integral image.
 *
 * <p>An integral image makes a box blur cost the same at radius 40 as at radius 1, so the
 * card shadow stays cheap while the preview re-renders on every edit. Three box passes
 * approximate a Gaussian closely enough that no banding is visible.
 */
public final class BlurFilter {

    private BlurFilter() {}

    /**
     * A shadow raster and the image coordinate its top-left corner belongs at.
     *
     * <p>Returned instead of a bare image because a partial shadow starts at the edge of the
     * patch that was blurred, not at the origin of the canvas.
     */
    public record Shadow(BufferedImage image, int x, int y) {}

    /**
     * Soft drop shadow for a rounded card, optionally only the part of it a caller needs.
     *
     * <p>The shadow is rasterised once as a coverage mask, blurred, then composited in a
     * single draw. Drawing concentric {@code fillRoundRect} calls instead accumulates alpha
     * as {@code 1-(1-a)^n}, which is what made the old shadow edge nearly opaque.
     *
     * <p>A full shadow costs two byte arrays and a {@code long} prefix array of the whole
     * canvas per box pass, so on a tall card it dominates the paint. Passing {@code wanted}
     * blurs a patch grown by the blur's own reach and returns only that patch, which is what
     * lets the preview pay for the visible area instead of the whole card.
     *
     * <p>The patch is padded by the full reach of the three box passes, so every pixel inside
     * {@code wanted} averages a complete window and the result is identical to the full blur.
     * Where the patch meets the canvas edge the two clamp identically, because the card is
     * inset from the canvas by the shadow margin, which is wider than that reach.
     *
     * @param peakAlpha alpha directly under the card edge, before falloff
     * @param wanted    the part of the canvas to produce, or {@code null} for all of it
     * @return the shadow, or {@code null} if {@code wanted} lies outside the canvas
     */
    public static Shadow softShadow(int width, int height, Shape cardShape,
                                    int radius, float peakAlpha, Rectangle wanted) {
        int boxRadius = Math.max(1, radius / 3);
        int reach = boxRadius * 3;

        int x0 = 0;
        int y0 = 0;
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        if (wanted != null) {
            x0 = Math.max(0, wanted.x - reach);
            y0 = Math.max(0, wanted.y - reach);
            int x1 = Math.min(width, wanted.x + wanted.width + reach);
            int y1 = Math.min(height, wanted.y + wanted.height + reach);
            if (x1 <= x0 || y1 <= y0) return null;
            w = x1 - x0;
            h = y1 - y0;
        }

        BufferedImage mask = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = mask.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // An integer shift, so the shape's antialiasing is sampled at the same subpixel
        // positions as it would be on the full canvas.
        g.translate(-x0, -y0);
        g.setColor(Color.WHITE);
        g.fill(cardShape);
        g.dispose();

        byte[] coverage = ((DataBufferByte) mask.getRaster().getDataBuffer()).getData();
        byte[] blurred = boxBlur(coverage, w, h, boxRadius, 3);

        int peak = Math.max(0, Math.min(255, Math.round(peakAlpha * 255f)));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            px[i] = ((blurred[i] & 0xFF) * peak / 255) << 24;
        }
        out.setRGB(0, 0, w, h, px, 0, w);
        return new Shadow(out, x0, y0);
    }

    /** Blurs a standalone ARGB image. The caller owns the result. */
    public static BufferedImage blurArgb(BufferedImage src, int radius) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w < 3 || h < 3 || radius < 1) return copy(src);

        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        int n = w * h;

        byte[] a = new byte[n];
        byte[] r = new byte[n];
        byte[] g = new byte[n];
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            int p = argb[i];
            a[i] = (byte) (p >>> 24);
            r[i] = (byte) (p >> 16);
            g[i] = (byte) (p >> 8);
            b[i] = (byte) p;
        }

        int passes = 3;
        int boxRadius = Math.max(1, radius / passes);
        for (int pass = 0; pass < passes; pass++) {
            a = boxBlur(a, w, h, boxRadius, 1);
            r = boxBlur(r, w, h, boxRadius, 1);
            g = boxBlur(g, w, h, boxRadius, 1);
            b = boxBlur(b, w, h, boxRadius, 1);
        }

        for (int i = 0; i < n; i++) {
            argb[i] = ((a[i] & 0xFF) << 24) | ((r[i] & 0xFF) << 16) | ((g[i] & 0xFF) << 8) | (b[i] & 0xFF);
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, w, h, argb, 0, w);
        return out;
    }

    /**
     * Blurs a rectangular region of {@code src} in place, clamped to the image bounds.
     *
     * <p>The blur is computed over a patch grown by {@code pad} pixels on each side, so the
     * pixels along the region's own edge are averaged with their real neighbours rather than
     * with the clamped edge of a tight crop. Only the requested rectangle is written back,
     * so the blur never bleeds outside it.
     */
    public static void blurRegion(BufferedImage src, int x, int y, int w, int h,
                                  int pad, int radius) {
        int dx0 = Math.max(0, x);
        int dy0 = Math.max(0, y);
        int dx1 = Math.min(src.getWidth(), x + w);
        int dy1 = Math.min(src.getHeight(), y + h);
        if (dx1 <= dx0 || dy1 <= dy0) return;

        int px0 = Math.max(0, dx0 - pad);
        int py0 = Math.max(0, dy0 - pad);
        int px1 = Math.min(src.getWidth(), dx1 + pad);
        int py1 = Math.min(src.getHeight(), dy1 + pad);
        int rw = px1 - px0;
        int rh = py1 - py0;
        if (rw < 3 || rh < 3) return;

        // Copy out first: getSubimage shares the parent raster, so blurring it in place
        // would read pixels that earlier writes already changed.
        BufferedImage patch = new BufferedImage(rw, rh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = patch.createGraphics();
        pg.drawImage(src, 0, 0, rw, rh, px0, py0, px1, py1, null);
        pg.dispose();

        BufferedImage blurred = blurArgb(patch, radius);

        Graphics2D g = src.createGraphics();
        g.drawImage(blurred, dx0, dy0, dx1, dy1,
                dx0 - px0, dy0 - py0, dx1 - px0, dy1 - py0, null);
        g.dispose();
    }

    // ------------------------------------------------------------------ core

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /** Clamped, averaged box blur of a single 8-bit channel. */
    private static byte[] boxBlur(byte[] src, int w, int h, int radius, int passes) {
        if (radius < 1) return src;
        byte[] current = src;
        for (int pass = 0; pass < passes; pass++) {
            current = boxBlurOnce(current, w, h, radius);
        }
        return current;
    }

    private static byte[] boxBlurOnce(byte[] src, int w, int h, int r) {
        long[] prefix = new long[(w + 1) * (h + 1)];

        for (int y = 0; y < h; y++) {
            long rowSum = 0;
            int rowBase = y * w;
            int cur = (y + 1) * (w + 1);
            int prev = y * (w + 1);
            for (int x = 0; x < w; x++) {
                rowSum += src[rowBase + x] & 0xFF;
                prefix[cur + x + 1] = prefix[prev + x + 1] + rowSum;
            }
        }

        byte[] dst = new byte[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - r);
            int y1 = Math.min(h - 1, y + r);
            int rows = y1 - y0 + 1;
            int top = y0 * (w + 1);
            int bottom = (y1 + 1) * (w + 1);
            int outBase = y * w;
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - r);
                int x1 = Math.min(w - 1, x + r);
                long sum = prefix[bottom + x1 + 1] - prefix[top + x1 + 1]
                        - prefix[bottom + x0] + prefix[top + x0];
                dst[outBase + x] = (byte) (sum / ((long) rows * (x1 - x0 + 1)));
            }
        }
        return dst;
    }
}
