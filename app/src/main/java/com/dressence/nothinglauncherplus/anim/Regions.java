package com.dressence.nothinglauncherplus.anim;

/**
 * What an emoji is made of, found once from its resting dot frame: the parts effects act on.
 * A TV's screen, a crystal ball's glass, a face and its eyes, its top and bottom edges, its own
 * palette. Pure Java on a {@code size x size} ARGB grid, so it runs anywhere.
 */
final class Regions {

    final int size;
    final int[] rest;
    final boolean[] ink;
    final float[] luma;
    final float[] hue;
    final float[] sat;

    int minX;
    int minY;
    int maxX;
    int maxY;

    /** A dark or grey enclosed area: the screen of a TV, phone, computer or camera. */
    final boolean[] screen;
    int screenCount;
    int screenMinX;
    int screenMinY;
    int screenMaxX;
    int screenMaxY;

    /** The largest saturated round-ish area: a ball, globe, bubble or gem. */
    final boolean[] ball;
    float ballX;
    float ballY;
    float ballRadius;
    int ballColor;

    /** The emoji's own water: tears, sweat, drops (the blue, fairly light parts). */
    final boolean[] water;
    int waterCount;
    /** Which body of water each dot belongs to (-1: none), and each body's size and lowest dot. */
    final int[] waterBody;
    int[] bodySize = new int[0];
    int[] bodyLowX = new int[0];
    int[] bodyLowY = new int[0];
    /** Height of each body in dots: tall bodies are streams, short ones are drops. */
    int[] bodyHeight = new int[0];
    /** How far each drop can run down before it would leave the emoji (it stops on the skin). */
    int[] bodyRoom = new int[0];
    /**
     * The blended rim around each body (water mixed with skin: greenish on yellow, bluish on
     * white) and the body it belongs to. Uncovered with the body, but never drawn moving.
     */
    final boolean[] halo;
    final int[] haloBody;
    /**
     * What lies behind each water dot: the nearest non-water ink (skin under a tear) or nothing
     * (a drop hanging off the edge). A moving drop leaves this behind.
     */
    final int[] behindWater;
    /** The dry disc of a round emoji with water on it (radius 0 when there is none). */
    float discX;
    float discY;
    float discRadius;

    /** Small dark blobs in the upper part: eyes (of a face, an animal, a robot). */
    final boolean[] eyes;
    int eyeCount;
    /** Per-effect analyses, computed once on first use. */
    final java.util.Map<String, Object> memo = new java.util.HashMap<>();
    /** Window pane number of each dot (-1 for none), and how many panes there are. */
    final int[] window;
    int windowCount;
    /** Eyes that can close: {x0, y0, x1, y1, lash colour, lid colour} of each solid one. */
    final java.util.List<int[]> openEyes = new java.util.ArrayList<>();
    /** The colour eyes close to (the face around them). */
    int lidColor;

    /** For each column, the first and last inked row (-1 when empty). */
    final int[] top;
    final int[] bottom;

    /** The brightest and most saturated colours in the emoji, for its own sparkles and glows. */
    int brightest;
    int accent;

    Regions(int[] rest, int size) {
        this.size = size;
        this.rest = rest.clone();
        int n = size * size;
        ink = new boolean[n];
        luma = new float[n];
        hue = new float[n];
        sat = new float[n];
        screen = new boolean[n];
        ball = new boolean[n];
        eyes = new boolean[n];
        window = new int[n];
        java.util.Arrays.fill(window, -1);
        water = new boolean[n];
        waterBody = new int[n];
        behindWater = new int[n];
        halo = new boolean[n];
        haloBody = new int[n];
        top = new int[size];
        bottom = new int[size];
        analyse();
    }

    boolean in(int x, int y) {
        return x >= 0 && y >= 0 && x < size && y < size;
    }

