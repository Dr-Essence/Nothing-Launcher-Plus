package com.dressence.nothinglauncherplus.anim;

/**
 * One frame for an effect to paint on: the posed, quantized dots, and for every dot the spot on
 * the resting emoji it shows. Effects look up regions through that map, so static stays on the
 * TV's screen and a blink stays on the eyes while the emoji hops, tilts or spins. Particles
 * (rain, steam, notes) are placed relative to the resting emoji and mapped forward the same way.
 */
final class Scene {

    final int size;
    final int[] dots;
    /** Resting-grid position of each output dot (NaN when nothing maps there). */
    final float[] srcX;
    final float[] srcY;
    Regions regions;
    /** Seconds, continuous; the effect's own clock. */
    float t;
    /** 0 at rest, up to 1 while reacting to a touch or the drawer opening. */
    float boost;
    /** Seconds since the last reaction began (huge when there was none). */
    float since = Float.MAX_VALUE;
    // -------------------------------------------------------------------------------------
    // Parts: pieces of the drawing an effect moves before the emoji is turned into dots
    // (a joystick's stick, a compass needle), so they move as smoothly as the whole emoji.
    // -------------------------------------------------------------------------------------

    static final int MAX_PARTS = 4;
    int partCount;
    /** Per part: the region it is cut from (resting grid units: x0, y0, x1, y1). */
    final float[] partRect = new float[MAX_PARTS * 4];
    /** Per part: cut as a circle (centre and radius from partRect) rather than a rectangle. */
    final boolean[] partRound = new boolean[MAX_PARTS];
    /** Per part: pivot (grid units), turn (degrees), shift (grid units). */
    final float[] partPivot = new float[MAX_PARTS * 2];
    final float[] partAngle = new float[MAX_PARTS];
    final float[] partShift = new float[MAX_PARTS * 2];
    /** Per part: horizontal scale about the pivot (a turn about a vertical axis: cos θ). */
    final float[] partScaleX = new float[MAX_PARTS];
    /**
     * Per part: the hue (degrees) of something drawn over it that must stay put while it turns
     * (a projector's arm across its reel), or -1; and the part's n-fold symmetry, which fills
     * in what that piece hides.
     */
    final int[] partKeepHue = new int[MAX_PARTS];
    final int[] partFold = new int[MAX_PARTS];
    /**
     * Per part: what it is when the emoji is split by {@link #detach}: 0, cut from the drawing;
     * 1, the lifted object; 2, its shadow; and how opaque it is drawn.
     */
    final int[] partLayer = new int[MAX_PARTS];
    final float[] partAlpha = new float[MAX_PARTS];
    /** Set by {@link #detach}: the board's centre, radius (grid units) and the directions. */
    boolean detached;
    final float[] detachSpec = new float[5];
    /** True when the emoji is split by colour ({@link #liftHue}) rather than as a board. */
    boolean detachByHue;

    /**
     * Splits the emoji into a round board (centre and radius in resting grid units), the
     * object lying on it toward {@code objectDegrees} and its shadow toward
     * {@code shadowDegrees} (see Detach); move them with {@link #addLayer}.
     */
    void detach(float cx, float cy, float radius, float objectDegrees, float shadowDegrees) {
        detached = true;
        detachByHue = false;
        detachSpec[0] = cx;
        detachSpec[1] = cy;
        detachSpec[2] = radius;
        detachSpec[3] = objectDegrees;
        detachSpec[4] = shadowDegrees;
    }

    /**
     * Splits off everything of hue {@code hue} (degrees) as layer 1 (a padlock's shackle),
     * leaving the rest, with what it covered filled in, as the board.
     */
    void liftHue(float hue) {
        detached = true;
        detachByHue = true;
        detachSpec[0] = hue;
    }

    /** As {@link #addLayer}, showing only the part of the layer inside x0, y0, x1, y1. */
    void addLayerPiece(int layer, float x0, float y0, float x1, float y1, float dx, float dy) {
        addLayer(layer, 0, 0, 0, dx, dy, 1);
        if (partCount > 0 && partLayer[partCount - 1] == layer) {
            int k = partCount - 1;
            partRect[k * 4] = x0;
            partRect[k * 4 + 1] = y0;
            partRect[k * 4 + 2] = x1;
            partRect[k * 4 + 3] = y1;
        }
    }

