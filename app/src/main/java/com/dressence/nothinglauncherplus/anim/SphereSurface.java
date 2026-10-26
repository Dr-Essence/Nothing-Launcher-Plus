package com.dressence.nothinglauncherplus.anim;

/**
 * A round emoji (a ball, a globe, a rugby ball) turned as a real solid about its own axis, in
 * the supersampled frame: upright for a round one, along its length for a long one.
 *
 * <p>The drawing is split once into its surface (what turns) and its lighting (what stays: the
 * shaded side, the rim). Lighting is found from the drawing's own palette: a colour that is a
 * darker copy of another (a white panel's grey side, the basketball's darker orange) is the
 * lit colour in shade. Each frame the surface is turned with an orthographic projection, across
 * each slice of the solid, and lit again where the light was, so the shadow stays on its side
 * while the panels roll through it. The far side is the near side mirrored, so the surface
 * never shows a seam.
 */
final class SphereSurface {

    /** Share of each slice's half-width at the edge that stays put: outline and limb. */
    private static final float RIM = 0.08f;

    private final int size;
    private final int[] rest;
    /** Per pixel: the unlit surface colour and the light falling there (1: full). */
    private final int[] albedo;
    private final float[] shade;
    /**
     * Per pixel inside the turning area: its longitude on its slice, the slice's middle and
     * half-width across the axis, and its place along the axis (NaN longitude: stays put).
     */
    private final float[] lon;
    private final float[] mid;
    private final float[] half;
    private final float[] along;
    /** The axis (unit vector) and the direction across it. */
    private final float ax;
    private final float ay;

    /** {@code pixels}: the emoji at rest, {@code size} square; the solid's centre and radius. */
    SphereSurface(int[] pixels, int size, float cx, float cy, float radius, boolean roll) {
        this.size = size;
        this.roll = roll;
        rest = pixels.clone();
        int n = size * size;
        if (roll) {
            // Everything of it turns, out to its farthest point (a rugby ball's tips).
            float far = 0;
            for (int i = 0; i < n; i++) {
                float dx = i % size + 0.5f - cx;
                float dy = i / size + 0.5f - cy;
                if ((rest[i] >>> 24) > 0) {
                    far = Math.max(far, dx * dx + dy * dy);
                }
            }
            radius = Math.max(radius, (float) Math.sqrt(far) + 1);
            // How much it must shrink at each angle to stay inside the frame (a long ball
            // stood upright is taller than it is wide lying across the corner).
            fit = new float[FIT_STEPS];
            for (int k = 0; k < FIT_STEPS; k++) {
                double a = Math.PI * 2 * k / FIT_STEPS;
                float cos = (float) Math.cos(a);
                float sin = (float) Math.sin(a);
                float reach = 0;
                for (int i = 0; i < n; i++) {
                    if ((rest[i] >>> 24) > 64) {
                        float dx = i % size + 0.5f - cx;
                        float dy = i / size + 0.5f - cy;
                        float x = dx * cos - dy * sin;
                        float y = dx * sin + dy * cos;
                        reach = Math.max(reach, Math.max(Math.abs(x + cx - size / 2f),
                                Math.abs(y + cy - size / 2f)));
                    }
                }
                fit[k] = Math.min(1, (size / 2f - 1.5f) / Math.max(1, reach));
            }
        } else {
            fit = null;
        }
        this.radius = radius;
        // The axis: the solid's length if it is clearly long, else upright.
        double sxx = 0, syy = 0, sxy = 0, sum = 0, mx = 0, my = 0;
        for (int i = 0; i < n; i++) {
            if (inside(i, cx, cy, radius)) {
                mx += i % size;
                my += i / size;
                sum++;
            }
        }
        mx /= Math.max(1, sum);
        my /= Math.max(1, sum);
        for (int i = 0; i < n; i++) {
            if (inside(i, cx, cy, radius)) {
                double dx = i % size - mx;
                double dy = i / size - my;
                sxx += dx * dx;
                syy += dy * dy;
                sxy += dx * dy;
            }
        }
        double tr = sxx + syy;
        double det = sxx * syy - sxy * sxy;
        double l1 = tr / 2 + Math.sqrt(Math.max(0, tr * tr / 4 - det));
        double l2 = tr - l1;
        if (l2 > 0 && l1 / l2 > 1.3) {
            double angle = 0.5 * Math.atan2(2 * sxy, sxx - syy);
            ax = (float) Math.cos(angle);
            ay = (float) Math.sin(angle);
        } else {
            ax = 0;
            ay = 1;
        }
        // Slices across the axis, one per pixel step along it: where each starts and ends.
        int span = size * 2;
        float[] lo = new float[span];
        float[] hi = new float[span];
        java.util.Arrays.fill(lo, Float.MAX_VALUE);
        java.util.Arrays.fill(hi, -Float.MAX_VALUE);
        along = new float[n];
        float[] across = new float[n];
        for (int i = 0; i < n; i++) {
            float x = i % size + 0.5f - cx;
            float y = i / size + 0.5f - cy;
            along[i] = x * ax + y * ay;
            across[i] = -x * ay + y * ax;
            if (inside(i, cx, cy, radius)) {
                int k = slice(along[i]);
                lo[k] = Math.min(lo[k], across[i] - 0.5f);
                hi[k] = Math.max(hi[k], across[i] + 0.5f);
            }
        }
        lon = new float[n];
        mid = new float[n];
        half = new float[n];
        java.util.Arrays.fill(lon, Float.NaN);
        // Every pixel between a slice's ends turns, see-through ones too (a wire globe's gaps).
        for (int i = 0; i < n; i++) {
            int k = slice(along[i]);
            if (hi[k] < lo[k]) {
                continue;
            }
            float h = (hi[k] - lo[k]) / 2 * (1 - RIM);
            float m = (hi[k] + lo[k]) / 2;
            float u = (across[i] - m) / h;
            if (h >= 1 && u > -1 && u < 1) {
                lon[i] = (float) Math.asin(u);
                mid[i] = m;
                half[i] = h;
            }
        }
        this.cx = cx;
        this.cy = cy;
        shade = new float[n];
        albedo = new int[n];
        light();
    }

