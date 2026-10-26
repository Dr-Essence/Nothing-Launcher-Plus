package com.dressence.nothinglauncherplus.anim;

/**
 * The whole Earth, put together from the three keyboard globes. Each globe shows one side of
 * it, seen from 20° north (🌍 at 20° east, 🌎 at 80° west, 🌏 at 110° east), so between them
 * they show every longitude. Each is unprojected onto one map of longitude and latitude,
 * nearer the middle of a view counting for more, so a globe can turn all the way round and
 * show what is really on the far side instead of its own front again.
 *
 * <p>The drawings are lit: a gloss near the top, darker toward the rim. The shading is measured
 * on their open ocean as one smooth surface over the disc, taken out of the surface and put
 * back by where a point is on the disc as it turns, and the gloss is the drawing's own, so the
 * light stays where it is while the Earth turns under it. Open water is one even blue, so no
 * seam shows where one view meets the next.
 */
final class Earth {

    /** The keyboard globes and the longitude each one is centred on (degrees east). */
    static final String[] GLOBES = {"🌍", "🌎", "🌏"};
    static final float[] CENTRES = {20, -80, 110};
    /** The latitude all three are seen from. */
    private static final double VIEW_LAT = Math.toRadians(20);

    private static final int W = 512;
    private static final int H = 256;
    private static final int FEATURES = 8;

    /** The map, unshaded: longitude -180..180 across, latitude 90..-90 down. */
    private final int[] map = new int[W * H];
    /** The open ocean's own colour, unshaded. */
    private int ocean;
    /** The shade over the disc: a smooth fit (see {@link #features}) to the open ocean. */
    private final double[] fit = new double[FEATURES];

    /** {@code views}: the three globes ({@link #GLOBES}), each {@code size} square. */
    Earth(int[][] views, int size) {
        float[][] disc = new float[views.length][];
        for (int k = 0; k < views.length; k++) {
            disc[k] = disc(views[k], size);
        }
        measureShade(views, disc, size);
        // Per texel: the weight of views seeing water, and of views seeing land with its
        // colour (unshaded), so a coast's blend of the two never makes a pale ghost of either.
        float[] water = new float[W * H];
        float[] land = new float[W * H * 4];
        double sin0 = Math.sin(VIEW_LAT);
        double cos0 = Math.cos(VIEW_LAT);
        for (int v = 0; v < H; v++) {
            double lat = Math.toRadians(90 - (v + 0.5) * 180.0 / H);
            for (int u = 0; u < W; u++) {
                double lon = Math.toRadians(-180 + (u + 0.5) * 360.0 / W);
                int i = v * W + u;
                for (int k = 0; k < views.length; k++) {
                    double d = lon - Math.toRadians(CENTRES[k]);
                    double x = Math.cos(lat) * Math.sin(d);
                    double y = cos0 * Math.sin(lat) - sin0 * Math.cos(lat) * Math.cos(d);
                    double z = sin0 * Math.sin(lat) + cos0 * Math.cos(lat) * Math.cos(d);
                    // Near the rim a view is squeezed: it counts for little there.
                    if (z < 0.12) {
                        continue;
                    }
                    float[] c = disc[k];
                    float r = c[2] * 0.96f;
                    int px = sample(views[k], size, c[0] + (float) (x * r),
                            c[1] - (float) (y * r));
                    if ((px >>> 24) < 250 || gloss(px)) {
                        continue; // Under the gloss the surface hardly shows.
                    }
                    float w = (float) Math.pow(z, 4);
                    int flat = unshade(px, shadeAt((float) x, (float) y));
                    if (isOcean(flat)) {
                        water[i] += w;
                    } else {
                        land[i * 4] += w;
                        land[i * 4 + 1] += w * ((flat >> 16) & 0xFF);
                        land[i * 4 + 2] += w * ((flat >> 8) & 0xFF);
                        land[i * 4 + 3] += w * (flat & 0xFF);
                    }
                }
            }
        }
        for (int i = 0; i < W * H; i++) {
            float w = land[i * 4];
            // Open water is one even blue; where no view sees at all it is open ocean (the
            // middle of the Pacific); land must clearly outweigh water, so a few stray
            // samples are not an island.
            map[i] = w <= water[i] * 1.5f ? ocean : 0xFF000000
                    | Math.round(land[i * 4 + 1] / w) << 16
                    | Math.round(land[i * 4 + 2] / w) << 8 | Math.round(land[i * 4 + 3] / w);
        }
    }