    private void analyse() {
        minX = minY = size;
        maxX = maxY = -1;
        float bestBright = -1;
        float bestSat = -1;
        for (int i = 0; i < size * size; i++) {
            int c = rest[i];
            if ((c >>> 24) < 60) {
                continue;
            }
            ink[i] = true;
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            float max = Math.max(r, Math.max(g, b)) / 255f;
            float min = Math.min(r, Math.min(g, b)) / 255f;
            luma[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
            sat[i] = max == 0 ? 0 : (max - min) / max;
            hue[i] = hueOf(r, g, b);
            int x = i % size;
            int y = i / size;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            if (luma[i] > bestBright) {
                bestBright = luma[i];
                brightest = c | 0xFF000000;
            }
            if (sat[i] * luma[i] > bestSat) {
                bestSat = sat[i] * luma[i];
                accent = c | 0xFF000000;
            }
        }
        for (int x = 0; x < size; x++) {
            top[x] = -1;
            bottom[x] = -1;
            for (int y = 0; y < size; y++) {
                if (ink[y * size + x]) {
                    if (top[x] < 0) {
                        top[x] = y;
                    }
                    bottom[x] = y;
                }
            }
        }
        if (maxX < 0) {
            return;
        }
        findScreen();
        findBall();
        findEyes();
        findWater();
        findWindows();
    }

    /**
     * A display: the largest enclosed, roughly rectangular area that is either dark or grey (a
     * phone, a monitor) or, failing that, any colour framed by the emoji (a TV's lit screen
     * inside its bezel).
     */
    private void findScreen() {
        boolean[] candidate = new boolean[size * size];
        for (int i = 0; i < candidate.length; i++) {
            candidate[i] = ink[i] && (luma[i] < 0.33f || (sat[i] < 0.22f && luma[i] < 0.6f));
        }
        int[] comp = largest(candidate, 10, true);
        if (comp == null || fill(comp) < 0.6f) {
            boolean[] lit = new boolean[size * size];
            for (int i = 0; i < lit.length; i++) {
                lit[i] = ink[i] && luma[i] >= 0.33f;
            }
            comp = largest(lit, 20, true);
        }
        if (comp == null) {
            return;
        }
        int x0 = size, y0 = size, x1 = -1, y1 = -1, count = 0;
        for (int i : comp) {
            int x = i % size;
            int y = i / size;
            x0 = Math.min(x0, x);
            x1 = Math.max(x1, x);
            y0 = Math.min(y0, y);
            y1 = Math.max(y1, y);
            count++;
        }
        float fill = count / (float) ((x1 - x0 + 1) * (y1 - y0 + 1));
        if (fill < 0.6f) {
            return; // Not a panel shape (an outline, a shadow).
        }
        for (int i : comp) {
            screen[i] = true;
        }
        screenCount = count;
        screenMinX = x0;
        screenMinY = y0;
        screenMaxX = x1;
        screenMaxY = y1;
    }

    /** Largest saturated component: what a magic swirl or a shine lives in. */
    private void findBall() {
        boolean[] candidate = new boolean[size * size];
        for (int i = 0; i < candidate.length; i++) {
            candidate[i] = ink[i] && sat[i] > 0.3f && luma[i] > 0.18f;
        }
        int[] comp = largest(candidate, 12, false);
        if (comp == null) {
            return;
        }
        comp = roundPart(comp);
        float sx = 0, sy = 0;
        long r = 0, g = 0, b = 0;
        for (int i : comp) {
            ball[i] = true;
            sx += i % size;
            sy += i / size;
            r += (rest[i] >> 16) & 0xFF;
            g += (rest[i] >> 8) & 0xFF;
            b += rest[i] & 0xFF;
        }
        ballX = sx / comp.length;
        ballY = sy / comp.length;
        ballRadius = (float) Math.sqrt(comp.length / Math.PI);
        ballColor = 0xFF000000 | (int) (r / comp.length) << 16 | (int) (g / comp.length) << 8
                | (int) (b / comp.length);
    }

    /**
     * The round part of a ball-like area, without what it stands on (a crystal ball's base, a
     * globe's stand): the disc as wide as its widest row near the top, hanging from its top.
     */
    private int[] roundPart(int[] comp) {
        int top = size, bottom = -1;
        for (int i : comp) {
            top = Math.min(top, i / size);
            bottom = Math.max(bottom, i / size);
        }
        int[] left = new int[size];
        int[] right = new int[size];
        java.util.Arrays.fill(left, size);
        java.util.Arrays.fill(right, -1);
        for (int i : comp) {
            int y = i / size;
            left[y] = Math.min(left[y], i % size);
            right[y] = Math.max(right[y], i % size);
        }
        int widest = -1;
        int limit = top + Math.round((bottom - top) * 0.7f);
        for (int y = top; y <= limit; y++) {
            if (right[y] >= 0 && (widest < 0 || right[y] - left[y] > right[widest] - left[widest])) {
                widest = y;
            }
        }
        if (widest < 0) {
            return comp;
        }
        float radius = (right[widest] - left[widest] + 1) / 2f;
        float cx = (left[widest] + right[widest]) / 2f;
        float cy = top - 0.5f + radius;
        int[] out = new int[comp.length];
        int n = 0;
        for (int i : comp) {
            if (Math.hypot(i % size - cx, i / size - cy) <= radius + 0.2f) {
                out[n++] = i;
            }
        }
        return n >= comp.length / 3 ? java.util.Arrays.copyOf(out, n) : comp;
    }

    /**
     * Eyes: small dark components surrounded by ink, in the upper part of the emoji. Their lid
     * colour is the average of the ink around them, so a blink paints them over naturally.
     */
    private void findEyes() {
        boolean[] dark = new boolean[size * size];
        for (int i = 0; i < dark.length; i++) {
            dark[i] = ink[i] && luma[i] < 0.3f;
        }
        boolean[] seen = new boolean[size * size];
        float limit = minY + (maxY - minY) * 0.62f;
        long r = 0, g = 0, b = 0;
        int around = 0;
        for (int i = 0; i < dark.length; i++) {
            if (!dark[i] || seen[i]) {
                continue;
            }
            int[] comp = flood(dark, seen, i);
            if (comp.length < 1 || comp.length > 36) {
                continue;
            }
            boolean enclosed = true;
            float cy = 0;
            for (int j : comp) {
                cy += j / size;
                int x = j % size;
                int y = j / size;
                for (int[] d : NEIGHBOURS) {
                    int nx = x + d[0];
                    int ny = y + d[1];
                    if (!in(nx, ny) || !ink[ny * size + nx]) {
                        enclosed = false;
                    }
                    // The lid colour comes from two dots out: the skin, not the eye's soft edge.
                    int fx = x + 2 * d[0];
                    int fy = y + 2 * d[1];
                    if (in(fx, fy) && ink[fy * size + fx] && !dark[fy * size + fx]
                            && luma[fy * size + fx] > 0.5f) {
                        int c = rest[fy * size + fx];
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        around++;
                    }
                }
            }
            if (!enclosed || cy / comp.length > limit) {
                continue;
            }
            int x0 = size, y0 = size, x1 = -1, y1 = -1;
            int darkest = 0;
            for (int j : comp) {
                if (darkest == 0 || luma[j] < luma(darkest)) {
                    darkest = rest[j] | 0xFF000000;
                }
                eyes[j] = true;
                x0 = Math.min(x0, j % size);
                x1 = Math.max(x1, j % size);
                y0 = Math.min(y0, j / size);
                y1 = Math.max(y1, j / size);
            }
            eyeCount++;
            int w = x1 - x0 + 1;
            int h = y1 - y0 + 1;
            // Only round, solid eyes can blink: open eyes are upright ovals, while brows and
            // closed "^ ^" eyes are wider than they are tall, thin or hollow.
            if (h >= 2 && w <= h + 1 && comp.length >= 0.6f * w * h) {
                openEyes.add(new int[] {x0, y0, x1, y1, darkest,
                        skinAround(x0, y0, x1, y1, dark)});
            }
        }
        if (around > 0) {
            lidColor = 0xFF000000 | (int) (r / around) << 16 | (int) (g / around) << 8
                    | (int) (b / around);
        }
    }

    /**
     * Water bodies: connected blue areas. Each gets its lowest point (where drops form) and
     * every water dot learns what is behind it.
     */
    private void findWater() {
        for (int i = 0; i < size * size; i++) {
            waterBody[i] = -1;
            // Emoji water is a clear cyan-blue (hue ~199); the bluish tint of a scared face
            // (hue ~225, half the saturation) is not water.
            if (ink[i] && hue[i] >= 185 && hue[i] <= 215 && sat[i] > 0.55f && luma[i] > 0.3f) {
                water[i] = true;
                waterCount++;
            }
        }
        if (waterCount == 0) {
            return;
        }
        // Take in each body's soft edge (paler blue dots touching it), so a drop that moves
        // away leaves no outline behind.
        boolean[] edge = new boolean[size * size];
        for (int i = 0; i < size * size; i++) {
            if (water[i] || !ink[i] || hue[i] < 175 || hue[i] > 240 || sat[i] < 0.15f) {
                continue;
            }
            int x = i % size;
            int y = i / size;
            for (int[] d : NEIGHBOURS) {
                if (in(x + d[0], y + d[1]) && water[(y + d[1]) * size + x + d[0]]) {
                    edge[i] = true;
                }
            }
        }
        for (int i = 0; i < size * size; i++) {
            if (edge[i]) {
                water[i] = true;
                waterCount++;
            }
        }
        boolean[] seen = new boolean[size * size];
        java.util.ArrayList<int[]> bodies = new java.util.ArrayList<>();
        for (int i = 0; i < size * size; i++) {
            if (water[i] && !seen[i]) {
                int[] comp = flood(water, seen, i);
                if (comp.length >= 2) {
                    bodies.add(comp);
                }
            }
        }
        bodySize = new int[bodies.size()];
        bodyLowX = new int[bodies.size()];
        bodyLowY = new int[bodies.size()];
        bodyHeight = new int[bodies.size()];
        bodyRoom = new int[bodies.size()];
        for (int k = 0; k < bodies.size(); k++) {
            int[] comp = bodies.get(k);
            int low = -1;
            int high = size;
            float lowX = 0;
            int lowCount = 0;
            for (int i : comp) {
                waterBody[i] = k;
                int y = i / size;
                high = Math.min(high, y);
                if (y > low) {
                    low = y;
                    lowX = 0;
                    lowCount = 0;
                }
                if (y == low) {
                    lowX += i % size;
                    lowCount++;
                }
            }
            bodySize[k] = comp.length;
            bodyLowX[k] = Math.round(lowX / lowCount);
            bodyLowY[k] = low;
            bodyHeight[k] = low - high + 1;
            // Ink continuing below the drop's lowest point, without gaps: the skin it runs on.
            int room = 0;
            int x = bodyLowX[k];
            for (int y = low + 1; y < size && ink[y * size + x] && !water[y * size + x]; y++) {
                room++;
            }
            bodyRoom[k] = room;
        }
        for (int i = 0; i < size * size; i++) {
            haloBody[i] = -1;
            if (water[i] || !ink[i] || hue[i] < 70 || hue[i] > 240 || sat[i] < 0.15f) {
                continue;
            }
            int x = i % size;
            int y = i / size;
            for (int dy = -1; dy <= 1 && haloBody[i] < 0; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (in(x + dx, y + dy) && waterBody[(y + dy) * size + x + dx] >= 0) {
                        halo[i] = true;
                        haloBody[i] = waterBody[(y + dy) * size + x + dx];
                        break;
                    }
                }
            }
        }
        fitDisc();
        for (int i = 0; i < size * size; i++) {
            if (water[i] || halo[i]) {
                behindWater[i] = nearestDry(i % size, i / size);
            }
        }
    }

