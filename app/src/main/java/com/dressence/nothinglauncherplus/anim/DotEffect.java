package com.dressence.nothinglauncherplus.anim;

/**
 * What happens inside an emoji's dots, on top of its motion: the TV's screen fills with static,
 * a crystal ball swirls with magic, faces blink, fire flickers, clouds rain, coffee steams. Each
 * effect finds its part of the emoji through {@link Regions}, takes its colours from the emoji
 * itself and its timing from the emoji's seed, so no two emojis run it the same way. A reaction
 * ({@link Scene#boost}) makes every effect flare: more static, a faster swirl, a double blink.
 */
public enum DotEffect {

    NONE {
        @Override
        void apply(Scene s) {
        }
    },

    /** Faces, animals, robots: the eyes close now and then; a double blink when reacting. */
    BLINK {
        @Override
        void apply(Scene s) {
            blink(s);
        }
    },

    /** Love: blink, and little hearts float up and fade. */
    HEARTS {
        @Override
        void apply(Scene s) {
            blink(s);
            Regions r = s.regions;
            for (int k = 0; k < 2 + Math.round(s.boost * 2); k++) {
                float life = cycle(s, 2.2f, k * 0.5f);
                float x = r.maxX - 1 - k * 3 + (float) Math.sin(life * 6 + k) * 1.2f;
                float y = r.minY + 3 - life * 9;
                sprite(s, HEART, x, y, Scene.alpha(0xFFFF5C8A, Math.round(255 * fade(life))));
            }
        }
    },

    /** Crying: the emoji's own tears run (small ones slide, rivers flow and drip). */
    TEARS {
        @Override
        void apply(Scene s) {
            blink(s);
            if (s.regions.waterCount == 0) {
                fallbackTear(s);
                return;
            }
            animateWater(s, 1 + s.boost);
        }
    },

    /** Nervous: the bead of sweat slides down the face, glistening, and forms again. */
    SWEAT {
        @Override
        void apply(Scene s) {
            blink(s);
            if (s.regions.waterCount == 0) {
                fallbackTear(s);
                return;
            }
            animateWater(s, 0.7f + s.boost);
        }
    },

