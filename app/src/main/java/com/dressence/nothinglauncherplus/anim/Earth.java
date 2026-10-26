package com.dressence.nothinglauncherplus.anim;

/**
 * The whole Earth, put together from the three keyboard globes. Each globe shows one side of
 * it, seen from 20° north (🌍 at 20° east, 🌎 at 80° west, 🌏 at 110° east), so between them
 * they show every longitude. Each is unprojected onto one map of longitude and latitude,
 * nearer the middle of a view counting for more, so a globe can turn all the way round and
 * show what is really on the far side instead of its own front again.
 *
 * <p>The drawings are shaded darker toward their rim. That shading is measured on their ocean,
 * taken out of the map and put back by where a point is on the disc as it turns, so the light
 * stays where it is and no seam shows where one view meets the next.
 */
final class Earth {

    /** The keyboard globes and the longitude each one is centred on (degrees east). */
    static final String[] GLOBES = {"🌍", "🌎", "🌏"};
    static final float[] CENTRES = {20, -80, 110};
    /** The latitude all three are seen from. */
    private static final double VIEW_LAT = Math.toRadians(20);

    private static final int W = 512;
    private static final int H = 256;
    private static final int BINS = 24;

    /** The map, unshaded: longitude -180..180 across, latitude 90..-90 down. */
    private final int[] map = new int[W * H];
    /** How much light falls where the surface faces the viewer by z (0 at the rim, 1 middle). */
    private final float[] light = new float[BINS];

    /** {@code views}: the three globes ({@link #GLOBES}), each {@code size} square. */
    Earth(int[][] views, int size) {
        float[][] disc = new float[views.length][];
        for (int k = 0; k < views.length; k++) {
            disc[k] = disc(views[k], size);
        }
        measureLight(views, disc, size);
        float[] acc = new float[W * H * 4];
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
                    // Near the rim a view is squeezed and darkened: use only what it sees well.
                    if (z < 0.3) {
                        continue;
                    }
                    float[] c = disc[k];
                    // Just inside the rim, clear of its anti-aliased edge.
                    float r = c[2] * 0.97f;
                    int px = sample(views[k], size, c[0] + (float) (x * r), c[1] - (float) (y * r));
                    if ((px >>> 24) < 250) {
                        continue;
                    }
                    float shade = lightAt((float) z);
                    float w = (float) (z * z * z);
                    acc[i * 4] += w;
                    acc[i * 4 + 1] += w * Math.min(255, ((px >> 16) & 0xFF) / shade);
                    acc[i * 4 + 2] += w * Math.min(255, ((px >> 8) & 0xFF) / shade);
                    acc[i * 4 + 3] += w * Math.min(255, (px & 0xFF) / shade);
                }
            }
        }
        for (int i = 0; i < W * H; i++) {
            float w = acc[i * 4];
            map[i] = w <= 0 ? 0 : 0xFF000000 | Math.round(acc[i * 4 + 1] / w) << 16
                    | Math.round(acc[i * 4 + 2] / w) << 8 | Math.round(acc[i * 4 + 3] / w);
        }
        // Where no view sees well (mid-Pacific), blend across from either side.
        for (int v = 0; v < H; v++) {
            int start = -1;
            for (int u = 0; u < W && start < 0; u++) {
                if (map[v * W + u] != 0) {
                    start = u;
                }
            }
            if (start < 0) {
                continue;
            }
            int[] row = new int[W];
            System.arraycopy(map, v * W, row, 0, W);
            for (int k = 1; k <= W; k++) {
                int u = (start + k) % W;
                if (row[u] != 0) {
                    continue;
                }
                int left = (u - 1 + W) % W;
                while (row[left] == 0) {
                    left = (left - 1 + W) % W;
                }
                int right = (u + 1) % W;
                while (map[v * W + right] == 0) {
                    right = (right + 1) % W;
                }
                int gap = ((right - left) % W + W) % W;
                float f = (((u - left) % W + W) % W) / (float) gap;
                row[u] = 0xFF000000 | Scene.mix(row[left], map[v * W + right], f) & 0xFFFFFF;
            }
            System.arraycopy(row, 0, map, v * W, W);
        }
        // Nothing sees round the south pole: it takes the colour of the nearest row above.
        for (int v = 1; v < H; v++) {
            for (int u = 0; u < W; u++) {
                if (map[v * W + u] == 0) {
                    map[v * W + u] = map[(v - 1) * W + u];
                }
            }
        }
        for (int v = H - 2; v >= 0; v--) {
            for (int u = 0; u < W; u++) {
                if (map[v * W + u] == 0) {
                    map[v * W + u] = map[(v + 1) * W + u];
                }
            }
        }
    }

    /**
     * Draws the Earth into {@code out} where the globe {@code rest} ({@code size} square) is,
     * turned so that longitude {@code centre} (degrees east) faces the viewer; the outline and
     * its soft edge are the drawing's own.
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
            int px = mapAt(lon, lat);
            float shade = lightAt((float) nz);
            out[i] = a << 24 | Math.min(255, Math.round(((px >> 16) & 0xFF) * shade)) << 16
                    | Math.min(255, Math.round(((px >> 8) & 0xFF) * shade)) << 8
                    | Math.min(255, Math.round((px & 0xFF) * shade));
        }
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

    /** The shading toward the rim, from how bright the open ocean is at each distance. */
    private void measureLight(int[][] views, float[][] disc, int size) {
        float[] sum = new float[BINS];
        int[] count = new int[BINS];
        for (int k = 0; k < views.length; k++) {
            float[] c = disc[k];
            for (int i = 0; i < views[k].length; i++) {
                int p = views[k][i];
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                if ((p >>> 24) < 250 || b < r + 60 || b < g) {
                    continue;
                }
                float nx = (i % size + 0.5f - c[0]) / c[2];
                float ny = (i / size + 0.5f - c[1]) / c[2];
                float rr = nx * nx + ny * ny;
                if (rr >= 1) {
                    continue;
                }
                int bin = Math.min(BINS - 1, (int) (Math.sqrt(1 - rr) * BINS));
                sum[bin] += 0.3f * r + 0.59f * g + 0.11f * b;
                count[bin]++;
            }
        }
        float top = 0;
        for (int k = 0; k < BINS; k++) {
            light[k] = count[k] > 0 ? sum[k] / count[k] : Float.NaN;
            if (light[k] == light[k]) {
                top = Math.max(top, light[k]);
            }
        }
        for (int k = 0; k < BINS; k++) {
            light[k] = light[k] == light[k] ? light[k] / top : Float.NaN;
        }
        // Bins with no ocean take their neighbour's light; it never brightens toward the rim.
        for (int k = BINS - 2; k >= 0; k--) {
            if (light[k] != light[k]) {
                light[k] = light[k + 1];
            }
        }
        for (int k = 1; k < BINS; k++) {
            if (light[k] != light[k]) {
                light[k] = light[k - 1];
            }
            light[k] = Math.max(light[k], light[k - 1]);
        }
        for (int k = 0; k < BINS; k++) {
            if (light[k] != light[k]) {
                light[k] = 1;
            }
        }
    }

    private float lightAt(float z) {
        float f = Math.max(0, Math.min(BINS - 1, z * BINS - 0.5f));
        int k = (int) f;
        int k1 = Math.min(BINS - 1, k + 1);
        return Math.max(0.2f, light[k] + (light[k1] - light[k]) * (f - k));
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