    /**
     * For a round emoji (a face) with water on it, the disc of its dry skin: where the outline
     * runs under a drop it can be redrawn, and a drop's tip past the outline is left empty.
     */
    private void fitDisc() {
        discRadius = 0;
        if (ballRadius < 8 || waterCount == 0) {
            return;
        }
        float cx = 0, cy = 0;
        int count = 0;
        for (int i = 0; i < size * size; i++) {
            if (dry(i)) {
                cx += i % size;
                cy += i / size;
                count++;
            }
        }
        if (count < 50) {
            return;
        }
        cx /= count;
        cy /= count;
        float[] reach = new float[32];
        for (int i = 0; i < size * size; i++) {
            if (dry(i)) {
                float dx = i % size - cx;
                float dy = i / size - cy;
                int bin = (int) ((Math.atan2(dy, dx) + Math.PI) / (2 * Math.PI) * reach.length) % reach.length;
                reach[bin] = Math.max(reach[bin], (float) Math.sqrt(dx * dx + dy * dy));
            }
        }
        float[] sorted = reach.clone();
        java.util.Arrays.sort(sorted);
        discX = cx;
        discY = cy;
        discRadius = sorted[sorted.length / 2];
    }

    /**
     * The skin right around an eye's box (two dots out), so an eye on a patch of fur closes in
     * that patch's colour; 0 if there is none.
     */
    private int skinAround(int x0, int y0, int x1, int y1, boolean[] dark) {
        long r = 0, g = 0, b = 0;
        int n = 0;
        for (int y = y0 - 2; y <= y1 + 2; y++) {
            for (int x = x0 - 2; x <= x1 + 2; x++) {
                boolean ring = x < x0 - 1 || x > x1 + 1 || y < y0 - 1 || y > y1 + 1;
                if (!ring || !in(x, y)) {
                    continue;
                }
                int i = y * size + x;
                if (ink[i] && !dark[i] && luma[i] > 0.3f && rest[i] >>> 24 >= 225) {
                    r += (rest[i] >> 16) & 0xFF;
                    g += (rest[i] >> 8) & 0xFF;
                    b += rest[i] & 0xFF;
                    n++;
                }
            }
        }
        return n == 0 ? 0 : 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    /**
     * Windows: small panes of glass (bluish, lighter or darker than the wall) enclosed by the
     * building, numbered so each can light up on its own. The sky is one big area, not a pane.
     */
    private void findWindows() {
        boolean[] glass = new boolean[size * size];
        for (int i = 0; i < glass.length; i++) {
            glass[i] = ink[i] && hue[i] >= 165 && hue[i] <= 235 && sat[i] > 0.12f
                    && !water[i] && !eyes[i];
        }
        boolean[] seen = new boolean[size * size];
        for (int i = 0; i < glass.length; i++) {
            if (!glass[i] || seen[i]) {
                continue;
            }
            int[] comp = flood(glass, seen, i);
            if (comp.length < 2 || comp.length > 16 || touchesOutside(comp)) {
                continue;
            }
            for (int j : comp) {
                window[j] = windowCount;
            }
            windowCount++;
        }
    }

    /** Perceived brightness of a colour, 0..1. */
    static float luma(int c) {
        return (0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF))
                / 255f;
    }