    /** Moves layer 1 (the object) or 2 (its shadow) of a {@link #detach}ed emoji. */
    void addLayer(int layer, float pivotX, float pivotY, float degrees, float dx, float dy,
            float alpha) {
        int before = partCount;
        addPart(0, 0, size, size, pivotX, pivotY, degrees, dx, dy);
        if (partCount > before) {
            partLayer[partCount - 1] = layer;
            partAlpha[partCount - 1] = alpha;
        }
    }
    /**
     * A round emoji turned as a sphere (see SphereSurface): centre and radius in resting grid
     * units, and the turn in radians; NaN when there is none this frame.
     */
    float sphereTurn = Float.NaN;
    float sphereX;
    float sphereY;
    float sphereRadius;
    boolean sphereRoll;

    /**
     * Turns the round emoji centred at (cx, cy) by {@code radians}: about its own axis (a globe)
     * or, {@code roll}, in the picture plane (a ball rolling).
     */
    void turnSphere(float cx, float cy, float radius, float radians, boolean roll) {
        sphereRoll = roll;
        sphereX = cx;
        sphereY = cy;
        sphereRadius = radius;
        sphereTurn = radians;
    }

    /** Set by an effect while something is moving: render at the display's full rate. */
    boolean busy;

    /** Cuts out a rectangle of the drawing, turns it about a pivot and shifts it. */
    void addPart(float x0, float y0, float x1, float y1, float pivotX, float pivotY,
            float degrees, float dx, float dy) {
        if (partCount >= MAX_PARTS) {
            return;
        }
        int k = partCount++;
        partRect[k * 4] = x0;
        partRect[k * 4 + 1] = y0;
        partRect[k * 4 + 2] = x1;
        partRect[k * 4 + 3] = y1;
        partRound[k] = false;
        partPivot[k * 2] = pivotX;
        partPivot[k * 2 + 1] = pivotY;
        partAngle[k] = degrees;
        partShift[k * 2] = dx;
        partShift[k * 2 + 1] = dy;
        partScaleX[k] = 1;
        partKeepHue[k] = -1;
        partLayer[k] = 0;
        partAlpha[k] = 1;
    }

    /** Turns the last part about the vertical line through its pivot, as seen from the front. */
    void turnLastPart(float scaleX) {
        if (partCount > 0) {
            partScaleX[partCount - 1] = scaleX;
        }
    }

    /**
     * Keeps whatever of hue {@code hue} (degrees) lies over the last part where it is while the
     * part turns underneath; what it covered is filled in from the part turned by 1/{@code fold}.
     */
    void keepOnLastPart(int hue, int fold) {
        if (partCount > 0) {
            partKeepHue[partCount - 1] = hue;
            partFold[partCount - 1] = fold;
        }
    }

    /** Cuts out a disc of the drawing and turns it about its centre (a dial, a needle). */
    void addDisc(float cx, float cy, float radius, float degrees) {
        int before = partCount;
        addPart(cx - radius, cy - radius, cx + radius, cy + radius, cx, cy, degrees, 0, 0);
        if (partCount > before) {
            partRound[partCount - 1] = true;
        }
    }

    /** Points the emoji table gives an effect (fractions of the emoji's bounds), or null. */
    float[] anchor;
    /** Per-emoji seed: every emoji runs its effect its own way. */
    int seed;
    /** Rest-to-output affine map: ox = a*x + b*y + c, oy = d*x + e*y + f. */
    float a = 1, b, c, d, e = 1, f;
    private boolean mapped;

    Scene(int size) {
        this.size = size;
        dots = new int[size * size];
        srcX = new float[size * size];
        srcY = new float[size * size];
    }

    /** Sets the maps from the posed frame (inverse) and to it (forward). */
    void map(float[] inverse, float[] forward) {
        mapped = inverse != null;
        if (!mapped) {
            return;
        }
        for (int i = 0; i < dots.length; i++) {
            float x = i % size + 0.5f;
            float y = i / size + 0.5f;
            srcX[i] = inverse[0] * x + inverse[1] * y + inverse[2] - 0.5f;
            srcY[i] = inverse[3] * x + inverse[4] * y + inverse[5] - 0.5f;
        }
        a = forward[0];
        b = forward[1];
        c = forward[2];
        d = forward[3];
        e = forward[4];
        f = forward[5];
    }