    /**
     * Draws the Earth into {@code out} where the globe {@code rest} ({@code size} square) is,
     * turned so that longitude {@code centre} (degrees east) faces the viewer; the outline, its
     * soft edge and the gloss are the drawing's own.
     */
    void render(int[] rest, int size, float centre, int[] out) {
        float[] c = disc(rest, size);
        double sin0 = Math.sin(VIEW_LAT);
        double cos0 = Math.cos(VIEW_LAT);
        double lon0 = Math.toRadians(centre);
        for (int i = 0; i < out.length; i++) {
            int a = rest[i] >>> 24;
            if (a == 0) {
                continue;
            }
            // The gloss lies where the drawing has it: it does not turn.
            if (gloss(rest[i])) {
                out[i] = rest[i];
                continue;
            }
            float nx = (i % size + 0.5f - c[0]) / c[2];
            float ny = -(i / size + 0.5f - c[1]) / c[2];
            float rr = nx * nx + ny * ny;
            if (rr > 1) {
                // The outermost edge: pulled in onto the rim.
                float k = (float) (1 / Math.sqrt(rr)) * 0.999f;
                nx *= k;
                ny *= k;
                rr = nx * nx + ny * ny;
            }
            double nz = Math.sqrt(Math.max(0, 1 - rr));
            double lat = Math.asin(Math.max(-1, Math.min(1, ny * cos0 + nz * sin0)));
            double lon = lon0 + Math.atan2(nx, nz * cos0 - ny * sin0);
            out[i] = shade(mapAt(lon, lat), shadeAt(nx, ny), a);
        }
    }

    private static void features(double x, double y, double[] f) {
        double rr = x * x + y * y;
        f[0] = 1;
        f[1] = x;
        f[2] = y;
        f[3] = x * x;
        f[4] = y * y;
        f[5] = x * y;
        f[6] = rr * rr;
        f[7] = rr * rr * rr;
    }

    /**
     * The drawing's shading, from its open ocean: the ocean's own colour is taken from the
     * middle of the discs; then how much darker it is everywhere else (green, which darkens
     * most toward the rim, tells it), fitted as one smooth surface over the disc for all three
     * views, so no coast or lone patch of one view shows in it.
     */
    private void measureShade(int[][] views, float[][] disc, int size) {
        long sr = 0, sg = 0, sb = 0;
        int n = 0;
        for (int k = 0; k < views.length; k++) {
            float[] c = disc[k];
            for (int i = 0; i < views[k].length; i++) {
                int p = views[k][i];
                float nx = (i % size + 0.5f - c[0]) / c[2];
                float ny = (i / size + 0.5f - c[1]) / c[2];
                if ((p >>> 24) < 250 || !isOcean(p) || nx * nx + ny * ny > 0.3f) {
                    continue;
                }
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
                n++;
            }
        }
        n = Math.max(1, n);
        ocean = 0xFF000000 | (int) (sr / n) << 16 | (int) (sg / n) << 8 | (int) (sb / n);
        double og = Math.max(1, sg / (double) n);
        double[][] m = new double[FEATURES][FEATURES + 1];
        double[] f = new double[FEATURES];
        for (int k = 0; k < views.length; k++) {
            float[] c = disc[k];
            for (int i = 0; i < views[k].length; i++) {
                int p = views[k][i];
                double nx = (i % size + 0.5f - c[0]) / c[2];
                double ny = -(i / size + 0.5f - c[1]) / c[2];
                if ((p >>> 24) < 250 || nx * nx + ny * ny >= 0.97 || gloss(p)
                        || !isOcean(p) || !openWater(views[k], size, i)) {
                    continue;
                }
                double shade = ((p >> 8) & 0xFF) / og;
                features(nx, ny, f);
                for (int r = 0; r < FEATURES; r++) {
                    for (int q = 0; q < FEATURES; q++) {
                        m[r][q] += f[r] * f[q];
                    }
                    m[r][FEATURES] += f[r] * shade;
                }
            }
        }
        for (int r = 0; r < FEATURES; r++) {
            m[r][r] += 1e-6; // Kept solvable however few samples there are.
        }
        for (int col = 0; col < FEATURES; col++) {
            int best = col;
            for (int r = col + 1; r < FEATURES; r++) {
                if (Math.abs(m[r][col]) > Math.abs(m[best][col])) {
                    best = r;
                }
            }
            double[] t = m[col];
            m[col] = m[best];
            m[best] = t;
            for (int r = 0; r < FEATURES; r++) {
                if (r != col && m[col][col] != 0) {
                    double q = m[r][col] / m[col][col];
                    for (int j = col; j <= FEATURES; j++) {
                        m[r][j] -= q * m[col][j];
                    }
                }
            }
        }
        for (int r = 0; r < FEATURES; r++) {
            fit[r] = m[r][r] == 0 ? 0 : m[r][FEATURES] / m[r][r];
        }
    }