    /** Whether (x, y) lies on the emoji: inside its dry disc when it has one, else on ink. */
    boolean onEmoji(int x, int y) {
        if (!in(x, y)) {
            return false;
        }
        if (discRadius > 0) {
            return Math.hypot(x - discX, y - discY) <= discRadius + 0.5f;
        }
        return ink[y * size + x];
    }

    private boolean dry(int i) {
        return ink[i] && !water[i] && !halo[i];
    }

    /**
     * The skin behind a water dot: the average of the light, dry ink around it, or transparent
     * when the dot is not enclosed by the emoji (a drop's tip poking past the outline) so nothing
     * is left behind there when the drop runs off.
     */
    private int nearestDry(int x, int y) {
        if (discRadius > 0) {
            float d = (float) Math.hypot(x - discX, y - discY);
            if (d > discRadius + 0.5f) {
                return 0;
            }
            if (d > discRadius - 1.6f) {
                // On the outline: continue the ring from the nearest dry dot at the same radius.
                int best = 0;
                float bestDist = Float.MAX_VALUE;
                for (int dy = -4; dy <= 4; dy++) {
                    for (int dx = -4; dx <= 4; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if (!in(nx, ny) || !dry(ny * size + nx)) {
                            continue;
                        }
                        float dj = (float) Math.hypot(nx - discX, ny - discY);
                        float dist = dx * dx + dy * dy;
                        if (Math.abs(dj - d) < 0.8f && dist < bestDist) {
                            bestDist = dist;
                            best = rest[ny * size + nx];
                        }
                    }
                }
                return best;
            }
        }
        int sides = 0;
        for (int dir = 0; dir < 4; dir++) {
            int dx = dir == 0 ? 1 : dir == 1 ? -1 : 0;
            int dy = dir == 2 ? 1 : dir == 3 ? -1 : 0;
            for (int step = 1; step <= 8; step++) {
                int nx = x + dx * step;
                int ny = y + dy * step;
                if (!in(nx, ny)) {
                    break;
                }
                int j = ny * size + nx;
                if (water[j] || halo[j]) {
                    continue;
                }
                if (ink[j] && luma[j] > 0.3f) {
                    sides++;
                }
                break;
            }
        }
        if (sides < 3 && discRadius == 0) {
            return 0;
        }
        for (int radius = 1; radius <= 3; radius++) {
            int r = 0, g = 0, b = 0, count = 0;
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int nx = x + dx;
                    int ny = y + dy;
                    if (!in(nx, ny)) {
                        continue;
                    }
                    int j = ny * size + nx;
                    if (ink[j] && !water[j] && !halo[j] && luma[j] > 0.45f && rest[j] >>> 24 >= 225) {
                        r += (rest[j] >> 16) & 0xFF;
                        g += (rest[j] >> 8) & 0xFF;
                        b += rest[j] & 0xFF;
                        count++;
                    }
                }
            }
            if (count > 0) {
                return 0xFF000000 | (r / count) << 16 | (g / count) << 8 | (b / count);
            }
        }
        return 0;
    }

    /** How much of its bounding box a component fills (1 = a solid rectangle). */
    private float fill(int[] comp) {
        int x0 = size, y0 = size, x1 = -1, y1 = -1;
        for (int i : comp) {
            x0 = Math.min(x0, i % size);
            x1 = Math.max(x1, i % size);
            y0 = Math.min(y0, i / size);
            y1 = Math.max(y1, i / size);
        }
        return comp.length / (float) ((x1 - x0 + 1) * (y1 - y0 + 1));
    }

    private static final int[][] NEIGHBOURS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** Largest 4-connected component of {@code mask} of at least {@code min} dots. */
    private int[] largest(boolean[] mask, int min, boolean mustBeEnclosed) {
        boolean[] seen = new boolean[size * size];
        int[] best = null;
        for (int i = 0; i < mask.length; i++) {
            if (!mask[i] || seen[i]) {
                continue;
            }
            int[] comp = flood(mask, seen, i);
            if (comp.length < min || (best != null && comp.length <= best.length)) {
                continue;
            }
            if (mustBeEnclosed && touchesOutside(comp)) {
                continue;
            }
            best = comp;
        }
        return best;
    }

    /** True if the component borders empty space (it is an outline, not an inside). */
    private boolean touchesOutside(int[] comp) {
        int edge = 0;
        for (int i : comp) {
            int x = i % size;
            int y = i / size;
            for (int[] d : NEIGHBOURS) {
                int nx = x + d[0];
                int ny = y + d[1];
                if (!in(nx, ny) || !ink[ny * size + nx]) {
                    edge++;
                }
            }
        }
        return edge > comp.length / 4;
    }

    private int[] flood(boolean[] mask, boolean[] seen, int start) {
        int[] stack = new int[size * size];
        int[] out = new int[size * size];
        int sp = 0, count = 0;
        stack[sp++] = start;
        seen[start] = true;
        while (sp > 0) {
            int i = stack[--sp];
            out[count++] = i;
            int x = i % size;
            int y = i / size;
            for (int[] d : NEIGHBOURS) {
                int nx = x + d[0];
                int ny = y + d[1];
                if (in(nx, ny)) {
                    int j = ny * size + nx;
                    if (mask[j] && !seen[j]) {
                        seen[j] = true;
                        stack[sp++] = j;
                    }
                }
            }
        }
        int[] trimmed = new int[count];
        System.arraycopy(out, 0, trimmed, 0, count);
        return trimmed;
    }

    static float hueOf(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float d = max - min;
        if (d == 0) {
            return 0;
        }
        float h;
        if (max == rf) {
            h = ((gf - bf) / d) % 6;
        } else if (max == gf) {
            h = (bf - rf) / d + 2;
        } else {
            h = (rf - gf) / d + 4;
        }
        h *= 60;
        return h < 0 ? h + 360 : h;
    }
}
