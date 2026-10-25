package com.dressence.nothinglauncherplus.anim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Effects for things that make sound, each following its own logic: a speaker lights its own
 * sound waves inner to outer, a piano presses its keys to a melody, a drum's head ripples when
 * hit, guitar strings vibrate after a strum, notes come out of a trumpet's bell, headphones
 * show an equaliser between the cups. All play to one shared tempo, so every music folder in the
 * drawer grooves in time with the others.
 *
 * <p>Where the drawing alone doesn't say where something is (a horn's mouth, a guitar's bridge
 * and nut), the effect takes {@link Scene#anchor} points from the emoji table, as fractions of
 * the emoji's bounds.
 */
final class MusicEffects {

    /** Seconds per beat: 96 BPM. */
    static final float BEAT = 60f / 96;
    private static final int WHITE = 0xFFFFFFFF;
    /** Nothing's red, for equaliser peaks. */
    private static final int RED = 0xFFD71921;

    private MusicEffects() {
    }

    // -------------------------------------------------------------------------------------
    // Timing
    // -------------------------------------------------------------------------------------

    static int beat(Scene s) {
        return (int) (s.t / BEAT);
    }

    /** 0 on the beat, rising to 1 just before the next. */
    static float phase(Scene s) {
        return (s.t % BEAT) / BEAT;
    }

    /** A hit's decay: 1 on the beat, fading fast (a reaction keeps it up). */
    static float kick(Scene s) {
        return Math.min(1, (float) Math.exp(-phase(s) * 5) + 0.4f * s.boost);
    }

    // -------------------------------------------------------------------------------------
    // Speakers: 🔊 🔉 🔈 🗣️
    // -------------------------------------------------------------------------------------

    /**
     * The speaker's own drawn sound waves light up one after another on every beat, inner to
     * outer, and dim back, like a volume meter; the cone thumps. A speaker drawn without waves
     * (🔈) sends out a small one from its mouth ({@code anchor}: x, y, direction in degrees).
     */
    static void speaker(Scene s) {
        Regions r = s.regions;
        Arcs arcs = arcs(r);
        float p = s.t % BEAT;
        float k = kick(s);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri]) {
                continue;
            }
            int arc = arcs.rank[ri];
            if (arc >= 0) {
                float since = p - arc * 0.09f;
                float lit = since < 0 ? 0 : (float) Math.exp(-since * 6);
                lit = Math.max(lit, s.boost);
                int c = Scene.scale(s.dots[i], 0.45f + 0.55f * lit);
                s.dots[i] = Scene.alpha(Scene.mix(c, WHITE, 0.45f * lit), s.dots[i] >>> 24);
            } else if (r.luma[ri] < 0.45f) {
                lighten(s, i, 0.22f * k);
            }
        }
        if (arcs.count == 0 && s.anchor != null && s.anchor.length >= 3) {
            float life = phase(s);
            float x = ax(s, 0);
            float y = ay(s, 1);
            double dir = Math.toRadians(s.anchor[2]);
            float rad = 1 + life * 4;
            int c = Scene.alpha(WHITE, Math.round(230 * (1 - life)));
            for (float a = -0.8f; a <= 0.8f; a += 0.2f) {
                s.plotRest(x + (float) Math.cos(dir + a) * rad, y + (float) Math.sin(dir + a) * rad,
                        c);
            }
        }
    }

    /** Separate thin pieces beside the main body: drawn sound waves, ranked inner to outer. */
    static final class Arcs {
        int[] rank;
        int count;
    }

    private static Arcs arcs(Regions r) {
        Object cached = r.memo.get("arcs");
        if (cached != null) {
            return (Arcs) cached;
        }
        Arcs arcs = new Arcs();
        arcs.rank = new int[r.size * r.size];
        Arrays.fill(arcs.rank, -1);
        List<int[]> comps = components(r, r.ink, true);
        int[] body = null;
        for (int[] c : comps) {
            if (body == null || c.length > body.length) {
                body = c;
            }
        }
        if (body != null) {
            float bx = cx(r, body);
            float by = cy(r, body);
            List<float[]> found = new ArrayList<>();
            for (int k = 0; k < comps.size(); k++) {
                int[] c = comps.get(k);
                if (c != body && c.length >= 4) {
                    found.add(new float[] {(float) Math.hypot(cx(r, c) - bx, cy(r, c) - by), k});
                }
            }
            found.sort((a, b) -> Float.compare(a[0], b[0]));
            for (int n = 0; n < found.size(); n++) {
                for (int i : comps.get((int) found.get(n)[1])) {
                    arcs.rank[i] = n;
                }
            }
            arcs.count = found.size();
        }
        r.memo.put("arcs", arcs);
        return arcs;
    }

    // -------------------------------------------------------------------------------------
    // Muted: 🔇 🔕
    // -------------------------------------------------------------------------------------

    /** Silenced: on the beat the red "no" sign flares, holding the sound back. */
    static void muted(Scene s) {
        Regions r = s.regions;
        float k = beat(s) % 2 == 0 ? kick(s) : 0.4f * s.boost;
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri >= 0 && r.ink[ri] && r.sat[ri] > 0.5f
                    && (r.hue[ri] < 15 || r.hue[ri] > 340)) {
                s.dots[i] = Scene.alpha(Scene.mix(s.dots[i], 0xFFFF8A80, 0.6f * k),
                        s.dots[i] >>> 24);
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Rings from a point: bells, megaphones, antennas
    // -------------------------------------------------------------------------------------

    /**
     * A bell rings ding-ding (two strikes, two beats of rest): each strike sends a ring of
     * light through the bell from where it is struck ({@code anchor}: x, y).
     */
    static void bell(Scene s) {
        int b = beat(s) % 4;
        // Strikes on beats 1 and 2; after that the second ring fades out.
        float since = (b < 2 || s.boost > 0 ? phase(s) : b - 1 + phase(s)) * BEAT;
        rings(s, ax(s, 0), ay(s, 1), since, 0.9f);
    }

    /**
     * A megaphone speaks: syllables, each a wave travelling from the narrow end
     * ({@code anchor}: x, y) out through the horn to its mouth.
     */
    static void horn(Scene s) {
        float half = BEAT / 2;
        int slot = (int) (s.t / half);
        float since = s.t % half;
        boolean voiced = Scene.hash(slot, 7, s.seed) > 0.3f || s.boost > 0;
        if (voiced) {
            rings(s, ax(s, 0), ay(s, 1), since, 0.8f);
        }
    }

    /** An antenna or dish: signal pulses leaving its tip ({@code anchor}: x, y). */
    static void signal(Scene s) {
        rings(s, ax(s, 0), ay(s, 1), (s.t % (BEAT * 2)) * 0.6f, 0.75f);
    }

    /** A ring of light expanding from (x, y), {@code since} seconds after it set off. */
    private static void rings(Scene s, float x, float y, float since, float strength) {
        Regions r = s.regions;
        float rad = since * 34;
        float k = strength * Math.max(0, 1 - since / 0.9f);
        if (k <= 0) {
            return;
        }
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri]) {
                continue;
            }
            float d = (float) Math.hypot(ri % r.size - x, ri / r.size - y);
            float edge = 1 - Math.abs(d - rad) / 2f;
            if (edge > 0) {
                lighten(s, i, k * edge);
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Notes and the score: 🎵 🎶 🎼
    // -------------------------------------------------------------------------------------

    /**
     * Notes play themselves: on every beat the next note head lights up in a colour of its
     * own and sends out a little burst, as if that note just sounded.
     */
    static void notes(Scene s) {
        Regions r = s.regions;
        List<int[]> heads = heads(r);
        if (heads.isEmpty()) {
            return;
        }
        int b = beat(s);
        int head = b % heads.size();
        float k = kick(s);
        int color = Scene.hsv(Scene.hash(s.seed, 4, 4) * 360 + b * 47, 0.55f, 1);
        int[] headOf = headOf(r, heads);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri >= 0 && headOf[ri] == head) {
                s.dots[i] = Scene.alpha(Scene.mix(s.dots[i], color, 0.85f * k), s.dots[i] >>> 24);
            } else if (ri >= 0 && r.ink[ri]) {
                lighten(s, i, 0.08f * k);
            }
        }
        // The burst: a ring of dots leaving the head.
        int[] h = heads.get(head);
        float x = cx(r, h);
        float y = cy(r, h);
        float life = phase(s);
        float rad = 2.5f + life * 3;
        int c = Scene.alpha(color, Math.round(220 * (1 - life)));
        for (int a = 0; a < 8; a++) {
            double ang = a * Math.PI / 4 + 0.39;
            s.plotRest(x + (float) Math.cos(ang) * rad, y + (float) Math.sin(ang) * rad, c);
        }
    }

    /** For each dot, which note head it belongs to (-1 for none); computed once. */
    private static int[] headOf(Regions r, List<int[]> heads) {
        int[] cached = (int[]) r.memo.get("headOf");
        if (cached != null) {
            return cached;
        }
        int[] out = new int[r.size * r.size];
        Arrays.fill(out, -1);
        for (int h = 0; h < heads.size(); h++) {
            for (int i : heads.get(h)) {
                out[i] = h;
            }
        }
        r.memo.put("headOf", out);
        return out;
    }

    /** Note heads: the round, wider parts in the lower two thirds (not stems, not beams). */
    private static List<int[]> heads(Regions r) {
        @SuppressWarnings("unchecked")
        List<int[]> cached = (List<int[]>) r.memo.get("heads");
        if (cached != null) {
            return cached;
        }
        boolean[] wide = new boolean[r.size * r.size];
        float top = r.minY + (r.maxY - r.minY) * 0.35f;
        for (int y = 0; y < r.size; y++) {
            for (int x = 0; x < r.size; x++) {
                int i = y * r.size + x;
                if (y < top || !r.ink[i]) {
                    continue;
                }
                int run = 1;
                for (int d = 1; x - d >= 0 && r.ink[i - d] && run < 3; d++) {
                    run++;
                }
                for (int d = 1; x + d < r.size && r.ink[i + d] && run < 3; d++) {
                    run++;
                }
                wide[i] = run >= 3;
            }
        }
        List<int[]> heads = new ArrayList<>();
        for (int[] c : components(r, wide, false)) {
            if (c.length >= 4) {
                heads.add(c);
            }
        }
        heads.sort((a, b) -> Float.compare(cx(r, a), cx(r, b)));
        r.memo.put("heads", heads);
        return heads;
    }

    /** A playhead runs across the score, two bars at a time, lighting what it passes. */
    static void score(Scene s) {
        Regions r = s.regions;
        float bars = BEAT * 8;
        float head = r.minX + (s.t % bars) / bars * (r.maxX - r.minX + 4) - 2;
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri]) {
                continue;
            }
            float d = ri % r.size - head;
            if (d > -0.5f && d < 1) {
                lighten(s, i, 0.85f);
            } else if (d <= -0.5f && d > -5) {
                lighten(s, i, 0.4f * (1 + d / 5));
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Equaliser: 🎧 📻 🎚️
    // -------------------------------------------------------------------------------------

    /**
     * Equaliser bars bouncing to the beat, white with a red peak: in {@code anchor} (x0, y0,
     * x1, y1), e.g. between headphone cups, or else inside the emoji's display if it has one.
     */
    static void equaliser(Scene s) {
        Regions r = s.regions;
        float x0, y0, x1, y1;
        if (s.anchor != null && s.anchor.length >= 4) {
            x0 = ax(s, 0);
            y0 = ay(s, 1);
            x1 = ax(s, 2);
            y1 = ay(s, 3);
        } else if (r.screenCount > 0) {
            x0 = r.screenMinX + 1;
            x1 = r.screenMaxX - 1;
            y0 = r.screenMinY + 1;
            y1 = r.screenMaxY - 1;
        } else {
            return;
        }
        int bars = Math.max(1, Math.round((x1 - x0 + 1) / 2));
        float left = (x0 + x1) / 2 - (bars - 1);
        int height = Math.max(2, Math.round(y1 - y0 + 1));
        int b = beat(s);
        float decay = 0.35f + 0.65f * (float) Math.exp(-phase(s) * 3);
        for (int k = 0; k < bars; k++) {
            float peak = 0.3f + 0.7f * Scene.hash(k, b, s.seed);
            // The middle bars hit hardest on the beat, like a real spectrum.
            float level = Math.min(1, peak * decay + 0.3f * s.boost);
            int lit = Math.max(1, Math.round(level * height));
            float x = left + k * 2;
            for (int j = 0; j < lit; j++) {
                s.paintRest(x, y1 - j, j == lit - 1 ? RED : WHITE);
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Microphones: 🎤 🎙️
    // -------------------------------------------------------------------------------------

    /**
     * Someone sings into it: the grille ({@code anchor}: centre x, y and radius, as fractions)
     * glints and brightens with the voice, syllable by syllable.
     */
    static void mic(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null || s.anchor.length < 3) {
            return;
        }
        float x = ax(s, 0);
        float y = ay(s, 1);
        float rad = s.anchor[2] * (r.maxX - r.minX);
        float syllable = BEAT / 2;
        float env = Scene.hash((int) (s.t / syllable), 9, s.seed) > 0.25f
                ? (float) Math.sin(Math.PI * (s.t % syllable) / syllable) : 0;
        env = Math.max(env, s.boost);
        int frame = (int) (s.t * 24);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri]
                    || Math.hypot(ri % r.size - x, ri / r.size - y) > rad) {
                continue;
            }
            if (Scene.hash(ri, frame, s.seed) < 0.3f * env) {
                s.dots[i] = Scene.alpha(WHITE, s.dots[i] >>> 24);
            } else {
                lighten(s, i, 0.25f * env);
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Wind: 🎷 🎺 📯 🪈
    // -------------------------------------------------------------------------------------

    /**
     * Notes come out of the bell ({@code anchor}: x, y and a direction dx, dy), one per beat,
     * in changing colours, and the bell brightens as each is blown.
     */
    static void wind(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null || s.anchor.length < 4) {
            return;
        }
        float bx = ax(s, 0);
        float by = ay(s, 1);
        float dx = s.anchor[2];
        float dy = s.anchor[3];
        float k = kick(s);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri >= 0 && r.ink[ri] && Math.hypot(ri % r.size - bx, ri / r.size - by) < 4) {
                lighten(s, i, 0.35f * k);
            }
        }
        float base = Scene.hash(s.seed, 6, 6) * 360;
        int b = beat(s);
        for (int n = 0; n < 3; n++) {
            float life = (phase(s) + n) / 3; // Each note lives three beats.
            int color = Scene.hsv(base + (b - n) * 55, 0.5f, 1);
            float sway = (float) Math.sin(life * 7 + n) * 1.2f;
            float x = bx + dx * life * 9 + sway * -dy;
            float y = by + dy * life * 9 + sway * dx;
            int c = Scene.alpha(color, Math.round(255 * DotEffect.fade(life)));
            for (int j = 0; j < DotEffect.NOTE.length; j++) {
                for (int i = 0; i < DotEffect.NOTE[j].length(); i++) {
                    if (DotEffect.NOTE[j].charAt(i) == 'x') {
                        s.paintRest(x + i - 1, y + j - 1, c);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Strings: 🎸 🎻 🪕, and a harp's glissando 🪉
    // -------------------------------------------------------------------------------------

    /**
     * Strummed every two beats: the strings between the bridge and the nut ({@code anchor}:
     * bx, by, nx, ny) shimmer in a standing wave that dies away.
     */
    static void strum(Scene s) {
        Regions r = s.regions;
        if (s.anchor == null || s.anchor.length < 4) {
            return;
        }
        float bx = ax(s, 0);
        float by = ay(s, 1);
        float nx = ax(s, 2);
        float ny = ay(s, 3);
        float len = (float) Math.hypot(nx - bx, ny - by);
        if (len < 1) {
            return;
        }
        float since = (s.t % (BEAT * 2)) / (BEAT * 2);
        float env = Math.min(1, (float) Math.exp(-since * 3) + 0.5f * s.boost);
        int mode = 2 + beat(s) / 2 % 2;
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri]) {
                continue;
            }
            float px = ri % r.size - bx;
            float py = ri / r.size - by;
            float along = (px * (nx - bx) + py * (ny - by)) / (len * len);
            float across = Math.abs(px * (ny - by) - py * (nx - bx)) / len;
            if (along < 0.05f || along > 1 || across > 1.6f) {
                continue;
            }
            // A standing wave: the string swings through rest every frame or so, so on the
            // panel its dots flicker light and dark, strongest at the antinodes.
            float wave = (float) Math.sin(Math.PI * along * mode)
                    * (float) Math.sin(s.t * 37.7f + along * 3);
            if (wave > 0) {
                lighten(s, i, 0.8f * env * wave);
            } else {
                s.dots[i] = Scene.alpha(Scene.scale(s.dots[i], 1 + 0.55f * env * wave),
                        s.dots[i] >>> 24);
            }
        }
    }

    /** A harp: a glissando sweeps across its strings once a bar. */
    static void gliss(Scene s) {
        Regions r = s.regions;
        float bar = BEAT * 4;
        float since = s.t % bar;
        float sweep = since / (BEAT * 1.5f);
        if (sweep > 1.3f && s.boost == 0) {
            return;
        }
        float head = r.minX + sweep * (r.maxX - r.minX);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri] || r.luma[ri] < 0.3f) {
                continue;
            }
            float d = head - ri % r.size;
            if (d >= 0 && d < 5) {
                lighten(s, i, 0.7f * (1 - d / 5));
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Keys: 🎹 🪗
    // -------------------------------------------------------------------------------------

    /** The keys play a little melody: each pressed key lights up in its note's colour. */
    static void keys(Scene s) {
        Regions r = s.regions;
        int[][] keys = keys(r);
        if (keys.length == 0) {
            return;
        }
        float eighth = BEAT / 2;
        int step = (int) (s.t / eighth);
        float env = (float) Math.exp(-((s.t % eighth) / eighth) * 2.5f);
        // A walking melody: steps of a scale, up and down, from the seed.
        int note = melody(step, keys.length, s.seed);
        boolean chord = s.boost > 0.3f; // A chord when touched.
        int presses = chord ? CHORD.length : 1;
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri] || r.luma[ri] < 0.6f) {
                continue;
            }
            int x = ri % r.size;
            for (int n = 0; n < presses; n++) {
                int p = chord ? CHORD[n] : note;
                int[] key = keys[Math.min(keys.length - 1, p % keys.length)];
                if (x >= key[0] && x <= key[1]) {
                    int color = Scene.hsv(200 + p * 40, 0.55f, 1);
                    s.dots[i] = Scene.alpha(Scene.mix(s.dots[i], color, 0.8f * Math.max(env,
                            s.boost)), s.dots[i] >>> 24);
                }
            }
        }
    }

    private static final int[] CHORD = {0, 2, 4};

    private static int melody(int step, int keys, int seed) {
        int len = 8;
        int pos = step % len;
        int walk = 0;
        for (int n = 0; n <= pos; n++) {
            walk += Scene.hash(n, step / len, seed) < 0.5f ? 1 : -1;
        }
        return Math.floorMod(walk + keys / 2, keys);
    }

    /**
     * White keys: the bright span across the lower part, split where the black keys sit (as on
     * a real keyboard, white keys meet under the middle of a black key). Each key is {x0, x1}.
     */
    private static int[][] keys(Regions r) {
        Object cached = r.memo.get("keys");
        if (cached != null) {
            return (int[][]) cached;
        }
        int lower = Math.round(r.minY + (r.maxY - r.minY) * 0.8f);
        int x0 = -1, x1 = -1;
        for (int x = r.minX; x <= r.maxX; x++) {
            int i = lower * r.size + x;
            if (r.ink[i] && r.luma[i] > 0.6f) {
                if (x0 < 0) {
                    x0 = x;
                }
                x1 = x;
            }
        }
        List<int[]> keys = new ArrayList<>();
        if (x0 >= 0) {
            // Black keys: dark columns standing in the bright area, a little above the bottom.
            int probe = Math.round(r.minY + (r.maxY - r.minY) * 0.45f);
            List<Integer> splits = new ArrayList<>();
            int start = -1;
            for (int x = x0; x <= x1 + 1; x++) {
                boolean black = x <= x1 && r.ink[probe * r.size + x]
                        && r.luma[probe * r.size + x] < 0.25f;
                if (black && start < 0) {
                    start = x;
                } else if (!black && start >= 0) {
                    splits.add((start + x - 1) / 2);
                    start = -1;
                }
            }
            int from = x0;
            for (int split : splits) {
                if (split > from) {
                    keys.add(new int[] {from, split - 1});
                    from = split + 1;
                }
            }
            keys.add(new int[] {from, x1});
        }
        int[][] out = keys.toArray(new int[0][]);
        r.memo.put("keys", out);
        return out;
    }

    // -------------------------------------------------------------------------------------
    // Percussion: 🥁 🪘 🪇
    // -------------------------------------------------------------------------------------

    /**
     * A drum is hit on the beat (with a quick fill at the end of each bar): the head flashes
     * and a ripple runs across it from the centre.
     */
    static void drum(Scene s) {
        Regions r = s.regions;
        int[] head = drumHead(r);
        if (head == null) {
            return;
        }
        boolean[] on = (boolean[]) r.memo.get("headMask");
        if (on == null) {
            on = new boolean[r.size * r.size];
            for (int i : head) {
                on[i] = true;
            }
            r.memo.put("headMask", on);
        }
        float hx = cx(r, head);
        float hy = cy(r, head);
        int b = beat(s) % 4;
        float sub = b == 3 ? BEAT / 2 : BEAT; // Eighths in the fill.
        float since = s.t % sub;
        float env = Math.min(1, (float) Math.exp(-since / sub * 5) + 0.4f * s.boost);
        float rad = since * 30;
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri]) {
                continue;
            }
            if (on[ri]) {
                float d = (float) Math.hypot(ri % r.size - hx, (ri / r.size - hy) * 1.6f);
                float ring = Math.max(0, 1 - Math.abs(d - rad) / 1.5f);
                lighten(s, i, 0.3f * env + 0.5f * ring * env);
            } else {
                lighten(s, i, 0.12f * env);
            }
        }
    }

    /** The drum head: the largest light, unsaturated area. */
    private static int[] drumHead(Regions r) {
        if (r.memo.containsKey("head")) {
            return (int[]) r.memo.get("head");
        }
        boolean[] light = new boolean[r.size * r.size];
        for (int i = 0; i < light.length; i++) {
            light[i] = r.ink[i] && r.luma[i] > 0.65f && r.sat[i] < 0.4f;
        }
        int[] best = null;
        for (int[] c : components(r, light, false)) {
            if (best == null || c.length > best.length) {
                best = c;
            }
        }
        if (best != null && best.length < 12) {
            best = null;
        }
        r.memo.put("head", best);
        return best;
    }

    /** Maracas: shaken on every eighth, left and right in turn; the beads inside rattle. */
    static void maracas(Scene s) {
        Regions r = s.regions;
        float eighth = BEAT / 2;
        int step = (int) (s.t / eighth);
        float env = Math.min(1, (float) Math.exp(-(s.t % eighth) / eighth * 4) + 0.5f * s.boost);
        float mid = (r.minX + r.maxX) / 2f;
        boolean left = step % 2 == 0;
        int frame = (int) (s.t * 24);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || !r.ink[ri] || r.sat[ri] < 0.45f || (ri % r.size < mid) != left) {
                continue;
            }
            if (Scene.hash(ri, frame, s.seed) < 0.35f * env) {
                s.dots[i] = Scene.alpha(WHITE, s.dots[i] >>> 24);
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Knobs: 🎛️
    // -------------------------------------------------------------------------------------

    /** Each round knob's pointer turns on its own, to and fro, like hands on a mixing desk. */
    static void knobs(Scene s) {
        Regions r = s.regions;
        List<int[]> knobs = knobs(r);
        for (int k = 0; k < knobs.size(); k++) {
            int[] c = knobs.get(k);
            float x = cx(r, c);
            float y = cy(r, c);
            float rad = Math.max(1, (float) Math.sqrt(c.length / Math.PI) - 0.5f);
            double swing = Math.sin(s.t * (0.8 + 0.3 * k) + k * 1.7) * 2.2
                    + Math.sin(s.t * 2.4) * 0.5 * kick(s);
            double ang = -Math.PI / 2 + swing;
            s.plotRest(x + (float) Math.cos(ang) * rad, y + (float) Math.sin(ang) * rad, WHITE);
        }
    }

    /** Dark round blobs enclosed by the emoji. */
    private static List<int[]> knobs(Regions r) {
        @SuppressWarnings("unchecked")
        List<int[]> cached = (List<int[]>) r.memo.get("knobs");
        if (cached != null) {
            return cached;
        }
        boolean[] dark = new boolean[r.size * r.size];
        for (int i = 0; i < dark.length; i++) {
            dark[i] = r.ink[i] && r.luma[i] < 0.45f;
        }
        List<int[]> knobs = new ArrayList<>();
        for (int[] c : components(r, dark, false)) {
            if (c.length >= 5 && c.length <= 80) {
                knobs.add(c);
            }
        }
        r.memo.put("knobs", knobs);
        return knobs;
    }

    // -------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------

    /** Anchor value {@code k} as a resting x position (fraction of the emoji's width). */
    static float ax(Scene s, int k) {
        Regions r = s.regions;
        float f = s.anchor != null && s.anchor.length > k ? s.anchor[k] : 0.5f;
        return r.minX + f * (r.maxX - r.minX);
    }

    static float ay(Scene s, int k) {
        Regions r = s.regions;
        float f = s.anchor != null && s.anchor.length > k ? s.anchor[k] : 0.5f;
        return r.minY + f * (r.maxY - r.minY);
    }

    static void lighten(Scene s, int i, float k) {
        if (k > 0) {
            s.dots[i] = Scene.alpha(Scene.mix(s.dots[i], WHITE, Math.min(1, k)), s.dots[i] >>> 24);
        }
    }

    static float cx(Regions r, int[] comp) {
        float sum = 0;
        for (int i : comp) {
            sum += i % r.size;
        }
        return sum / comp.length;
    }

    static float cy(Regions r, int[] comp) {
        float sum = 0;
        for (int i : comp) {
            sum += i / r.size;
        }
        return sum / comp.length;
    }

    /** Connected components of {@code mask} (8-connected when {@code diagonal}). */
    static List<int[]> components(Regions r, boolean[] mask, boolean diagonal) {
        int n = r.size * r.size;
        boolean[] seen = new boolean[n];
        int[] stack = new int[n];
        List<int[]> out = new ArrayList<>();
        for (int start = 0; start < n; start++) {
            if (!mask[start] || seen[start]) {
                continue;
            }
            int sp = 0;
            int count = 0;
            int[] comp = new int[n];
            stack[sp++] = start;
            seen[start] = true;
            while (sp > 0) {
                int i = stack[--sp];
                comp[count++] = i;
                int x = i % r.size;
                int y = i / r.size;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if ((dx == 0 && dy == 0) || (!diagonal && dx != 0 && dy != 0)) {
                            continue;
                        }
                        int nx = x + dx;
                        int ny = y + dy;
                        if (r.in(nx, ny) && mask[ny * r.size + nx] && !seen[ny * r.size + nx]) {
                            seen[ny * r.size + nx] = true;
                            stack[sp++] = ny * r.size + nx;
                        }
                    }
                }
            }
            out.add(Arrays.copyOf(comp, count));
        }
        return out;
    }
}