    private final float cx;
    private final float cy;
    private final float radius;
    private static final int FIT_STEPS = 64;
    /** Rolling: the scale that keeps it inside the frame, per step of a turn. */
    private final float[] fit;
    /** Rolls in the picture plane (a ball) rather than turning about its axis (a globe). */
    private final boolean roll;

    private int slice(float a) {
        return Math.max(0, Math.min(size * 2 - 1, Math.round(a) + size));
    }

    /** Part of the solid: drawn, and within its round outline. */
    private boolean inside(int i, float cx, float cy, float radius) {
        float dx = i % size + 0.5f - cx;
        float dy = i / size + 0.5f - cy;
        return (rest[i] >>> 24) > 128 && dx * dx + dy * dy <= radius * radius;
    }

    /**
     * Writes the solid turned by {@code turn} radians into {@code out} (same layout as the
     * input); pixels outside the turning area are left as they are.
     */
    void render(float turn, int[] out) {
        if (roll) {
            roll(turn, out);
            return;
        }
        // The drawing only shows the limb squeezed, so read straight back it would smear when
        // it comes round to the front. While turning, the surface is read evenly by longitude
        // instead, easing back to the exact drawing as the turn comes round to rest.
        float even = (1 - (float) Math.cos(turn)) / 2;
        for (int i = 0; i < out.length; i++) {
            float l = lon[i];
            if (l != l) {
                continue;
            }
            float theta = l - turn;
            float u = (float) Math.sin(theta);
            if (even > 0) {
                u += (wave(theta) - u) * even;
            }
            // Where on this slice the surface now in front of this pixel was drawn.
            float b = mid[i] + u * half[i];
            float a = along[i];
            float x = cx + a * ax - b * ay - 0.5f;
            float y = cy + a * ay + b * ax - 0.5f;
            int c = sample(x, y);
            out[i] = lit(c, shade[i], c >>> 24);
        }
    }

