package com.dressence.nothinglauncherplus.anim;

import java.util.Arrays;

/**
 * Lifts an object off a round board (the dart off 🎯) so it can move: the board alone with
 * what the object covered filled in, the object, and the shadow it casts.
 *
 * <p>A target's rings look the same all the way round, so a pixel that differs from the
 * pixels at its distance from the centre all round is not the board: it is the object, or,
 * if it is just the board darker, the object's shadow. The board is filled in with what is
 * there all round; the shadow is kept as darkness alone (black, as see-through as the board
 * was darkened), so it falls correctly on whatever ring it moves over.
 */
final class Detach {

    private static final int SAMPLES = 24;
    /** How far apart in colour (summed channels) a pixel must be from its ring to stand out. */
    private static final int APART = 90;
    /** The sector round each direction that the object and the shadow can lie in (radians). */
    private static final double SECTOR = Math.toRadians(40);

    final int[] board;
    final int[] object;
    final int[] shadow;

    private Detach(int[] board, int[] object) {
        this.board = board;
        this.object = object;
        this.shadow = new int[board.length];
    }

    /**
     * Lifts everything of hue {@code hue} (degrees) off the emoji (a padlock's shackle): the
     * object is those pixels, the board is the rest with the gaps filled in from each side,
     * so what the piece lay on (the lock's top) shows when it moves away.
     */
    static Detach byHue(int[] pixels, int size, float hue) {
        return fromPiece(pixels, size, pieceOfHue(pixels, size, hue, 32, 0.1f), false);
    }

    /**
     * Lifts small pieces of hue {@code hue} (within {@code tolerance} degrees, at least this
     * saturated) lying on a rounded surface: beads of sweat on a face. What they covered is
     * patched from the skin all round, and where they hung over the outline it is redrawn
     * along the curve of the outline beside them.
     */
    static Detach byHue(int[] pixels, int size, float hue, float tolerance, float minSat) {
        return fromPiece(pixels, size, pieceOfHue(pixels, size, hue, tolerance, minSat), true);
    }

    private static boolean[] pieceOfHue(int[] pixels, int size, float hue, float tolerance,
            float minSat) {
        int n = size * size;
        boolean[] piece = new boolean[n];
        float[] hsv = new float[3];
        for (int i = 0; i < n; i++) {
            int c = pixels[i];
            if ((c >>> 24) < 24) {
                continue;
            }
            rgbToHsv(c, hsv);
            float d = Math.abs(hsv[0] - hue);
            piece[i] = hsv[1] > minSat && Math.min(d, 360 - d) < tolerance;
        }
        return piece;
    }

    /**
     * Lifts the separate piece of the drawing under (x, y) (pixels): one half of a broken
     * heart. Nothing lies under it, so the board is simply the rest.
     */
    static Detach piece(int[] pixels, int size, int x, int y) {
        int n = size * size;
        boolean[] piece = new boolean[n];
        int start = Math.max(0, Math.min(size - 1, y)) * size + Math.max(0, Math.min(size - 1, x));
        int[] stack = new int[n];
        int top = 0;
        if ((pixels[start] >>> 24) >= 24) {
            stack[top++] = start;
            piece[start] = true;
        }
        while (top > 0) {
            int i = stack[--top];
            int px = i % size;
            for (int j : new int[] {px > 0 ? i - 1 : -1, px < size - 1 ? i + 1 : -1, i - size,
                    i + size}) {
                if (j >= 0 && j < n && !piece[j] && (pixels[j] >>> 24) >= 24) {
                    piece[j] = true;
                    stack[top++] = j;
                }
            }
        }
        int[] board = pixels.clone();
        int[] object = new int[n];
        for (int i = 0; i < n; i++) {
            if (piece[i]) {
                object[i] = pixels[i];
                board[i] = 0;
            }
        }
        return new Detach(board, object);
    }

