package com.dressence.nothinglauncherplus.anim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Effects for objects, each doing what the real thing does: a game controller's buttons are
 * pressed in combos, a joystick tilts on its base, a camera's iris closes and its flash fires,
 * traffic lights cycle, an hourglass drains, a battery charges, a compass needle swings and
 * settles, slot reels spin and stop one by one, wheels turn, a globe rotates, a printer feeds
 * paper. Parts are found in the emoji's own dots (and placed by {@link Scene#anchor} where the
 * drawing alone can't say), moves are eased like real objects, and nothing allocates per frame:
 * anything worked out from the drawing is computed once and kept in {@link Regions#memo}.
 */
final class ObjectEffects {

    private static final int WHITE = 0xFFFFFFFF;

    private ObjectEffects() {
    }

    // -------------------------------------------------------------------------------------
    // Controllers: 🎮 🕹️
    // -------------------------------------------------------------------------------------

    /**
     * A game being played: every so often a quick combo of the coloured face buttons, each
     * pressed in (darker) and released with a flash, while the d-pad clicks a direction.
     */
    static void controller(Scene s) {
        Regions r = s.regions;
        Parts p = controllerParts(r);
        if (p.buttons.isEmpty()) {
            return;
        }
        float combo = 1.7f;
        int round = (int) (s.t / combo);
        float t = s.t % combo;
        if (s.since < combo) {
            t = s.since;
            round = 7;
        }
        int pressed = -1;
        float press = 0;
        for (int k = 0; k < 3; k++) {
            float local = t - k * 0.17f;
            if (local >= 0 && local < 0.3f) {
                pressed = (int) (Scene.hash(round, k, s.seed) * p.buttons.size());
                press = local;
            }
        }
        // The d-pad: one arm pressed during the combo.
        int arm = (int) (Scene.hash(round, 9, s.seed) * 4);
        boolean dpad = t > 0.1f && t < 0.45f;
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0) {
                continue;
            }
            int b = p.button[ri];
            if (b >= 0 && b == pressed) {
                if (press < 0.11f) {
                    s.dots[i] = Scene.alpha(Scene.scale(s.dots[i], 0.4f), s.dots[i] >>> 24);
                } else {
                    MusicEffects.lighten(s, i, 0.6f * (1 - (press - 0.11f) / 0.19f));
                }
            } else if (dpad && p.dpad[ri] == arm) {
                MusicEffects.lighten(s, i, 0.55f);
            }
        }
    }

    /** A controller's face buttons (each numbered) and d-pad arms (0..3), per dot. */
    static final class Parts {
        final List<int[]> buttons = new ArrayList<>();
        int[] button;
        int[] dpad;
    }

    private static Parts controllerParts(Regions r) {
        Object cached = r.memo.get("controller");
        if (cached != null) {
            return (Parts) cached;
        }
        Parts p = new Parts();
        int n = r.size * r.size;
        p.button = new int[n];
        p.dpad = new int[n];
        Arrays.fill(p.button, -1);
        Arrays.fill(p.dpad, -1);
        float midX = (r.minX + r.maxX) / 2f;
        boolean[] coloured = new boolean[n];
        for (int i = 0; i < n; i++) {
            coloured[i] = r.ink[i] && r.sat[i] > 0.45f && r.luma[i] > 0.2f && i % r.size > midX;
        }
        for (int[] c : MusicEffects.components(r, coloured, false)) {
            for (int i : c) {
                p.button[i] = p.buttons.size();
            }
            p.buttons.add(c);
        }
        // The d-pad: the lighter cross on the left half, split into four arms.
        float sum = 0;
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (r.ink[i]) {
                sum += r.luma[i];
                count++;
            }
        }
        float mean = count > 0 ? sum / count : 0.5f;
        float sx = 0, sy = 0;
        int pad = 0;
        boolean[] light = new boolean[n];
        for (int i = 0; i < n; i++) {
            light[i] = r.ink[i] && r.sat[i] < 0.3f && r.luma[i] > mean + 0.08f
                    && i % r.size < midX - 1 && i / r.size < r.minY + (r.maxY - r.minY) * 0.7f;
            if (light[i]) {
                sx += i % r.size;
                sy += i / r.size;
                pad++;
            }
        }
        if (pad >= 3) {
            float cx = sx / pad;
            float cy = sy / pad;
            for (int i = 0; i < n; i++) {
                if (light[i]) {
                    float dx = i % r.size - cx;
                    float dy = i / r.size - cy;
                    if (Math.abs(dx) + Math.abs(dy) < 0.6f) {
                        continue; // The centre doesn't tilt.
                    }
                    p.dpad[i] = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 0 : 2) : (dy > 0 ? 1 : 3);
                }
            }
        }
        r.memo.put("controller", p);
        return p;
    }

    /** One move of the stick: how long it lasts, and how long the stick takes to get there. */
    private static final float MOVE = 0.78f;
    private static final float TRAVEL = 0.42f;

    /**
     * Someone plays: the stick and its ball ({@code anchor}: the pivot x, y inside the boot,
     * the ball's lowest row, the stick's hue and the row of the boot's opening) are lifted off
     * the drawing by their own shapes and pushed from one direction to the next, springing
     * there with a touch of overshoot. The stick turns about its pivot inside the boot and
     * shows only above the opening, so it slides in and out of the boot as it tilts rather
     * than bending, and nothing of the boot or the background goes with it.
     */
    static void joystickPose(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null || s.anchor.length < 5) {
            return;
        }
        int move = (int) (s.t / MOVE);
        float local = s.t % MOVE;
        float k = spring(local / TRAVEL);
        float angle = lerp(stickAngle(move - 1, s.seed), stickAngle(move, s.seed), k);
        float lean = lerp(stickLean(move - 1, s.seed), stickLean(move, s.seed), k);
        s.busy = local < TRAVEL + 0.08f;
        s.liftStick(edgeY(r, s.anchor[2]), s.anchor[3], edgeY(r, s.anchor[4]));
        s.tiltLayer(edgeX(r, s.anchor[0]), edgeY(r, s.anchor[1]), angle, 0, lean,
                edgeY(r, s.anchor[4]));
    }

    /** The fire button: pressed down for a moment, then lit, on the moves that fire. */
    static void joystick(Scene s) {
        Regions r = s.regions;
        int move = (int) (s.t / MOVE);
        float local = s.t % MOVE;
        boolean fires = Scene.hash(move, 3, s.seed) > 0.5f || s.since < MOVE;
        if (!fires || local > 0.5f) {
            return;
        }
        s.busy = true;
        // Below the boot's opening: the fire button, not the ball.
        float lowest = s.anchor != null && s.anchor.length >= 5 ? edgeY(r, s.anchor[4]) : 0;
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || ri / r.size < lowest || !r.ink[ri] || r.sat[ri] < 0.5f
                    || !(r.hue[ri] < 20 || r.hue[ri] > 330)) {
                continue;
            }
            if (local < 0.1f) {
                s.dots[i] = Scene.alpha(Scene.scale(s.dots[i], 0.55f), s.dots[i] >>> 24);
            } else {
                float glow = 1 - ease((local - 0.1f) / 0.4f);
                s.dots[i] = Scene.alpha(Scene.mix(s.dots[i], 0xFFFFB4A8, 0.7f * glow),
                        s.dots[i] >>> 24);
            }
        }
    }

    /** Where move {@code n} pushes the stick sideways, in degrees: often back to centre. */
    private static float stickAngle(int n, int seed) {
        float h = Scene.hash(n, 1, seed);
        if (h < 0.34f) {
            return 0;
        }
        float side = Scene.hash(n, 2, seed) < 0.5f ? -1 : 1;
        return side * (14 + 7 * Scene.hash(n, 4, seed));
    }

    /** How far move {@code n} leans the stick towards (down) or away from us (up), in dots. */
    private static float stickLean(int n, int seed) {
        float h = Scene.hash(n, 5, seed);
        return h < 0.6f ? 0 : h < 0.8f ? 0.7f : -0.6f;
    }

    private static float lerp(float a, float b, float k) {
        return a + (b - a) * k;
    }

    /** An anchor fraction as a grid position across the emoji's drawn width. */
    static float edgeX(Regions r, float f) {
        return r.minX + f * (r.maxX - r.minX + 1);
    }

    static float edgeY(Regions r, float f) {
        return r.minY + f * (r.maxY - r.minY + 1);
    }

    // -------------------------------------------------------------------------------------
    // Cameras: 📷 📸 📹 🎥 📽️
    // -------------------------------------------------------------------------------------

    /** A photo every few seconds: how long the shot takes from button to glint. */
    private static final float SHOT = 1.25f;

    /**
     * A photo is taken, as a real camera does it: the shutter button ({@code anchor} 2..5, a
     * cut x0, y0, x1, y1) goes down; the iris blades close across the glass (only the glass:
     * the metal ring stays) and open again, each dot shaded by how much blade covers it, so the
     * hexagonal aperture closes smoothly; at the moment it is shut the flash ({@code anchor}
     * 0, 1) fires, a soft white bloom; then a glint slides across the lens. A touch takes one.
     */
    static void cameraPose(Scene s) {
        Regions r = s.regions;
        float t = shotTime(s);
        if (t >= SHOT) {
            return;
        }
        s.busy = true;
        if (s.anchor != null && s.anchor.length >= 6 && t < 0.4f) {
            float press = t < 0.08f ? ease(t / 0.08f) : 1 - ease((t - 0.08f) / 0.3f);
            s.addPart(edgeX(r, s.anchor[2]), edgeY(r, s.anchor[3]), edgeX(r, s.anchor[4]),
                    edgeY(r, s.anchor[5]), 0, 0, 0, 0, 0.9f * press);
        }
    }

    static void camera(Scene s) {
        Regions r = s.regions;
        float t = shotTime(s);
        if (t >= SHOT) {
            return;
        }
        s.busy = true;
        Lens lens = lens(r);
        // The iris: closing fast (ease in), shut a moment, opening (ease out).
        float shut = t < 0.08f ? 0 : t < 0.2f ? easeIn((t - 0.08f) / 0.12f)
                : t < 0.26f ? 1 : t < 0.5f ? 1 - easeOut((t - 0.26f) / 0.24f) : 0;
        // The flash: instant on as the shutter shuts, a quick bright decay.
        float flash = t >= 0.2f ? (float) Math.exp(-(t - 0.2f) * 6f) : 0;
        float glint = t > 0.5f ? (t - 0.5f) / (SHOT - 0.5f) : -1;
        float spin = shut * 0.52f; // The blades turn as they close.
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri]) {
                continue;
            }
            float x = ri % r.size;
            float y = ri / r.size;
            if (lens != null && lens.glass[ri]) {
                if (shut > 0) {
                    float cover = bladeCover(x - lens.x, y - lens.y, lens.radius * (1 - shut),
                            spin);
                    if (cover > 0) {
                        int blade = blade(x - lens.x, y - lens.y, spin);
                        s.dots[i] = Scene.alpha(Scene.mix(s.dots[i], blade, cover),
                                s.dots[i] >>> 24);
                    }
                }
                if (glint >= 0) {
                    // A soft diagonal highlight crossing the glass once.
                    float along = ((x - lens.x) + (y - lens.y)) / (lens.radius * 2.8f);
                    float d = Math.abs(along - (glint * 2.2f - 1.1f));
                    if (d < 0.28f) {
                        MusicEffects.lighten(s, i, 0.45f * (1 - d / 0.28f));
                    }
                }
            } else if (flash > 0.02f) {
                MusicEffects.lighten(s, i, 0.22f * flash); // The whole body catches the light.
            }
        }
        if (flash > 0.02f && s.anchor != null && s.anchor.length >= 2) {
            bloom(s, edgeX(r, s.anchor[0]), edgeY(r, s.anchor[1]), 6.5f, flash);
        }
    }

    /** Seconds into the current shot (or the one a touch started); SHOT or more when idle. */
    private static float shotTime(Scene s) {
        if (s.since < SHOT) {
            return s.since;
        }
        float period = 4.2f + 1.2f * Scene.hash(s.seed, 2, 2);
        return (s.t + Scene.hash(s.seed, 3, 3) * period) % period;
    }

    /**
     * How much of the dot at (dx, dy) from the lens centre the blades cover (0..1), sampling
     * it 3 x 3 against a hexagonal aperture of inner radius {@code open} turned by {@code spin}.
     */
    private static float bladeCover(float dx, float dy, float open, float spin) {
        int covered = 0;
        for (int sy = -1; sy <= 1; sy++) {
            for (int sx = -1; sx <= 1; sx++) {
                float px = dx + sx / 3f;
                float py = dy + sy / 3f;
                if (!insideHexagon(px, py, open, spin)) {
                    covered++;
                }
            }
        }
        return covered / 9f;
    }

    private static boolean insideHexagon(float x, float y, float apothem, float spin) {
        if (apothem <= 0) {
            return false;
        }
        for (int k = 0; k < 6; k++) {
            double a = spin + k * Math.PI / 3;
            if (x * Math.cos(a) + y * Math.sin(a) > apothem) {
                return false;
            }
        }
        return true;
    }

    /** A blade's colour: graphite, alternate blades a shade lighter so they read as blades. */
    private static int blade(float dx, float dy, float spin) {
        double a = Math.atan2(dy, dx) - spin;
        int sector = (int) Math.floor((a + Math.PI * 4) / (Math.PI / 3)) % 6;
        return sector % 2 == 0 ? 0xFF2C3036 : 0xFF3A3F46;
    }

    /**
     * A flash of light at (x, y) in resting dots: a white-hot core and a halo falling off
     * softly over {@code radius}, painted over the camera.
     */
    private static void bloom(Scene s, float x, float y, float radius, float strength) {
        int reach = (int) Math.ceil(radius);
        for (int dy = -reach; dy <= reach; dy++) {
            for (int dx = -reach; dx <= reach; dx++) {
                float d = (float) Math.hypot(dx, dy);
                if (d >= radius) {
                    continue;
                }
                float core = d < 1.6f ? 1 : 0;
                float halo = 1 - d / radius;
                float a = Math.max(core, halo * halo * 0.85f) * strength;
                if (a > 0.03f) {
                    s.paintRest(x + dx, y + dy, Scene.alpha(0xFFFFFDF2, Math.round(255 * a)));
                }
            }
        }
    }

    /** A camera's glass: the coloured disc inside the lens ring, found in the drawing. */
    static final class Lens {
        boolean[] glass;
        float x;
        float y;
        float radius;
    }

    private static Lens lens(Regions r) {
        if (r.memo.containsKey("lens")) {
            return (Lens) r.memo.get("lens");
        }
        int n = r.size * r.size;
        boolean[] teal = new boolean[n];
        for (int i = 0; i < n; i++) {
            teal[i] = r.ink[i] && r.hue[i] > 160 && r.hue[i] < 230 && r.sat[i] > 0.3f
                    && r.luma[i] > 0.15f;
        }
        int[] best = null;
        for (int[] c : MusicEffects.components(r, teal, false)) {
            if (best == null || c.length > best.length) {
                best = c;
            }
        }
        Lens lens = null;
        if (best != null && best.length >= 12) {
            lens = new Lens();
            lens.glass = new boolean[n];
            for (int i : best) {
                lens.glass[i] = true;
            }
            lens.x = MusicEffects.cx(r, best);
            lens.y = MusicEffects.cy(r, best);
            lens.radius = (float) Math.sqrt(best.length / Math.PI) + 0.5f;
        }
        r.memo.put("lens", lens);
        return lens;
    }

    /** Recording: the camera's own red REC lamp ({@code anchor}: x, y) pulses. */
    static void record(Scene s) {
        lamp(s, MusicEffects.ax(s, 0), MusicEffects.ay(s, 1), recLamp(s));
    }

    /** How lit a REC lamp is: on most of each beat, a soft dip, never fully off. */
    private static float recLamp(Scene s) {
        float beat = (s.t * 0.8f) % 1;
        float on = beat < 0.55f ? 1
                : 1 - ease((beat - 0.55f) / 0.2f) + ease((beat - 0.8f) / 0.2f);
        return Math.max(on, s.boost);
    }

    /**
     * A small red lamp at (x, y): its own red dots brighten and dim with {@code on}, and while
     * lit its light spills a little onto the body round it.
     */
    private static void lamp(Scene s, float x, float y, float on) {
        Regions r = s.regions;
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri]) {
                continue;
            }
            float dx = ri % r.size - x;
            float dy = ri / r.size - y;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d > 2.2f) {
                continue;
            }
            int c = s.dots[i];
            if (red(c)) {
                int lit = Scene.scale(c, 0.3f + 0.8f * on);
                s.dots[i] = Scene.alpha(Scene.mix(lit, 0xFFFFB0A8, 0.25f * on * on), c >>> 24);
            } else {
                float k = 0.42f * on * Math.max(0, 1 - d / 2.2f);
                s.dots[i] = Scene.alpha(Scene.mix(c, 0xFFFF3B30, k), c >>> 24);
            }
        }
    }

    /**
     * The film reels ({@code anchor}: x, y, radius as a fraction of the width, per reel) spin,
     * each turned as a disc of the drawing so their spokes and holes really go round. Two
     * trailing values (hue, spokes) keep what of that hue lies over the reels (the projector's
     * arms) still while the reels turn beneath it.
     */
    static void reelsPose(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null) {
            return;
        }
        int reels = s.anchor.length / 3;
        boolean keep = s.anchor.length % 3 == 2;
        // A projector runs at a steady speed; a reaction whirls it one extra turn, which lands
        // where the steady turn would have been.
        float degrees = s.t * 150 + 360 * easeOut(Math.min(1, s.since / 1.4f));
        for (int k = 0; k < reels * 3; k += 3) {
            float rad = s.anchor[k + 2] * (r.maxX - r.minX);
            s.addDisc(MusicEffects.ax(s, k) + 0.5f, MusicEffects.ay(s, k + 1) + 0.5f, rad + 0.5f,
                    degrees);
            if (keep) {
                s.keepOnLastPart(Math.round(s.anchor[reels * 3]),
                        Math.round(s.anchor[reels * 3 + 1]));
            }
        }
        s.busy = true;
    }

    /**
     * A film camera rolling ({@code anchor}: the REC light's x, y, then x, y, radius per reel):
     * the red light pulses like a recording lamp and light runs round the turning reels.
     */
    static void filmCamera(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null || s.anchor.length < 2) {
            return;
        }
        lamp(s, MusicEffects.ax(s, 0), MusicEffects.ay(s, 1), recLamp(s));
        float speed = 2.4f + 3 * s.boost;
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri]) {
                continue;
            }
            float x = ri % r.size;
            float y = ri / r.size;
            for (int k = 2; k + 2 < s.anchor.length; k += 3) {
                float rx = x - MusicEffects.ax(s, k);
                float ry = y - MusicEffects.ay(s, k + 1);
                float rad = s.anchor[k + 2] * (r.maxX - r.minX);
                float d = (float) Math.sqrt(rx * rx + ry * ry);
                if (d > rad || d < rad * 0.3f) {
                    continue;
                }
                // Two soft highlights opposite each other, as on a spinning metal reel.
                float v = (float) Math.cos(2 * (Math.atan2(ry, rx) - s.t * speed));
                if (v > 0) {
                    MusicEffects.lighten(s, i, 0.45f * v * v * v);
                }
            }
        }
    }

    private static boolean red(int c) {
        int rr = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        return rr > 120 && rr > g * 2 && rr > b * 2;
    }

    // -------------------------------------------------------------------------------------
    // Cooking: 🍳 🫕
    // -------------------------------------------------------------------------------------

    /** The egg sizzles: oil pops around the white, specks jump, and a little steam rises. */
    static void sizzle(Scene s) {
        DotEffect.STEAM.apply(s);
        Regions r = s.regions;
        boolean[] edge = eggEdge(r);
        int frame = (int) (s.t * 12);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !edge[ri]) {
                continue;
            }
            float h = Scene.hash(ri, frame, s.seed);
            if (h < 0.16f + 0.2f * s.boost) {
                s.dots[i] = Scene.alpha(WHITE, s.dots[i] >>> 24);
            } else if (h > 0.9f) {
                s.dots[i] = Scene.alpha(Scene.scale(s.dots[i], 0.55f), s.dots[i] >>> 24);
            }
        }
    }

    /** The pan's dots right next to the egg white: where the oil sizzles. */
    private static boolean[] eggEdge(Regions r) {
        Object cached = r.memo.get("eggEdge");
        if (cached != null) {
            return (boolean[]) cached;
        }
        int n = r.size * r.size;
        boolean[] white = new boolean[n];
        for (int i = 0; i < n; i++) {
            white[i] = r.ink[i] && r.luma[i] > 0.82f && r.sat[i] < 0.2f;
        }
        boolean[] edge = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (!r.ink[i] || white[i]) {
                continue;
            }
            int x = i % r.size;
            int y = i / r.size;
            for (int dy = -1; dy <= 1 && !edge[i]; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (r.in(x + dx, y + dy) && white[(y + dy) * r.size + x + dx]) {
                        edge[i] = true;
                        break;
                    }
                }
            }
        }
        r.memo.put("eggEdge", edge);
        return edge;
    }

    /** A pot on a burner: the flame underneath flickers and the pot steams. */
    static void burner(Scene s) {
        DotEffect.STEAM.apply(s);
        Regions r = s.regions;
        float low = r.minY + (r.maxY - r.minY) * 0.6f;
        int frame = (int) (s.t * 16);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri] || ri / r.size < low || r.sat[ri] < 0.5f
                    || r.hue[ri] < 10 || r.hue[ri] > 60) {
                continue;
            }
            float h = Scene.hash(ri % r.size, frame, s.seed);
            s.dots[i] = Scene.alpha(Scene.mix(s.dots[i], h > 0.5f ? 0xFFFFE27A : 0xFFFF7A1F,
                    0.6f), Math.round((s.dots[i] >>> 24) * (0.55f + 0.45f * h)));
        }
    }

    // -------------------------------------------------------------------------------------
    // Signals: 🚦 🚥
    // -------------------------------------------------------------------------------------

    /** Traffic lights in their real cycle: green, then amber, then red; the others dim. */
    static void trafficLights(Scene s) {
        Regions r = s.regions;
        int[] lamp = lamps(r);
        float cycle = 5.6f;
        float t = (s.t + Scene.hash(s.seed, 4, 4) * cycle) % cycle;
        int lit = t < 2.4f ? 2 : t < 3.2f ? 1 : 0; // 0 red, 1 amber, 2 green
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || lamp[ri] < 0) {
                continue;
            }
            if (lamp[ri] == lit) {
                MusicEffects.lighten(s, i, 0.18f);
            } else {
                s.dots[i] = Scene.alpha(Scene.scale(s.dots[i], 0.28f), s.dots[i] >>> 24);
            }
        }
        // The lit lamp's light falls softly on the housing round it.
        float[][] centres = lampCentres(r, lamp);
        float[] c = centres[lit];
        if (c != null) {
            int colour = lit == 0 ? 0xFFFF5A4A : lit == 1 ? 0xFFFFC24A : 0xFF5AE68A;
            s.blob(c[0], c[1], c[2] + 1.6f, colour, 0.35f);
        }
    }

    /** Per lamp colour, the centre and radius of its lamps together (null if none). */
    private static float[][] lampCentres(Regions r, int[] lamp) {
        Object cached = r.memo.get("lampCentres");
        if (cached != null) {
            return (float[][]) cached;
        }
        float[][] out = new float[3][];
        for (int k = 0; k < 3; k++) {
            float sx = 0, sy = 0;
            int n = 0;
            for (int i = 0; i < lamp.length; i++) {
                if (lamp[i] == k) {
                    sx += i % r.size;
                    sy += i / r.size;
                    n++;
                }
            }
            if (n > 0) {
                out[k] = new float[] {sx / n, sy / n, (float) Math.sqrt(n / Math.PI)};
            }
        }
        r.memo.put("lampCentres", out);
        return out;
    }

    /** Each dot's lamp colour: 0 red, 1 amber, 2 green, -1 not a lamp. */
    private static int[] lamps(Regions r) {
        Object cached = r.memo.get("lamps");
        if (cached != null) {
            return (int[]) cached;
        }
        int[] out = new int[r.size * r.size];
        for (int i = 0; i < out.length; i++) {
            out[i] = -1;
            if (!r.ink[i] || r.sat[i] < 0.45f || r.luma[i] < 0.2f) {
                continue;
            }
            float h = r.hue[i];
            out[i] = h < 22 || h > 330 ? 0 : h < 70 ? 1 : h < 170 ? 2 : -1;
        }
        // A lamp's pale highlight belongs to it: a bright dot mostly ringed by one lamp.
        for (int pass = 0; pass < 2; pass++) {
            int[] next = out.clone();
            for (int i = 0; i < out.length; i++) {
                if (out[i] >= 0 || !r.ink[i] || r.luma[i] < 0.5f) {
                    continue;
                }
                int x = i % r.size;
                int y = i / r.size;
                int[] votes = new int[3];
                for (int oy = -1; oy <= 1; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        if (r.in(x + ox, y + oy) && out[(y + oy) * r.size + x + ox] >= 0) {
                            votes[out[(y + oy) * r.size + x + ox]]++;
                        }
                    }
                }
                for (int k = 0; k < 3; k++) {
                    if (votes[k] >= 4) {
                        next[i] = k;
                    }
                }
            }
            out = next;
        }
        r.memo.put("lamps", out);
        return out;
    }

    // -------------------------------------------------------------------------------------
    // Hourglass: ⏳ ⌛
    // -------------------------------------------------------------------------------------

    private static final float SAND_FLIP = 0.75f;
    private static final float SAND_DRAIN = 6f;
    private static final float SAND_CYCLE = SAND_FLIP + SAND_DRAIN + 1.4f;

    /** The hourglass is turned over: the whole glass, upside down over SAND_FLIP seconds. */
    static void hourglassPose(Scene s) {
        Regions r = s.regions;
        float t = s.t % SAND_CYCLE;
        if (t >= SAND_FLIP || sand(r) == null) {
            return;
        }
        float cx = (r.minX + r.maxX + 1) / 2f;
        float cy = (r.minY + r.maxY + 1) / 2f;
        s.addPart(0, 0, r.size, r.size, cx, cy, 180 * ease(t / SAND_FLIP), 0, 0);
        s.busy = true;
    }

    /**
     * Sand drains from the top bulb through the neck into the bottom one, a thin stream
     * falling between; it rests, then the glass is turned over and it runs again.
     */
    static void hourglass(Scene s) {
        Regions r = s.regions;
        Sand sand = sand(r);
        if (sand == null) {
            return;
        }
        float t = s.t % SAND_CYCLE;
        int total = Math.min(sand.capTop, sand.capBottom);
        if (t < SAND_FLIP) {
            // Turning: the drained glass, carried round with the drawing.
            double a = -Math.toRadians(180 * ease(t / SAND_FLIP));
            float cos = (float) Math.cos(a);
            float sin = (float) Math.sin(a);
            float cx = (r.minX + r.maxX + 1) / 2f;
            float cy = (r.minY + r.maxY + 1) / 2f;
            for (int i = 0; i < s.dots.length; i++) {
                if ((s.dots[i] >>> 24) == 0 || Float.isNaN(s.srcX[i])) {
                    continue;
                }
                float dx = s.srcX[i] + 0.5f - cx;
                float dy = s.srcY[i] + 0.5f - cy;
                int sx = (int) Math.floor(cx + dx * cos - dy * sin);
                int sy = (int) Math.floor(cy + dx * sin + dy * cos);
                if (!r.in(sx, sy)) {
                    continue;
                }
                int rank = sand.rank[sy * r.size + sx];
                if (rank == Integer.MIN_VALUE) {
                    continue;
                }
                boolean full = rank < 0 && -rank - 1 < total;
                s.dots[i] = Scene.alpha(full ? sand.sandColor : sand.glassColor,
                        s.dots[i] >>> 24);
            }
            return;
        }
        float p = Math.min(1, (t - SAND_FLIP) / SAND_DRAIN);
        int moved = Math.round(total * p);
        int top = total - moved;
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || sand.rank[ri] == Integer.MIN_VALUE) {
                continue;
            }
            int rank = sand.rank[ri];
            boolean full = rank >= 0 ? rank < top : -rank - 1 < moved;
            boolean stream = p < 1 && ri % r.size == sand.neckX && ri / r.size > sand.neckY
                    && rank < 0 && -rank - 1 >= moved
                    && ((ri / r.size) + (int) (s.t * 18)) % 2 == 0;
            int target = full || stream ? sand.sandColor : sand.glassColor;
            s.dots[i] = Scene.alpha(target, r.rest[ri] >>> 24);
        }
    }

    /** Sand in an hourglass: the order dots fill in each bulb, and its colours. */
    static final class Sand {
        /** Top bulb: rank 0.. (lowest first); bottom: -1, -2, … (lowest first); else MIN. */
        int[] rank;
        int top;
        int bottom;
        /** How many dots of sand each bulb holds. */
        int capTop;
        int capBottom;
        int neckX;
        int neckY;
        int sandColor;
        int glassColor;
    }

    private static Sand sand(Regions r) {
        if (r.memo.containsKey("sand")) {
            return (Sand) r.memo.get("sand");
        }
        int n = r.size * r.size;
        boolean[] sandDot = new boolean[n];
        boolean[] glass = new boolean[n];
        long sr = 0, sg = 0, sb = 0, gr = 0, gg = 0, gb = 0;
        int ns = 0, ng = 0;
        for (int i = 0; i < n; i++) {
            if (!r.ink[i]) {
                continue;
            }
            int c = r.rest[i];
            if (r.hue[i] > 30 && r.hue[i] < 62 && r.sat[i] > 0.4f && r.luma[i] > 0.45f) {
                sandDot[i] = true;
                glass[i] = true;
                sr += (c >> 16) & 0xFF;
                sg += (c >> 8) & 0xFF;
                sb += c & 0xFF;
                ns++;
            } else if (r.hue[i] > 170 && r.hue[i] < 230 && r.sat[i] > 0.12f) {
                glass[i] = true;
                gr += (c >> 16) & 0xFF;
                gg += (c >> 8) & 0xFF;
                gb += c & 0xFF;
                ng++;
            }
        }
        Sand sand = null;
        if (ns >= 6 && ng >= 6) {
            sand = new Sand();
            sand.sandColor = 0xFF000000 | (int) (sr / ns) << 16 | (int) (sg / ns) << 8
                    | (int) (sb / ns);
            sand.glassColor = 0xFF000000 | (int) (gr / ng) << 16 | (int) (gg / ng) << 8
                    | (int) (gb / ng);
            // The neck: the narrowest row of glass between the bulbs.
            int y0 = r.size, y1 = -1;
            for (int i = 0; i < n; i++) {
                if (glass[i]) {
                    y0 = Math.min(y0, i / r.size);
                    y1 = Math.max(y1, i / r.size);
                }
            }
            int neck = (y0 + y1) / 2;
            int narrowest = Integer.MAX_VALUE;
            for (int y = y0 + (y1 - y0) / 4; y <= y1 - (y1 - y0) / 4; y++) {
                int w = 0;
                for (int x = 0; x < r.size; x++) {
                    if (glass[y * r.size + x]) {
                        w++;
                    }
                }
                if (w > 0 && w < narrowest) {
                    narrowest = w;
                    neck = y;
                }
            }
            float cx = (r.minX + r.maxX) / 2f;
            sand.neckY = neck;
            sand.neckX = Math.round(cx);
            List<Integer> upper = new ArrayList<>();
            List<Integer> lower = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (glass[i]) {
                    if (i / r.size <= neck) {
                        upper.add(i);
                        if (sandDot[i]) {
                            sand.top++;
                        }
                    } else {
                        lower.add(i);
                        if (sandDot[i]) {
                            sand.bottom++;
                        }
                    }
                }
            }
            // Sand settles lowest first, in the middle first (a pile).
            java.util.Comparator<Integer> settle = (a, b) -> {
                int ya = a / r.size, yb = b / r.size;
                if (ya != yb) {
                    return yb - ya;
                }
                return Float.compare(Math.abs(a % r.size - cx), Math.abs(b % r.size - cx));
            };
            upper.sort(settle);
            lower.sort(settle);
            sand.capTop = upper.size();
            sand.capBottom = lower.size();
            sand.rank = new int[n];
            Arrays.fill(sand.rank, Integer.MIN_VALUE);
            for (int k = 0; k < upper.size(); k++) {
                sand.rank[upper.get(k)] = k;
            }
            for (int k = 0; k < lower.size(); k++) {
                sand.rank[lower.get(k)] = -k - 1;
            }
        }
        r.memo.put("sand", sand);
        return sand;
    }

    // -------------------------------------------------------------------------------------
    // Batteries, thermometers: 🔋 🪫 🌡️
    // -------------------------------------------------------------------------------------

    /**
     * A full battery charges up from empty, bar by bar, and glows when full; a low battery's
     * red bar blinks for attention.
     */
    static void battery(Scene s) {
        Regions r = s.regions;
        int[] span = colourSpan(r, "span-green", 70, 165);
        if (span != null) {
            float cycle = 3.4f;
            float t = s.t % cycle;
            float p = Math.min(1, t / 2.4f);
            float level = span[1] + 1 - ease(p) * (span[1] - span[0] + 1);
            float glow = t > 2.4f ? (float) Math.sin((t - 2.4f) / 1f * Math.PI) : 0;
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri < 0 || !inHue(r, ri, 70, 165)) {
                    continue;
                }
                if (ri / r.size < level) {
                    s.dots[i] = Scene.alpha(Scene.scale(s.dots[i], 0.22f), s.dots[i] >>> 24);
                } else if (glow > 0) {
                    MusicEffects.lighten(s, i, 0.3f * glow);
                }
            }
            return;
        }
        float on = 0.5f + 0.5f * (float) Math.cos(s.t * Math.PI * 2 * 0.8);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri >= 0 && (inHue(r, ri, 330, 360) || inHue(r, ri, 0, 22))) {
                s.dots[i] = Scene.alpha(Scene.scale(s.dots[i], 0.3f + 0.7f * on),
                        s.dots[i] >>> 24);
            }
        }
    }

    /**
     * The mercury rises and falls smoothly in its tube, climbing above where it is drawn up
     * the empty glass and sinking back; the bulb stays full.
     */
    static void thermometer(Scene s) {
        Regions r = s.regions;
        int[] span = colourSpan(r, "span-red", 340, 380);
        if (span == null) {
            return;
        }
        int[] column = mercuryColumn(r, span);
        int bulb = span[1] - 4;
        int tubeTop = r.minY + 2;
        float swing = 0.5f + 0.5f * (float) Math.sin(s.t * 0.9f);
        float level = bulb - (bulb - tubeTop) * (0.25f + 0.75f * swing);
        int glass = tubeColour(r);
        int red = mercuryColour(r);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri]) {
                continue;
            }
            int x = ri % r.size;
            int y = ri / r.size;
            if (y >= bulb || x < column[0] || x > column[1] || y < tubeTop) {
                continue;
            }
            s.dots[i] = Scene.alpha(y >= level ? red : glass, s.dots[i] >>> 24);
        }
    }

    /** The columns the mercury runs up: where the red sits above the bulb. */
    private static int[] mercuryColumn(Regions r, int[] span) {
        Object cached = r.memo.get("column");
        if (cached != null) {
            return (int[]) cached;
        }
        int x0 = r.size, x1 = -1;
        for (int y = span[0]; y < span[1] - 6; y++) {
            for (int x = 0; x < r.size; x++) {
                int i = y * r.size + x;
                if (inHue(r, i, 340, 360) || inHue(r, i, 0, 20)) {
                    x0 = Math.min(x0, x);
                    x1 = Math.max(x1, x);
                }
            }
        }
        int[] out = x1 < 0 ? new int[] {r.size, -1} : new int[] {x0, x1};
        r.memo.put("column", out);
        return out;
    }

    private static int mercuryColour(Regions r) {
        Object cached = r.memo.get("mercury");
        if (cached != null) {
            return (Integer) cached;
        }
        long sr = 0, sg = 0, sb = 0;
        int n = 0;
        for (int i = 0; i < r.size * r.size; i++) {
            if (inHue(r, i, 340, 360) || inHue(r, i, 0, 20)) {
                sr += (r.rest[i] >> 16) & 0xFF;
                sg += (r.rest[i] >> 8) & 0xFF;
                sb += r.rest[i] & 0xFF;
                n++;
            }
        }
        int c = n == 0 ? 0xFFE53935
                : 0xFF000000 | (int) (sr / n) << 16 | (int) (sg / n) << 8 | (int) (sb / n);
        r.memo.put("mercury", c);
        return c;
    }

    /** The light colour of a thermometer's glass (the brightest unsaturated ink). */
    private static int tubeColour(Regions r) {
        Object cached = r.memo.get("tube");
        if (cached != null) {
            return (Integer) cached;
        }
        long sr = 0, sg = 0, sb = 0;
        int n = 0;
        for (int i = 0; i < r.size * r.size; i++) {
            if (r.ink[i] && r.sat[i] < 0.25f && r.luma[i] > 0.7f) {
                sr += (r.rest[i] >> 16) & 0xFF;
                sg += (r.rest[i] >> 8) & 0xFF;
                sb += r.rest[i] & 0xFF;
                n++;
            }
        }
        int c = n == 0 ? 0xFFDDDDDD
                : 0xFF000000 | (int) (sr / n) << 16 | (int) (sg / n) << 8 | (int) (sb / n);
        r.memo.put("tube", c);
        return c;
    }

    private static boolean inHue(Regions r, int i, float from, float to) {
        if (!r.ink[i] || r.sat[i] < 0.4f || r.luma[i] < 0.2f) {
            return false;
        }
        float h = r.hue[i];
        return to > 360 ? h >= from || h <= to - 360 : h >= from && h <= to;
    }

    /** First and last rows holding ink of a hue range, or null when there is little of it. */
    private static int[] colourSpan(Regions r, String key, float from, float to) {
        if (r.memo.containsKey(key)) {
            return (int[]) r.memo.get(key);
        }
        int y0 = r.size, y1 = -1, count = 0;
        for (int i = 0; i < r.size * r.size; i++) {
            if (inHue(r, i, from, to)) {
                y0 = Math.min(y0, i / r.size);
                y1 = Math.max(y1, i / r.size);
                count++;
            }
        }
        int[] span = count >= 8 ? new int[] {y0, y1} : null;
        r.memo.put(key, span);
        return span;
    }

    // -------------------------------------------------------------------------------------
    // Compass, slot machine: 🧭 🎰
    // -------------------------------------------------------------------------------------

    /**
     * The needle is disturbed now and then and swings back to north, overshooting less each
     * time (a damped oscillation), like a real compass carried a step. The face and needle
     * turn together as one disc of the drawing, before it becomes dots, so the needle stays a
     * clean line at every angle.
     */
    static void compassPose(Scene s) {
        Regions r = s.regions;
        Needle needle = needle(r);
        if (needle == null) {
            return;
        }
        float period = 4.6f;
        int round = (int) (s.t / period);
        float t = s.t % period;
        float amp = (Scene.hash(round, 6, s.seed) < 0.5f ? -1 : 1)
                * (22 + 18 * Scene.hash(round, 7, s.seed));
        if (s.since < period) {
            t = s.since;
            amp = 40;
        }
        float envelope = (float) Math.exp(-t * 1.5f);
        float angle = amp * envelope * (float) Math.sin(t * 6.2f);
        s.busy = Math.abs(amp * envelope) > 0.4f;
        s.addDisc(needle.x + 0.5f, needle.y + 0.5f, needle.radius - 0.6f, angle);
    }

    /** A compass needle: its dots, the face it turns over, and its pivot. */
    static final class Needle {
        boolean[] mask;
        int face;
        float x;
        float y;
        float radius;
    }

    private static Needle needle(Regions r) {
        if (r.memo.containsKey("needle")) {
            return (Needle) r.memo.get("needle");
        }
        int n = r.size * r.size;
        float sx = 0, sy = 0;
        long fr = 0, fg = 0, fb = 0;
        int faces = 0;
        for (int i = 0; i < n; i++) {
            if (r.ink[i] && r.luma[i] > 0.85f && r.sat[i] < 0.2f) {
                sx += i % r.size;
                sy += i / r.size;
                fr += (r.rest[i] >> 16) & 0xFF;
                fg += (r.rest[i] >> 8) & 0xFF;
                fb += r.rest[i] & 0xFF;
                faces++;
            }
        }
        Needle needle = null;
        if (faces >= 20) {
            needle = new Needle();
            needle.x = sx / faces;
            needle.y = sy / faces;
            needle.radius = (float) Math.sqrt(faces / Math.PI) + 1;
            needle.face = 0xFF000000 | (int) (fr / faces) << 16 | (int) (fg / faces) << 8
                    | (int) (fb / faces);
            needle.mask = new boolean[n];
            int count = 0;
            for (int i = 0; i < n; i++) {
                float d = (float) Math.hypot(i % r.size - needle.x, i / r.size - needle.y);
                boolean pointer = r.sat[i] > 0.35f && (r.hue[i] < 25 || r.hue[i] > 330
                        || (r.hue[i] > 185 && r.hue[i] < 250));
                if (r.ink[i] && d < needle.radius - 0.5f && pointer) {
                    needle.mask[i] = true;
                    count++;
                }
            }
            if (count < 3) {
                needle = null;
            }
        }
        r.memo.put("needle", needle);
        return needle;
    }

    private static boolean[] pips(Regions r) {
        Object cached = r.memo.get("pips");
        if (cached != null) {
            return (boolean[]) cached;
        }
        int n = r.size * r.size;
        boolean[] dark = new boolean[n];
        for (int i = 0; i < n; i++) {
            dark[i] = r.ink[i] && (r.luma[i] < 0.4f || (r.sat[i] > 0.5f
                    && (r.hue[i] < 20 || r.hue[i] > 330)));
        }
        boolean[] out = new boolean[n];
        for (int[] c : MusicEffects.components(r, dark, false)) {
            if (c.length <= 10) {
                for (int i : c) {
                    out[i] = true;
                }
            }
        }
        r.memo.put("pips", out);
        return out;
    }

    /**
     * The slot machine plays: the reels in its window ({@code anchor}: x0, y0, x1, y1) spin
     * and stop one after another, each easing to a halt back on its symbol.
     */
    static void slots(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null || s.anchor.length < 4) {
            return;
        }
        int x0 = Math.round(MusicEffects.ax(s, 0));
        int y0 = Math.round(MusicEffects.ay(s, 1));
        int x1 = Math.round(MusicEffects.ax(s, 2));
        int y1 = Math.round(MusicEffects.ay(s, 3));
        int h = y1 - y0 + 1;
        if (h < 2 || x1 <= x0) {
            return;
        }
        float period = 4.8f;
        float t = s.since < period ? s.since : s.t % period;
        float reelWidth = (x1 - x0 + 1) / 3f;
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0) {
                continue;
            }
            int x = ri % r.size;
            int y = ri / r.size;
            if (x < x0 || x > x1 || y < y0 || y > y1) {
                continue;
            }
            int reel = Math.min(2, (int) ((x - x0) / reelWidth));
            float stop = 1.1f + 0.45f * reel;
            if (t >= stop) {
                continue;
            }
            float spins = 3 + reel;
            float offset = spins * h * ease(t / stop);
            int src = y0 + Math.floorMod(Math.round(y - y0 - offset), h);
            s.dots[i] = r.rest[src * r.size + x];
        }
    }

    // -------------------------------------------------------------------------------------
    // Wheels and rotors: 🚗 🚲 🚁 🎡
    // -------------------------------------------------------------------------------------

    /**
     * The wheels turn: spokes of light sweep round each wheel ({@code anchor}: x, y, radius
     * per wheel; else the round dark parts in the lower half of the vehicle).
     */
    static void wheels(Scene s) {
        Regions r = s.regions;
        float speed = 7 + 5 * s.boost;
        if (s.anchor != null && s.anchor.length >= 3) {
            for (int k = 0; k + 2 < s.anchor.length; k += 3) {
                spokes(s, MusicEffects.ax(s, k), MusicEffects.ay(s, k + 1),
                        s.anchor[k + 2] * (r.maxX - r.minX), s.t * speed, 3, 0.8f);
            }
            return;
        }
        for (float[] w : wheelList(r)) {
            spokes(s, w[0], w[1], w[2], s.t * speed, 2, 0.85f);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<float[]> wheelList(Regions r) {
        Object cached = r.memo.get("wheels");
        if (cached != null) {
            return (List<float[]>) cached;
        }
        int n = r.size * r.size;
        boolean[] dark = new boolean[n];
        float low = r.minY + (r.maxY - r.minY) * 0.5f;
        for (int i = 0; i < n; i++) {
            dark[i] = r.ink[i] && r.luma[i] < 0.42f && i / r.size >= low;
        }
        List<float[]> out = new ArrayList<>();
        for (int[] c : MusicEffects.components(r, dark, false)) {
            if (c.length < 4 || c.length > 140) {
                continue;
            }
            int x0 = r.size, y0 = r.size, x1 = 0, y1 = 0;
            for (int i : c) {
                x0 = Math.min(x0, i % r.size);
                x1 = Math.max(x1, i % r.size);
                y0 = Math.min(y0, i / r.size);
                y1 = Math.max(y1, i / r.size);
            }
            float w = x1 - x0 + 1;
            float h = y1 - y0 + 1;
            // A wheel: round, filled, and down where the vehicle meets the road.
            if (w / h > 0.6f && w / h < 1.6f && c.length > 0.5f * w * h && y1 >= r.maxY - 3) {
                out.add(new float[] {MusicEffects.cx(r, c), MusicEffects.cy(r, c),
                        (float) Math.sqrt(c.length / Math.PI) + 0.5f});
            }
        }
        r.memo.put("wheels", out);
        return out;
    }

    /** The rotor blurs: its blades flicker in a fast sweep across the top. */
    static void rotor(Scene s) {
        Regions r = s.regions;
        float top = r.minY + (r.maxY - r.minY) * 0.14f;
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri] || ri / r.size > top) {
                continue;
            }
            float v = (float) Math.sin(ri % r.size * 0.9f - s.t * 34);
            if (v < -0.2f) {
                s.dots[i] = Scene.alpha(Scene.scale(s.dots[i], 0.3f), s.dots[i] >>> 24);
            }
        }
    }

    /**
     * Lights round a rim (a flying saucer's) blink on in turn, one after another round the
     * edge, each with a soft glow: the lights are the small bright pieces of the emoji's most
     * vivid colour.
     */
    static void chase(Scene s) {
        Regions r = s.regions;
        List<float[]> lights = lights(r);
        if (lights.isEmpty()) {
            return;
        }
        float step = 0.16f - 0.06f * s.boost;
        float pos = (s.t / step) % lights.size();
        int[] which = lightOf(r, lights);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || which[ri] < 0) {
                continue;
            }
            float d = Math.abs(which[ri] - pos);
            d = Math.min(d, lights.size() - d);
            float on = Math.max(0, 1 - d / 1.5f);
            if (on > 0) {
                MusicEffects.lighten(s, i, 0.6f * on);
            } else {
                s.dots[i] = Scene.alpha(Scene.scale(s.dots[i], 0.45f), s.dots[i] >>> 24);
            }
        }
        float[] lit = lights.get(Math.round(pos) % lights.size());
        s.blob(lit[0], lit[1], 2.2f, (int) lit[3], 0.45f);
    }

    /** The small vivid lights: per light its centre x, y, size and colour, left to right. */
    @SuppressWarnings("unchecked")
    private static List<float[]> lights(Regions r) {
        Object cached = r.memo.get("lights");
        if (cached != null) {
            return (List<float[]>) cached;
        }
        boolean[] vivid = new boolean[r.size * r.size];
        for (int i = 0; i < vivid.length; i++) {
            vivid[i] = r.ink[i] && r.sat[i] > 0.4f && r.luma[i] > 0.45f;
        }
        List<float[]> out = new ArrayList<>();
        for (int[] c : MusicEffects.components(r, vivid, false)) {
            if (c.length >= 1 && c.length <= 12) {
                out.add(new float[] {MusicEffects.cx(r, c), MusicEffects.cy(r, c), c.length,
                        r.rest[c[0]] | 0xFF000000});
            }
        }
        out.sort((a, b) -> Float.compare(a[0], b[0]));
        r.memo.put("lights", out);
        return out;
    }

    /** For each dot, the index of the light it belongs to, or -1. */
    private static int[] lightOf(Regions r, List<float[]> lights) {
        Object cached = r.memo.get("lightOf");
        if (cached != null) {
            return (int[]) cached;
        }
        int[] out = new int[r.size * r.size];
        java.util.Arrays.fill(out, -1);
        for (int i = 0; i < out.length; i++) {
            if (!r.ink[i] || r.sat[i] <= 0.4f || r.luma[i] <= 0.45f) {
                continue;
            }
            for (int k = 0; k < lights.size(); k++) {
                float[] l = lights.get(k);
                float dx = i % r.size - l[0];
                float dy = i / r.size - l[1];
                if (dx * dx + dy * dy <= l[2] + 1) {
                    out[i] = k;
                }
            }
        }
        r.memo.put("lightOf", out);
        return out;
    }

    /**
     * Emergency lights on a vehicle's roof flash wig-wag: the blue ones twice, then the red
     * ones twice, each throwing its colour round it, while the others go dim.
     */
    static void siren(Scene s) {
        Regions r = s.regions;
        if (s.anchor != null && s.anchor.length >= 2) {
            // A lamp joined to the bodywork (a fire engine's): it flashes where it is told.
            float t = s.t % 0.8f;
            float k = t < 0.1f || (t > 0.2f && t < 0.3f) ? 1 : 0;
            for (int j = 0; j + 1 < s.anchor.length; j += 2) {
                float x = edgeX(r, s.anchor[j]);
                float y = edgeY(r, s.anchor[j + 1]);
                s.blob(x, y, 1.3f, 0xFFFFE0E0, 0.9f * k);
                s.blob(x, y, 3.2f, 0xFFFF3B3B, 0.45f * k);
            }
            s.busy = true;
            return;
        }
        int[] side = sirenSide(r);
        float[][] centres = sirenCentres(r, side);
        float t = s.t % 0.8f;
        // Two quick flashes per side, a side per 0.4 s.
        int on = t < 0.4f ? 0 : 1;
        float local = t % 0.4f;
        float flash = local < 0.09f || (local > 0.17f && local < 0.26f) ? 1 : 0;
        if (centres[0] == null) {
            on = 1; // Red lights only: they flash on their own.
        }
        if (centres[1] == null) {
            on = 0;
        }
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || side[ri] < 0) {
                continue;
            }
            if (side[ri] == on && flash > 0) {
                s.dots[i] = Scene.alpha(Scene.mix(s.dots[i], 0xFFFFFFFF, 0.45f), s.dots[i] >>> 24);
            } else {
                s.dots[i] = Scene.alpha(Scene.scale(s.dots[i], 0.5f), s.dots[i] >>> 24);
            }
        }
        float[] c = centres[on];
        if (c != null && flash > 0) {
            s.blob(c[0], c[1], c[2] + 2.5f, on == 0 ? 0xFF4D8CFF : 0xFFFF3B3B, 0.5f);
        }
        s.busy = true;
    }

    /** Per dot of the roof lights: 0 blue, 1 red, -1 not a roof light. */
    private static int[] sirenSide(Regions r) {
        Object cached = r.memo.get("sirenSide");
        if (cached != null) {
            return (int[]) cached;
        }
        int[] out = new int[r.size * r.size];
        float roof = r.minY + (r.maxY - r.minY) * 0.3f;
        for (int i = 0; i < out.length; i++) {
            out[i] = -1;
            if (!r.ink[i] || i / r.size > roof || r.sat[i] < 0.5f || r.luma[i] < 0.25f) {
                continue;
            }
            float h = r.hue[i];
            out[i] = h > 195 && h < 255 ? 0 : h < 15 || h > 340 ? 1 : -1;
        }
        // Only small pieces are lamps: a red cab or body is not.
        for (int k = 0; k < 2; k++) {
            boolean[] mask = new boolean[out.length];
            for (int i = 0; i < out.length; i++) {
                mask[i] = out[i] == k;
            }
            for (int[] c : MusicEffects.components(r, mask, true)) {
                if (c.length > 14) {
                    for (int i : c) {
                        out[i] = -1;
                    }
                }
            }
        }
        r.memo.put("sirenSide", out);
        return out;
    }

    private static float[][] sirenCentres(Regions r, int[] side) {
        Object cached = r.memo.get("sirenCentres");
        if (cached != null) {
            return (float[][]) cached;
        }
        float[][] out = new float[2][];
        for (int k = 0; k < 2; k++) {
            float sx = 0, sy = 0;
            int n = 0;
            for (int i = 0; i < side.length; i++) {
                if (side[i] == k) {
                    sx += i % r.size;
                    sy += i / r.size;
                    n++;
                }
            }
            if (n > 0) {
                out[k] = new float[] {sx / n, sy / n, (float) Math.sqrt(n / Math.PI)};
            }
        }
        r.memo.put("sirenCentres", out);
        return out;
    }

    // -------------------------------------------------------------------------------------
    // Barber pole, globe: 💈 🌍 🌎 🌏 🌐
    // -------------------------------------------------------------------------------------

    /** The stripes climb the pole ({@code anchor}: the striped part, x0, y0, x1, y1). */
    static void pole(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null || s.anchor.length < 4) {
            return;
        }
        int x0 = Math.round(MusicEffects.ax(s, 0));
        int y0 = Math.round(MusicEffects.ay(s, 1));
        int x1 = Math.round(MusicEffects.ax(s, 2));
        int y1 = Math.round(MusicEffects.ay(s, 3));
        int h = y1 - y0 + 1;
        if (h < 2) {
            return;
        }
        int offset = Math.round(s.t * (5 + 4 * s.boost));
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri]) {
                continue;
            }
            int x = ri % r.size;
            int y = ri / r.size;
            if (x < x0 || x > x1 || y < y0 || y > y1) {
                continue;
            }
            int src = (y0 + Math.floorMod(y - y0 + offset, h)) * r.size + x;
            if (r.ink[src]) {
                s.dots[i] = Scene.alpha(r.rest[src], s.dots[i] >>> 24);
            }
        }
    }

    /**
     * The emoji turns as a real solid (see SphereSurface): a globe steadily, its continents
     * travelling round; a ball ({@code anchor} 1) rolls one full turn in the picture plane,
     * rests, its shading staying on its shaded side all the while. A touch spins it faster.
     */
    static void globePose(Scene s) {
        Regions r = s.regions;
        if (s.anchor != null && s.anchor.length > 0 && s.anchor[0] == 2) {
            s.busy = true; // A wire globe is drawn afresh each frame (see wireGlobe).
            return;
        }
        float cx = (r.minX + r.maxX + 1) / 2f;
        float cy = (r.minY + r.maxY + 1) / 2f;
        float radius = Math.max(r.maxX - r.minX + 1, r.maxY - r.minY + 1) / 2f + 0.5f;
        float full = (float) (Math.PI * 2);
        float turn;
        boolean ball = s.anchor != null && s.anchor.length > 0 && s.anchor[0] == 1;
        if (ball) {
            // A ball: turned as far as its motion has rolled it (spinning while it is kicked
            // up); a touch kicks it into a quick extra turn.
            turn = s.poseRoll;
            s.busy = turn != 0;
            if (s.since < 1.2f) {
                turn += full * easeOut(s.since / 1.2f);
                s.busy = true;
            }
        } else {
            turn = s.t * 0.45f + full * easeOut(Math.min(1, s.since / 1.6f));
            s.busy = true;
        }
        s.turnSphere(cx, cy, radius, turn, ball);
    }

    /** One bounce of the tennis ball, 0 (resting spot) to 1 (on the strings) and back. */
    private static final float BOUNCE = 0.86f;

    /**
     * How far down the tennis ball is at {@code t}: it drops onto the strings under gravity
     * and pops back up, twice, then rests in the air where it is drawn; -1 while resting.
     */
    private static float tennisDrop(Scene s) {
        float period = BOUNCE * 2 + 1.7f;
        float t = s.since < BOUNCE * 2 ? s.since : s.t % period;
        if (t >= BOUNCE * 2) {
            return -1;
        }
        float b = (t % BOUNCE) / BOUNCE;
        // Falling it speeds up, rising it slows down: a parabola either way.
        return b < 0.5f ? (b * 2) * (b * 2) : 1 - ((b - 0.5f) * 2) * ((b - 0.5f) * 2);
    }

    /**
     * 🎾: the ball ({@code anchor}: x, y, radius as a fraction of the width, then the strings'
     * middle x, y) is bounced on the racket, spinning as it flies.
     */
    static void tennisPose(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null || s.anchor.length < 5) {
            return;
        }
        float drop = tennisDrop(s);
        if (drop < 0) {
            return;
        }
        float bx = edgeX(r, s.anchor[0]);
        float by = edgeY(r, s.anchor[1]);
        float rad = s.anchor[2] * (r.maxX - r.minX + 1);
        float dx = (edgeX(r, s.anchor[3]) - bx) * drop;
        // It falls along a slight arc, as a ball tossed toward the racket face does.
        float dy = (edgeY(r, s.anchor[4]) - by) * drop - 2.2f * drop * (1 - drop);
        float t = s.since < BOUNCE * 2 ? s.since : s.t % (BOUNCE * 2 + 1.7f);
        // Cut a dot wider than the ball, so none of its soft edge stays behind.
        rad += 1;
        s.addPart(bx - rad, by - rad, bx + rad, by + rad, bx, by, -t * 420, dx, dy);
        s.partRound[s.partCount - 1] = true;
        s.busy = true;
    }

    /** 🎾: the strings flash where the ball hits them. */
    static void tennis(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null || s.anchor.length < 5) {
            return;
        }
        float period = BOUNCE * 2 + 1.7f;
        float t = s.since < BOUNCE * 2 ? s.since : s.t % period;
        if (t >= BOUNCE * 2) {
            return;
        }
        float sinceHit = (t + BOUNCE / 2) % BOUNCE;
        float flash = 1 - ease(sinceHit / 0.22f);
        if (flash <= 0) {
            return;
        }
        float sx = edgeX(r, s.anchor[3]);
        float sy = edgeY(r, s.anchor[4]);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri] || r.sat[ri] > 0.3f || r.luma[ri] < 0.55f) {
                continue; // Only the pale strings.
            }
            float dx = ri % r.size + 0.5f - sx;
            float dy = ri / r.size + 0.5f - sy;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < 4.5f) {
                MusicEffects.lighten(s, i, 0.6f * flash * (1 - d / 4.5f));
            }
        }
    }

    private static final float DART_FLIGHT = 0.3f;
    private static final float DART_PULL = 2.3f;
    private static final float DART_GONE = 2.62f;
    private static final float DART_PERIOD = 3.4f;

    /**
     * 🎯: a dart is thrown into the bull ({@code anchor}: the board's centre x, y and radius,
     * then the directions in degrees the dart and its shadow point from the bull). It flies in
     * from beyond the frame, slowing a little as darts do, strikes and quivers about its tip,
     * its shadow closing in and darkening as it lands; later it is drawn back out and lifted
     * away, and the board waits empty for the next throw.
     */
    static void dartsPose(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null || s.anchor.length < 5) {
            return;
        }
        float cx = edgeX(r, s.anchor[0]);
        float cy = edgeY(r, s.anchor[1]);
        float radius = s.anchor[2] * (r.maxX - r.minX + 1);
        s.detach(cx, cy, radius, s.anchor[3], s.anchor[4]);
        float t = s.since < DART_PERIOD ? s.since : s.t % DART_PERIOD;
        if (t >= DART_GONE) {
            return; // The empty board.
        }
        double along = Math.toRadians(s.anchor[3]);
        double fall = Math.toRadians(s.anchor[4]);
        // How far back along its line the dart is, how high off the board, how lit its shadow.
        float back;
        float height;
        float alpha = 1;
        float wobble = 0;
        if (t < DART_FLIGHT) {
            float u = t / DART_FLIGHT;
            back = 26 * (1 - u) * (1 - 0.35f * u);
            height = 1 - u;
            s.busy = true;
        } else if (t < DART_PULL) {
            float k = t - DART_FLIGHT;
            back = 0;
            height = 0;
            wobble = 7 * (float) (Math.exp(-k * 6.5) * Math.sin(k * 36));
            s.busy = k < 0.8f;
        } else {
            float u = (t - DART_PULL) / (DART_GONE - DART_PULL);
            back = 1.6f * easeOut(u);
            height = 0.25f * u;
            alpha = 1 - ease(u);
            s.busy = true;
        }
        float dx = (float) Math.cos(along) * back;
        float dy = (float) Math.sin(along) * back;
        // The higher the dart, the farther its shadow falls from it, and the fainter it is.
        float sx = dx + (float) Math.cos(fall) * 7 * height;
        float sy = dy + (float) Math.sin(fall) * 7 * height;
        float shade = alpha * (1 - height) * (1 - height);
        s.addLayer(2, cx, cy, wobble * 0.6f, sx, sy, shade);
        s.addLayer(1, cx, cy, wobble, dx, dy, alpha);
    }

    /**
     * A round symbol on its tile turns ({@code anchor}: the disc's x, y, radius as a fraction of
     * the width, then degrees a second): the tile stays, only the disc turns. Two more values
     * (hue, symmetry) keep what of that hue stands in front still (a big wheel's legs).
     */
    static void spinPose(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null || s.anchor.length < 4) {
            return;
        }
        float rad = s.anchor[2] * (r.maxX - r.minX + 1);
        float degrees = s.t * s.anchor[3] + 360 * easeOut(Math.min(1, s.since / 1.4f));
        s.addDisc(edgeX(r, s.anchor[0]), edgeY(r, s.anchor[1]), rad, degrees);
        if (s.anchor.length >= 6) {
            s.keepOnLastPart(Math.round(s.anchor[4]), Math.round(s.anchor[5]));
        }
        s.busy = true;
    }

    /**
     * Sparks at the points in {@code anchor} (x, y pairs): now and then a burst of crackling
     * blue-white flashes at each, and an arc flickering between the first two.
     */
    static void spark(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null || s.anchor.length < 2) {
            return;
        }
        float period = 2.6f;
        float t = s.since < 0.6f ? s.since : (s.t + Scene.hash(s.seed, 5, 5) * period) % period;
        if (t > 0.6f) {
            return;
        }
        s.busy = true;
        int flick = (int) (s.t * 30);
        int colour = 0xFFC8ECFF;
        for (int k = 0; k + 1 < s.anchor.length; k += 2) {
            float h = Scene.hash(flick, k, s.seed);
            if (h < 0.35f) {
                continue; // Sparks come and go between frames.
            }
            float jx = (Scene.hash(flick, k + 7, s.seed) - 0.5f) * 1.6f;
            float jy = (Scene.hash(flick, k + 9, s.seed) - 0.5f) * 1.6f;
            s.glint(edgeX(r, s.anchor[k]) + jx, edgeY(r, s.anchor[k + 1]) + jy, 0.8f + 1.2f * h,
                    colour, h);
        }
        if (s.anchor.length >= 4 && Scene.hash(flick, 3, s.seed) > 0.4f) {
            // The arc: a jagged line of light between the pins.
            float x0 = edgeX(r, s.anchor[0]);
            float y0 = edgeY(r, s.anchor[1]);
            float x1 = edgeX(r, s.anchor[2]);
            float y1 = edgeY(r, s.anchor[3]);
            float len = (float) Math.hypot(x1 - x0, y1 - y0);
            float nx = -(y1 - y0) / Math.max(1, len);
            float ny = (x1 - x0) / Math.max(1, len);
            int steps = Math.max(4, Math.round(len * 1.5f));
            for (int j = 0; j <= steps; j++) {
                float f = j / (float) steps;
                // Pulled away from the straight line, most in the middle, new every flicker.
                float off = (Scene.hash(flick, j + 20, s.seed) - 0.5f) * 1.6f
                        * (float) Math.sin(Math.PI * f);
                s.blob(x0 + (x1 - x0) * f + nx * off, y0 + (y1 - y0) * f + ny * off, 0.45f,
                        colour, 0.7f + 0.3f * Scene.hash(flick, j, 4));
            }
        }
    }

    /**
     * Electrons race round the atom's three orbits ({@code anchor}: centre x, y, then each
     * orbit's long and short radius as fractions of the width), each trailing a little light.
     */
    static void atom(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null || s.anchor.length < 4) {
            return;
        }
        float cx = edgeX(r, s.anchor[0]);
        float cy = edgeY(r, s.anchor[1]);
        float w = r.maxX - r.minX + 1;
        float a = s.anchor[2] * w;
        float b = s.anchor[3] * w;
        float speed = 2.6f + 3 * s.boost;
        for (int k = 0; k < 3; k++) {
            double tilt = Math.toRadians(90 + k * 60);
            float ct = (float) Math.cos(tilt);
            float st = (float) Math.sin(tilt);
            for (int j = 0; j < 4; j++) {
                // The electron, then its fading trail.
                double ang = s.t * speed + k * 2.1 - j * 0.16;
                float ex = (float) Math.cos(ang) * a;
                float ey = (float) Math.sin(ang) * b;
                float x = cx + ex * ct - ey * st;
                float y = cy + ex * st + ey * ct;
                if (j == 0) {
                    s.glint(x, y, 1.2f, 0xFF3FE0FF, 1);
                } else {
                    s.blob(x, y, 0.7f, 0xFF3FE0FF, 0.6f * (1 - j / 4f));
                }
            }
        }
        s.busy = true;
    }

    /** Rings come in pairs, a beat apart, then a pause: true while one is sounding. */
    private static float ringing(Scene s) {
        float t = s.since < 1.2f ? s.since : s.t % 3.2f;
        return t < 0.45f || (t > 0.7f && t < 1.15f) ? 1 : 0;
    }

    /**
     * A ringing phone: the handset ({@code anchor}: the cut x0, y0, x1, y1; none for the whole
     * emoji) rattles on its cradle, ring, ring, then rests.
     */
    static void ringPose(Scene s) {
        Regions r = s.regions;
        if (ringing(s) == 0) {
            return;
        }
        float x0 = r.minX - 1, y0 = r.minY - 1, x1 = r.maxX + 2, y1 = r.maxY + 2;
        if (s.anchor != null && s.anchor.length >= 4) {
            x0 = edgeX(r, s.anchor[0]);
            y0 = edgeY(r, s.anchor[1]);
            x1 = edgeX(r, s.anchor[2]);
            y1 = edgeY(r, s.anchor[3]);
        }
        // The striker beats about twenty times a second: hop and rock.
        float buzz = (float) Math.sin(s.t * Math.PI * 2 * 18);
        s.addPart(x0, y0, x1, y1, (x0 + x1) / 2, y1, 2.5f * buzz, 0,
                -0.35f - 0.35f * Math.abs(buzz));
        s.busy = true;
    }

    /**
     * A service bell is struck: the plunger ({@code anchor}: the cut x0, y0, x1, y1) is pushed
     * down and springs back, and the dome shines as it rings.
     */
    static void dingPose(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null || s.anchor.length < 4) {
            return;
        }
        float t = s.since < 1f ? s.since : s.t % 2.6f;
        float press = t < 0.08f ? t / 0.08f : t < 0.5f ? 1 - spring((t - 0.08f) / 0.42f) : 0;
        if (press <= 0 && t > 0.5f) {
            return;
        }
        s.addPart(edgeX(r, s.anchor[0]), edgeY(r, s.anchor[1]), edgeX(r, s.anchor[2]),
                edgeY(r, s.anchor[3]), 0, 0, 0, 0, 1.2f * press);
        s.busy = true;
    }

    static void ding(Scene s) {
        float t = s.since < 1f ? s.since : s.t % 2.6f;
        if (t > 0.06f && t < 0.9f) {
            DotEffect.glow(s, 1 - (t - 0.06f) / 0.84f);
        }
    }

    /**
     * The emoji's bright pieces (signal bars, wi-fi arcs) light one after another from the
     * first (left, or bottom) to the last, hold, and start again, the rest dimmed meanwhile.
     */
    static void sequence(Scene s) {
        Regions r = s.regions;
        List<int[]> pieces = brightPieces(r);
        if (pieces.size() < 2) {
            return;
        }
        float step = 0.32f;
        float pos = (s.t / step) % (pieces.size() + 3);
        int[] which = pieceOf(r, pieces);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || which[ri] < 0) {
                continue;
            }
            float on = Math.max(0, Math.min(1, (pos - which[ri]) * 2));
            s.dots[i] = Scene.alpha(Scene.scale(s.dots[i], 0.3f + 0.7f * on), s.dots[i] >>> 24);
        }
    }

    /** Bright, pale pieces of the emoji, ordered from the bottom left up. */
    @SuppressWarnings("unchecked")
    private static List<int[]> brightPieces(Regions r) {
        Object cached = r.memo.get("brightPieces");
        if (cached != null) {
            return (List<int[]>) cached;
        }
        boolean[] mask = new boolean[r.size * r.size];
        for (int i = 0; i < mask.length; i++) {
            mask[i] = r.ink[i] && r.luma[i] > 0.7f && r.sat[i] < 0.3f;
        }
        List<int[]> out = new ArrayList<>();
        for (int[] c : MusicEffects.components(r, mask, false)) {
            if (c.length >= 2) {
                out.add(c);
            }
        }
        out.sort((a, b) -> Float.compare(MusicEffects.cx(r, a) - MusicEffects.cy(r, a),
                MusicEffects.cx(r, b) - MusicEffects.cy(r, b)));
        r.memo.put("brightPieces", out);
        return out;
    }

    private static int[] pieceOf(Regions r, List<int[]> pieces) {
        Object cached = r.memo.get("pieceOf");
        if (cached != null) {
            return (int[]) cached;
        }
        int[] out = new int[r.size * r.size];
        java.util.Arrays.fill(out, -1);
        for (int k = 0; k < pieces.size(); k++) {
            for (int i : pieces.get(k)) {
                out[i] = k;
            }
        }
        r.memo.put("pieceOf", out);
        return out;
    }

    /**
     * A vehicle seen from the front flashes its headlights now and then, twice, each throwing
     * a warm glow round it.
     */
    static void headlights(Scene s) {
        Regions r = s.regions;
        List<float[]> lamps = headlamps(r);
        if (s.anchor != null && s.anchor.length >= 4) {
            // Lamps that blend into the bodywork: where the emoji table puts them.
            lamps = new ArrayList<>();
            for (int k = 0; k + 1 < s.anchor.length; k += 2) {
                lamps.add(new float[] {edgeX(r, s.anchor[k]), edgeY(r, s.anchor[k + 1]), 1});
            }
        }
        if (lamps.isEmpty()) {
            return;
        }
        float period = 3.4f;
        float t = s.since < 0.6f ? s.since : (s.t + Scene.hash(s.seed, 6, 6) * period) % period;
        float k = t < 0.12f ? 1 : t > 0.22f && t < 0.34f ? 1 : 0;
        if (k == 0) {
            return;
        }
        s.busy = true;
        for (float[] l : lamps) {
            s.blob(l[0], l[1], l[2] + 0.6f, 0xFFFFFFFF, 0.95f);
            s.blob(l[0], l[1], l[2] + 3, 0xFFFFF2C8, 0.5f);
        }
    }

    /** Headlamps: amber, or else bright, pieces in the lower half, as a left-right pair. */
    @SuppressWarnings("unchecked")
    private static List<float[]> headlamps(Regions r) {
        Object cached = r.memo.get("headlamps");
        if (cached != null) {
            return (List<float[]>) cached;
        }
        List<float[]> out = new ArrayList<>();
        for (int pass = 0; pass < 2 && out.isEmpty(); pass++) {
            boolean[] mask = new boolean[r.size * r.size];
            float mid = r.minY + (r.maxY - r.minY) * 0.35f;
            for (int i = 0; i < mask.length; i++) {
                boolean amber = r.sat[i] > 0.5f && r.luma[i] > 0.4f && r.hue[i] > 18
                        && r.hue[i] < 50;
                mask[i] = r.ink[i] && i / r.size > mid && (pass == 0 ? amber : r.luma[i] > 0.7f);
            }
            List<float[]> pieces = new ArrayList<>();
            for (int[] c : MusicEffects.components(r, mask, true)) {
                if (c.length <= 16) {
                    pieces.add(new float[] {MusicEffects.cx(r, c), MusicEffects.cy(r, c),
                            (float) Math.sqrt(c.length / Math.PI)});
                }
            }
            // The pair farthest apart on about the same row.
            float best = (r.maxX - r.minX) * 0.3f;
            for (float[] a : pieces) {
                for (float[] b : pieces) {
                    float apart = b[0] - a[0];
                    if (apart > best && Math.abs(a[1] - b[1]) < 2) {
                        best = apart;
                        out.clear();
                        out.add(a);
                        out.add(b);
                    }
                }
            }
        }
        r.memo.put("headlamps", out);
        return out;
    }

    /**
     * A fader ({@code anchor}: the knob's box x0, y0, x1, y1, then how far it travels, as a
     * fraction of the height) is pushed up and pulled down, easing, as on a mixing desk.
     */
    static void faderPose(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null || s.anchor.length < 5) {
            return;
        }
        float h = r.maxY - r.minY + 1;
        float travel = s.anchor[4] * h;
        // Slow moves with rests between, like a hand riding the level.
        float phase = (s.t % 5.6f) / 5.6f;
        float level = phase < 0.3f ? ease(phase / 0.3f) : phase < 0.5f ? 1
                : phase < 0.85f ? 1 - ease((phase - 0.5f) / 0.35f) : 0;
        s.liftDark(edgeX(r, s.anchor[0]), edgeY(r, s.anchor[1]), edgeX(r, s.anchor[2]),
                edgeY(r, s.anchor[3]));
        s.addLayer(1, 0, 0, 0, 0, travel * level, 1);
        s.busy = phase < 0.85f && (phase < 0.3f || phase > 0.5f);
    }

    /**
     * A carousel horse rides up and down its pole ({@code anchor}: the pole's hue), the pole
     * standing still through it.
     */
    static void carouselPose(Scene s) {
        if (s.anchor == null || s.anchor.length < 1) {
            return;
        }
        s.liftHue(s.anchor[0]);
        s.boardDy = -1.6f * (float) Math.sin(s.t * Math.PI * 2 / 2.4f);
        s.addLayer(1, 0, 0, 0, 0, 0, 1);
        s.busy = true;
    }

    /** Wire globe: the meridians' spacing, and the parallels' latitudes, in degrees. */
    private static final float MERIDIANS = 60;
    private static final float[] PARALLELS = {-40, 0, 40};
    /** How bright the far side of the wire globe shows through, against the near side. */
    private static final float FAR_SIDE = 0.3f;

    /**
     * 🌐 turning ({@code anchor} 2): a see-through wire globe drawn afresh as a real sphere,
     * every line one dot fine: the rim, the parallels (straight, as seen from the equator)
     * and the meridians, which turn right round it, bright as they pass in front, dim as they
     * go round the back, so each one is followed all the way.
     */
    static void wireGlobe(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null || s.anchor.length < 1 || s.anchor[0] != 2) {
            return;
        }
        float cx = (r.minX + r.maxX + 1) / 2f;
        float cy = (r.minY + r.maxY + 1) / 2f;
        float radius = Math.max(r.maxX - r.minX + 1, r.maxY - r.minY + 1) / 2f;
        int color = r.brightest != 0 ? r.brightest | 0xFF000000 : 0xFF01B3F7;
        // Clear the drawing; the globe is drawn in its place.
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0) {
                continue;
            }
            float dx = ri % r.size + 0.5f - cx;
            float dy = ri / r.size + 0.5f - cy;
            if (dx * dx + dy * dy <= (radius + 1) * (radius + 1)) {
                s.dots[i] = 0;
            }
        }
        // The drawing works in dot indices (a dot's centre is half a dot in).
        float ox = cx - 0.5f;
        float oy = cy - 0.5f;
        float inner = radius - 0.8f;
        float fine = 0.75f;
        float turn = (float) Math.toDegrees(s.t * 0.5f + 2 * Math.PI
                * ease(Math.min(1, s.since / 1.6f)));
        // Meridians: the far side first, so the near side is drawn over it.
        for (int pass = 0; pass < 2; pass++) {
            for (float m = 0; m < 360; m += MERIDIANS) {
                double lon = Math.toRadians(m + turn);
                float facing = (float) Math.cos(lon);
                if ((facing >= 0) != (pass == 1)) {
                    continue;
                }
                // Brightening smoothly as it comes round the edge to the front.
                float k = FAR_SIDE + (1 - FAR_SIDE) * ease((facing + 0.25f) / 0.5f);
                float sx = (float) Math.sin(lon) * inner;
                float px = ox;
                float py = oy - inner;
                for (int j = 1; j <= 24; j++) {
                    double lat = Math.PI / 2 - Math.PI * j / 24;
                    float x = ox + sx * (float) Math.cos(lat);
                    float y = oy - (float) Math.sin(lat) * inner;
                    float len = (float) Math.hypot(x - px, y - py);
                    if (len > 0.01f) {
                        s.strip((x + px) / 2, (y + py) / 2, len + 0.1f, fine,
                                (float) Math.atan2(y - py, x - px), color, k);
                    }
                    px = x;
                    py = y;
                }
            }
        }
        // Parallels: on dot rows, one dot fine, from rim to rim.
        for (float lat : PARALLELS) {
            double l = Math.toRadians(lat);
            float y = Math.round(oy - (float) Math.sin(l) * inner);
            float half = (float) Math.sqrt(Math.max(0, inner * inner - (y - oy) * (y - oy)));
            s.strip(ox, y, half * 2, fine, 0, color, 1);
        }
        s.ring(ox, oy, inner, 1.0f, color, 1);
    }

    /** The toolbox's timeline: clasps flip open, the lid swings up, back down, clasps shut. */
    private static float toolboxTime(Scene s) {
        float period = 6f;
        return s.since < period ? s.since : (s.t + Scene.hash(s.seed, 9, 9) * period) % period;
    }

    /** How far the clasps are flipped down (0 shut, 1 hanging open). */
    private static float claspOpen(float t) {
        if (t < 0.8f) {
            return 0;
        }
        if (t < 1.35f) {
            return spring((t - 0.8f) / 0.45f);
        }
        if (t < 4.1f) {
            return 1;
        }
        // Snapped back up, quick, as a thumb pushes them home.
        return t < 4.35f ? 1 - easeIn((t - 4.1f) / 0.25f) : 0;
    }

    /** How far the lid is open (0 shut, 1 swung back), with a little settle. */
    private static float lidOpen(float t) {
        if (t < 1.4f) {
            return 0;
        }
        if (t < 1.95f) {
            return spring((t - 1.4f) / 0.55f);
        }
        if (t < 3.4f) {
            return 1;
        }
        if (t < 3.8f) {
            return 1 - easeIn((t - 3.4f) / 0.4f);
        }
        // Shut with a small bounce as it lands.
        return t < 3.96f ? 0.08f * (float) Math.sin(Math.PI * (t - 3.8f) / 0.16f) : 0;
    }

    /**
     * 🧰 ({@code anchor}: the lid's hinge row, the seam row, then each clasp's box x0, y0, x1,
     * y1): the clasps flip down on their hinges, the lid swings back (its face shortening
     * toward the hinge), showing the shadowed inside of the box; it drops shut with a small
     * bounce and the clasps snap back up.
     */
    static void toolboxPose(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null || s.anchor.length < 2) {
            return;
        }
        float t = toolboxTime(s);
        float clasp = claspOpen(t);
        float open = lidOpen(t);
        if (clasp <= 0 && open <= 0) {
            return;
        }
        s.busy = true;
        float hinge = edgeY(r, s.anchor[0]);
        float seam = edgeY(r, s.anchor[1]);
        // The clasps come off the drawing (by their colour); the box and lid are drawn without
        // them, and each clasp is drawn on its own, flipping about its lower hinge.
        s.liftHue(200);
        if (open > 0) {
            s.addPart(-1, -1, r.size + 1, seam, (r.minX + r.maxX + 1) / 2f, hinge, 0, 0, 0);
            s.swingLastPart((float) Math.cos(Math.toRadians(68 * open)));
        }
        for (int k = 2; k + 3 < s.anchor.length; k += 4) {
            float x0 = edgeX(r, s.anchor[k]);
            float y0 = edgeY(r, s.anchor[k + 1]);
            float x1 = edgeX(r, s.anchor[k + 2]);
            float y1 = edgeY(r, s.anchor[k + 3]);
            s.addLayerPiece(1, x0, y0, x1, y1, 0, 0);
            int p = s.partCount - 1;
            s.partPivot[p * 2] = (x0 + x1) / 2;
            s.partPivot[p * 2 + 1] = y1;
            // Down and over, past level, till it hangs from its hinge.
            s.partScaleY[p] = (float) Math.cos(Math.toRadians(165 * clasp));
        }
    }

    /** The shadowed inside of the open toolbox, and the clasps' glint as they snap shut. */
    static void toolbox(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null || s.anchor.length < 2) {
            return;
        }
        float t = toolboxTime(s);
        if (t > 4.3f && t < 4.7f) {
            // Clicked home: the clasps catch the light.
            float g = 1 - ease((t - 4.3f) / 0.4f);
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri >= 0 && r.ink[ri] && r.sat[ri] > 0.12f && r.hue[ri] > 170
                        && r.hue[ri] < 230) {
                    MusicEffects.lighten(s, i, 0.55f * g);
                }
            }
        }
        if (lidOpen(t) <= 0) {
            return;
        }
        float hinge = edgeY(r, s.anchor[0]);
        float seam = edgeY(r, s.anchor[1]);
        // The box's own colour, from its front just below the seam.
        int bx = Math.round((r.minX + r.maxX) / 2f);
        int by = Math.min(r.size - 1, Math.round(seam) + 3);
        int box = r.rest[by * r.size + bx] | 0xFF000000;
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri] || (s.dots[i] >>> 24) > 110) {
                continue;
            }
            int y = ri / r.size;
            if (y < hinge || y >= seam) {
                continue;
            }
            // The back wall, in shadow, deepening toward the floor of the box.
            float depth = (y - hinge) / Math.max(1, seam - hinge);
            s.dots[i] = Scene.alpha(Scene.scale(box, 0.42f - 0.24f * depth), r.rest[ri] >>> 24);
        }
    }

    /**
     * The knobs ({@code anchor}: x, y per knob, then their radius as a fraction of the width)
     * are turned by hand, one after another: each twists to a new setting, overshooting a
     * touch and settling, then rests while the next is set. Each knob turns as a disc of the
     * drawing, its pointer going round.
     */
    static void knobsPose(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null || s.anchor.length < 3) {
            return;
        }
        int knobs = (s.anchor.length - 1) / 2;
        float rad = s.anchor[s.anchor.length - 1] * (r.maxX - r.minX + 1);
        float step = 1.1f - 0.5f * s.boost;
        int now = (int) (s.t / step);
        for (int j = 0; j < knobs; j++) {
            // The last turn this knob was given, and the one before.
            int last = now - ((now - j) % knobs + knobs) % knobs;
            float since = s.t - last * step;
            float from = knobSetting(last - knobs, j, s.seed);
            float to = knobSetting(last, j, s.seed);
            float k = spring(since / 0.55f);
            s.busy |= since < 0.65f;
            s.addDisc(edgeX(r, s.anchor[j * 2]), edgeY(r, s.anchor[j * 2 + 1]), rad,
                    from + (to - from) * k);
        }
    }

    /** Where turn {@code n} leaves knob {@code j}, in degrees from as drawn. */
    private static float knobSetting(int n, int j, int seed) {
        float h = Scene.hash(n, j + 11, seed);
        return h < 0.2f ? 0 : -120 + 240 * Scene.hash(n, j + 23, seed);
    }

    // -------------------------------------------------------------------------------------
    // Desk: ⌨️ 🖱️ 🖨️
    // -------------------------------------------------------------------------------------

    /** Someone types: keys go down one after another in bursts, with pauses between words. */
    static void typing(Scene s) {
        Regions r = s.regions;
        float stepTime = 0.11f;
        int step = (int) (s.t / stepTime);
        if (Scene.hash(step / 7, 2, s.seed) < 0.3f && s.boost == 0) {
            return; // Thinking.
        }
        int keyX = r.minX + (int) (Scene.hash(step, 3, s.seed) * (r.maxX - r.minX - 1));
        int keyY = r.minY + (int) (Scene.hash(step, 4, s.seed) * (r.maxY - r.minY));
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri] || r.luma[ri] < 0.6f) {
                continue;
            }
            int x = ri % r.size;
            int y = ri / r.size;
            if ((x == keyX || x == keyX + 1) && Math.abs(y - keyY) <= 0) {
                s.dots[i] = Scene.alpha(Scene.scale(s.dots[i], 0.5f), s.dots[i] >>> 24);
            }
        }
    }

    /** A double click on the left button, and the scroll wheel turning now and then. */
    static void click(Scene s) {
        Regions r = s.regions;
        float period = 2.8f;
        float t = s.since < period ? s.since : s.t % period;
        boolean down = (t < 0.1f) || (t > 0.2f && t < 0.3f);
        float midX = (r.minX + r.maxX) / 2f;
        float buttons = r.minY + (r.maxY - r.minY) * 0.42f;
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri]) {
                continue;
            }
            int x = ri % r.size;
            int y = ri / r.size;
            if (down && x < midX - 0.5f && y < buttons && r.luma[ri] > 0.5f) {
                s.dots[i] = Scene.alpha(Scene.scale(s.dots[i], 0.72f), s.dots[i] >>> 24);
            } else if (r.luma[ri] < 0.5f && y < buttons && Math.abs(x - midX) < 1.5f
                    && t > 1.2f && t < 2f && (y + (int) (s.t * 14)) % 2 == 0) {
                MusicEffects.lighten(s, i, 0.5f);
            }
        }
    }

    /** Paper feeds out of the printer, lines of print moving down it as it comes. */
    static void print(Scene s) {
        Regions r = s.regions;
        float low = r.minY + (r.maxY - r.minY) * 0.62f;
        int feed = (int) (s.t * 5);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri] || ri / r.size < low || r.luma[ri] < 0.85f
                    || r.sat[ri] > 0.2f) {
                continue;
            }
            int x = ri % r.size;
            int line = ri / r.size - feed;
            if (Math.floorMod(line, 2) == 0 && Scene.hash(x / 2, line, s.seed) < 0.7f) {
                s.dots[i] = Scene.alpha(0xFF707070, s.dots[i] >>> 24);
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Messages, film, locks: 💬 🎬 🔒
    // -------------------------------------------------------------------------------------

    /**
     * Someone is typing, the way iMessage shows it: the bubble's three dots rest a soft grey;
     * one after another each rises a little and darkens, then settles back, in bursts with
     * pauses between. Each dot glides between rows by blending its light across the two.
     */
    static void typingDots(Scene s) {
        Regions r = s.regions;
        List<int[]> dots = bubbleDots(r);
        if (dots.isEmpty()) {
            return;
        }
        int bubble = bubbleColour(r);
        float burst = 2.6f;
        float t = s.since < burst ? s.since : s.t % burst;
        boolean typing = t < 1.6f;
        s.busy = typing;
        for (int k = 0; k < dots.size(); k++) {
            int[] comp = dots.get(k);
            int ink = r.rest[comp[0]] | 0xFF000000;
            float local = typing ? (t - k * 0.16f) % 0.8f : -1;
            float wave = local >= 0 && local < 0.45f
                    ? (float) Math.sin(local / 0.45f * Math.PI) : 0;
            float lift = 1.1f * wave * wave;
            // Resting grey, darkening to the drawn colour at the top of its rise.
            int colour = Scene.mix(Scene.mix(bubble, ink, 0.78f), Scene.scale(ink, 0.55f), wave);
            for (int i : comp) {
                s.paintRest(i % r.size, i / r.size, bubble);
            }
            for (int i : comp) {
                float y = i / r.size - lift;
                int row = (int) Math.floor(y);
                float frac = y - row;
                s.paintRest(i % r.size, row, Scene.alpha(colour, Math.round(255 * (1 - frac))));
                if (frac > 0.02f) {
                    s.paintRest(i % r.size, row + 1, Scene.alpha(colour, Math.round(255 * frac)));
                }
            }
        }
    }

    /** The bubble's own colour around its dots. */
    private static int bubbleColour(Regions r) {
        Object cached = r.memo.get("bubble");
        if (cached != null) {
            return (Integer) cached;
        }
        long sr = 0, sg = 0, sb = 0;
        int n = 0;
        for (int i = 0; i < r.size * r.size; i++) {
            if (r.ink[i] && r.luma[i] > 0.85f) {
                sr += (r.rest[i] >> 16) & 0xFF;
                sg += (r.rest[i] >> 8) & 0xFF;
                sb += r.rest[i] & 0xFF;
                n++;
            }
        }
        int c = n == 0 ? 0xFFFFFFFF
                : 0xFF000000 | (int) (sr / n) << 16 | (int) (sg / n) << 8 | (int) (sb / n);
        r.memo.put("bubble", c);
        return c;
    }

    /** The bubble's dots: small dark blobs inside it, left to right. */
    @SuppressWarnings("unchecked")
    private static List<int[]> bubbleDots(Regions r) {
        Object cached = r.memo.get("bubbleDots");
        if (cached != null) {
            return (List<int[]>) cached;
        }
        int n = r.size * r.size;
        boolean[] dark = new boolean[n];
        for (int i = 0; i < n; i++) {
            dark[i] = r.ink[i] && r.luma[i] < 0.6f && enclosedByLight(r, i);
        }
        List<int[]> out = new ArrayList<>();
        for (int[] c : MusicEffects.components(r, dark, false)) {
            if (c.length >= 2 && c.length <= 16) {
                out.add(c);
            }
        }
        out.sort((a, b) -> Float.compare(MusicEffects.cx(r, a), MusicEffects.cx(r, b)));
        if (out.size() > 4) {
            out = new ArrayList<>(out.subList(0, 0)); // Not a typing bubble.
        }
        r.memo.put("bubbleDots", out);
        return out;
    }

    /** Light ink two dots away on both sides: inside a pale bubble, not on its outline. */
    private static boolean enclosedByLight(Regions r, int i) {
        int x = i % r.size;
        int y = i / r.size;
        return light(r, x - 3, y) && light(r, x + 3, y) && light(r, x, y - 3)
                && light(r, x, y + 3);
    }

    private static boolean light(Regions r, int x, int y) {
        return r.in(x, y) && r.ink[y * r.size + x] && r.luma[y * r.size + x] > 0.75f;
    }

    /** {x0, y0, x1, y1} of a component. */
    private static int[] bounds(Regions r, int[] comp) {
        int x0 = r.size, y0 = r.size, x1 = -1, y1 = -1;
        for (int i : comp) {
            x0 = Math.min(x0, i % r.size);
            x1 = Math.max(x1, i % r.size);
            y0 = Math.min(y0, i / r.size);
            y1 = Math.max(y1, i / r.size);
        }
        return new int[] {x0, y0, x1, y1};
    }

    /**
     * "Action!": the clapstick ({@code anchor}: hinge x, y, then the cut x0, y0, x1, y1), drawn
     * open, snaps shut on its hinge, bounces once as it lands, rests, and lifts open again.
     */
    /** How far the clapstick turns to close, in degrees (it is drawn open about 11°). */
    private static final float CLAP_ANGLE = 11;

    static void clapperPose(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null || s.anchor.length < 6) {
            return;
        }
        float period = 3.4f;
        float t = s.since < period ? s.since : (s.t + Scene.hash(s.seed, 1, 1) * period) % period;
        float shut = CLAP_ANGLE;
        float angle;
        if (t < 0.13f) {
            angle = shut * easeIn(t / 0.13f);
        } else if (t < 0.45f) {
            float b = (t - 0.13f) / 0.32f;
            angle = shut - 3.5f * (float) Math.sin(b * Math.PI) * (1 - b);
        } else if (t < 0.95f) {
            angle = shut;
        } else if (t < 1.45f) {
            angle = shut * (1 - ease((t - 0.95f) / 0.5f));
        } else {
            return;
        }
        s.busy = true;
        s.addPart(edgeX(r, s.anchor[2]), edgeY(r, s.anchor[3]), edgeX(r, s.anchor[4]),
                edgeY(r, s.anchor[5]), edgeX(r, s.anchor[0]), edgeY(r, s.anchor[1]), angle, 0, 0);
    }

    /** The clap: a flash along the seam as the stick lands. */
    static void clapper(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null || s.anchor.length < 6) {
            return;
        }
        float period = 3.4f;
        float t = s.since < period ? s.since : (s.t + Scene.hash(s.seed, 1, 1) * period) % period;
        if (t < 0.13f || t > 0.45f) {
            return;
        }
        float k = 1 - (t - 0.13f) / 0.32f;
        float seam = edgeY(r, s.anchor[5]);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri >= 0 && r.ink[ri] && Math.abs(ri / r.size + 0.5f - seam) < 1.5f) {
                MusicEffects.lighten(s, i, 0.5f * k);
            }
        }
    }

    private static final float LOCK_POP = 1.2f;
    private static final float LOCK_SHUT = 2.9f;
    private static final float LOCK_HOME = 3.08f;
    private static final float LOCK_PERIOD = 4.6f;

    /**
     * The padlock opens and closes as a real one does ({@code anchor}: the shackle's hue in
     * degrees, the long leg's x0 and x1, where the legs end, and how far it lifts in dots). The
     * key turns, the shackle springs up and its free leg clears the body while the long leg
     * stays in it, held by the lock; then it is pushed home and snaps shut.
     */
    static void lockPose(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null || s.anchor.length < 5) {
            return;
        }
        float t = lockTime(s);
        float lift;
        if (t < LOCK_POP) {
            return;
        } else if (t < LOCK_SHUT) {
            // Sprung: up fast, a touch past, settling.
            lift = spring((t - LOCK_POP) / 0.45f);
        } else if (t < LOCK_HOME) {
            // Pushed home, quickening till it catches.
            lift = 1 - easeIn((t - LOCK_SHUT) / (LOCK_HOME - LOCK_SHUT));
        } else if (t < LOCK_HOME + 0.16f) {
            // It catches with a little rebound.
            lift = 0.1f * (float) Math.sin(Math.PI * (t - LOCK_HOME) / 0.16f);
        } else {
            return;
        }
        s.busy = true;
        float up = -s.anchor[4] * lift;
        s.liftHue(s.anchor[0]);
        s.addLayer(1, 0, 0, 0, 0, up, 1);
        // The long leg runs on down into the body: below the lifted shackle it is still there.
        if (up < 0) {
            float end = edgeY(r, s.anchor[3]);
            s.addLayerPiece(1, edgeX(r, s.anchor[1]), end + up - 0.5f, edgeX(r, s.anchor[2]),
                    end + 0.5f, 0, 0);
        }
    }

    /** The key turning in the keyhole before it opens, and its glint as it snaps shut. */
    static void lock(Scene s) {
        Regions r = s.regions;
        float t = lockTime(s);
        float k;
        if (t > LOCK_POP - 0.35f && t < LOCK_POP) {
            k = 0.45f * (float) Math.sin(Math.PI * (t - LOCK_POP + 0.35f) / 0.35f);
        } else if (t > LOCK_HOME && t < LOCK_HOME + 0.4f) {
            k = 0.6f * (1 - ease((t - LOCK_HOME) / 0.4f));
        } else {
            return;
        }
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri >= 0 && r.ink[ri] && r.luma[ri] < 0.4f) {
                MusicEffects.lighten(s, i, k);
            }
        }
    }

    private static float lockTime(Scene s) {
        return s.since < LOCK_PERIOD ? s.since
                : (s.t + Scene.hash(s.seed, 2, 2) * LOCK_PERIOD) % LOCK_PERIOD;
    }

    // -------------------------------------------------------------------------------------
    // Paper: 📖 📝 📅 📈 📊
    // -------------------------------------------------------------------------------------

    /**
     * A page turns: lifted at the right edge, it sweeps over the spine to the left, its face
     * catching the light and casting a shadow on the page beneath, then lies flat.
     */
    static void pageTurn(Scene s) {
        Regions r = s.regions;
        float period = 3.6f;
        float t = s.since < period ? s.since : (s.t + Scene.hash(s.seed, 3, 3) * period) % period;
        if (t > 0.8f) {
            return;
        }
        s.busy = true;
        float spine = (r.minX + r.maxX) / 2f + 0.5f;
        float half = (r.maxX - r.minX) / 2f;
        // The page's free edge: from the right edge, over the spine, to the left edge.
        float edge = r.maxX + 0.5f - ease(t / 0.8f) * (r.maxX - r.minX + 1);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri] || r.luma[ri] < 0.6f) {
                continue;
            }
            float x = ri % r.size + 0.5f;
            boolean onTurning = edge >= spine ? x >= spine && x <= edge : x <= spine && x >= edge;
            float fromEdge = Math.abs(x - edge);
            if (onTurning) {
                // The lifted page: brighter where it faces the light, darker towards the edge.
                // Curled near its free edge, so that side turns away from the light.
                float curl = Math.max(0, 1 - fromEdge / 4f);
                s.dots[i] = Scene.alpha(Scene.scale(s.dots[i], 1 - 0.42f * curl * curl),
                        s.dots[i] >>> 24);
            } else if (fromEdge < 3.5f) {
                // Its shadow on the page beneath, darkest right under the edge.
                float shade = 1 - fromEdge / 3.5f;
                s.dots[i] = Scene.alpha(Scene.scale(s.dots[i], 1 - 0.5f * shade),
                        s.dots[i] >>> 24);
            }
        }
    }

    /** Lines of handwriting appear across the paper one after another, then it starts over. */
    static void writing(Scene s) {
        Regions r = s.regions;
        int[] lines = paperLines(r);
        if (lines.length == 0) {
            return;
        }
        float perLine = 0.9f;
        float period = lines.length * perLine + 1.6f;
        float t = s.t % period;
        float fade = t > period - 0.5f ? (period - t) / 0.5f : 1;
        s.busy = t < lines.length * perLine;
        int[] span = paperSpan(r);
        for (int k = 0; k < lines.length; k++) {
            float p = Math.max(0, Math.min(1, (t - k * perLine) / perLine));
            if (p <= 0) {
                break;
            }
            float end = span[0] + p * (span[1] - span[0]);
            int y = lines[k];
            for (int x = span[0]; x <= span[1] && x <= end; x++) {
                int ri = y * r.size + x;
                // Words: short strokes with gaps between them.
                if (!r.ink[ri] || r.luma[ri] < 0.8f || Scene.hash(x / 3, k, s.seed) < 0.22f) {
                    continue;
                }
                s.paintRest(x, y, Scene.alpha(0xFF4F5B66, Math.round(235 * fade)));
            }
        }
    }

    /** Rows of the paper wide enough to write on, every third row. */
    private static int[] paperLines(Regions r) {
        Object cached = r.memo.get("paperLines");
        if (cached != null) {
            return (int[]) cached;
        }
        int[] span = paperSpan(r);
        List<Integer> rows = new ArrayList<>();
        int last = -10;
        for (int y = r.minY + 3; y <= r.maxY - 3; y++) {
            int paper = 0;
            for (int x = span[0]; x <= span[1]; x++) {
                int i = y * r.size + x;
                if (r.ink[i] && r.luma[i] > 0.85f && r.sat[i] < 0.2f) {
                    paper++;
                }
            }
            if (paper > (span[1] - span[0] + 1) * 0.6f && y - last >= 3) {
                rows.add(y);
                last = y;
            }
        }
        int[] out = new int[Math.min(rows.size(), 5)];
        for (int k = 0; k < out.length; k++) {
            out[k] = rows.get(k);
        }
        r.memo.put("paperLines", out);
        return out;
    }

    /** The left and right columns of the paper (the light area), inset a dot. */
    private static int[] paperSpan(Regions r) {
        Object cached = r.memo.get("paperSpan");
        if (cached != null) {
            return (int[]) cached;
        }
        int x0 = r.size, x1 = -1;
        for (int i = 0; i < r.size * r.size; i++) {
            if (r.ink[i] && r.luma[i] > 0.85f && r.sat[i] < 0.2f) {
                x0 = Math.min(x0, i % r.size);
                x1 = Math.max(x1, i % r.size);
            }
        }
        int[] out = x1 < 0 ? new int[] {1, 0} : new int[] {x0 + 2, x1 - 2};
        r.memo.put("paperSpan", out);
        return out;
    }

    /**
     * The date flips like a split-flap board: the top half of the page folds down over the
     * bottom, darkening as it turns away from the light, and the new page settles.
     */
    static void dateFlip(Scene s) {
        Regions r = s.regions;
        int[] page = pageRows(r);
        if (page == null) {
            return;
        }
        float period = 4f;
        float t = s.since < period ? s.since : (s.t + Scene.hash(s.seed, 4, 4) * period) % period;
        if (t > 0.6f) {
            return;
        }
        s.busy = true;
        float mid = (page[0] + page[1]) / 2f;
        float p = ease(t / 0.6f);
        // The flap's edge travels from the top of the page to the hinge in the middle, then on
        // down to the bottom as it lands.
        float edge = page[0] + p * (page[1] - page[0] + 1);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri] || r.luma[ri] < 0.5f) {
                continue;
            }
            int y = ri / r.size;
            if (y < page[0] || y > page[1]) {
                continue;
            }
            float d = y + 0.5f - edge;
            if (Math.abs(d) < 1) {
                MusicEffects.lighten(s, i, 0.5f * (1 - Math.abs(d))); // The flap's lit edge.
            } else if (d < 0 && y < mid) {
                // Behind the falling flap: the back of the next page, in its shadow.
                s.dots[i] = Scene.alpha(Scene.scale(s.dots[i], 0.55f), s.dots[i] >>> 24);
            } else if (d > 0 && d < 4) {
                // The flap's shadow ahead of it.
                s.dots[i] = Scene.alpha(Scene.scale(s.dots[i], 0.5f + 0.125f * d),
                        s.dots[i] >>> 24);
            }
        }
    }

    /** The calendar page: the rows of light paper below the coloured header. */
    private static int[] pageRows(Regions r) {
        if (r.memo.containsKey("pageRows")) {
            return (int[]) r.memo.get("pageRows");
        }
        int y0 = -1, y1 = -1;
        for (int y = r.minY; y <= r.maxY; y++) {
            int light = 0, width = 0;
            for (int x = r.minX; x <= r.maxX; x++) {
                int i = y * r.size + x;
                if (r.ink[i]) {
                    width++;
                    if (r.luma[i] > 0.75f && r.sat[i] < 0.2f || r.luma[i] < 0.3f) {
                        light++;
                    }
                }
            }
            if (width > 0 && light > width * 0.6f && y > r.minY + 3) {
                if (y0 < 0) {
                    y0 = y;
                }
                y1 = y;
            }
        }
        int[] out = y0 >= 0 && y1 - y0 >= 4 ? new int[] {y0, y1} : null;
        r.memo.put("pageRows", out);
        return out;
    }

    /**
     * The line chart draws itself left to right, its tip glowing as it climbs, holds, and
     * fades back to start over.
     */
    static void chartLine(Scene s) {
        Regions r = s.regions;
        float period = 4.2f;
        float t = s.t % period;
        float draw = 1.5f;
        float p = ease(Math.min(1, t / draw));
        float fade = t > period - 0.45f ? (period - t) / 0.45f : 1;
        s.busy = t < draw;
        float tip = r.minX + p * (r.maxX - r.minX + 1);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            // The line and its soft edge: any dot tinted with the line's own hue.
            if (ri < 0 || !r.ink[ri] || r.sat[ri] < 0.04f
                    || hueGap(r.hue[ri], lineHue(r)) > 32) {
                continue;
            }
            float x = ri % r.size + 0.5f;
            if (x > tip + 0.5f) {
                s.dots[i] = Scene.alpha(paperUnder(r, ri), s.dots[i] >>> 24);
            } else if (tip - x < 1.5f && p < 1) {
                MusicEffects.lighten(s, i, 0.6f);
            } else if (fade < 1) {
                s.dots[i] = Scene.alpha(Scene.mix(paperUnder(r, ri), s.dots[i], fade),
                        s.dots[i] >>> 24);
            }
        }
    }

    /** The hue of a chart's line: that of its most saturated dots. */
    private static float lineHue(Regions r) {
        Object cached = r.memo.get("lineHue");
        if (cached != null) {
            return (Float) cached;
        }
        float best = -1, hue = 0;
        for (int i = 0; i < r.size * r.size; i++) {
            if (r.ink[i] && r.sat[i] > best) {
                best = r.sat[i];
                hue = r.hue[i];
            }
        }
        r.memo.put("lineHue", hue);
        return hue;
    }

    private static float hueGap(float a, float b) {
        float d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    /** The chart's paper: its light, untinted colour, measured once. */
    private static int paperUnder(Regions r, int ri) {
        Object cached = r.memo.get("paper");
        if (cached != null) {
            return (Integer) cached;
        }
        long sr = 0, sg = 0, sb = 0;
        int n = 0;
        for (int i = 0; i < r.size * r.size; i++) {
            if (r.ink[i] && r.luma[i] > 0.8f && r.sat[i] < 0.05f) {
                sr += (r.rest[i] >> 16) & 0xFF;
                sg += (r.rest[i] >> 8) & 0xFF;
                sb += r.rest[i] & 0xFF;
                n++;
            }
        }
        int c = n == 0 ? 0xFFF1F1F1
                : 0xFF000000 | (int) (sr / n) << 16 | (int) (sg / n) << 8 | (int) (sb / n);
        r.memo.put("paper", c);
        return c;
    }

    /** The bars dip and spring back up one after another, like figures updating live. */
    static void chartBars(Scene s) {
        Regions r = s.regions;
        List<int[]> bars = bars(r);
        if (bars.isEmpty()) {
            return;
        }
        float period = 4f;
        float t = s.since < period ? s.since : s.t % period;
        if (t > 1.4f) {
            return;
        }
        s.busy = true;
        for (int k = 0; k < bars.size(); k++) {
            int[] b = bars.get(k);
            float local = t - k * 0.13f;
            float h;
            if (local < 0) {
                h = 1;
            } else if (local < 0.22f) {
                h = 1 - 0.65f * easeOut(local / 0.22f);
            } else {
                h = 0.35f + 0.65f * spring((local - 0.22f) / 0.7f);
            }
            float top = b[3] + 1 - h * (b[3] - b[1] + 1);
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri < 0 || barOf(r)[ri] != k) {
                    continue;
                }
                float y = ri / r.size;
                if (y + 1 <= top) {
                    s.dots[i] = Scene.alpha(paperUnder(r, ri), s.dots[i] >>> 24);
                } else if (y < top) {
                    float part = y + 1 - top; // The top dot dims while it fills.
                    s.dots[i] = Scene.alpha(Scene.mix(paperUnder(r, ri), s.dots[i], part),
                            s.dots[i] >>> 24);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<int[]> bars(Regions r) {
        Object cached = r.memo.get("bars");
        if (cached != null) {
            return (List<int[]>) cached;
        }
        int n = r.size * r.size;
        boolean[] coloured = new boolean[n];
        for (int i = 0; i < n; i++) {
            coloured[i] = r.ink[i] && r.sat[i] > 0.45f && r.luma[i] > 0.15f;
        }
        List<int[]> out = new ArrayList<>();
        int[] owner = new int[n];
        Arrays.fill(owner, -1);
        List<int[]> comps = MusicEffects.components(r, coloured, false);
        comps.sort((a, b) -> Float.compare(MusicEffects.cx(r, a), MusicEffects.cx(r, b)));
        for (int[] c : comps) {
            int[] b = bounds(r, c);
            if (c.length >= 4 && b[3] - b[1] >= 2) {
                for (int i : c) {
                    owner[i] = out.size();
                }
                out.add(b);
            }
        }
        // Each bar's soft edge (faint dots of its colour beside it) goes with it.
        int[] grown = owner.clone();
        for (int i = 0; i < n; i++) {
            if (owner[i] >= 0 || !r.ink[i] || r.sat[i] < 0.05f) {
                continue;
            }
            int x = i % r.size;
            int y = i / r.size;
            for (int dy = -1; dy <= 1 && grown[i] < 0; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (!r.in(x + dx, y + dy)) {
                        continue;
                    }
                    int j = (y + dy) * r.size + x + dx;
                    if (owner[j] >= 0 && hueGap(r.hue[i], r.hue[j]) < 35) {
                        grown[i] = owner[j];
                        break;
                    }
                }
            }
        }
        r.memo.put("bars", out);
        r.memo.put("barOf", grown);
        return out;
    }

    private static int[] barOf(Regions r) {
        return (int[]) r.memo.get("barOf");
    }

    // -------------------------------------------------------------------------------------
    // Shared moves
    // -------------------------------------------------------------------------------------

    /**
     * Turns part of the drawing by {@code angle} (radians) about (cx, cy), within
     * {@code radius}: with a {@code mask}, only those dots move, and where they left the
     * {@code fill} colour shows; without one, everything in the circle turns.
     */
    static void rotate(Scene s, float cx, float cy, float radius, float angle, boolean[] mask,
            int fill) {
        Regions r = s.regions;
        float c = (float) Math.cos(angle);
        float sn = (float) Math.sin(angle);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0) {
                continue;
            }
            float dx = ri % r.size - cx;
            float dy = ri / r.size - cy;
            if (dx * dx + dy * dy > radius * radius) {
                continue;
            }
            int sx = Math.round(cx + dx * c + dy * sn);
            int sy = Math.round(cy - dx * sn + dy * c);
            int src = r.in(sx, sy) ? sy * r.size + sx : -1;
            if (mask == null) {
                s.dots[i] = src >= 0 ? r.rest[src] : 0;
            } else if (src >= 0 && mask[src]) {
                s.dots[i] = r.rest[src];
            } else if (mask[ri]) {
                s.dots[i] = fill;
            }
        }
    }

    /** Light spokes sweeping round a wheel at (cx, cy): {@code count} of them at {@code angle}. */
    static void spokes(Scene s, float cx, float cy, float radius, float angle, int count,
            float strength) {
        Regions r = s.regions;
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri]) {
                continue;
            }
            float dx = ri % r.size - cx;
            float dy = ri / r.size - cy;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d > radius || d < radius * 0.2f) {
                continue;
            }
            float v = (float) Math.cos(count * (Math.atan2(dy, dx) - angle));
            if (v > 0.7f) {
                MusicEffects.lighten(s, i, strength * (v - 0.7f) / 0.3f);
            }
        }
    }

    static float easeIn(float x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * x;
    }

    static float easeOut(float x) {
        x = Math.max(0, Math.min(1, 1 - x));
        return 1 - x * x * x;
    }

    /** Ease in and out (smoothstep). */
    static float ease(float x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    /** A critically damped spring from 0 to 1 with a touch of overshoot, like iOS motion. */
    static float spring(float x) {
        x = Math.max(0, Math.min(1, x));
        double w = 8;
        return (float) (1 - (1 + w * x * 0.8) * Math.exp(-w * x));
    }
}
