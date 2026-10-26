package com.dressence.nothinglauncherplus.anim;

/**
 * A plant in the wind: the drawing is bent sideways, not at all at its root and more and more
 * toward its top, as a stem bends, in the supersampled frame, so it sways smoothly instead of
 * row by row in whole dots.
 */
final class Bend {

    private Bend() {
    }

    /**
     * Writes {@code rest} ({@code size} square) bent by {@code amount} pixels at its free end
     * ({@code top}, pixels) and none at {@code root} into {@code out}.
     */
    static void render(int[] rest, int size, float top, float root, float amount, int[] out) {
        // The root may be at the top (a chime hanging from it): then it bends downward.
        float height = root - top;
        if (Math.abs(height) < 1) {
            height = height < 0 ? -1 : 1;
        }
        for (int y = 0; y < size; y++) {
            float h = Math.max(0, Math.min(1.2f, (root - (y + 0.5f)) / height));
            // A stem bends along a curve: little near the root, most at the tip.
            float dx = amount * h * h;
            // A bent stem is a little shorter: its top dips as it leans.
            float dy = -Math.abs(amount) * 0.12f * h * h * h;
            for (int x = 0; x < size; x++) {
                out[y * size + x] = sample(rest, size, x + 0.5f - dx, y + 0.5f - dy);
            }
        }
    }

    private static int sample(int[] px, int size, float x, float y) {
        int x0 = (int) Math.floor(x - 0.5f);
        int y0 = (int) Math.floor(y - 0.5f);
        float fx = x - 0.5f - x0;
        float fy = y - 0.5f - y0;
        float a = 0;
        float r = 0;
        float g = 0;
        float b = 0;
        for (int k = 0; k < 4; k++) {
            int xx = x0 + (k & 1);
            int yy = y0 + (k >> 1);
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
