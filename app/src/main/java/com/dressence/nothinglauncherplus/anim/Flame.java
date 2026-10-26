package com.dressence.nothinglauncherplus.anim;

/**
 * A drawn flame made to burn: the flame itself (warm, bright pixels inside a box) is bent each
 * frame by waves that run up it, small at its root and growing toward the tips, so its tongues
 * lick and sway; the whole flame stretches and settles with each gust. The root stays where
 * the fuel is. Done on the supersampled drawing, so the flame's edges stay smooth.
 */
final class Flame {

    private final int size;
    private final int[] rest;
    /** The drawing with the flame taken out (what shows where the flame has moved off). */
    private final int[] under;
    /** The flame alone. */
    private final int[] flame;
    private final int x0;
    private final int y0;
    private final int x1;
    private final int y1;
    private final float base;
    private final float top;

    /**
     * {@code pixels}: the emoji at rest, {@code size} square; the flame's box and the row its
     * root is on, in pixels; the reddest hue that is still flame (0: any warm colour).
     */
    Flame(int[] pixels, int size, float bx0, float by0, float bx1, float by1, float root,
            float minHue) {
        this.size = size;
        rest = pixels;
        x0 = Math.max(0, (int) Math.floor(bx0));
        y0 = Math.max(0, (int) Math.floor(by0));
        x1 = Math.min(size - 1, (int) Math.ceil(bx1));
        y1 = Math.min(size - 1, (int) Math.ceil(by1));
        base = root;
        under = pixels.clone();
        flame = new int[pixels.length];
        float[] hsv = new float[3];
        int highest = y1;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int i = y * size + x;
                int c = pixels[i];
                if ((c >>> 24) == 0) {
                    continue;
                }
                Detach.rgbToHsv(c, hsv);
                // Flames are orange to yellow; a red they lick round (a heart) is not flame.
                boolean warm = minHue > 0 ? hsv[0] >= minHue && hsv[0] < 62
                        : hsv[0] < 62 || hsv[0] > 335;
                if (warm && hsv[1] > 0.25f && hsv[2] > 0.45f) {
                    flame[i] = c;
                    under[i] = 0;
                    highest = Math.min(highest, y);
                }
            }
        }
        top = highest;
    }

    /** Writes the burning flame at time {@code t} (seconds) into {@code out}. */
    void render(float t, int[] out) {
        System.arraycopy(rest, 0, out, 0, out.length);
        float height = Math.max(1, base - top);
        // Gusts: the flame stretches up and draws in, irregularly.
        float gust = 0.07f * (float) (Math.sin(t * 5.3) * 0.6 + Math.sin(t * 8.9 + 1.3) * 0.4);
        float scale = height / 96f * size;
        // How far the tips sway: a small flame (a candle's) still visibly dances.
        float sway = Math.max(height * 0.09f, size / 40f);
        // Pixels above the box are open air the tips may lick into.
        int top0 = Math.max(0, y0 - Math.round(height * 0.25f));
        int pad = Math.round(height * 0.2f);
        for (int y = top0; y <= y1; y++) {
            for (int x = Math.max(0, x0 - pad); x <= Math.min(size - 1, x1 + pad); x++) {
                int i = y * size + x;
                float h = Math.max(0, Math.min(1.3f, (base - y) / height));
                // Waves running up the flame, faster and finer near the tips.
                float w = h * h;
                float dx = (float) (Math.sin(y * 0.11 / scale * 1.0 + t * 9.0) * 0.55
                        + Math.sin(y * 0.23 / scale + t * 13.0 + x * 0.04 / scale) * 0.3
                        + Math.sin(t * 3.1) * 0.25) * w * sway;
                float sy = base - (base - y) / (1 + gust * h);
                float sx = x + 0.5f - dx;
                int c = sample(flame, sx, sy + 0.5f);
                int u = x >= x0 && x <= x1 && y >= y0 ? under[i] : rest[i];
                out[i] = over(c, u);
            }
        }
    }

    /** {@code top} drawn over {@code bottom}. */
    private static int over(int top, int bottom) {
        float a = (top >>> 24) / 255f;
        if (a <= 0) {
            return bottom;
        }
        float b = (bottom >>> 24) / 255f * (1 - a);
        float oa = a + b;
        int r = Math.round((((top >> 16) & 0xFF) * a + ((bottom >> 16) & 0xFF) * b) / oa);
        int g = Math.round((((top >> 8) & 0xFF) * a + ((bottom >> 8) & 0xFF) * b) / oa);
        int bl = Math.round(((top & 0xFF) * a + (bottom & 0xFF) * b) / oa);
        return Math.round(oa * 255) << 24 | r << 16 | g << 8 | bl;
    }

    private int sample(int[] px, float x, float y) {
        int ix = (int) Math.floor(x - 0.5f);
        int iy = (int) Math.floor(y - 0.5f);
        float fx = x - 0.5f - ix;
        float fy = y - 0.5f - iy;
        float a = 0;
        float r = 0;
        float g = 0;
        float b = 0;
        for (int k = 0; k < 4; k++) {
            int xx = ix + (k & 1);
            int yy = iy + (k >> 1);
            if (xx < 0 || yy < 0 || xx >= size || yy >= size) {
                continue;
            }
            float w = ((k & 1) == 0 ? 1 - fx : fx) * ((k >> 1) == 0 ? 1 - fy : fy);
            int c = px[yy * size + xx];
            float wa = w * (c >>> 24);
            a += wa;
            r += wa * ((c >> 16) & 0xFF);
            g += wa * ((c >> 8) & 0xFF);
            b += wa * (c & 0xFF);
        }
        if (a <= 0) {
            return 0;
        }
        return Math.round(a) << 24 | Math.round(r / a) << 16 | Math.round(g / a) << 8
                | Math.round(b / a);
    }
}