    boolean mapped() {
        return mapped && regions != null && regions.maxX >= 0;
    }

    /** Index of the resting dot output dot {@code i} shows, or -1. */
    int restIndex(int i) {
        int x = Math.round(srcX[i]);
        int y = Math.round(srcY[i]);
        return x >= 0 && y >= 0 && x < size && y < size ? y * size + x : -1;
    }

    /** Lights the output dot nearest to resting position (x, y) in {@code color}. */
    void plotRest(float x, float y, int color) {
        plot(a * (x + 0.5f) + b * (y + 0.5f) + c, d * (x + 0.5f) + e * (y + 0.5f) + f, color);
    }

    /** Paints the output dot nearest to resting position (x, y), over whatever is there. */
    void paintRest(float x, float y, int color) {
        float ox = a * (x + 0.5f) + b * (y + 0.5f) + c;
        float oy = d * (x + 0.5f) + e * (y + 0.5f) + f;
        int ix = (int) Math.floor(ox);
        int iy = (int) Math.floor(oy);
        if (ix >= 0 && iy >= 0 && ix < size && iy < size && (color >>> 24) > 0) {
            int i = iy * size + ix;
            dots[i] = Scene.mix(dots[i] == 0 ? color & 0xFFFFFF : dots[i], color | 0xFF000000,
                    (color >>> 24) / 255f) | 0xFF000000;
        }
    }

    /** Lights output dot (x, y) unless it is already brighter. */
    void plot(float x, float y, int color) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        if (ix < 0 || iy < 0 || ix >= size || iy >= size) {
            return;
        }
        int i = iy * size + ix;
        if ((dots[i] >>> 24) < (color >>> 24)) {
            dots[i] = color;
        }
    }

    // -------------------------------------------------------------------------------------
    // Colour and noise helpers
    // -------------------------------------------------------------------------------------

    /** Deterministic noise in [0, 1) for integer inputs. */
    static float hash(int x, int y, int z) {
        int h = x * 374761393 + y * 668265263 + z * 2147483647;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    static int argb(int a, int r, int g, int b) {
        return clamp(a) << 24 | clamp(r) << 16 | clamp(g) << 8 | clamp(b);
    }

    static int clamp(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }

    static int alpha(int color, int alpha) {
        return (color & 0xFFFFFF) | clamp(alpha) << 24;
    }

    static int mix(int from, int to, float t) {
        t = Math.max(0, Math.min(1, t));
        return argb(
                Math.round(((from >>> 24) * (1 - t)) + ((to >>> 24) * t)),
                Math.round(((from >> 16) & 0xFF) * (1 - t) + ((to >> 16) & 0xFF) * t),
                Math.round(((from >> 8) & 0xFF) * (1 - t) + ((to >> 8) & 0xFF) * t),
                Math.round((from & 0xFF) * (1 - t) + (to & 0xFF) * t));
    }

    static int scale(int color, float k) {
        return argb(color >>> 24, Math.round(((color >> 16) & 0xFF) * k),
                Math.round(((color >> 8) & 0xFF) * k), Math.round((color & 0xFF) * k));
    }

    /** Colour from hue (degrees), saturation and value, at full opacity. */
    static int hsv(float h, float s, float v) {
        h = ((h % 360) + 360) % 360 / 60f;
        int i = (int) h;
        float f = h - i;
        float p = v * (1 - s);
        float q = v * (1 - s * f);
        float t = v * (1 - s * (1 - f));
        float r, g, b;
        switch (i) {
            case 0: r = v; g = t; b = p; break;
            case 1: r = q; g = v; b = p; break;
            case 2: r = p; g = v; b = t; break;
            case 3: r = p; g = q; b = v; break;
            case 4: r = t; g = p; b = v; break;
            default: r = v; g = p; b = q; break;
        }
        return argb(255, Math.round(r * 255), Math.round(g * 255), Math.round(b * 255));
    }
}