    /** The shade at (x, y) on the disc (y up). */
    private float shadeAt(float x, float y) {
        double[] f = new double[FEATURES];
        double rr = x * x + y * y;
        double px = x;
        double py = y;
        if (rr > 1) {
            px /= Math.sqrt(rr);
            py /= Math.sqrt(rr);
        }
        features(px, py, f);
        double v = 0;
        for (int r = 0; r < FEATURES; r++) {
            v += fit[r] * f[r];
        }
        return (float) Math.max(0.2, Math.min(1.3, v));
    }

    /** The near-white gloss the drawing lays over the top of the globe. */
    private static boolean gloss(int c) {
        return ((c >> 16) & 0xFF) > 150 && ((c >> 8) & 0xFF) > 200 && (c & 0xFF) > 230;
    }

    private static boolean isOcean(int c) {
        int r = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        return b > r + 50 && b >= g;
    }

    /** Water all round pixel {@code i}, two pixels out: away from coasts and the gloss. */
    private static boolean openWater(int[] px, int size, int i) {
        int x = i % size;
        int y = i / size;
        for (int dy = -2; dy <= 2; dy += 2) {
            for (int dx = -2; dx <= 2; dx += 2) {
                int xx = x + dx;
                int yy = y + dy;
                if (xx < 0 || yy < 0 || xx >= size || yy >= size) {
                    return false;
                }
                int c = px[yy * size + xx];
                if ((c >>> 24) < 250 || !isOcean(c) || gloss(c)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** {@code c} with the shade {@code s} taken off it. */
    private static int unshade(int c, float s) {
        int out = 0xFF000000;
        for (int sh = 16; sh >= 0; sh -= 8) {
            out |= Math.max(0, Math.min(255, Math.round(((c >> sh) & 0xFF) / s))) << sh;
        }
        return out;
    }

    /** {@code c} in the shade {@code s}, with {@code alpha}. */
    private static int shade(int c, float s, int alpha) {
        int out = alpha << 24;
        for (int sh = 16; sh >= 0; sh -= 8) {
            out |= Math.max(0, Math.min(255, Math.round(((c >> sh) & 0xFF) * s))) << sh;
        }
        return out;
    }

    /** The map at a longitude and latitude (radians), blended from the four texels round it. */
    private int mapAt(double lon, double lat) {
        double u = ((Math.toDegrees(lon) + 180) % 360 + 360) % 360 * W / 360.0 - 0.5;
        double v = (90 - Math.toDegrees(lat)) * H / 180.0 - 0.5;
        int u0 = (int) Math.floor(u);
        int v0 = (int) Math.floor(v);
        float fu = (float) (u - u0);
        float fv = (float) (v - v0);
        float r = 0;
        float g = 0;
        float b = 0;
        for (int k = 0; k < 4; k++) {
            int uu = ((u0 + (k & 1)) % W + W) % W;
            int vv = Math.max(0, Math.min(H - 1, v0 + (k >> 1)));
            float w = ((k & 1) == 0 ? 1 - fu : fu) * ((k >> 1) == 0 ? 1 - fv : fv);
            int c = map[vv * W + uu];
            r += w * ((c >> 16) & 0xFF);
            g += w * ((c >> 8) & 0xFF);
            b += w * (c & 0xFF);
        }
        return Math.round(r) << 16 | Math.round(g) << 8 | Math.round(b);
    }

    /** The globe's disc in a drawing: centre x, y and radius. */
    private static float[] disc(int[] px, int size) {
        int x0 = size, y0 = size, x1 = -1, y1 = -1;
        for (int i = 0; i < px.length; i++) {
            if ((px[i] >>> 24) > 128) {
                x0 = Math.min(x0, i % size);
                x1 = Math.max(x1, i % size);
                y0 = Math.min(y0, i / size);
                y1 = Math.max(y1, i / size);
            }
        }
        return new float[] {(x0 + x1 + 1) / 2f, (y0 + y1 + 1) / 2f,
                Math.max(1, Math.max(x1 - x0 + 1, y1 - y0 + 1) / 2f)};
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
            int xx = Math.max(0, Math.min(size - 1, x0 + (k & 1)));
            int yy = Math.max(0, Math.min(size - 1, y0 + (k >> 1)));
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
