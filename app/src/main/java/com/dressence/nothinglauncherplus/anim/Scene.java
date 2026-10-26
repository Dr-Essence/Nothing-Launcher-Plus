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

    static final int MAX_PARTS = 6;
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
    /** Per part: vertical scale about the pivot (a lid swinging back: cos θ). */
    final float[] partScaleY = new float[MAX_PARTS];
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
    /** Per layer part: its size about its pivot (a heart beating). */
    final float[] partScale = new float[MAX_PARTS];
    /** Set by {@link #detach}: the board's centre, radius (grid units) and the directions. */
    boolean detached;
    final float[] detachSpec = new float[5];
    /** For {@link #liftHue}: the disc it is limited to (centre, radius; radius 0: none). */
    final float[] detachArea = new float[3];
    /** True when the emoji is split by colour ({@link #liftHue}) rather than as a board. */
    boolean detachByHue;
    /** True when dark pieces inside a box are lifted ({@link #liftDark}). */
    boolean detachByDark;
    /** True when a stick and its knob are lifted ({@link #liftStick}). */
    boolean detachStick;
    /** True when a separate piece of the drawing is lifted ({@link #liftPiece}). */
    boolean detachPiece;
    /** True when a walker's legs are lifted ({@link #liftLegs}): layers 1 and 2 are legs. */
    boolean detachLegs;
    /** Per part: clipped where the board is (a fixed opening), not where the part has gone. */
    final boolean[] partClipFixed = new boolean[MAX_PARTS];
    /** How far the board itself (what is left when a piece is lifted) moves down, in dots. */
    float boardDy;

    /**
     * Splits the emoji into a round board (centre and radius in resting grid units), the
     * object lying on it toward {@code objectDegrees} and its shadow toward
     * {@code shadowDegrees} (see Detach); move them with {@link #addLayer}.
     */
    void detach(float cx, float cy, float radius, float objectDegrees, float shadowDegrees) {
        detached = true;
        detachLegs = false;
        detachPiece = false;
        detachStick = false;
        detachByHue = false;
        detachByDark = false;
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
        liftHue(hue, 32, 0.1f);
        detachSpec[3] = 0;
    }

    /** As above, taking only colours within {@code tolerance} degrees and this saturated. */
    void liftHue(float hue, float tolerance, float minSat) {
        liftHue(hue, tolerance, minSat, 0, 0, 0);
    }

    /** As above, only within {@code radius} of (cx, cy) (resting grid units; 0: anywhere). */
    void liftHue(float hue, float tolerance, float minSat, float cx, float cy, float radius) {
        detachArea[0] = cx;
        detachArea[1] = cy;
        detachArea[2] = radius;
        detached = true;
        detachLegs = false;
        detachPiece = false;
        detachStick = false;
        detachByDark = false;
        detachByHue = true;
        detachSpec[0] = hue;
        detachSpec[1] = tolerance;
        detachSpec[2] = minSat;
        // What it covered is filled in from all round (see Detach#patched).
        detachSpec[3] = 1;
    }

    /**
     * Splits off the dark pieces inside x0, y0, x1, y1 (resting grid units) as layer 1 (a
     * fader's knob), what they covered filled in from above and below.
     */
    void liftDark(float x0, float y0, float x1, float y1) {
        detached = true;
        detachLegs = false;
        detachPiece = false;
        detachStick = false;
        detachByHue = false;
        detachByDark = true;
        detachSpec[0] = x0;
        detachSpec[1] = y0;
        detachSpec[2] = x1;
        detachSpec[3] = y1;
    }

    /**
     * Lifts a walker's legs below row {@code hipY} (resting grid units), each whole: layer 1
     * is the leg of the shoe further left, layer 2 the other.
     */
    void liftLegs(float hipY) {
        detached = true;
        detachStick = false;
        detachByHue = false;
        detachByDark = false;
        detachPiece = false;
        detachLegs = true;
        detachSpec[0] = hipY;
    }

    /**
     * Lifts the separate piece of the drawing under (x, y) (resting grid units) as layer 1:
     * one half of a broken heart.
     */
    void liftPiece(float x, float y) {
        detached = true;
        detachLegs = false;
        detachStick = false;
        detachByHue = false;
        detachByDark = false;
        detachPiece = true;
        detachSpec[0] = x;
        detachSpec[1] = y;
    }

    /**
     * Lifts a stick and its knob as layer 1: everything above {@code knobBottom}, then the
     * pieces of hue {@code hue} down to {@code cut} (resting grid rows).
     */
    void liftStick(float knobBottom, float hue, float cut) {
        detached = true;
        detachLegs = false;
        detachPiece = false;
        detachByHue = false;
        detachByDark = false;
        detachStick = true;
        detachSpec[0] = knobBottom;
        detachSpec[1] = hue;
        detachSpec[2] = cut;
    }

    /**
     * Turns layer 1 about (pivotX, pivotY) and shifts it, showing it only above row
     * {@code opening} of the board: a stick tilting in the hole it comes out of.
     */
    void tiltLayer(float pivotX, float pivotY, float degrees, float dx, float dy, float opening) {
        addLayer(1, pivotX, pivotY, degrees, dx, dy, 1);
        if (partCount > 0 && partLayer[partCount - 1] == 1) {
            int k = partCount - 1;
            partRect[k * 4] = -size;
            partRect[k * 4 + 1] = -size;
            partRect[k * 4 + 2] = size * 2;
            partRect[k * 4 + 3] = opening;
            partClipFixed[k] = true;
        }
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

    /**
     * Shows the part of layer {@code layer} inside x0, y0, x1, y1 scaled by {@code scale}
     * about (pivotX, pivotY): a heart of the drawing beating.
     */
    void scaleLayerPiece(int layer, float x0, float y0, float x1, float y1, float pivotX,
            float pivotY, float scale) {
        addLayerPiece(layer, x0, y0, x1, y1, 0, 0);
        if (partCount > 0 && partLayer[partCount - 1] == layer) {
            int k = partCount - 1;
            partPivot[k * 2] = pivotX;
            partPivot[k * 2 + 1] = pivotY;
            partScale[k] = scale;
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
    /**
     * For a keyboard globe turned as the whole Earth (see {@link Earth}): the longitude now
     * facing the viewer, degrees east; NaN to turn the drawing itself.
     */
    float earthCentre = Float.NaN;
    /** A drawn flame burning (see {@link Flame}): its time, NaN for none, and its box. */
    float flameT = Float.NaN;
    /**
     * The flame's box (left, top, right, bottom) and root row, resting grid units, and the
     * reddest hue that is still flame (0: any warm colour).
     */
    final float[] flameBox = new float[6];
    /**
     * A plant bending in the wind (see {@link Bend}): how far its top leans, in dots (NaN for
     * none), and the rows of its top and its root (resting grid units).
     */
    float bendAmount = Float.NaN;
    float bendTop;
    float bendRoot;
    /** A drawn burst going off (see {@link Burst}): its mode, -1 for none. */
    int burstMode = -1;
    /** Through one bloom (0..1), or the crackle's time. */
    float burstLife;
    float burstT;
    /** The burst's centre, reach (resting grid units) and 1 for warm colours only. */
    final float[] burstSpec = new float[4];
    /** How far the emoji's motion has rolled it, in radians (see {@link Pose#roll}). */
    float poseRoll;
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
        partScaleY[k] = 1;
        partKeepHue[k] = -1;
        partLayer[k] = 0;
        partAlpha[k] = 1;
        partClipFixed[k] = false;
        partScale[k] = 1;
    }

    /** Swings the last part about the level line through its pivot (a lid opening back). */
    void swingLastPart(float scaleY) {
        if (partCount > 0) {
            partScaleY[partCount - 1] = scaleY;
        }
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
    // Particles: soft, anti-aliased, placed between dots, so they glide rather than jump from
    // dot to dot. Positions are on the resting emoji (they follow its pose), sizes in dots.
    // -------------------------------------------------------------------------------------

    /** Where resting point (x, y) shows now, in output dots (centre-based). */
    private float outX(float x, float y) {
        return a * (x + 0.5f) + b * (y + 0.5f) + c;
    }

    private float outY(float x, float y) {
        return d * (x + 0.5f) + e * (y + 0.5f) + f;
    }

    /**
     * Adds light: output dot {@code i} moves toward {@code color} by {@code w} (0..1); a dark
     * dot lights up in it, as faint as {@code w}.
     */
    void light(int i, int color, float w) {
        if (w <= 0.004f) {
            return;
        }
        w = Math.min(1, w);
        int dot = dots[i];
        int alpha = dot >>> 24;
        int lit = Math.round(255 * w);
        if (alpha == 0) {
            dots[i] = (color & 0xFFFFFF) | lit << 24;
        } else {
            dots[i] = (mix(dot, color, w) & 0xFFFFFF) | Math.max(alpha, lit) << 24;
        }
    }

    /** A soft round glow at resting (x, y): {@code radius} dots, fading smoothly to its edge. */
    void blob(float x, float y, float radius, int color, float strength) {
        float ox = outX(x, y);
        float oy = outY(x, y);
        float reach = radius + 0.5f;
        int x0 = Math.max(0, (int) Math.floor(ox - reach));
        int x1 = Math.min(size - 1, (int) Math.floor(ox + reach));
        int y0 = Math.max(0, (int) Math.floor(oy - reach));
        int y1 = Math.min(size - 1, (int) Math.floor(oy + reach));
        for (int py = y0; py <= y1; py++) {
            for (int px = x0; px <= x1; px++) {
                float dx = px + 0.5f - ox;
                float dy = py + 0.5f - oy;
                float k = 1 - (dx * dx + dy * dy) / (reach * reach);
                if (k > 0) {
                    light(py * size + px, color, strength * k * k);
                }
            }
        }
    }

    /** A thin ring (a bubble) at resting (x, y): radius and line width in dots. */
    void ring(float x, float y, float radius, float width, int color, float strength) {
        float ox = outX(x, y);
        float oy = outY(x, y);
        float reach = radius + width + 0.5f;
        int x0 = Math.max(0, (int) Math.floor(ox - reach));
        int x1 = Math.min(size - 1, (int) Math.floor(ox + reach));
        int y0 = Math.max(0, (int) Math.floor(oy - reach));
        int y1 = Math.min(size - 1, (int) Math.floor(oy + reach));
        for (int py = y0; py <= y1; py++) {
            for (int px = x0; px <= x1; px++) {
                float dx = px + 0.5f - ox;
                float dy = py + 0.5f - oy;
                float off = Math.abs((float) Math.sqrt(dx * dx + dy * dy) - radius);
                light(py * size + px, color, strength * coverage(off, width / 2 + 0.35f));
            }
        }
    }

    /**
     * A solid ball at resting (x, y), {@code radius} dots, its edge anti-aliased; when
     * {@code behind}, only where nothing else is drawn, as if passing behind it.
     */
    void ball(float x, float y, float radius, int color, boolean behind) {
        float ox = outX(x, y);
        float oy = outY(x, y);
        int x0 = Math.max(0, (int) Math.floor(ox - radius - 1));
        int x1 = Math.min(size - 1, (int) Math.floor(ox + radius + 1));
        int y0 = Math.max(0, (int) Math.floor(oy - radius - 1));
        int y1 = Math.min(size - 1, (int) Math.floor(oy + radius + 1));
        for (int py = y0; py <= y1; py++) {
            for (int px = x0; px <= x1; px++) {
                int i = py * size + px;
                if (behind && (dots[i] >>> 24) > 0) {
                    continue;
                }
                float dx = px + 0.5f - ox;
                float dy = py + 0.5f - oy;
                light(i, color, coverage((float) Math.sqrt(dx * dx + dy * dy), radius - 0.5f));
            }
        }
    }

    /** A heart at resting (x, y), {@code size} dots across, its edge anti-aliased. */
    void heart(float x, float y, float across, int color, float strength) {
        float ox = outX(x, y);
        float oy = outY(x, y);
        float half = across / 2;
        int x0 = Math.max(0, (int) Math.floor(ox - half - 1));
        int x1 = Math.min(size - 1, (int) Math.floor(ox + half + 1));
        int y0 = Math.max(0, (int) Math.floor(oy - half - 1));
        int y1 = Math.min(size - 1, (int) Math.floor(oy + half + 1));
        for (int py = y0; py <= y1; py++) {
            for (int px = x0; px <= x1; px++) {
                int inside = 0;
                for (int sy = 0; sy < 4; sy++) {
                    for (int sx = 0; sx < 4; sx++) {
                        // The classic heart curve, y up, about 2.3 units across.
                        float u = (px + (sx + 0.5f) / 4 - ox) / half * 1.2f;
                        float v = -(py + (sy + 0.5f) / 4 - oy) / half * 1.2f + 0.15f;
                        float q = u * u + v * v - 1;
                        if (q * q * q - u * u * v * v * v <= 0) {
                            inside++;
                        }
                    }
                }
                if (inside > 0) {
                    light(py * size + px, color, strength * inside / 16f);
                }
            }
        }
    }

    /**
     * A star glint at resting (x, y): a bright core and four soft rays {@code reach} dots
     * long, like light catching a point.
     */
    void glint(float x, float y, float reach, int color, float strength) {
        float ox = outX(x, y);
        float oy = outY(x, y);
        int r = (int) Math.ceil(reach) + 1;
        int cx = (int) Math.floor(ox);
        int cy = (int) Math.floor(oy);
        for (int py = Math.max(0, cy - r); py <= Math.min(size - 1, cy + r); py++) {
            for (int px = Math.max(0, cx - r); px <= Math.min(size - 1, cx + r); px++) {
                float dx = Math.abs(px + 0.5f - ox);
                float dy = Math.abs(py + 0.5f - oy);
                float core = Math.max(0, 1 - (dx * dx + dy * dy) / 1.1f);
                // Each ray: thin across, tapering along.
                float h = coverage(dy, 0.45f) * Math.max(0, 1 - dx / (reach + 0.5f));
                float v = coverage(dx, 0.45f) * Math.max(0, 1 - dy / (reach + 0.5f));
                light(py * size + px, color, strength * Math.min(1, core + 0.85f * Math.max(h, v)));
            }
        }
    }

    /** A small strip (confetti) at resting (x, y), w x h dots, turned {@code radians}. */
    void strip(float x, float y, float w, float h, float radians, int color, float strength) {
        float ox = outX(x, y);
        float oy = outY(x, y);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        float reach = (w + h) / 2 + 1;
        for (int py = Math.max(0, (int) (oy - reach)); py <= Math.min(size - 1, (int) (oy + reach)); py++) {
            for (int px = Math.max(0, (int) (ox - reach)); px <= Math.min(size - 1, (int) (ox + reach)); px++) {
                float dx = px + 0.5f - ox;
                float dy = py + 0.5f - oy;
                float u = Math.abs(dx * cos + dy * sin);
                float v = Math.abs(-dx * sin + dy * cos);
                light(py * size + px, color,
                        strength * coverage(u, w / 2 + 0.3f) * coverage(v, h / 2 + 0.3f));
            }
        }
    }

    /** How much of a dot at {@code distance} from a shape's middle line of half-width {@code half} it covers. */
    private static float coverage(float distance, float half) {
        float k = half + 0.5f - distance;
        return k <= 0 ? 0 : Math.min(1, k);
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
