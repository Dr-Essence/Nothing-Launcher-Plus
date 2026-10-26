package com.dressence.nothinglauncherplus.anim;

/**
 * A drawn burst of light made to go off: the bright burst (a firework's trails, a sparkler's
 * rays, a fuse's spark) is lifted off what it is drawn on and shown as it happens.
 *
 * <p>A firework ({@link #BLOOM}) blooms out from its centre, its trails growing to full
 * length, then droops and fades as the sparks fall and burn out. A sparkler or a fuse
 * ({@link #CRACKLE}) crackles: its rays flash on and off in quick, random bursts, now longer,
 * now shorter, never all at once.
 */
final class Burst {

    static final int BLOOM = 0;
    static final int CRACKLE = 1;

    private final int size;
    private final int[] under;
    private final int[] burst;
    private final boolean[] in;
    private final float cx;
    private final float cy;
    private final float radius;

    /**
     * {@code pixels}: the emoji at rest, {@code size} square; the burst's centre and reach
     * (pixels), and {@code kind}: 1 for a warm spark only (a fuse's, on a red stick), 2 for
     * a sparkler (not its stick).
     */
    Burst(int[] pixels, int size, float cx, float cy, float radius, int kind) {
        boolean warmOnly = kind == 1;
        // A sparkler's wooden stick is a pale orange: its sparks are yellower than that.
        float minHue = kind == 2 ? 38 : 15;
        this.size = size;
        this.cx = cx;
        this.cy = cy;
        this.radius = radius;
        int n = size * size;
        in = new boolean[n];
        float[] hsv = new float[3];
        for (int i = 0; i < n; i++) {
            int c = pixels[i];
            if ((c >>> 24) < 24) {
                continue;
            }
            float dx = i % size + 0.5f - cx;
            float dy = i / size + 0.5f - cy;
            if (dx * dx + dy * dy > radius * radius) {
                continue;
            }
            Detach.rgbToHsv(c, hsv);
            in[i] = warmOnly ? hsv[0] > 25 && hsv[0] < 65 && hsv[2] > 0.6f
                    : hsv[2] > 0.62f && (hsv[1] < 0.3f || hsv[0] >= minHue && hsv[0] <= 70
                            || hsv[0] >= 270);
        }
        // The edge round each trail goes with it.
        boolean[] grown = in.clone();
        for (int i = 0; i < n; i++) {
            int x = i % size;
            if (!in[i] && (pixels[i] >>> 24) > 0 && (x > 0 && in[i - 1] || x < size - 1
                    && in[i + 1] || i >= size && in[i - size] || i < n - size && in[i + size])) {
                grown[i] = true;
            }
        }
        System.arraycopy(grown, 0, in, 0, n);
        under = Detach.fill(pixels, size, in);
        burst = new int[n];
        for (int i = 0; i < n; i++) {
            if (in[i]) {
                burst[i] = pixels[i];
            }
        }
    }

    /**
     * Writes the burst at {@code life} (0..1 through one go: a firework's bloom and fall) or
     * time {@code t} (a sparkler's crackle) into {@code out}.
     */
    void render(int mode, float life, float t, int[] out) {
        System.arraycopy(under, 0, out, 0, out.length);
        if (mode == BLOOM) {
            bloom(life, out);
        } else {
            crackle(t, out);
        }
    }

    private void bloom(float life, int[] out) {
        if (life <= 0 || life >= 1) {
            return;
        }
        float grow = 1 - (float) Math.pow(1 - Math.min(1, life / 0.32f), 3);
        float fade = life < 0.55f ? 1 : 1 - (life - 0.55f) / 0.45f;
        // The sparks fall as they burn out: farther out, falling more.
        float droop = radius * 0.16f * life * life;
        float scale = 0.08f + 0.92f * grow;
        for (int i = 0; i < out.length; i++) {
            float dx = i % size + 0.5f - cx;
            float dy = i / size + 0.5f - cy;
            float d = (float) Math.sqrt(dx * dx + dy * dy) / radius;
            float sy = dy - droop * d * d;
            int c = sample(burst, cx + dx / scale, cy + sy / scale);
            if ((c >>> 24) == 0) {
                continue;
            }
            // The newest part, at the tips, burns brightest; the trail behind dims first.
            float a = fade * Math.min(1, 0.35f + 1.3f * d / Math.max(0.05f, grow));
            out[i] = over(Scene.alpha(c, Math.round((c >>> 24) * Math.min(1, a))), out[i]);
        }
    }

    private void crackle(float t, int[] out) {
        int frame = (int) Math.floor(t * 15);
        for (int i = 0; i < out.length; i++) {
            int c = burst[i];
            if ((c >>> 24) == 0) {
                continue;
            }
            float dx = i % size + 0.5f - cx;
            float dy = i / size + 0.5f - cy;
            float d = (float) Math.sqrt(dx * dx + dy * dy) / radius;
            int sector = (int) Math.floor((Math.atan2(dy, dx) / (2 * Math.PI) + 0.5) * 20);
            // Each ray flashes on for a frame or two, reaching a random length.
            float on = Scene.hash(sector, frame, 17);
            float reach = 0.3f + 0.75f * Scene.hash(frame, sector, 31);
            float a;
            if (d < 0.22f) {
                a = 1; // The burning core always glows.
            } else if (on < 0.55f && d < reach) {
                a = Math.min(1, (reach - d) / 0.12f + 0.3f);
            } else {
                a = 0;
            }
            if (a > 0) {
                out[i] = over(Scene.alpha(c, Math.round((c >>> 24) * a)), out[i]);
            }
        }
    }

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
