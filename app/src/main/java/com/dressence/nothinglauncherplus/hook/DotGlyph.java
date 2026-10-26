package com.dressence.nothinglauncherplus.hook;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

/**
 * An icon redrawn the way NDot draws its letters: whole round dots on an even grid, each dot
 * either lit or not, never a smear of half-lit ones. A dot is lit where the icon covers most
 * of its cell; the faint parts of two-tone icons (the empty bars of the signal icon, say) light
 * their dots dimly, as they are drawn faint in the icon itself.
 */
final class DotGlyph {

    /** How much of a cell the icon must cover for its dot to light. */
    private static final float COVER = 0.4f;
    /** A dot's radius, in grid pitches: as NDot57's, nearly touching. */
    private static final float RADIUS = 0.44f;
    /** The level of a dot in a faint part of the icon. */
    private static final float FAINT = 0.4f;

    private static int[] pixels = new int[0];

    private DotGlyph() {
    }

    /**
     * Draws {@code src} on {@code canvas} as dots {@code pitch} apart, centred: in {@code colour},
     * or in the icon's own colours when it is 0.
     */
    static void draw(Canvas canvas, Bitmap src, float pitch, int colour, float alpha,
            Paint paint) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (pitch < 2) {
            pitch = 2;
        }
        if (pixels.length < w * h) {
            pixels = new int[w * h];
        }
        src.getPixels(pixels, 0, w, 0, 0, w, h);
        int cols = (int) (w / pitch);
        int rows = (int) (h / pitch);
        float ox = (w - cols * pitch) / 2;
        float oy = (h - rows * pitch) / 2;
        float radius = pitch * RADIUS;
        for (int gy = 0; gy < rows; gy++) {
            int y0 = Math.round(oy + gy * pitch);
            int y1 = Math.min(h, Math.round(oy + (gy + 1) * pitch));
            for (int gx = 0; gx < cols; gx++) {
                int x0 = Math.round(ox + gx * pitch);
                int x1 = Math.min(w, Math.round(ox + (gx + 1) * pitch));
                int n = 0;
                int covered = 0;
                long a = 0, r = 0, g = 0, b = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int p = pixels[y * w + x];
                        int pa = p >>> 24;
                        n++;
                        if (pa < 32) {
                            continue;
                        }
                        covered++;
                        a += pa;
                        r += ((p >> 16) & 0xFF) * pa;
                        g += ((p >> 8) & 0xFF) * pa;
                        b += (p & 0xFF) * pa;
                    }
                }
                if (n == 0 || covered < COVER * n) {
                    continue;
                }
                float bright = a / (255f * covered);
                float level = bright > 0.6f ? 1 : FAINT;
                int c = colour != 0 ? colour
                        : Color.rgb((int) (r / a), (int) (g / a), (int) (b / a));
                paint.setColor(c);
                paint.setAlpha(Math.round(Color.alpha(c) * level * alpha));
                canvas.drawCircle(ox + (gx + 0.5f) * pitch, oy + (gy + 0.5f) * pitch, radius,
                        paint);
            }
        }
    }
}