    /** Droplets and splashes: light runs down the water and drops fall from it. */
    DRIP {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            if (r.waterCount == 0) {
                SHINE.apply(s);
                return;
            }
            flowWater(s, 4 + 5 * s.boost);
            drips(s, 1.7f - 0.6f * s.boost);
        }
    },

    /** Sleeping: Zs drift up and away. */
    SLEEPY {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            for (int k = 0; k < 2; k++) {
                float life = cycle(s, 3f, k * 1.5f);
                float x = r.maxX - 2 + life * 5;
                float y = r.minY + 2 - life * 6;
                sprite(s, ZED, x, y, Scene.alpha(0xFFBFD8FF, Math.round(255 * fade(life))));
            }
        }
    },

    /** Angry: the face flushes red in pulses, steam puffs from both sides. */
    ANGRY {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            float pulse = 0.5f + 0.5f * (float) Math.sin(s.t * 6);
            float k = 0.25f + 0.2f * pulse + 0.3f * s.boost;
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri >= 0 && r.ink[ri] && !r.eyes[ri] && r.luma[ri] > 0.3f) {
                    s.dots[i] = Scene.alpha(Scene.mix(s.dots[i], 0xFFE5322D, k), s.dots[i] >>> 24);
                }
            }
            for (int side = -1; side <= 1; side += 2) {
                float life = cycle(s, 1.2f, side > 0 ? 0.6f : 0);
                float x = (side < 0 ? r.minX : r.maxX) + side * (1 + life * 3);
                float y = r.minY + 2 - life * 4;
                s.plotRest(x, y, Scene.alpha(0xFFE0E0E0, Math.round(220 * fade(life))));
            }
        }
    },

    /** Star-struck: the eyes (dark or star-shaped) sparkle gold and white. */
    STAR_EYES {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            float upper = r.minY + (r.maxY - r.minY) * 0.6f;
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                boolean eye = ri >= 0 && (r.eyes[ri]
                        || (r.eyeCount == 0 && r.luma[ri] > 0.8f && ri / r.size < upper));
                if (eye) {
                    float tw = Scene.hash(ri, (int) (s.t * 10), s.seed);
                    s.dots[i] = tw > 0.5f ? 0xFFFFE066 : 0xFFFFFFFF;
                }
            }
            twinkleAround(s, 2);
        }
    },

    /** TVs and monitors: static snow on the screen, a rolling bar, now and then colour bars. */
    STATIC {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            if (r.screenCount == 0) {
                SHINE.apply(s);
                return;
            }
            int frame = (int) (s.t * 18);
            float bar = (s.t * 9) % (r.screenMaxY - r.screenMinY + 6) + r.screenMinY - 3;
            boolean bars = (s.t + Math.floorMod(s.seed, 7)) % 9 < 0.7f && s.boost < 0.3f;
            int width = r.screenMaxX - r.screenMinX + 1;
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri < 0 || !r.screen[ri]) {
                    continue;
                }
                int x = ri % r.size;
                int y = ri / r.size;
                if (bars) {
                    float u = (x - r.screenMinX) / (float) width;
                    s.dots[i] = Scene.hsv(u * 300, 0.75f, 0.9f);
                    continue;
                }
                float v = 0.2f + 0.75f * Scene.hash(x, y, frame);
                if (Math.abs(y - bar) < 1.2f) {
                    v = Math.min(1, v + 0.35f);
                }
                if (s.boost > 0 && Scene.hash(y, frame, 3) < s.boost * 0.5f) {
                    s.dots[i] = Scene.hsv(Scene.hash(x, frame, 9) * 360, 0.8f, v);
                } else {
                    int g = Math.round(v * 255);
                    s.dots[i] = Scene.argb(255, g, g, g);
                }
            }
        }
    },

    /** Phones and computers: lines of content scroll up the screen, a cursor blinks. */
    SCREEN {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            if (r.screenCount == 0) {
                SHINE.apply(s);
                return;
            }
            float scroll = s.t * (2.5f + 4 * s.boost);
            int line = r.accent != 0 ? r.accent : 0xFF66B3FF;
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri < 0 || !r.screen[ri]) {
                    continue;
                }
                int x = ri % r.size - r.screenMinX;
                int y = ri / r.size;
                int row = (int) Math.floor(y + scroll);
                boolean text = row % 2 == 0 && x >= 1
                        && x < 2 + Scene.hash(row, 1, s.seed) * (r.screenMaxX - r.screenMinX - 2);
                s.dots[i] = text ? Scene.mix(line, 0xFFFFFFFF, 0.3f) : 0xFF101820;
            }
            if ((int) (s.t * 2) % 2 == 0) {
                s.plotRest(r.screenMinX + 1, r.screenMaxY - 1, 0xFFFFFFFF);
            }
        }
    },

    /** Crystal balls, globes, bubbles: colour swirls inside, sparkles orbit, light breathes. */
    MAGIC {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            if (r.ballRadius == 0) {
                TWINKLE.apply(s);
                return;
            }
            float base = Regions.hueOf((r.ballColor >> 16) & 0xFF, (r.ballColor >> 8) & 0xFF,
                    r.ballColor & 0xFF);
            float speed = 1.4f + 2.5f * s.boost;
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri < 0 || !r.ball[ri]) {
                    continue;
                }
                float dx = ri % r.size - r.ballX;
                float dy = ri / r.size - r.ballY;
                float angle = (float) Math.atan2(dy, dx);
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float swirl = (float) Math.sin(angle * 2 + dist * 0.7f - s.t * speed * 2);
                float v = Math.min(1, 0.68f + 0.27f * swirl + 0.2f * s.boost);
                s.dots[i] = Scene.alpha(Scene.hsv(base + 35 * swirl, 0.65f, v), s.dots[i] >>> 24);
            }
            int sparkles = 2 + Math.round(3 * s.boost);
            for (int k = 0; k < sparkles; k++) {
                float a = s.t * speed + k * (float) (Math.PI * 2 / sparkles);
                float rad = r.ballRadius * (0.35f + 0.25f * (float) Math.sin(s.t * 1.3f + k));
                s.plotRest(r.ballX + (float) Math.cos(a) * rad, r.ballY + (float) Math.sin(a) * rad,
                        0xFFFFFFFF);
            }
        }
    },

    /** Fire and candles: flames flicker column by column, embers rise. */
    FLAME {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            int frame = (int) (s.t * 14);
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri < 0 || !r.ink[ri] || r.sat[ri] < 0.35f || r.hue[ri] > 60) {
                    continue;
                }
                int x = ri % r.size;
                int y = ri / r.size;
                float n = Scene.hash(x, frame, s.seed);
                float tip = (y - r.top[x]) / (float) Math.max(1, r.bottom[x] - r.top[x] + 1);
                if (tip < 0.18f && n > 0.6f + 0.3f * s.boost) {
                    s.dots[i] = 0; // Tips lick up and down.
                    continue;
                }
                float k = 0.75f + 0.45f * n + 0.3f * s.boost;
                s.dots[i] = Scene.alpha(Scene.mix(Scene.scale(s.dots[i], k), 0xFFFFE680,
                        (1 - tip) * 0.25f * n), s.dots[i] >>> 24);
            }
            int embers = 2 + Math.round(4 * s.boost);
            for (int k = 0; k < embers; k++) {
                float life = cycle(s, 1.3f, k * 0.37f);
                float x = (r.minX + r.maxX) / 2f + (Scene.hash(k, (int) (s.t / 1.3f), s.seed) - 0.5f)
                        * (r.maxX - r.minX) * 0.6f;
                s.plotRest(x, r.minY - life * 6, Scene.alpha(0xFFFFA040, Math.round(255 * fade(life))));
            }
        }
    },

    /** Stars, sparkles, gems, money: dots catch the light and flash white; sparkles around. */
    TWINKLE {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            float rate = 2.5f + 4 * s.boost;
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri < 0 || !r.ink[ri]) {
                    continue;
                }
                float phase = Scene.hash(ri, 7, s.seed) * 40;
                if (Math.sin(s.t * rate + phase) > 0.96f - 0.1f * s.boost) {
                    s.dots[i] = 0xFFFFFFFF;
                }
            }
            twinkleAround(s, 2 + Math.round(3 * s.boost));
        }
    },

    /** Objects: a band of light sweeps across now and then, like a reflection. */
    SHINE {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            float period = 4.5f + 2 * Scene.hash(s.seed, 1, 1);
            float local = s.boost > 0.2f ? (1 - s.boost) * 1.2f : (s.t % period);
            float span = (r.maxX - r.minX) + (r.maxY - r.minY) + 8;
            float band = local / 1.2f * span - 4;
            if (local > 1.2f) {
                return;
            }
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri < 0 || !r.ink[ri]) {
                    continue;
                }
                float pos = ri % r.size - r.minX + ri / r.size - r.minY;
                float d = Math.abs(pos - band);
                if (d < 2.5f) {
                    s.dots[i] = Scene.alpha(Scene.mix(s.dots[i], 0xFFFFFFFF, 0.55f * (1 - d / 2.5f)),
                            s.dots[i] >>> 24);
                }
            }
        }
    },

    /** Hearts, lamps, bulbs: a warm glow pulses, with a halo when it peaks. */
    GLOW {
        @Override
        void apply(Scene s) {
            glow(s, (float) Math.max(0, Math.sin(s.t * 2.2f)));
        }
    },

    /** Hearts: a double heartbeat glow. */
    HEARTBEAT {
        @Override
        void apply(Scene s) {
            float p = s.t % 1.1f;
            float beat = p < 0.12f ? p / 0.12f : p < 0.24f ? 1 - (p - 0.12f) / 0.12f
                    : p < 0.36f ? (p - 0.24f) / 0.12f * 0.7f : p < 0.5f ? 0.7f * (1 - (p - 0.36f) / 0.14f) : 0;
            glow(s, beat);
        }
    },

    /** Rain clouds, umbrellas, showers: drops fall from beneath. */
    RAIN {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            int drops = 6 + Math.round(6 * s.boost);
            for (int k = 0; k < drops; k++) {
                float life = cycle(s, 0.9f, k * 0.29f);
                int cyc = (int) ((s.t + k * 0.29f) / 0.9f);
                int x = r.minX + 1 + (int) (Scene.hash(k, cyc, s.seed) * Math.max(1, r.maxX - r.minX - 1));
                int from = r.bottom[x] >= 0 ? r.bottom[x] + 1 : r.maxY + 1;
                float y = from + life * (r.size - from);
                s.plotRest(x, y, 0xC07FC8FF);
            }
        }
    },

    /** Snow: flakes drift down, swaying. */
    SNOW {
        @Override
        void apply(Scene s) {
            for (int k = 0; k < 9; k++) {
                float life = cycle(s, 3.2f, k * 0.36f);
                int cyc = (int) ((s.t + k * 0.36f) / 3.2f);
                float x = Scene.hash(k, cyc, s.seed) * s.size + (float) Math.sin(s.t * 1.5f + k) * 1.5f;
                s.plotRest(x, -1 + life * (s.size + 1), 0xE0FFFFFF);
            }
            TWINKLE.apply(s);
        }
    },

    /** Hot drinks and food: wisps of steam rise, wavering, and fade. */
    STEAM {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            float cx = (r.minX + r.maxX) / 2f;
            for (int k = 0; k < 3; k++) {
                for (int j = 0; j < 3; j++) {
                    float life = cycle(s, 2.4f, k * 0.8f + j * 0.12f);
                    float x = cx + (k - 1) * 3 + (float) Math.sin(s.t * 2 + k + life * 5) * 1.2f;
                    s.plotRest(x, r.minY - 1 - life * 8,
                            Scene.alpha(0xFFF2F2F2, Math.round(235 * fade(life))));
                }
            }
        }
    },

    /** Music: notes float up and away. */
    NOTES {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            // Notes in soft, changing colours rise from either side and sway as they go.
            float base = Scene.hash(s.seed, 3, 3) * 360;
            float start = r.minY + (r.maxY - r.minY) * 0.55f;
            for (int k = 0; k < 3 + Math.round(2 * s.boost); k++) {
                float life = cycle(s, 2.4f, k * 0.83f);
                boolean right = k % 2 == 0;
                float x = (right ? r.maxX - 3 : r.minX + 1)
                        + (float) Math.sin(life * 6 + k) * 1.5f;
                int color = Scene.hsv(base + k * 72 + s.t * 20, 0.45f, 1);
                sprite(s, NOTE, x, start - life * 12,
                        Scene.alpha(color, Math.round(255 * fade(life))));
            }
        }
    },

    /** Speakers, megaphones, bells: sound waves ripple outwards. */
    SOUND {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            // Sound pulses travel out from the source through the emoji, brightening its dots
            // as each wavefront passes, like a speaker cone thumping on an LED panel.
            float cx = r.minX + (r.maxX - r.minX) * 0.3f;
            float cy = (r.minY + r.maxY) / 2f;
            for (int k = 0; k < 2; k++) {
                float life = cycle(s, 1.1f - 0.4f * s.boost, k * 0.55f);
                float rad = life * 26;
                float strength = 0.85f * (1 - 0.6f * life);
                for (int i = 0; i < s.dots.length; i++) {
                    int ri = s.restIndex(i);
                    if (ri < 0 || !r.ink[ri]) {
                        continue;
                    }
                    float d = (float) Math.hypot(ri % r.size - cx, ri / r.size - cy);
                    float edge = 1 - Math.abs(d - rad) / 2f;
                    if (edge > 0) {
                        s.dots[i] = Scene.alpha(Scene.mix(s.dots[i], 0xFFFFFFFF, strength * edge),
                                s.dots[i] >>> 24);
                    }
                }
            }
        }
    },

    /** Waves and the sea: light ripples run across the blues. */
    RIPPLE {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri < 0 || !r.ink[ri] || r.hue[ri] < 170 || r.hue[ri] > 250) {
                    continue;
                }
                int x = ri % r.size;
                int y = ri / r.size;
                float w = (float) Math.sin(x * 0.8f + y * 0.3f - s.t * (3 + 3 * s.boost));
                if (w > 0.6f) {
                    s.dots[i] = Scene.alpha(Scene.mix(s.dots[i], 0xFFFFFFFF, (w - 0.6f) * 1.4f),
                            s.dots[i] >>> 24);
                }
            }
        }
    },

    /** Rainbows, prides, discos: the colours flow across. */
    RAINBOW {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri < 0 || !r.ink[ri] || r.sat[ri] < 0.25f) {
                    continue;
                }
                float h = r.hue[ri] + 40 * (float) Math.sin(s.t * (1 + 2 * s.boost) + ri % r.size * 0.3f);
                s.dots[i] = Scene.alpha(Scene.hsv(h, r.sat[ri], Math.max(0.5f, r.luma[ri] + 0.2f)),
                        s.dots[i] >>> 24);
            }
        }
    },

    /** Flags: the cloth ripples in the wind. */
    FLAG {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            float speed = 3.5f + 3 * s.boost;
            for (int i = 0; i < s.dots.length; i++) {
                if (Float.isNaN(s.srcX[i])) {
                    continue;
                }
                float x = s.srcX[i];
                float wave = (float) Math.sin((x - r.minX) * 0.45f - s.t * speed);
                float amp = Math.min(1, (x - r.minX) / 6f) * 1.3f;
                int sx = Math.round(x);
                int sy = Math.round(s.srcY[i] + wave * amp);
                if (sx < 0 || sy < 0 || sx >= r.size || sy >= r.size || !r.ink[sy * r.size + sx]) {
                    s.dots[i] = 0;
                    continue;
                }
                int c = r.rest[sy * r.size + sx];
                s.dots[i] = Scene.scale(c, 0.82f + 0.18f * (float) Math.cos((x - r.minX) * 0.45f - s.t * speed));
            }
        }
    },

    /** Plants: the top sways more than the stem. */
    SWAY {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            float h = Math.max(1, r.maxY - r.minY);
            for (int i = 0; i < s.dots.length; i++) {
                if (Float.isNaN(s.srcX[i])) {
                    continue;
                }
                float lift = 1 - (s.srcY[i] - r.minY) / h;
                int sx = Math.round(s.srcX[i] - (float) Math.sin(s.t * (1.6f + 2 * s.boost)) * lift * 1.8f);
                int sy = Math.round(s.srcY[i]);
                if (sx < 0 || sy < 0 || sx >= r.size || sy >= r.size) {
                    s.dots[i] = 0;
                    continue;
                }
                s.dots[i] = r.ink[sy * r.size + sx] ? r.rest[sy * r.size + sx] : 0;
            }
        }
    },

    /** Lightning and power: a white flash with a crackle at the edges. */
    ZAP {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            float p = s.t % (3.2f + Scene.hash(s.seed, 2, 2));
            boolean flash = p < 0.08f || (p > 0.16f && p < 0.22f) || s.boost > 0.6f;
            if (!flash) {
                return;
            }
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri >= 0 && r.ink[ri]) {
                    s.dots[i] = Scene.mix(s.dots[i], 0xFFFFFFE0, 0.7f);
                }
            }
            twinkleAround(s, 4);
        }
    },

    /** Eyes: the pupils look left and right. */
    LOOK {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            if (r.eyeCount == 0) {
                BLINK.apply(s);
                return;
            }
            float look = (float) Math.sin(s.t * 0.9f + Scene.hash(s.seed, 3, 3) * 6);
            int dx = Math.round(look * 1.6f);
            int pupil = 0xFF1A1A1A;
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri >= 0 && r.eyes[ri]) {
                    s.dots[i] = r.lidColor;
                }
            }
            for (int i = 0; i < r.size * r.size; i++) {
                if (r.eyes[i]) {
                    s.plotRest(i % r.size + dx, i / r.size, pupil);
                    setRest(s, i % r.size + dx, i / r.size, pupil);
                }
            }
        }
    },

    /** Clocks and watches: a hand sweeps round. */
    CLOCK {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            float cx = (r.minX + r.maxX) / 2f;
            float cy = (r.minY + r.maxY) / 2f + 1;
            float len = Math.min(r.maxX - r.minX, r.maxY - r.minY) * 0.3f;
            float a = s.t * (1 + 4 * s.boost) - (float) Math.PI / 2;
            for (float d = 0; d <= len; d += 0.5f) {
                setRest(s, cx + (float) Math.cos(a) * d, cy + (float) Math.sin(a) * d, 0xFFE5322D);
            }
        }
    },

    /** Sirens and fairground lights: a beam of light circles round. */
    BEACON {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            float cx = (r.minX + r.maxX) / 2f;
            float cy = (r.minY + r.maxY) / 2f;
            float beam = s.t * (4 + 4 * s.boost);
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri < 0 || !r.ink[ri]) {
                    continue;
                }
                float a = (float) Math.atan2(ri / r.size - cy, ri % r.size - cx);
                float diff = (float) Math.abs(Math.IEEEremainder(a - beam, Math.PI * 2));
                if (diff < 0.6f) {
                    s.dots[i] = Scene.mix(s.dots[i], 0xFFFFFFFF, 0.6f * (1 - diff / 0.6f));
                }
            }
        }
    },

    /** Bubbles, fish, soap: little bubbles rise and sway. */
    BUBBLES {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            for (int k = 0; k < 4 + Math.round(3 * s.boost); k++) {
                float life = cycle(s, 2.2f, k * 0.55f);
                int cyc = (int) ((s.t + k * 0.55f) / 2.2f);
                float x = r.minX + Scene.hash(k, cyc, s.seed) * (r.maxX - r.minX)
                        + (float) Math.sin(s.t * 3 + k) * 0.8f;
                s.plotRest(x, r.maxY - life * (r.maxY + 2), Scene.alpha(0xFFBFF4FF, Math.round(200 * fade(life))));
            }
            RIPPLE.apply(s);
        }
    },

    /** Ghosts: flickering in and out of sight. */
    GHOSTLY {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            float base = 0.55f + 0.35f * (float) Math.sin(s.t * 1.7f);
            int frame = (int) (s.t * 12);
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri < 0 || !r.ink[ri] || r.eyes[ri]) {
                    continue;
                }
                float a = base * (0.7f + 0.3f * Scene.hash(ri, frame, s.seed));
                s.dots[i] = Scene.alpha(s.dots[i], Math.round((s.dots[i] >>> 24) * a));
            }
            blink(s);
        }
    },

    /** Robots, aliens, games: a scan line sweeps down; eyes glow. */
    DIGITAL {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            float line = r.minY + (s.t * (8 + 8 * s.boost)) % (r.maxY - r.minY + 6) - 2;
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri < 0 || !r.ink[ri]) {
                    continue;
                }
                if (r.eyes[ri]) {
                    float g = 0.5f + 0.5f * (float) Math.sin(s.t * 4);
                    s.dots[i] = Scene.mix(0xFF00E5FF, 0xFFFFFFFF, g * 0.5f);
                } else if (Math.abs(ri / r.size - line) < 0.8f) {
                    s.dots[i] = Scene.mix(s.dots[i], 0xFF9CFFE8, 0.6f);
                }
            }
        }
    },

    /** Parties: confetti tumbles down around. */
    CONFETTI {
        @Override
        void apply(Scene s) {
            for (int k = 0; k < 9 + Math.round(9 * s.boost); k++) {
                float life = cycle(s, 2.4f, k * 0.27f);
                int cyc = (int) ((s.t + k * 0.27f) / 2.4f);
                float x = Scene.hash(k, cyc, s.seed) * s.size + (float) Math.sin(s.t * 4 + k) * 1.2f;
                s.plotRest(x, -1 + life * (s.size + 1), Scene.hsv(Scene.hash(cyc, k, 5) * 360, 0.8f, 1));
            }
        }
    },

    /** Fireworks: bursts bloom into rings of colour and fade. */
    FIREWORKS {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            for (int k = 0; k < 2; k++) {
                float life = cycle(s, 1.8f, k * 0.9f);
                int cyc = (int) ((s.t + k * 0.9f) / 1.8f);
                float cx = r.minX + Scene.hash(k, cyc, s.seed) * (r.maxX - r.minX);
                float cy = r.minY + Scene.hash(cyc, k, s.seed) * (r.maxY - r.minY) * 0.6f;
                int color = Scene.alpha(Scene.hsv(Scene.hash(cyc, 3, k) * 360, 0.7f, 1),
                        Math.round(255 * fade(life)));
                for (int ray = 0; ray < 8; ray++) {
                    double a = ray * Math.PI / 4;
                    s.plotRest(cx + (float) Math.cos(a) * life * 6, cy + (float) Math.sin(a) * life * 6, color);
                }
            }
        }
    },

    /** Smoke and wind: grey wisps drift away. */
    SMOKE {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            for (int k = 0; k < 4; k++) {
                float life = cycle(s, 2.8f, k * 0.7f);
                float x = r.minX - 1 - life * 6 + (float) Math.sin(life * 6 + k) * 1.4f;
                float y = (r.minY + r.maxY) / 2f + (k - 1.5f) * 3 - life * 2;
                s.plotRest(x, y, Scene.alpha(0xFFDADADA, Math.round(230 * fade(life))));
            }
        }
    },

    /** Buildings and cities: windows light up and go dark. */
    WINDOWS {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            // Each pane of glass switches its light on or off on its own slow clock, so the
            // building lives quietly like a city at dusk; a reaction turns more lights on.
            if (r.windowCount == 0) {
                SHINE.apply(s);
                return;
            }
            float lit = 0.62f - 0.3f * s.boost;
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                int pane = ri < 0 ? -1 : r.window[ri];
                if (pane < 0) {
                    continue;
                }
                float period = 2.5f + 3f * Scene.hash(pane, 1, s.seed);
                float clock = s.t / period + Scene.hash(pane, 2, s.seed);
                if (Scene.hash(pane, (int) clock, s.seed) > lit) {
                    s.dots[i] = Scene.alpha(0xFFFFD98A, s.dots[i] >>> 24);
                }
            }
        }
    },

    /** Symbols and anything else: a soft shine, and a sparkle now and then. */
    SPARKLE {
        @Override
        void apply(Scene s) {
            SHINE.apply(s);
            twinkleAround(s, 1 + Math.round(2 * s.boost));
        }
    };

    abstract void apply(Scene s);

    /** True for effects that belong on a face, so reactions can be expressive. */
    /** A display name: "STAR_EYES" reads "Star eyes". */
    public String title() {
        String s = name().replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public boolean isFace() {
        switch (this) {
            case BLINK: case HEARTS: case TEARS: case SWEAT: case SLEEPY: case ANGRY:
            case STAR_EYES: case LOOK: case GHOSTLY:
                return true;
            default:
                return false;
        }
    }

    public static DotEffect parse(String name) {
        if (name != null) {
            for (DotEffect e : values()) {
                if (e.name().equals(name)) {
                    return e;
                }
            }
        }
        return SPARKLE;
    }

    // -------------------------------------------------------------------------------------
    // Shared pieces
    // -------------------------------------------------------------------------------------

    private static final String[] HEART = {"x.x", "xxx", ".x."};
    private static final String[] ZED = {"xxx", ".x.", "xxx"};
    private static final String[] NOTE = {".xx", ".x.", "xx."};
    private static final String[] CROSS = {".x.", "xxx", ".x."};

    /** Position in a repeating life cycle of {@code period} seconds, 0..1. */
    static float cycle(Scene s, float period, float offset) {
        float v = ((s.t + offset + (s.seed & 0xFF) * 0.013f) % period) / period;
        return v < 0 ? v + 1 : v;
    }

    /** Fades in quickly, out slowly: for particles. */
    static float fade(float life) {
        return life < 0.15f ? life / 0.15f : 1 - (life - 0.15f) / 0.85f;
    }

    static void sprite(Scene s, String[] rows, float x, float y, int color) {
        for (int j = 0; j < rows.length; j++) {
            for (int i = 0; i < rows[j].length(); i++) {
                if (rows[j].charAt(i) == 'x') {
                    s.plotRest(x + i - 1, y + j - 1, color);
                }
            }
        }
    }

    /** Overwrites the output dot at resting position (x, y), even over brighter ink. */
    static void setRest(Scene s, float x, float y, int color) {
        float ox = s.a * (x + 0.5f) + s.b * (y + 0.5f) + s.c;
        float oy = s.d * (x + 0.5f) + s.e * (y + 0.5f) + s.f;
        int ix = (int) Math.floor(ox);
        int iy = (int) Math.floor(oy);
        if (ix >= 0 && iy >= 0 && ix < s.size && iy < s.size) {
            s.dots[iy * s.size + ix] = color;
        }
    }

    /**
     * Bodies of water at least this tall (in dots) are streams that flow; shorter ones are drops
     * that slide. Measured: tears and sweat beads are 8-11 tall, a sobbing face's rivers 17.
     */
    private static final int STREAM_HEIGHT = 14;

    private static boolean isDrop(Regions r, int body) {
        return body >= 0 && r.bodyHeight[body] < STREAM_HEIGHT;
    }

    /**
     * Animates the emoji's own water. Small bodies (a tear, a bead of sweat) slide down,
     * glisten, fade at the end of their run and form again, leaving behind what was under them;
     * large bodies (rivers of tears) flow, with drops forming at their lowest point and falling.
     */
    static void animateWater(Scene s, float pace) {
        Regions r = s.regions;
        boolean anyDrop = false;
        for (int k = 0; k < r.bodySize.length; k++) {
            anyDrop |= isDrop(r, k);
        }
        if (anyDrop) {
            // Uncover what the drops were hiding, then draw each drop further down its path.
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri >= 0 && (isDrop(r, r.waterBody[ri]) || isDrop(r, r.haloBody[ri]))) {
                    int behind = r.behindWater[ri];
                    s.dots[i] = behind == 0 ? 0 : Scene.alpha(behind, s.dots[i] >>> 24);
                }
            }
            for (int i = 0; i < r.size * r.size; i++) {
                int body = r.waterBody[i];
                if (!isDrop(r, body)) {
                    continue;
                }
                float life = cycle(s, 2.6f / pace, body * 0.7f);
                // Runs down the skin and fades where the skin ends; never off into the void.
                float slide = life * life * Math.min(7, Math.max(0, r.bodyRoom[body] - 1));
                float y = i / r.size + slide;
                int c = r.rest[i];
                float shine = (float) Math.max(0, Math.sin((i / r.size) * 1.1f - s.t * 7));
                c = Scene.mix(c, 0xFFFFFFFF, 0.45f * shine * shine);
                // Forms and vanishes dot by dot, the way an LED panel shows it: no colour
                // blending, so blue water never smears green into yellow skin.
                float visible = life < 0.75f ? 1 : (1 - life) / 0.25f;
                float formed = Math.min(1, life / 0.1f);
                if (Scene.hash(i, body, s.seed) >= visible * formed) {
                    continue;
                }
                // Water that overhangs the outline only shows where it started: once the drop
                // runs, it stays on the emoji instead of leaving dots floating beside it.
                int ty = Math.round(y);
                if (slide >= 0.5f && !r.onEmoji(i % r.size, ty)) {
                    continue;
                }
                setRest(s, i % r.size, y, c);
            }
        }
        flowWater(s, 6 * pace);
        for (int k = 0; k < r.bodySize.length && k < 4; k++) {
            if (isDrop(r, k)) {
                continue;
            }
            float life = cycle(s, 1.3f / pace, k * 0.41f);
            float y = r.bodyLowY[k] + 1 + life * life * (r.size - r.bodyLowY[k]);
            s.plotRest(r.bodyLowX[k], y, Scene.alpha(0xFF8FDBFF, Math.round(240 * fade(life))));
        }
    }

    /** Light running down large water (small drops get their own shine as they slide). */
    static void flowWater(Scene s, float speed) {
        Regions r = s.regions;
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || r.waterBody[ri] < 0 || isDrop(r, r.waterBody[ri])) {
                continue;
            }
            int x = ri % r.size;
            int y = ri / r.size;
            float w = (float) Math.sin(y * 0.85f - s.t * speed + x * 0.35f);
            if (w > 0.35f) {
                float k = (w - 0.35f) / 0.65f;
                s.dots[i] = Scene.alpha(Scene.mix(s.dots[i], 0xFFE8FBFF, 0.65f * k * k),
                        s.dots[i] >>> 24);
            }
        }
    }

    /** Drops forming at the lowest point of each body of water and falling. */
    static void drips(Scene s, float period) {
        Regions r = s.regions;
        for (int k = 0; k < r.bodySize.length && k < 4; k++) {
            float life = cycle(s, period, k * 0.41f);
            float y = r.bodyLowY[k] + 1 + life * life * (r.size - r.bodyLowY[k]);
            s.plotRest(r.bodyLowX[k], y, Scene.alpha(0xFF8FDBFF, Math.round(240 * fade(life))));
        }
    }

    /** A face without drawn water: a tear runs from an eye. */
    private static void fallbackTear(Scene s) {
        Regions r = s.regions;
        float[][] eyes = eyeCentres(r);
        float life = cycle(s, 2.2f, 0);
        float y = eyes[1][1] + 1 + life * (r.size - eyes[1][1]);
        s.plotRest(eyes[1][0], y, Scene.alpha(0xFF66CCFF, Math.round(230 * fade(life))));
    }

    private static final float BLINK_TIME = 0.15f;

    static void blink(Scene s) {
        Regions r = s.regions;
        if (r.openEyes.isEmpty() || r.lidColor == 0) {
            return;
        }
        float period = 3.4f + 2.2f * Scene.hash(s.seed, 5, 5);
        // A reaction is a quick double blink; otherwise one now and then.
        float p = s.since < 0.45f ? s.since % 0.28f : s.t % period;
        if (p >= BLINK_TIME) {
            return;
        }
        // Lid half-way, shut, half-way: a real blink, not a cut.
        float q = p / BLINK_TIME;
        float shut = q < 0.25f || q > 0.75f ? 0.5f : 1;
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0) {
                continue;
            }
            int x = ri % r.size;
            int y = ri / r.size;
            for (int[] eye : r.openEyes) {
                if (x < eye[0] - 1 || x > eye[2] + 1 || y < eye[1] - 1 || y > eye[3] + 1) {
                    continue;
                }
                int lid = eye[5] != 0 ? eye[5] : r.lidColor;
                float lidLuma = Regions.luma(lid);
                int h = eye[3] - eye[1] + 1;
                int w = eye[2] - eye[0] + 1;
                float cy = (eye[1] + eye[3]) / 2f;
                if (shut >= 1) {
                    // Shut: a lash line a little wider than the eye, curving down at the ends
                    // like a closed eye drawn as "‿".
                    int lash = Math.round(cy + h * 0.2f);
                    int from = eye[0] - (w >= 3 ? 1 : 0);
                    int to = eye[2] + (w >= 3 ? 1 : 0);
                    boolean end = x == from || x == to;
                    if (x >= from && x <= to && y == (end ? lash - 1 : lash)) {
                        s.dots[i] = Scene.alpha(eye[4], 255);
                        break;
                    }
                }
                // The eye, its soft rim and its highlight: everything that isn't plain skin.
                boolean part = r.eyes[ri] || (r.ink[ri]
                        && Math.abs(r.luma[ri] - lidLuma) > 0.08f);
                // Half-way the lid covers the top 40%; shut, all of it.
                if (part && (shut >= 1 || y < eye[1] + h * 0.4f)) {
                    s.dots[i] = Scene.alpha(lid, 255);
                }
                break;
            }
        }
    }

    static void glow(Scene s, float k) {
        Regions r = s.regions;
        k = Math.min(1, k + s.boost);
        int halo = Scene.alpha(r.accent != 0 ? r.accent : r.brightest, Math.round(110 * k));
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri >= 0 && r.ink[ri]) {
                s.dots[i] = Scene.alpha(Scene.mix(s.dots[i], r.brightest, 0.35f * k), s.dots[i] >>> 24);
            }
        }
        if (k > 0.5f) {
            for (int y = 0; y < r.size; y++) {
                for (int x = 0; x < r.size; x++) {
                    if (!r.ink[y * r.size + x] && touchesInk(r, x, y)) {
                        s.plotRest(x, y, halo);
                    }
                }
            }
        }
    }

    private static boolean touchesInk(Regions r, int x, int y) {
        return (r.in(x + 1, y) && r.ink[y * r.size + x + 1])
                || (r.in(x - 1, y) && r.ink[y * r.size + x - 1])
                || (r.in(x, y + 1) && r.ink[(y + 1) * r.size + x])
                || (r.in(x, y - 1) && r.ink[(y - 1) * r.size + x]);
    }

    /** Small four-point sparkles appearing just outside the emoji's outline. */
    static void twinkleAround(Scene s, int count) {
        Regions r = s.regions;
        int color = r.brightest != 0 ? Scene.mix(r.brightest, 0xFFFFFFFF, 0.5f) : 0xFFFFFFFF;
        for (int k = 0; k < count; k++) {
            float life = cycle(s, 1.6f, k * 0.53f);
            int cyc = (int) ((s.t + k * 0.53f) / 1.6f);
            float angle = Scene.hash(k, cyc, s.seed) * (float) (Math.PI * 2);
            float rx = (r.maxX - r.minX) / 2f + 2;
            float ry = (r.maxY - r.minY) / 2f + 2;
            float x = (r.minX + r.maxX) / 2f + (float) Math.cos(angle) * rx;
            float y = (r.minY + r.maxY) / 2f + (float) Math.sin(angle) * ry;
            int a = Math.round(255 * (float) Math.sin(life * Math.PI));
            sprite(s, life > 0.3f && life < 0.7f ? CROSS : new String[] {"x"}, x, y, Scene.alpha(color, a));
        }
    }

    /** Centres of the eye blobs, left to right (falls back to two guesses on a face). */
    static float[][] eyeCentres(Regions r) {
        float lx = 0, ly = 0, rx = 0, ry = 0;
        int ln = 0, rn = 0;
        float mid = (r.minX + r.maxX) / 2f;
        for (int i = 0; i < r.size * r.size; i++) {
            if (!r.eyes[i]) {
                continue;
            }
            if (i % r.size < mid) {
                lx += i % r.size;
                ly += i / r.size;
                ln++;
            } else {
                rx += i % r.size;
                ry += i / r.size;
                rn++;
            }
        }
        float h = r.minY + (r.maxY - r.minY) * 0.4f;
        float w = (r.maxX - r.minX) * 0.25f;
        return new float[][] {
                ln > 0 ? new float[] {lx / ln, ly / ln} : new float[] {mid - w, h},
                rn > 0 ? new float[] {rx / rn, ry / rn} : new float[] {mid + w, h}};
    }
}