    /**
     * Rolls the ball by {@code turn} radians in the picture plane, the way a ball rolling or
     * tumbling through the air is seen from the side: the surface turns, the light stays.
     */
    private void roll(float turn, int[] out) {
        float step = (float) (((turn / (Math.PI * 2)) % 1 + 1) % 1) * FIT_STEPS;
        int k = (int) step;
        float scale = fit[k % FIT_STEPS]
                + (fit[(k + 1) % FIT_STEPS] - fit[k % FIT_STEPS]) * (step - k);
        float cos = (float) Math.cos(turn) / scale;
        float sin = (float) Math.sin(turn) / scale;
        int x0 = Math.max(0, (int) (cx - radius) - 1);
        int x1 = Math.min(size - 1, (int) (cx + radius) + 1);
        int y0 = Math.max(0, (int) (cy - radius) - 1);
        int y1 = Math.min(size - 1, (int) (cy + radius) + 1);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                float dx = x + 0.5f - cx;
                float dy = y + 0.5f - cy;
                if (dx * dx + dy * dy > radius * radius) {
                    continue;
                }
                int i = y * size + x;
                int c = sample(cx + dx * cos + dy * sin - 0.5f, cy - dx * sin + dy * cos - 0.5f);
                out[i] = lit(c, shade[i], c >>> 24);
            }
        }
    }

    /** A triangle wave the shape of sin: -1 to 1 and back, evenly, every 2 pi. */
    private static float wave(float theta) {
        float p = (float) (((theta / (2 * Math.PI) + 0.25) % 1 + 1) % 1);
        return p < 0.5f ? 4 * p - 1 : 3 - 4 * p;
    }

    /** The surface at (x, y), blended from the four pixels round it by their coverage. */
    private int sample(float x, float y) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        float fx = x - x0;
        float fy = y - y0;
        float a = 0;
        float r = 0;
        float g = 0;
        float b = 0;
        for (int k = 0; k < 4; k++) {
            int px = Math.max(0, Math.min(size - 1, x0 + (k & 1)));
            int py = Math.max(0, Math.min(size - 1, y0 + (k >> 1)));
            float w = ((k & 1) == 0 ? 1 - fx : fx) * ((k >> 1) == 0 ? 1 - fy : fy);
            int c = albedo[py * size + px];
            float wa = w * (c >>> 24);
            a += wa;
            r += wa * ((c >> 16) & 0xFF);
            g += wa * ((c >> 8) & 0xFF);
            b += wa * (c & 0xFF);
        }
        if (a < 0.5f) {
            return 0;
        }
        return Math.round(a) << 24 | Math.round(r / a) << 16 | Math.round(g / a) << 8
                | Math.round(b / a);
    }

    /** Splits the drawing into surface and light (see the class comment). */
    private void light() {
        int n = size * size;
        // The palette: colours covering a fair share of the ball.
        java.util.HashMap<Integer, int[]> counts = new java.util.HashMap<>();
        int opaque = 0;
        for (int i = 0; i < n; i++) {
            if (lon[i] != lon[i] || (rest[i] >>> 24) < 250) {
                continue;
            }
            opaque++;
            int key = quantize(rest[i]);
            int[] c = counts.get(key);
            if (c == null) {
                counts.put(key, c = new int[4]);
            }
            c[0]++;
            c[1] += (rest[i] >> 16) & 0xFF;
            c[2] += (rest[i] >> 8) & 0xFF;
            c[3] += rest[i] & 0xFF;
        }
        java.util.ArrayList<float[]> palette = new java.util.ArrayList<>();
        for (int[] c : counts.values()) {
            if (c[0] >= opaque * 0.02f) {
                palette.add(new float[] {c[1] / (float) c[0], c[2] / (float) c[0],
                        c[3] / (float) c[0], c[0], 1, 0});
            }
        }
        // A colour that is another one darkened evenly is that colour in shade: [4] = the light
        // on it, [5] = 1 for a lit colour that has such a shaded copy.
        for (float[] dark : palette) {
            float best = 0;
            for (float[] lit : palette) {
                if (lit == dark) {
                    continue;
                }
                float k0 = ratio(dark[0], lit[0]);
                float k1 = ratio(dark[1], lit[1]);
                float k2 = ratio(dark[2], lit[2]);
                float k = (k0 + k1 + k2) / 3;
                float spread = Math.max(k0, Math.max(k1, k2)) - Math.min(k0, Math.min(k1, k2));
                if (k > 0.5f && k < 0.93f && spread < 0.1f && lit[3] > best) {
                    best = lit[3];
                    dark[4] = k;
                    lit[5] = 1;
                }
            }
        }
        // Light where it is known (a lit or shaded colour), filled in from the neighbours
        // elsewhere (outlines, colours that never meet the shade), then softened.
        float[] known = new float[n];
        java.util.Arrays.fill(known, Float.NaN);
        for (int i = 0; i < n; i++) {
            if ((rest[i] >>> 24) < 250) {
                continue;
            }
            float[] p = nearest(palette, rest[i]);
            if (p != null && (p[4] < 1 || p[5] == 1)) {
                known[i] = p[4];
            }
        }
        float[] fill = known.clone();
        for (int pass = 0; pass < size; pass++) {
            boolean open = false;
            float[] next = fill.clone();
            for (int i = 0; i < n; i++) {
                if (fill[i] == fill[i]) {
                    continue;
                }
                float sum = 0;
                int count = 0;
                int x = i % size;
                if (x > 0 && fill[i - 1] == fill[i - 1]) { sum += fill[i - 1]; count++; }
                if (x < size - 1 && fill[i + 1] == fill[i + 1]) { sum += fill[i + 1]; count++; }
                if (i >= size && fill[i - size] == fill[i - size]) { sum += fill[i - size]; count++; }
                if (i < n - size && fill[i + size] == fill[i + size]) { sum += fill[i + size]; count++; }
                if (count > 0) {
                    next[i] = sum / count;
                } else {
                    open = true;
                }
            }
            fill = next;
            if (!open) {
                break;
            }
        }
        for (int i = 0; i < n; i++) {
            shade[i] = fill[i] == fill[i] ? fill[i] : 1;
        }
        for (int pass = 0; pass < 2; pass++) {
            float[] soft = shade.clone();
            for (int y = 1; y < size - 1; y++) {
                for (int x = 1; x < size - 1; x++) {
                    int i = y * size + x;
                    soft[i] = (shade[i] * 4 + shade[i - 1] + shade[i + 1] + shade[i - size]
                            + shade[i + size]) / 8;
                }
            }
            System.arraycopy(soft, 0, shade, 0, n);
        }
        for (int i = 0; i < n; i++) {
            albedo[i] = lit(rest[i], 1 / shade[i], rest[i] >>> 24);
        }
    }

    private static float ratio(float dark, float lit) {
        return lit < 24 ? (dark < 24 ? 0.75f : 9) : dark / lit;
    }

    private static int quantize(int c) {
        return ((c >> 19) & 0x1F) << 10 | ((c >> 11) & 0x1F) << 5 | ((c >> 3) & 0x1F);
    }

    /** The palette colour within reach of {@code c}, or null (anti-aliased edges). */
    private static float[] nearest(java.util.ArrayList<float[]> palette, int c) {
        float r = (c >> 16) & 0xFF;
        float g = (c >> 8) & 0xFF;
        float b = c & 0xFF;
        float[] best = null;
        float bestD = 24 * 24 * 3;
        for (float[] p : palette) {
            float d = (p[0] - r) * (p[0] - r) + (p[1] - g) * (p[1] - g) + (p[2] - b) * (p[2] - b);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private static int lit(int c, float k, int alpha) {
        int r = Math.min(255, Math.round(((c >> 16) & 0xFF) * k));
        int g = Math.min(255, Math.round(((c >> 8) & 0xFF) * k));
        int b = Math.min(255, Math.round((c & 0xFF) * k));
        return alpha << 24 | r << 16 | g << 8 | b;
    }
}
