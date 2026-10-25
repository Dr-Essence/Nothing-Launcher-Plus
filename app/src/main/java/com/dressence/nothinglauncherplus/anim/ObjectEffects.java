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

    /**
     * The stick tilts on its base ({@code anchor}: pivot x, y), springing from one direction to
     * the next and back to centre, and the fire button flashes as it is played.
     */
    static void joystick(Scene s) {
        Regions r = s.regions;
        float px = MusicEffects.ax(s, 0);
        float py = MusicEffects.ay(s, 1);
        boolean[] stick = above(r, py);
        float step = 0.55f;
        int seg = (int) (s.t / step);
        float local = (s.t % step) / step;
        float from = tilt(seg - 1, s.seed);
        float to = tilt(seg, s.seed);
        float angle = from + (to - from) * spring(local);
        rotate(s, px, py, r.size, angle, stick, 0);
        boolean fire = Scene.hash(seg, 3, s.seed) > 0.55f || s.boost > 0.3f;
        if (fire && local < 0.5f) {
            float k = 0.6f * (1 - local / 0.5f);
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri >= 0 && !stick[ri] && r.ink[ri] && r.sat[ri] > 0.5f
                        && (r.hue[ri] < 20 || r.hue[ri] > 330)) {
                    MusicEffects.lighten(s, i, k);
                }
            }
        }
    }

    /** Where the stick points in step {@code seg}: often centred, else left or right. */
    private static float tilt(int seg, int seed) {
        float h = Scene.hash(seg, 1, seed);
        return h < 0.3f ? 0 : h < 0.65f ? -0.42f * (0.6f + h) : 0.42f * (0.2f + h);
    }

    /** The emoji's dots above row {@code y} (a stick on its base), once per emoji. */
    private static boolean[] above(Regions r, float y) {
        Object cached = r.memo.get("above");
        if (cached != null) {
            return (boolean[]) cached;
        }
        boolean[] out = new boolean[r.size * r.size];
        for (int i = 0; i < out.length; i++) {
            out[i] = r.ink[i] && i / r.size < y;
        }
        r.memo.put("above", out);
        return out;
    }

    // -------------------------------------------------------------------------------------
    // Cameras: 📷 📸 📹 🎥 📽️
    // -------------------------------------------------------------------------------------

    /**
     * A photo is taken: the iris closes across the lens ({@code anchor}: centre x, y, radius
     * as a fraction of the width) and opens again, and the flash ({@code anchor} 3, 4) fires
     * with a burst of light over the whole camera. A touch takes a photo at once.
     */
    static void camera(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null || s.anchor.length < 5) {
            return;
        }
        float period = 3.6f + Scene.hash(s.seed, 2, 2);
        float t = (s.t + Scene.hash(s.seed, 3, 3) * period) % period;
        if (s.since < 1.2f) {
            t = s.since;
        }
        float lx = MusicEffects.ax(s, 0);
        float ly = MusicEffects.ay(s, 1);
        float lr = s.anchor[2] * (r.maxX - r.minX);
        // The iris: closed share of the lens, shutting fast and opening a little slower.
        float shut = t < 0.18f ? ease(t / 0.18f) : t < 0.3f ? 1
                : t < 0.55f ? 1 - ease((t - 0.3f) / 0.25f) : 0;
        float flash = t >= 0.24f ? (float) Math.exp(-(t - 0.24f) * 9) : 0;
        float open = lr * (1 - shut);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri]) {
                continue;
            }
            float d = (float) Math.hypot(ri % r.size - lx, ri / r.size - ly);
            if (shut > 0 && d <= lr && d > open) {
                s.dots[i] = Scene.alpha(0xFF141414, s.dots[i] >>> 24);
            } else if (flash > 0.02f) {
                MusicEffects.lighten(s, i, 0.4f * flash);
            }
        }
        if (flash > 0.02f) {
            float fx = MusicEffects.ax(s, 3);
            float fy = MusicEffects.ay(s, 4);
            int a = Math.round(255 * Math.min(1, flash * 1.3f));
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    float fall = 1 - (float) Math.hypot(dx, dy) / 3f;
                    if (fall > 0) {
                        s.paintRest(fx + dx, fy + dy, Scene.alpha(WHITE, Math.round(a * fall)));
                    }
                }
            }
        }
    }

    /** Recording: the red REC light ({@code anchor}: x, y) blinks once a second. */
    static void record(Scene s) {
        float on = 0.5f + 0.5f * (float) Math.cos(s.t * Math.PI * 2 * 0.9);
        on = on * on;
        if (s.boost > 0) {
            on = Math.max(on, s.boost);
        }
        float x = MusicEffects.ax(s, 0);
        float y = MusicEffects.ay(s, 1);
        int c = Scene.alpha(0xFFFF3B30, Math.round(255 * (0.15f + 0.85f * on)));
        s.paintRest(x, y, c);
        s.paintRest(x + 1, y, c);
        s.paintRest(x, y + 1, c);
        s.paintRest(x + 1, y + 1, c);
    }

    /** Film reels ({@code anchor}: x, y, radius as a fraction of the width, per reel) turn. */
    static void reels(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null) {
            return;
        }
        float speed = 2.6f + 2 * s.boost;
        for (int k = 0; k + 2 < s.anchor.length; k += 3) {
            float x = MusicEffects.ax(s, k);
            float y = MusicEffects.ay(s, k + 1);
            float rad = s.anchor[k + 2] * (r.maxX - r.minX);
            spokes(s, x, y, rad, s.t * speed, 3, 0.85f);
        }
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
        r.memo.put("lamps", out);
        return out;
    }

    // -------------------------------------------------------------------------------------
    // Hourglass: ⏳ ⌛
    // -------------------------------------------------------------------------------------

    /**
     * Sand drains from the top bulb through the neck into a growing pile, a thin stream
     * falling between; then the glass is turned and it starts again.
     */
    static void hourglass(Scene s) {
        Regions r = s.regions;
        Sand sand = sand(r);
        if (sand == null) {
            return;
        }
        float drain = 6f;
        float cycle = drain + 1.2f;
        float t = s.t % cycle;
        // Start full on top (as the drawing, or as a turned glass when it shows none).
        int top0 = sand.top > 0 ? sand.top : Math.round(sand.bottom * 0.85f);
        int bottom0 = sand.top > 0 ? sand.bottom : sand.bottom - top0;
        float p = Math.min(1, t / drain);
        int moved = Math.round(top0 * p);
        int top = top0 - moved;
        int bottom = bottom0 + moved;
        // The turn: a soft fade back to the drawing over the last moments of the cycle, and
        // from it at the start, so the loop never jumps.
        float back = t > drain + 0.6f ? (t - drain - 0.6f) / 0.6f : t < 0.4f ? 1 - t / 0.4f : 0;
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || sand.rank[ri] == Integer.MIN_VALUE) {
                continue;
            }
            int rank = sand.rank[ri];
            boolean full = rank >= 0 ? rank < top : -rank - 1 < bottom;
            boolean stream = p < 1 && ri % r.size == sand.neckX && ri / r.size > sand.neckY
                    && rank < 0 && -rank - 1 >= bottom
                    && ((ri / r.size) + (int) (s.t * 18)) % 2 == 0;
            int target = full || stream ? sand.sandColor : sand.glassColor;
            target = Scene.alpha(target, r.rest[ri] >>> 24);
            s.dots[i] = back > 0 ? Scene.mix(target, r.rest[ri], back) : target;
        }
    }

    /** Sand in an hourglass: the order dots fill in each bulb, and its colours. */
    static final class Sand {
        /** Top bulb: rank 0.. (lowest first); bottom: -1, -2, … (lowest first); else MIN. */
        int[] rank;
        int top;
        int bottom;
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
    // Compass, dice, slot machine: 🧭 🎲 🎰
    // -------------------------------------------------------------------------------------

    /** The needle swings to a new heading and settles with a damped wobble, like a real one. */
    static void compass(Scene s) {
        Regions r = s.regions;
        Needle needle = needle(r);
        if (needle == null) {
            return;
        }
        float period = 4.5f;
        float t = s.since < period ? s.since : s.t % period;
        float amp = (Scene.hash((int) (s.t / period), 6, s.seed) - 0.5f) * 1.6f;
        if (s.since < period) {
            amp = 0.9f;
        }
        float angle = amp * (float) (Math.exp(-t * 1.6f) * Math.sin(t * 7.5f));
        rotate(s, needle.x, needle.y, needle.radius, angle, needle.mask, needle.face);
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

    /** The die is rolled: its pips flicker through other faces for a moment, then land. */
    static void dice(Scene s) {
        Regions r = s.regions;
        float period = 3.2f;
        float t = s.since < period ? s.since : s.t % period;
        if (t > 0.55f) {
            return;
        }
        boolean[] pips = pips(r);
        int face = tubeColour(r);
        int frame = (int) (s.t * 12);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri >= 0 && pips[ri] && Scene.hash(ri / 3, frame, s.seed) < 0.5f) {
                s.dots[i] = Scene.alpha(face, s.dots[i] >>> 24);
            }
        }
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
            if (c.length >= 4 && c.length <= 140) {
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

    /** A ferris wheel's lights chase round the rim ({@code anchor}: hub x, y). */
    static void chase(Scene s) {
        Regions r = s.regions;
        float cx = MusicEffects.ax(s, 0);
        float cy = MusicEffects.ay(s, 1);
        float turn = s.t * (1.4f + 2 * s.boost);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri] || r.sat[ri] < 0.3f) {
                continue;
            }
            float a = (float) Math.atan2(ri / r.size - cy, ri % r.size - cx);
            float v = (float) Math.cos(4 * (a - turn));
            if (v > 0.45f) {
                MusicEffects.lighten(s, i, 0.75f * (v - 0.45f) / 0.55f);
            } else {
                s.dots[i] = Scene.alpha(Scene.scale(s.dots[i], 0.5f), s.dots[i] >>> 24);
            }
        }
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
     * The globe turns: each row of the sphere is read at a turning longitude, so continents
     * travel across the face, squeezed at the edges as on a real ball.
     */
    static void globe(Scene s) {
        Regions r = s.regions;
        float cx = (r.minX + r.maxX) / 2f;
        float cy = (r.minY + r.maxY) / 2f;
        float radius = Math.max(r.maxX - r.minX, r.maxY - r.minY) / 2f + 0.5f;
        float turn = s.t * (0.32f + 0.6f * s.boost);
        float half = (float) (Math.PI / 2);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri]) {
                continue;
            }
            float dy = ri / r.size - cy;
            float chord = (float) Math.sqrt(Math.max(0, radius * radius - dy * dy)) - 1.2f;
            if (chord < 1) {
                continue;
            }
            float u = (ri % r.size - cx) / chord;
            if (u <= -1 || u >= 1) {
                continue; // The rim stays put.
            }
            float lon = (float) Math.asin(u) - turn;
            // The front half of the ball repeats round the back, so the turn never runs out.
            lon = (float) ((((lon + half) % Math.PI) + Math.PI) % Math.PI) - half;
            int sx = Math.round(cx + (float) Math.sin(lon) * chord);
            int src = ri - ri % r.size + Math.max(0, Math.min(r.size - 1, sx));
            if (r.ink[src]) {
                s.dots[i] = Scene.alpha(r.rest[src], s.dots[i] >>> 24);
            }
        }
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