    /**
     * Lifts a stick and its knob (a joystick's): everything above {@code knobBottom}, and
     * below it the pixels of hue {@code hue} down to {@code cut} (pixel rows).
     */
    static Detach stick(int[] pixels, int size, float knobBottom, float hue, float cut) {
        int n = size * size;
        boolean[] piece = new boolean[n];
        float[] hsv = new float[3];
        for (int i = 0; i < n; i++) {
            int c = pixels[i];
            int y = i / size;
            if ((c >>> 24) < 24 || y > cut) {
                continue;
            }
            if (y < knobBottom) {
                piece[i] = true;
                continue;
            }
            rgbToHsv(c, hsv);
            float d = Math.abs(hsv[0] - hue);
            piece[i] = hsv[1] > 0.1f && Math.min(d, 360 - d) < 30;
        }
        return fromPiece(pixels, size, piece, false);
    }

    /** Lifts {@code piece} off the drawing (see byHue), {@code patch}ing what it covered. */
    private static Detach fromPiece(int[] pixels, int size, boolean[] piece, boolean patch) {
        int n = size * size;
        // Grown a little (a third of a dot), so the blend where the piece meets what it lies
        // on goes with it rather than staying behind as a faint ghost.
        boolean[] grown = piece;
        for (int pass = 0; pass < Math.max(3, size / 32); pass++) {
            boolean[] was = grown;
            grown = was.clone();
            for (int i = 0; i < n; i++) {
                int x = i % size;
                if (!was[i] && (pixels[i] >>> 24) > 0 && (x > 0 && was[i - 1]
                        || x < size - 1 && was[i + 1] || i >= size && was[i - size]
                        || i < n - size && was[i + size])) {
                    grown[i] = true;
                }
            }
        }
        if (patch) {
            return patched(pixels, size, piece, grown);
        }
        // Where what lies under the piece begins, column by column: measured beside the piece
        // and carried straight across it, so a curved edge (the lock's rounded top) stays
        // curved where the piece is lifted away.
        float[] top = new float[size];
        java.util.Arrays.fill(top, Float.NaN);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                int i = y * size + x;
                if (!grown[i] && (pixels[i] >>> 24) > 128) {
                    // Open sky just above it: this is the edge itself.
                    if (y == 0 || !grown[i - size]) {
                        top[x] = y;
                    }
                    break;
                }
            }
        }
        for (int x = 0; x < size; x++) {
            if (top[x] == top[x]) {
                continue;
            }
            int left = x - 1;
            while (left >= 0 && top[left] != top[left]) {
                left--;
            }
            int right = x + 1;
            while (right < size && top[right] != top[right]) {
                right++;
            }
            if (left >= 0 && right < size) {
                top[x] = top[left] + (top[right] - top[left]) * (x - left) / (right - left);
            }
        }
        int[] board = pixels.clone();
        int[] object = new int[n];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int i = y * size + x;
                if (!grown[i]) {
                    continue;
                }
                // The nearest pixels on this row not of the piece, either side.
                int left = x;
                while (left >= 0 && grown[y * size + left]) {
                    left--;
                }
                int right = x;
                while (right < size && grown[y * size + right]) {
                    right++;
                }
                int a = left >= 0 ? pixels[y * size + left] : 0;
                int b = right < size ? pixels[y * size + right] : 0;
                int fill = (a >>> 24) < 128 ? b : (b >>> 24) < 128 ? a
                        : Scene.mix(a, b, (x - left) / (float) (right - left));
                float cover = top[x] == top[x] ? Math.max(0, Math.min(1, y + 0.5f - top[x])) : 0;
                // Only as solid as what lies either side: open air stays clear.
                cover *= Math.max(a >>> 24, b >>> 24) / 255f;
                board[i] = (fill & 0xFFFFFF) | Math.round(255 * cover) << 24;
                // The piece itself, and the blend at its edge; not plain board it grew over.
                if (piece[i] || distance(pixels[i], fill) > 140) {
                    object[i] = pixels[i];
                }
            }
        }
        return new Detach(board, object);
    }

    /**
     * The board with the {@code hole} filled in from its rim inward, ring by ring, from the
     * solid pixels round it; where the hole reaches open air its edge follows a circle fitted
     * to the outline nearby.
     */
    private static Detach patched(int[] pixels, int size, boolean[] piece, boolean[] hole) {
        int n = size * size;
        int[] board = pixels.clone();
        boolean[] known = new boolean[n];
        boolean open = false;
        int x0 = size, y0 = size, x1 = -1, y1 = -1;
        for (int i = 0; i < n; i++) {
            known[i] = !hole[i] && (pixels[i] >>> 24) > 200;
            if (hole[i]) {
                x0 = Math.min(x0, i % size);
                x1 = Math.max(x1, i % size);
                y0 = Math.min(y0, i / size);
                y1 = Math.max(y1, i / size);
            }
        }
        if (x1 < 0) {
            return new Detach(board, new int[n]);
        }
        // Colour: each ring of the hole the average of the known pixels next to it.
        boolean grew = true;
        while (grew) {
            grew = false;
            boolean[] was = known.clone();
            for (int i = 0; i < n; i++) {
                if (!hole[i] || was[i]) {
                    continue;
                }
                int x = i % size;
                int y = i / size;
                float r = 0, g = 0, b = 0, w = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= size || ny >= size) {
                            continue;
                        }
                        int j = ny * size + nx;
                        if (!was[j]) {
                            if (!hole[j] && (pixels[j] >>> 24) < 128) {
                                open = true;
                            }
                            continue;
                        }
                        float k = dx == 0 || dy == 0 ? 1 : 0.7f;
                        int c = board[j];
                        r += k * ((c >> 16) & 0xFF);
                        g += k * ((c >> 8) & 0xFF);
                        b += k * (c & 0xFF);
                        w += k;
                    }
                }
                if (w > 0) {
                    board[i] = 0xFF000000 | Math.round(r / w) << 16 | Math.round(g / w) << 8
                            | Math.round(b / w);
                    known[i] = true;
                    grew = true;
                }
            }
        }
        // Shape: the outline beside the hole, as a circle (least squares, Kasa's fit).
        float cx = 0, cy = 0, radius = -1;
        if (open) {
            int reach = size / 4;
            double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0, sxz = 0, syz = 0, sz = 0;
            int count = 0;
            for (int y = Math.max(1, y0 - reach); y < Math.min(size - 1, y1 + reach); y++) {
                for (int x = Math.max(1, x0 - reach); x < Math.min(size - 1, x1 + reach); x++) {
                    int i = y * size + x;
                    if (hole[i] || (pixels[i] >>> 24) < 128) {
                        continue;
                    }
                    boolean edge = false;
                    for (int j : new int[] {i - 1, i + 1, i - size, i + size}) {
                        edge |= !hole[j] && (pixels[j] >>> 24) < 128;
                    }
                    if (!edge) {
                        continue;
                    }
                    double z = (double) x * x + (double) y * y;
                    sx += x;
                    sy += y;
                    sxx += (double) x * x;
                    syy += (double) y * y;
                    sxy += (double) x * y;
                    sxz += x * z;
                    syz += y * z;
                    sz += z;
                    count++;
                }
            }
            if (count >= 8) {
                // Solves for a, b, c in x² + y² + a x + b y + c = 0.
                double[][] m = {{sxx, sxy, sx, -sxz}, {sxy, syy, sy, -syz}, {sx, sy, count, -sz}};
                for (int col = 0; col < 3; col++) {
                    int best = col;
                    for (int row = col + 1; row < 3; row++) {
                        if (Math.abs(m[row][col]) > Math.abs(m[best][col])) {
                            best = row;
                        }
                    }
                    double[] t = m[col];
                    m[col] = m[best];
                    m[best] = t;
                    for (int row = 0; row < 3; row++) {
                        if (row != col && m[col][col] != 0) {
                            double f = m[row][col] / m[col][col];
                            for (int k = col; k < 4; k++) {
                                m[row][k] -= f * m[col][k];
                            }
                        }
                    }
                }
                double a = m[0][3] / m[0][0];
                double b = m[1][3] / m[1][1];
                double c = m[2][3] / m[2][2];
                cx = (float) (-a / 2);
                cy = (float) (-b / 2);
                double r2 = cx * cx + cy * cy - c;
                radius = r2 > 0 && r2 < size * size ? (float) Math.sqrt(r2) : -1;
            }
        }
        int[] object = new int[n];
        for (int i = 0; i < n; i++) {
            if (!hole[i]) {
                continue;
            }
            int fill = board[i];
            if (!known[i]) {
                // Out in the air, with no skin to reach.
                board[i] = 0;
            } else if (radius > 0) {
                // Pixel centres, as the outline pixels the circle was fitted to.
                float dx = i % size - cx;
                float dy = i / size - cy;
                float cover = radius + 0.5f - (float) Math.sqrt(dx * dx + dy * dy);
                cover = Math.max(0, Math.min(1, cover));
                board[i] = (fill & 0xFFFFFF) | Math.round(255 * cover) << 24;
            }
            if (piece[i] || distance(pixels[i], fill) > 140) {
                object[i] = pixels[i];
            }
        }
        return new Detach(board, object);
    }

    /**
     * Lifts the dark pieces inside x0, y0, x1, y1 (pixels; a fader's knob), filling what they
     * covered from above and below, so a track running under them shows through.
     */
    static Detach byDark(int[] pixels, int size, int x0, int y0, int x1, int y1) {
        int n = size * size;
        boolean[] piece = new boolean[n];
        for (int y = Math.max(0, y0); y <= Math.min(size - 1, y1); y++) {
            for (int x = Math.max(0, x0); x <= Math.min(size - 1, x1); x++) {
                int c = pixels[y * size + x];
                float luma = (0.3f * ((c >> 16) & 0xFF) + 0.59f * ((c >> 8) & 0xFF)
                        + 0.11f * (c & 0xFF)) / 255;
                piece[y * size + x] = (c >>> 24) > 24 && luma < 0.3f;
            }
        }
        boolean[] grown = piece;
        for (int pass = 0; pass < Math.max(2, size / 48); pass++) {
            boolean[] was = grown;
            grown = was.clone();
            for (int i = 0; i < n; i++) {
                int x = i % size;
                if (!was[i] && (x > 0 && was[i - 1] || x < size - 1 && was[i + 1]
                        || i >= size && was[i - size] || i < n - size && was[i + size])) {
                    grown[i] = (pixels[i] >>> 24) > 0;
                }
            }
        }
        int[] board = pixels.clone();
        int[] object = new int[n];
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                int i = y * size + x;
                if (!grown[i]) {
                    continue;
                }
                object[i] = pixels[i];
                int up = y;
                while (up >= 0 && grown[up * size + x]) {
                    up--;
                }
                int down = y;
                while (down < size && grown[down * size + x]) {
                    down++;
                }
                int a = up >= 0 ? pixels[up * size + x] : 0;
                int b = down < size ? pixels[down * size + x] : 0;
                board[i] = Scene.mix(a, b, (y - up) / (float) (down - up));
            }
        }
        return new Detach(board, object);
    }

    private static void rgbToHsv(int c, float[] hsv) {
        float r = ((c >> 16) & 0xFF) / 255f;
        float g = ((c >> 8) & 0xFF) / 255f;
        float b = (c & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        float h = d == 0 ? 0 : max == r ? ((g - b) / d % 6) : max == g ? (b - r) / d + 2
                : (r - g) / d + 4;
        hsv[0] = (h * 60 + 360) % 360;
        hsv[1] = max == 0 ? 0 : d / max;
        hsv[2] = max;
    }

    /**
     * {@code pixels}, {@code size} square: the emoji; the board's centre and radius; the
     * directions (degrees, clockwise from the right) the object and its shadow point from it.
     */
    Detach(int[] pixels, int size, float cx, float cy, float radius, float objectDegrees,
            float shadowDegrees) {
        int n = size * size;
        double objectAt0 = Math.toRadians(objectDegrees);
        double shadowAt0 = Math.toRadians(shadowDegrees);
        // The rings' true centre: where the board looks most alike all round.
        float bestX = cx;
        float bestY = cy;
        double best = Double.MAX_VALUE;
        float reach = radius * 0.08f + 1;
        float step = Math.max(0.25f, reach / 8);
        for (float ox = -reach; ox <= reach; ox += step) {
            for (float oy = -reach; oy <= reach; oy += step) {
                double cost = 0;
                for (int i = 0; i < n; i += 3) {
                    float dx = i % size + 0.5f - (cx + ox);
                    float dy = i / size + 0.5f - (cy + oy);
                    double at = Math.atan2(dy, dx);
                    if (dx * dx + dy * dy > radius * radius * 0.9f
                            || apart(at, objectAt0) < SECTOR || apart(at, shadowAt0) < SECTOR) {
                        continue;
                    }
                    int c = pixels[i];
                    // The same distance a quarter turn round, and half a turn.
                    int q = at(pixels, size, Math.round(cx + ox - dy - 0.5f),
                            Math.round(cy + oy + dx - 0.5f));
                    int h = at(pixels, size, Math.round(cx + ox - dx - 0.5f),
                            Math.round(cy + oy - dy - 0.5f));
                    cost += distance(c, q) + distance(c, h);
                }
                if (cost < best) {
                    best = cost;
                    bestX = cx + ox;
                    bestY = cy + oy;
                }
            }
        }
        cx = bestX;
        cy = bestY;
        board = pixels.clone();
        object = new int[n];
        shadow = new int[n];
        double objectAt = objectAt0;
        double shadowAt = shadowAt0;
        int[] r = new int[SAMPLES - 1];
        int[] g = new int[SAMPLES - 1];
        int[] b = new int[SAMPLES - 1];
        for (int i = 0; i < n; i++) {
            int c = pixels[i];
            if ((c >>> 24) == 0) {
                continue;
            }
            float dx = i % size + 0.5f - cx;
            float dy = i / size + 0.5f - cy;
            double at = Math.atan2(dy, dx);
            boolean nearObject = apart(at, objectAt) < SECTOR;
            boolean nearShadow = apart(at, shadowAt) < SECTOR;
            if (!nearObject && !nearShadow) {
                continue;
            }
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d > radius + 1.5f) {
                // Off the board: only the object reaches out there.
                if (nearObject) {
                    object[i] = c;
                    board[i] = 0;
                }
                continue;
            }
            // What the board is at this distance all round, and just in and out from it (a
            // ring's edge a hair off is still the board).
            int ring = ring(pixels, size, cx, cy, at, d, r, g, b);
            int diff = distance(c, ring);
            for (int side = -1; side <= 1; side += 2) {
                diff = Math.min(diff, distance(c,
                        ring(pixels, size, cx, cy, at, d + side * 1.5f, r, g, b)));
            }
            float lighter = luma(c) / Math.max(1, luma(ring));
            boolean shaded = nearShadow && lighter < 0.93f && sameHue(c, ring);
            if (diff < APART && !shaded) {
                continue;
            }
            board[i] = ring;
            float k = luma(c) / Math.max(1, luma(ring));
            if (nearShadow && !nearObject || nearShadow && k < 1 && sameHue(c, ring)) {
                float dark = Math.max(0, Math.min(1, 1 - k));
                shadow[i] = Math.round(dark * 255) << 24;
            } else {
                object[i] = c;
            }
        }
        // Stray pixels (an anti-aliased ring edge) are not the object or its shadow: only the
        // largest piece of each is, and anything else goes back to the board.
        keepLargest(object, pixels, size, 0.15f);
        keepLargest(shadow, pixels, size, 0.02f);
    }

    /**
     * Clears the pieces of {@code layer} smaller than {@code share} of its largest, restoring
     * the board there.
     */
    private void keepLargest(int[] layer, int[] pixels, int size, float share) {
        int n = layer.length;
        int[] label = new int[n];
        int[] stack = new int[n];
        int[] counts = new int[n + 1];
        int bestCount = 0;
        int next = 0;
        for (int i = 0; i < n; i++) {
            if (layer[i] == 0 || label[i] != 0) {
                continue;
            }
            next++;
            int count = 0;
            int top = 0;
            stack[top++] = i;
            label[i] = next;
            while (top > 0) {
                int p = stack[--top];
                count++;
                int x = p % size;
                for (int k = 0; k < 8; k++) {
                    int ox = k < 3 ? -1 : k < 5 ? 0 : 1;
                    int oy = k == 0 || k == 3 || k == 5 ? -1 : k == 1 || k == 6 ? 0 : 1;
                    if (ox == 0 && oy == 0 || x + ox < 0 || x + ox >= size) {
                        continue;
                    }
                    int q = p + oy * size + ox;
                    if (q >= 0 && q < n && layer[q] != 0 && label[q] == 0) {
                        label[q] = next;
                        stack[top++] = q;
                    }
                }
            }
            counts[next] = count;
            bestCount = Math.max(bestCount, count);
        }
        for (int i = 0; i < n; i++) {
            if (layer[i] != 0 && counts[label[i]] < bestCount * share) {
                layer[i] = 0;
                board[i] = pixels[i];
            }
        }
    }

    private static int distance(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) + Math.abs((a & 0xFF) - (b & 0xFF))
                + Math.abs((a >>> 24) - (b >>> 24));
    }

    /** The board's colour at distance {@code d} from the centre, all round from {@code at}. */
    private static int ring(int[] pixels, int size, float cx, float cy, double at, float d,
            int[] r, int[] g, int[] b) {
        int[] a = new int[SAMPLES - 1];
        for (int k = 1; k < SAMPLES; k++) {
            double angle = at + Math.PI * 2 * k / SAMPLES;
            int s = sample(pixels, size, cx + (float) (Math.cos(angle) * d) - 0.5f,
                    cy + (float) (Math.sin(angle) * d) - 0.5f);
            a[k - 1] = s >>> 24;
            r[k - 1] = (s >> 16) & 0xFF;
            g[k - 1] = (s >> 8) & 0xFF;
            b[k - 1] = s & 0xFF;
        }
        return median(a) << 24 | median(r) << 16 | median(g) << 8 | median(b);
    }

    private static double apart(double a, double b) {
        double d = Math.abs(a - b) % (Math.PI * 2);
        return d > Math.PI ? Math.PI * 2 - d : d;
    }

    /** True if {@code c} is {@code ring} darkened: the same balance of colour, less of it. */
    private static boolean sameHue(int c, int ring) {
        float k = luma(c) / Math.max(1, luma(ring));
        float err = Math.abs(((c >> 16) & 0xFF) - ((ring >> 16) & 0xFF) * k)
                + Math.abs(((c >> 8) & 0xFF) - ((ring >> 8) & 0xFF) * k)
                + Math.abs((c & 0xFF) - (ring & 0xFF) * k);
        return err < 60;
    }

    private static float luma(int r, int g, int b) {
        return 0.3f * r + 0.59f * g + 0.11f * b;
    }

    private static float luma(int c) {
        return 0.3f * ((c >> 16) & 0xFF) + 0.59f * ((c >> 8) & 0xFF) + 0.11f * (c & 0xFF);
    }

    private static int median(int[] v) {
        int[] s = v.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }

    private static int sample(int[] px, int size, float x, float y) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        float fx = x - x0;
        float fy = y - y0;
        int top = Scene.mix(at(px, size, x0, y0), at(px, size, x0 + 1, y0), fx);
        int bottom = Scene.mix(at(px, size, x0, y0 + 1), at(px, size, x0 + 1, y0 + 1), fx);
        return Scene.mix(top, bottom, fy);
    }

    private static int at(int[] px, int size, int x, int y) {
        x = Math.max(0, Math.min(size - 1, x));
        y = Math.max(0, Math.min(size - 1, y));
        return px[y * size + x];
    }
}
