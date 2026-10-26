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

    /**
     * Writes {@code rest} ({@code size} square) with its cloth waving into {@code out}: the
     * part right of {@code pole} between {@code top} and {@code bottom} (pixels) ripples up
     * and down in waves running away from the pole, more the further from it, and is lit
     * and shaded along the folds. {@code wavelength} and {@code amount} are in pixels.
     */
    static void wave(int[] rest, int size, float pole, float top, float bottom, float phase,
            float wavelength, float amount, int[] out) {
        float k = (float) (Math.PI * 2 / wavelength);
        float ramp = wavelength * 0.6f;
        for (int x = 0; x < size; x++) {
            float d = x + 0.5f - pole;
            if (d <= 0) {
                for (int y = 0; y < size; y++) {
                    out[y * size + x] = rest[y * size + x];
                }
                continue;
            }
            float a = Math.min(1, d / ramp) * amount;
            float dy = a * (float) Math.sin(d * k - phase);
            // The fold facing up catches the light; facing away, it falls into shade.
            float light = 1 + 0.16f * Math.min(1, d / ramp) * (float) Math.cos(d * k - phase);
            for (int y = 0; y < size; y++) {
                float yy = y + 0.5f;
                int c;
                if (yy < top - Math.abs(a) - 1 || yy > bottom + Math.abs(a) + 1) {
                    c = rest[y * size + x];
                } else {
                    c = sample(rest, size, x + 0.5f, yy - dy);
                    float sy = yy - dy;
                    if (sy < top || sy > bottom) {
                        // Only the cloth moves: beyond it is what is there at rest.
                        c = sy < top - 0.5f || sy > bottom + 0.5f ? 0 : c;
                    }
                    c = shade(c, light);
                }
                out[y * size + x] = c;
            }
        }
    }

    private static int shade(int c, float k) {
        int out = c & 0xFF000000;
        for (int sh = 16; sh >= 0; sh -= 8) {
            out |= Math.max(0, Math.min(255, Math.round(((c >> sh) & 0xFF) * k))) << sh;
        }
        return out;
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
