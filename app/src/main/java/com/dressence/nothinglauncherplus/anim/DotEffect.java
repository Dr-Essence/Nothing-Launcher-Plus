package com.dressence.nothinglauncherplus.anim;

import java.util.List;

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

    /**
     * Love: the emoji's own hearts beat, lub-dub, one after another (heart eyes throb, a kiss
     * heart pulses), and the eyes blink.
     */
    HEARTS {
        @Override
        void pose(Scene s) {
            heartbeat(s);
        }

        @Override
        void apply(Scene s) {
            blink(s);
        }
    },

    /** A kiss: a little heart is blown from the puckered lips (anchor) and floats away. */
    KISS {
        @Override
        void apply(Scene s) {
            blink(s);
            blowKiss(s);
        }
    },

    /**
     * A broken heart: now and then the halves come apart along the crack, tipping away from
     * each other about the point where they meet, and slowly close again. Anchors: a point on
     * the right half, and the point they turn about.
     */
    BREAK {
        @Override
        void pose(Scene s) {
            if (s.anchor == null || s.anchor.length < 4) {
                return;
            }
            float t = (s.t + 0.8f) % 4.2f;
            float open;
            if (t < 0.45f) {
                // It gives way quickly, with a little overshoot.
                open = ObjectEffects.spring(t / 0.45f);
            } else if (t < 1.6f) {
                open = 1;
            } else if (t < 2.6f) {
                open = 1 - ObjectEffects.ease((t - 1.6f) / 1.0f);
            } else {
                return;
            }
            float px = MusicEffects.ax(s, 2);
            float py = MusicEffects.ay(s, 3);
            s.liftPiece(MusicEffects.ax(s, 0), MusicEffects.ay(s, 1));
            s.addPart(0, 0, s.size, s.size, px, py, -5.5f * open, -0.3f * open, 0);
            s.addLayer(1, px, py, 5.5f * open, 0.3f * open, 0, 1);
            s.busy = true;
        }

        @Override
        void apply(Scene s) {
        }
    },

    /**
     * Huffing: the clouds of steam blown from the nose (the emoji's own, lifted off) burst out
     * with each snort and settle back.
     */
    HUFF {
        @Override
        void pose(Scene s) {
            Regions r = s.regions;
            float mid = (r.minX + r.maxX) / 2f;
            float[] box = {r.size, r.size, -1, -1, r.size, r.size, -1, -1};
            for (int i = 0; i < r.size * r.size; i++) {
                int y = i / r.size;
                if (!r.ink[i] || r.luma[i] < 0.8f || r.sat[i] > 0.2f
                        || y < (r.minY + r.maxY) / 2) {
                    continue;
                }
                int x = i % r.size;
                int b = x < mid ? 0 : 4;
                box[b] = Math.min(box[b], x);
                box[b + 1] = Math.min(box[b + 1], y);
                box[b + 2] = Math.max(box[b + 2], x + 1);
                box[b + 3] = Math.max(box[b + 3], y + 1);
            }
            float t = (s.t + 0.3f) % 2.6f;
            float puff = t < 0.18f ? ObjectEffects.easeOut(t / 0.18f)
                    : t < 1.2f ? 1 - ObjectEffects.ease((t - 0.18f) / 1.02f) : 0;
            if (box[2] < 0 || box[6] < 0) {
                return;
            }
            s.liftHue(-1, 25, 0.15f);
            for (int side = 0; side < 2; side++) {
                int b = side * 4;
                float out = side == 0 ? -1 : 1;
                // Each grows from its inner top corner, where it leaves the nose.
                s.scaleLayerPiece(1, box[b] - 1.5f, box[b + 1] - 1.5f, box[b + 2] + 1.5f,
                        box[b + 3] + 1.5f, side == 0 ? box[2] : box[4], box[b + 1],
                        1 + 0.18f * puff);
                int k = s.partCount - 1;
                if (k >= 0 && s.partLayer[k] == 1) {
                    s.partShift[k * 2] = out * 0.8f * puff;
                }
            }
            s.busy = true;
        }

        @Override
        void apply(Scene s) {
        }
    },

    /** Wicked: now and then a glint flashes in the eye. */
    GLEAM {
        @Override
        void apply(Scene s) {
            blink(s);
            Regions r = s.regions;
            if (r.eyeCount == 0) {
                return;
            }
            float t = (s.t + 1.7f) % 3.6f;
            if (t > 0.6f) {
                return;
            }
            float[][] eyes = eyeCentres(r);
            float k = (float) Math.sin(t / 0.6f * Math.PI);
            s.glint(eyes[1][0] + 1.2f, eyes[1][1] - 1.2f, 1.2f + 1.6f * k, 0xFFFFFFFF, k);
            s.busy = true;
        }
    },

    /**
     * Juggling: the balls in the air (small, brightly coloured) loop up from one hand, over
     * the head to the other, and pass back behind the juggler.
     */
    JUGGLE {
        @Override
        void apply(Scene s) {
            juggle(s);
        }
    },

    /**
     * Real fire (🔥 🕯️ 🪔): the flame itself burns, its tongues licking and swaying up from
     * the root (see {@link Flame}); its heart brightens and dims with the gusts and now and
     * then a spark is carried up from the tips. Anchors: the flame's box (x0, y0, x1, y1) and
     * the row its root is on, as fractions of the emoji, and the reddest hue still flame; none:
     * all of it, rooted at the bottom.
     */
    BURN {
        @Override
        void pose(Scene s) {
            Regions r = s.regions;
            float[] a = s.anchor;
            boolean box = a != null && a.length >= 5;
            s.flameBox[0] = box ? MusicEffects.ax(s, 0) : r.minX;
            s.flameBox[1] = box ? MusicEffects.ay(s, 1) : r.minY;
            s.flameBox[2] = box ? MusicEffects.ax(s, 2) : r.maxX + 1;
            s.flameBox[3] = box ? MusicEffects.ay(s, 3) : r.maxY + 1;
            s.flameBox[4] = box ? MusicEffects.ay(s, 4) : r.maxY + 1;
            s.flameBox[5] = a != null && a.length >= 6 ? a[5] : 0;
            s.flameT = s.t * (1 + 0.6f * s.boost);
            s.busy = true;
        }

        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            float glow = 0.5f + 0.5f * (float) (Math.sin(s.t * 5.3) * 0.6
                    + Math.sin(s.t * 8.9 + 1.3) * 0.4);
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                int c = s.dots[i];
                if (ri < 0 || (c >>> 24) == 0 || r.hue[ri] > 62 && r.hue[ri] < 335
                        || r.luma[ri] < 0.55f) {
                    continue;
                }
                s.dots[i] = Scene.alpha(Scene.mix(c, 0xFFFFF4C0, 0.22f * glow), c >>> 24);
            }
            // A spark now and then, carried up from the tips into the clear air above.
            float life = cycle(s, 2.3f, 0);
            int cyc = (int) ((s.t + (s.seed & 0xFF) * 0.013f) / 2.3f);
            float tipY = s.flameBox[1];
            if (life < 0.7f && tipY > 4) {
                float q = life / 0.7f;
                float x = (s.flameBox[0] + s.flameBox[2]) / 2
                        + (Scene.hash(cyc, 1, s.seed) - 0.5f) * (s.flameBox[2] - s.flameBox[0])
                        * 0.4f + 0.8f * (float) Math.sin(q * 9);
                float y = tipY + 0.5f - Math.min(tipY - 1.5f, 5) * ObjectEffects.easeOut(q);
                s.blob(x, y, 0.5f, 0xFFFFC060, (float) Math.sin(q * Math.PI)
                        * (0.75f + 0.25f * (float) Math.sin(s.t * 31)));
            }
        }
    },

    /** Fizzy drinks: tiny bubbles stream up through the drink and burst at its top. */
    FIZZ {
        @Override
        void apply(Scene s) {
            fizz(s, 5 + Math.round(4 * s.boost));
        }
    },

    /**
     * Discs (💿 📀): spinning, a disc's rainbow stays where the light is, only shimmering as
     * the grooves pass under it; so the disc holds still and its colours play.
     */
    DISC {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            float[] hsv = new float[3];
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri < 0 || !r.ink[ri] || r.sat[ri] < 0.08f || r.luma[ri] < 0.45f) {
                    continue;
                }
                int c = s.dots[i];
                Detach.rgbToHsv(c, hsv);
                // Each band of the rainbow drifts through the spectrum at its own pace.
                float angle = (float) Math.atan2(ri / r.size - (r.minY + r.maxY) / 2f,
                        ri % r.size - (r.minX + r.maxX) / 2f);
                hsv[0] = (hsv[0] + 16 * (float) Math.sin(s.t * 1.3f + angle * 2) + 360) % 360;
                s.dots[i] = (hsvToRgb(hsv) & 0xFFFFFF) | (c & 0xFF000000);
            }
            s.busy = true;
        }
    },

    /** A halo: it hovers, rising and settling over the head, light gliding round it. */
    HALO {
        @Override
        void pose(Scene s) {
            s.liftHue(197);
            float bob = (float) Math.sin(s.t * Math.PI * 2 / 2.8f);
            s.addLayer(1, 0, 0, 0, 0, -0.6f - 0.9f * bob, 1);
            s.busy = true;
        }

        @Override
        void apply(Scene s) {
            blink(s);
            haloGlint(s);
        }
    },

    /** Crying: the emoji's own tears run (small ones slide, rivers flow and drip). */
    TEARS {
        @Override
        void pose(Scene s) {
            // Single tears run whole, as beads of sweat do; rivers of tears flow below.
            if (s.anchor == null || s.anchor.length == 0) {
                sweatDrops(s);
            }
        }

        @Override
        void apply(Scene s) {
            blink(s);
            if (s.regions.waterCount == 0) {
                fallbackTear(s);
                return;
            }
            if (s.anchor != null && s.anchor.length > 0 && s.anchor[0] == 1) {
                // Held back: the tears only well up, light trembling across them.
                Regions r = s.regions;
                for (int i = 0; i < s.dots.length; i++) {
                    int ri = s.restIndex(i);
                    if (ri < 0 || r.waterBody[ri] < 0) {
                        continue;
                    }
                    float w = (float) Math.sin(ri % r.size * 0.9f - s.t * (3 + 3 * s.boost));
                    if (w > 0.4f) {
                        float k = (w - 0.4f) / 0.6f;
                        s.dots[i] = Scene.alpha(Scene.mix(s.dots[i], 0xFFE8FBFF, 0.6f * k * k),
                                s.dots[i] >>> 24);
                    }
                }
                s.busy = true;
            } else if (!s.detached) {
                animateWater(s, 1 + s.boost);
            }
        }
    },

    /** Nervous: the bead of sweat slides down the face, glistening, and forms again. */
    SWEAT {
        @Override
        void pose(Scene s) {
            sweatDrops(s);
        }

        @Override
        void apply(Scene s) {
            blink(s);
            if (s.regions.waterCount == 0) {
                fallbackTear(s);
                return;
            }
            if (!s.detached) {
                animateWater(s, 0.7f + s.boost);
            }
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
        void pose(Scene s) {
            snoreBubble(s);
        }

        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            List<int[]> zs = drawnZs(r);
            if (zs.size() >= 2 && !hasBubble(r)) {
                // Its own Zs come one after another, z, zZ, zZZ, then fade together.
                float step = 0.5f;
                float pos = (s.t / step) % (zs.size() + 2.5f);
                int[] which = zOf(r, zs);
                for (int i = 0; i < s.dots.length; i++) {
                    int ri = s.restIndex(i);
                    if (ri < 0 || which[ri] < 0) {
                        continue;
                    }
                    float on = Math.max(0, Math.min(1, pos - which[ri]));
                    if (pos > zs.size() + 1) {
                        on *= Math.max(0, 1 - (pos - zs.size() - 1) / 1.2f);
                    }
                    s.dots[i] = Scene.alpha(Scene.scale(s.dots[i], 0.25f + 0.85f * on),
                            s.dots[i] >>> 24);
                }
                return;
            }
            if (hasBubble(r)) {
                return; // The bubble breathes (see pose).
            }
            float w = r.maxX - r.minX;
            float h = r.maxY - r.minY;
            for (int k = 0; k < 3; k++) {
                float life = cycle(s, 3.6f, k * 1.2f);
                float p = ObjectEffects.easeOut(life);
                // Up and out toward the corner, swaying, each one bigger than it started.
                float x = r.minX + w * (0.6f + 0.25f * p) + (float) Math.sin(life * 5) * 0.6f;
                float y = r.minY + h * (0.32f - 0.24f * p);
                float env = Math.min(1, life / 0.15f) * (life < 0.6f ? 1 : (1 - life) / 0.4f);
                zed(s, x, y, 2.2f + 2.2f * p, 0xFFCFE2FF, env);
            }
        }
    },

    /** Angry: the face flushes red in pulses, steam puffs from both sides. */
    ANGRY {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            // The face flushes red from the chin up, with the pulse.
            float pulse = 0.5f + 0.5f * (float) Math.sin(s.t * 6);
            float k = 0.18f + 0.14f * pulse + 0.3f * s.boost;
            float h = Math.max(1, r.maxY - r.minY);
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                // Only warm skin flushes: not a shirt, not hair.
                if (ri >= 0 && r.ink[ri] && !r.eyes[ri] && r.luma[ri] > 0.3f
                        && (r.hue[ri] < 55 || r.hue[ri] > 340) && r.sat[ri] > 0.3f) {
                    float up = Math.max(0, Math.min(1, (r.maxY - ri / r.size) / h));
                    float w = k * (1 - 0.6f * up * up);
                    s.dots[i] = Scene.alpha(Scene.mix(s.dots[i], 0xFFE5322D, w), s.dots[i] >>> 24);
                }
            }
            s.busy = true;
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
            float speed = 8 + 6 * s.boost;
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri < 0 || !r.ink[ri] || r.sat[ri] < 0.35f || r.hue[ri] > 60) {
                    continue;
                }
                int x = ri % r.size;
                int y = ri / r.size;
                float tip = (y - r.top[x]) / (float) Math.max(1, r.bottom[x] - r.top[x] + 1);
                // Waves of heat rising through it, each column a little out of step.
                float wave = (float) Math.sin(y * 0.9f + s.t * speed + x * 0.7f);
                float k = 0.88f + 0.18f * wave + 0.2f * s.boost;
                int c = Scene.mix(Scene.scale(s.dots[i], k), 0xFFFFE680,
                        (1 - tip) * 0.18f * (0.5f + 0.5f * wave));
                // The tips lick up and down smoothly, fading rather than blinking out.
                float lick = 0.16f + 0.12f * (float) Math.sin(s.t * 6.5f + x * 1.3f);
                float a = tip < lick ? Math.max(0, tip / lick) : 1;
                s.dots[i] = Scene.alpha(c, Math.round((s.dots[i] >>> 24) * a));
            }
            int embers = 2 + Math.round(4 * s.boost);
            for (int k = 0; k < embers; k++) {
                float life = cycle(s, 1.5f, k * 0.53f);
                int cyc = (int) ((s.t + k * 0.53f + (s.seed & 0xFF) * 0.013f) / 1.5f);
                float x = (r.minX + r.maxX) / 2f + (Scene.hash(k, cyc, s.seed) - 0.5f)
                        * (r.maxX - r.minX) * 0.6f + (float) Math.sin(life * 7 + k) * 0.7f;
                float y0 = Math.max(r.minY + 1, 6);
                float flicker = 0.7f + 0.3f * (float) Math.sin(s.t * 23 + k * 3);
                float go = ObjectEffects.easeOut(life) * 6;
                if (s.anchor != null && s.anchor.length >= 4) {
                    // Sparks thrown from a nozzle (a rocket's), along its way out.
                    float spread = (Scene.hash(k, cyc, s.seed) - 0.5f) * 1.6f;
                    x = ObjectEffects.edgeX(r, s.anchor[0]) + s.anchor[2] * go
                            - s.anchor[3] * spread;
                    y0 = ObjectEffects.edgeY(r, s.anchor[1]) + s.anchor[3] * go
                            + s.anchor[2] * spread;
                    go = 0;
                }
                s.blob(x, y0 - go, 0.55f, 0xFFFFB050, fade(life) * flicker);
            }
        }
    },

    /** Stars, night skies, magic: light catches points of the emoji and glints. */
    TWINKLE {
        @Override
        void apply(Scene s) {
            glints(s, 2 + Math.round(2 * s.boost));
            twinkleAround(s, 1 + Math.round(2 * s.boost));
        }
    },

    /** Dizzy: little stars circle round the head. */
    DIZZY {
        @Override
        void apply(Scene s) {
            dizzy(s);
        }
    },

    /** Objects: a band of light sweeps across now and then, like a reflection. */
    SHINE {
        @Override
        void apply(Scene s) {
            // A crisp highlight glides across the emoji now and then, easing in and out like
            // light moving over a polished surface.
            Regions r = s.regions;
            float period = 4.5f + 2 * Scene.hash(s.seed, 1, 1);
            float sweep = 0.9f;
            float local = s.since < sweep ? s.since : (s.t + Scene.hash(s.seed, 2, 1) * period)
                    % period;
            if (local > sweep) {
                return;
            }
            s.busy = true;
            float p = ObjectEffects.ease(local / sweep);
            // Anchor 1: only the dark glass catches it (sunglasses).
            boolean lensOnly = s.anchor != null && s.anchor.length > 0 && s.anchor[0] == 1;
            float span = (r.maxX - r.minX) + (r.maxY - r.minY) + 10;
            float band = p * span - 5;
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri < 0 || !r.ink[ri] || lensOnly && Regions.luma(r.rest[ri]) > 0.3f) {
                    continue;
                }
                float pos = ri % r.size - r.minX + ri / r.size - r.minY;
                float d = (pos - band) / 1.7f;
                float k = (float) Math.exp(-d * d);
                if (k > 0.03f) {
                    s.dots[i] = Scene.alpha(Scene.mix(s.dots[i], 0xFFFFFFFF, 0.6f * k),
                            s.dots[i] >>> 24);
                }
            }
        }
    },

    /** Lamps, signs, the moon: a soft glow breathes round it, its bright parts lifting. */
    GLOW {
        @Override
        void apply(Scene s) {
            float k = 0.5f - 0.5f * (float) Math.cos(s.t * 1.9f);
            glow(s, k * k);
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

    /** Rain clouds, umbrellas, showers: streaks of rain fall from beneath. */
    RAIN {
        @Override
        void apply(Scene s) {
            if (s.anchor != null && s.anchor.length > 0 && s.anchor[0] == 1) {
                rainOn(s, 8 + Math.round(6 * s.boost));
            } else {
                rain(s, 7 + Math.round(6 * s.boost));
            }
        }
    },

    /** Snow: soft flakes drift down, swaying. */
    SNOW {
        @Override
        void apply(Scene s) {
            snow(s, 9 + Math.round(5 * s.boost));
        }
    },

    /** Hot drinks and food: soft wisps of steam rise, curling, widening and fading. */
    STEAM {
        @Override
        void apply(Scene s) {
            steam(s);
        }
    },

    /** Music: notes float up and away. */
    NOTES {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            // Notes in soft, changing colours rise from either side and sway as they go.
            float base = Scene.hash(s.seed, 3, 3) * 360;
            float start = r.minY + (r.maxY - r.minY) * 0.6f;
            for (int k = 0; k < 3 + Math.round(2 * s.boost); k++) {
                float life = cycle(s, 2.6f, k * 0.87f);
                boolean right = k % 2 == 0;
                float x = (right ? r.maxX - 2.5f : r.minX + 1.5f)
                        + (float) Math.sin(life * 5 + k) * 1.3f;
                int color = Scene.hsv(base + k * 72 + s.t * 20, 0.35f, 1);
                float y = start - ObjectEffects.easeOut(life) * (start - 3);
                MusicEffects.note(s, x, y, color, Math.min(1, 1.4f * fade(life)));
            }
        }
    },

    /** Speakers: their own sound waves light up inner to outer on the beat (see MusicEffects). */
    SOUND {
        @Override
        void apply(Scene s) {
            MusicEffects.speaker(s);
        }
    },

    /** Muted speakers and bells: the red sign flares on the beat. */
    MUTED {
        @Override
        void apply(Scene s) {
            MusicEffects.muted(s);
        }
    },

    /** Bells: ding-ding, rings of light from where they are struck. */
    BELL {
        @Override
        void apply(Scene s) {
            MusicEffects.bell(s);
        }
    },

    /** Megaphones: syllables travelling through the horn to its mouth. */
    HORN {
        @Override
        void apply(Scene s) {
            MusicEffects.horn(s);
        }
    },

    /** Antennas and dishes: signal pulses from the tip. */
    SIGNAL {
        @Override
        void apply(Scene s) {
            MusicEffects.signal(s);
        }
    },

    /** Musical notes: each head lights up as its note sounds. */
    MELODY {
        @Override
        void apply(Scene s) {
            MusicEffects.notes(s);
        }
    },

    /** A score: a playhead runs along the staff. */
    SCORE {
        @Override
        void apply(Scene s) {
            MusicEffects.score(s);
        }
    },

    /** Headphones, radios, faders: equaliser bars to the beat. */
    EQUALIZER {
        @Override
        void apply(Scene s) {
            MusicEffects.equaliser(s);
        }
    },

    /** Microphones: the grille glints with a voice. */
    MIC {
        @Override
        void apply(Scene s) {
            MusicEffects.mic(s);
        }
    },

    /** Wind instruments: notes come out of the bell. */
    WIND {
        @Override
        void apply(Scene s) {
            MusicEffects.wind(s);
        }
    },

    /** String instruments: strummed strings vibrate. */
    STRUM {
        @Override
        void apply(Scene s) {
            MusicEffects.strum(s);
        }
    },

    /** Harps: a glissando across the strings. */
    GLISS {
        @Override
        void apply(Scene s) {
            MusicEffects.gliss(s);
        }
    },

    /** Keyboards: keys light up as a melody plays. */
    KEYS {
        @Override
        void apply(Scene s) {
            MusicEffects.keys(s);
        }
    },

    /** Drums: the head is hit and ripples. */
    DRUM {
        @Override
        void apply(Scene s) {
            MusicEffects.drum(s);
        }
    },

    /** Maracas: the beads rattle as they are shaken. */
    MARACAS {
        @Override
        void pose(Scene s) {
            MusicEffects.maracasPose(s);
        }

        @Override
        void apply(Scene s) {
        }
    },

    /** Mixing desks: the knobs turn. */
    KNOBS {
        @Override
        void pose(Scene s) {
            ObjectEffects.knobsPose(s);
        }

        @Override
        void apply(Scene s) {
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

    /**
     * Flags: the cloth ripples in the wind, more the farther from the pole. With an anchor
     * (pole x, the cloth's top and bottom) only the cloth moves: the pole and whatever it
     * stands in stay still.
     */
    FLAG {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            float speed = 3.5f + 3 * s.boost;
            float pole = r.minX;
            float top = -1;
            float bottom = r.size;
            if (s.anchor != null && s.anchor.length >= 3) {
                pole = ObjectEffects.edgeX(r, s.anchor[0]);
                top = ObjectEffects.edgeY(r, s.anchor[1]);
                bottom = ObjectEffects.edgeY(r, s.anchor[2]);
            }
            int[] out = s.dots.clone();
            for (int i = 0; i < s.dots.length; i++) {
                if (Float.isNaN(s.srcX[i])) {
                    continue;
                }
                float x = s.srcX[i];
                float y = s.srcY[i];
                if (x < pole + 0.5f || y < top - 1.5f || y > bottom + 1.5f) {
                    continue;
                }
                float wave = (float) Math.sin((x - pole) * 0.45f - s.t * speed);
                float amp = Math.min(1, (x - pole) / 6f) * 1.3f;
                int sx = Math.round(x);
                int sy = Math.round(y + wave * amp);
                if (sx < 0 || sy < 0 || sx >= r.size || sy >= r.size || !r.ink[sy * r.size + sx]
                        || sy < top - 1.5f || sy > bottom + 1.5f) {
                    out[i] = 0;
                    continue;
                }
                int c = r.rest[sy * r.size + sx];
                out[i] = Scene.scale(c, 0.82f + 0.18f
                        * (float) Math.cos((x - pole) * 0.45f - s.t * speed));
            }
            System.arraycopy(out, 0, s.dots, 0, out.length);
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

    /**
     * Lightning: the bolt flashes white twice, lighting what is round it (a storm cloud from
     * within), with a glow.
     */
    ZAP {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            float period = 3.2f + Scene.hash(s.seed, 2, 2);
            float p = s.since < 0.4f ? s.since : s.t % period;
            // Two strikes, the second fainter, each a quick flare and fall.
            float k = p < 0.12f ? 1 - p / 0.12f : p > 0.18f && p < 0.3f
                    ? 0.7f * (1 - (p - 0.18f) / 0.12f) : 0;
            if (k <= 0) {
                return;
            }
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri < 0 || !r.ink[ri]) {
                    continue;
                }
                float h = r.hue[ri];
                boolean bolt = r.sat[ri] > 0.5f && r.luma[ri] > 0.45f && h > 38 && h < 66;
                s.dots[i] = Scene.alpha(Scene.mix(s.dots[i], 0xFFFFFFF0, (bolt ? 0.8f : 0.3f) * k),
                        s.dots[i] >>> 24);
            }
            glow(s, k);
        }
    },

    /** A plug: blue-white sparks crackle at its pins and an arc jumps between them. */
    SPARK {
        @Override
        void apply(Scene s) {
            ObjectEffects.spark(s);
        }
    },

    /** An atom: electrons race round its orbits, trailing light. */
    ATOM {
        @Override
        void apply(Scene s) {
            ObjectEffects.atom(s);
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
            if (glance(s)) {
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

    /**
     * Clocks and watches: a red second hand ticks round, a step each second settling with
     * the tiny overshoot of a quartz movement.
     */
    CLOCK {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            float cx = (r.minX + r.maxX + 1) / 2f;
            float cy = (r.minY + r.maxY + 1) / 2f;
            float len = Math.min(r.maxX - r.minX, r.maxY - r.minY) * 0.34f;
            if (s.anchor != null && s.anchor.length >= 3) {
                // A face that is not the middle of the emoji (a mantel clock's).
                cx = ObjectEffects.edgeX(r, s.anchor[0]);
                cy = ObjectEffects.edgeY(r, s.anchor[1]);
                len = s.anchor[2] * (r.maxX - r.minX + 1);
            }
            float rate = 1 + 5 * s.boost;
            float tick = (float) Math.floor(s.t * rate);
            float step = tick + ObjectEffects.spring((s.t * rate - tick) / 0.3f);
            float a = (float) (step * Math.PI / 30 - Math.PI / 2 + Scene.hash(s.seed, 3, 9) * 6);
            float cos = (float) Math.cos(a);
            float sin = (float) Math.sin(a);
            int red = 0xFFE5322D;
            s.strip(cx + cos * len * 0.4f, cy + sin * len * 0.4f, len * 1.2f, 0.5f, a, red, 1);
            s.blob(cx, cy, 0.75f, red, 1);
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

    /** Water and drinks: bubbles rise, wobbling, growing, and pop at the surface. */
    BUBBLES {
        @Override
        void apply(Scene s) {
            bubbles(s, 5 + Math.round(3 * s.boost));
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

    /** Robots: their eyes glow like LEDs, breathing. */
    DIGITAL {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            // A lamp on top (a robot's antenna) blinks along.
            if (s.anchor != null && s.anchor.length >= 2) {
                ObjectEffects.record(s);
            }
            float g = 0.5f + 0.5f * (float) Math.sin(s.t * 3);
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri >= 0 && r.eyes[ri]) {
                    s.dots[i] = Scene.mix(0xFF00D8FF, 0xFFE8FFFF, 0.25f + 0.5f * g);
                }
            }
        }
    },

    /** 👾: the classic two-step march, legs in, legs out, like the arcade sprite. */
    INVADER {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            if (((int) (s.t / 0.5f)) % 2 == 0) {
                return;
            }
            float mid = (r.minX + r.maxX) / 2f;
            float legs = r.minY + (r.maxY - r.minY) * 0.68f;
            float ears = r.minY + (r.maxY - r.minY) * 0.18f;
            int[] out = s.dots.clone();
            for (int i = 0; i < s.dots.length; i++) {
                int y = i / s.size;
                int x = i % s.size;
                if (y < legs && y > ears) {
                    continue;
                }
                // Legs step in toward the middle, the antennae lean out.
                int dir = (x < mid ? 1 : -1) * (y >= legs ? -1 : 1);
                int sx = x + dir;
                out[i] = sx >= 0 && sx < s.size ? s.dots[y * s.size + sx] : 0;
            }
            System.arraycopy(out, 0, s.dots, 0, out.length);
        }
    },

    /** Buttons and keys: tapped now and then, the tile presses in and springs back. */
    PRESS {
        @Override
        void pose(Scene s) {
            float depth = press(s);
            if (depth > 0) {
                Regions r = s.regions;
                s.addPart(0, 0, r.size, r.size, 0, 0, 0, 0, 0.7f * depth);
                s.busy = true;
            }
        }

        @Override
        void apply(Scene s) {
            float depth = press(s);
            if (depth <= 0) {
                return;
            }
            for (int i = 0; i < s.dots.length; i++) {
                if ((s.dots[i] >>> 24) > 0) {
                    s.dots[i] = Scene.alpha(Scene.scale(s.dots[i], 1 - 0.18f * depth),
                            s.dots[i] >>> 24);
                }
            }
        }
    },

    /** Signal bars and wi-fi: the bars fill up one after another, as signal comes in. */
    SEQUENCE {
        @Override
        void apply(Scene s) {
            ObjectEffects.sequence(s);
        }
    },

    /** Parties: confetti strips tumble down, flashing as they turn. */
    CONFETTI {
        @Override
        void apply(Scene s) {
            confetti(s, 10 + Math.round(8 * s.boost));
        }
    },

    /** Fireworks: a flash, then sparks burst outward, fall and fade. */
    FIREWORKS {
        @Override
        void pose(Scene s) {
            burstPose(s);
        }

        @Override
        void apply(Scene s) {
            if (s.burstMode < 0) {
                fireworks(s);
            } else if (s.burstMode == Burst.BLOOM) {
                shell(s);
            }
        }
    },

    /** Smoke and wind: soft grey puffs drift away, growing and thinning. */
    SMOKE {
        @Override
        void apply(Scene s) {
            smoke(s);
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

    /**
     * Folders: the front panel swings on its bottom hinge. 📁 tips open (the front leans out
     * and shortens, the inside and the papers in it come into view) and shuts again; 📂 (anchor
     * negative) closes up and opens again. Drawn from the emoji's own dots, so its size never
     * changes; a touch opens it at once.
     */
    FOLDER {
        @Override
        void apply(Scene s) {
            Regions r = s.regions;
            Flap flap = flap(r);
            float open = folderOpen(s);
            if (flap == null || open <= 0) {
                if (flap == null) {
                    SHINE.apply(s);
                }
                return;
            }
            float swing = s.anchor != null && s.anchor.length > 0 ? s.anchor[0] : 0.45f;
            // How tall the front looks: shorter as it tips towards us, taller as it rises.
            float k = 1 - swing * open;
            for (int i = 0; i < s.dots.length; i++) {
                int ri = s.restIndex(i);
                if (ri < 0) {
                    continue;
                }
                int x = ri % r.size;
                int y = ri / r.size;
                int top = flap.top[x];
                int bottom = flap.bottom[x];
                if (bottom < 0) {
                    continue;
                }
                float height = bottom - top + 1;
                // Rising, the front never goes above the folder's own outline.
                float newTop = Math.max(flap.outline[x], bottom + 1 - height * k);
                if (y > bottom || y < Math.floor(Math.min(top, newTop))) {
                    continue;
                }
                if (y >= newTop - 0.5f) {
                    // On the front: the part of the panel that now shows here.
                    int src = Math.round(bottom - (bottom - y) / k);
                    src = Math.max(top, Math.min(bottom, src));
                    int c = r.rest[src * r.size + x];
                    s.dots[i] = Scene.alpha(Scene.scale(c, 1 + 0.06f * open), c >>> 24);
                } else if (y >= top) {
                    // Behind the lowered front: papers near its old top edge, then shadow.
                    boolean paper = y < top + (newTop - top) * 0.4f
                            && x > flap.left + 2 && x < flap.right - 2;
                    s.dots[i] = paper ? 0xFFF4F1E8 : Scene.alpha(flap.inside, 255);
                }
            }
        }
    },

    /** Game controllers: buttons pressed in combos, the d-pad clicks. */
    CONTROLLER {
        @Override
        void apply(Scene s) {
            ObjectEffects.controller(s);
        }
    },

    /** Joysticks: the stick tilts on its base, the fire button flashes. */
    JOYSTICK {
        @Override
        void pose(Scene s) {
            ObjectEffects.joystickPose(s);
        }

        @Override
        void apply(Scene s) {
            ObjectEffects.joystick(s);
        }
    },

    /** Cameras: the iris closes and opens, the flash fires. */
    CAMERA {
        @Override
        void pose(Scene s) {
            ObjectEffects.cameraPose(s);
        }

        @Override
        void apply(Scene s) {
            ObjectEffects.camera(s);
        }
    },

    /** Video cameras: the REC light blinks. */
    RECORD {
        @Override
        void apply(Scene s) {
            ObjectEffects.record(s);
        }
    },

    /** Film cameras: the REC lamp pulses and light runs round the reels. */
    FILM_CAMERA {
        @Override
        void apply(Scene s) {
            ObjectEffects.filmCamera(s);
        }
    },

    /** Film projectors: the reels turn under their arms. */
    REELS {
        @Override
        void pose(Scene s) {
            ObjectEffects.reelsPose(s);
        }

        @Override
        void apply(Scene s) {
        }
    },

    /** Frying: oil sizzles round the egg. */
    SIZZLE {
        @Override
        void apply(Scene s) {
            ObjectEffects.sizzle(s);
        }
    },

    /** A pot on a burner: the flame flickers, the pot steams. */
    BURNER {
        @Override
        void apply(Scene s) {
            ObjectEffects.burner(s);
        }
    },

    /** Traffic lights: green, amber, red. */
    TRAFFIC {
        @Override
        void apply(Scene s) {
            ObjectEffects.trafficLights(s);
        }
    },

    /** Hourglasses: the sand runs through. */
    HOURGLASS {
        @Override
        void pose(Scene s) {
            ObjectEffects.hourglassPose(s);
        }

        @Override
        void apply(Scene s) {
            ObjectEffects.hourglass(s);
        }
    },

    /** Batteries: charging up, or the low bar blinking. */
    BATTERY {
        @Override
        void apply(Scene s) {
            ObjectEffects.battery(s);
        }
    },

    /** Thermometers: the mercury rises and falls. */
    THERMOMETER {
        @Override
        void apply(Scene s) {
            ObjectEffects.thermometer(s);
        }
    },

    /** Compasses: the needle swings and settles. */
    COMPASS {
        @Override
        void pose(Scene s) {
            ObjectEffects.compassPose(s);
        }

        @Override
        void apply(Scene s) {
        }
    },

    /** Slot machines: the reels spin and stop one by one. */
    SLOTS {
        @Override
        void apply(Scene s) {
            ObjectEffects.slots(s);
        }
    },

    /** Vehicles: the wheels turn. */
    WHEELS {
        @Override
        void apply(Scene s) {
            ObjectEffects.wheels(s);
        }
    },

    /** Helicopters: the rotor blurs. */
    ROTOR {
        @Override
        void apply(Scene s) {
            ObjectEffects.rotor(s);
        }
    },

    /** Flying saucers: the lights round the rim blink on in turn. */
    CHASE {
        @Override
        void apply(Scene s) {
            ObjectEffects.chase(s);
        }
    },

    /** Barber poles: the stripes climb. */
    POLE {
        @Override
        void apply(Scene s) {
            ObjectEffects.pole(s);
        }
    },

    /** Round symbols on a tile (☯️ ☸️): the symbol turns, the tile stays. */
    SPIN {
        @Override
        void pose(Scene s) {
            ObjectEffects.spinPose(s);
        }

        @Override
        void apply(Scene s) {
        }
    },

    /** 🧰: the lid swings open, showing the tools inside, and drops shut. */
    TOOLBOX {
        @Override
        void pose(Scene s) {
            ObjectEffects.toolboxPose(s);
        }

        @Override
        void apply(Scene s) {
            ObjectEffects.toolbox(s);
        }
    },

    /** 🎠: the horse rides up and down its pole. */
    CAROUSEL {
        @Override
        void pose(Scene s) {
            ObjectEffects.carouselPose(s);
        }

        @Override
        void apply(Scene s) {
        }
    },

    /** 🎚️: the fader is pushed up and pulled back down its track. */
    FADER {
        @Override
        void pose(Scene s) {
            ObjectEffects.faderPose(s);
        }

        @Override
        void apply(Scene s) {
        }
    },

    /** Vehicles seen from the front: the headlights flash, twice. */
    HEADLIGHTS {
        @Override
        void apply(Scene s) {
            ObjectEffects.headlights(s);
        }
    },

    /** Police cars, ambulances, fire engines: the roof lights flash wig-wag. */
    SIREN {
        @Override
        void apply(Scene s) {
            ObjectEffects.siren(s);
        }
    },

    /** Phones: the handset rattles on its cradle, ring, ring. */
    RING {
        @Override
        void pose(Scene s) {
            ObjectEffects.ringPose(s);
        }

        @Override
        void apply(Scene s) {
        }
    },

    /** A service bell: the plunger is struck and the dome shines. */
    DING {
        @Override
        void pose(Scene s) {
            ObjectEffects.dingPose(s);
        }

        @Override
        void apply(Scene s) {
            ObjectEffects.ding(s);
        }
    },

    /** 🎯: a dart is thrown into the bull. */
    DARTS {
        @Override
        void pose(Scene s) {
            ObjectEffects.dartsPose(s);
        }

        @Override
        void apply(Scene s) {
        }
    },

    /** 🎾: the ball is bounced on the racket. */
    TENNIS {
        @Override
        void pose(Scene s) {
            ObjectEffects.tennisPose(s);
        }

        @Override
        void apply(Scene s) {
            ObjectEffects.tennis(s);
        }
    },

    /** Globes: the world turns. */
    GLOBE {
        @Override
        void pose(Scene s) {
            ObjectEffects.globePose(s);
        }

        @Override
        void apply(Scene s) {
            ObjectEffects.wireGlobe(s);
        }
    },

    /** Keyboards: typing in bursts. */
    TYPING {
        @Override
        void apply(Scene s) {
            ObjectEffects.typing(s);
        }
    },

    /** Mice: a double click, the wheel scrolls. */
    CLICK {
        @Override
        void apply(Scene s) {
            ObjectEffects.click(s);
        }
    },

    /** Printers: paper feeds out with print on it. */
    PRINT {
        @Override
        void apply(Scene s) {
            ObjectEffects.print(s);
        }
    },

    /** Chat bubbles: the typing dots rise one after another. */
    TYPING_DOTS {
        @Override
        void apply(Scene s) {
            ObjectEffects.typingDots(s);
        }
    },

    /** Clapper boards: the stick opens and snaps shut. */
    CLAPPER {
        @Override
        void pose(Scene s) {
            ObjectEffects.clapperPose(s);
        }

        @Override
        void apply(Scene s) {
            ObjectEffects.clapper(s);
        }
    },

    /** Padlocks: the shackle pops open and clicks shut. */
    LOCK {
        @Override
        void pose(Scene s) {
            ObjectEffects.lockPose(s);
        }

        @Override
        void apply(Scene s) {
            ObjectEffects.lock(s);
        }
    },

    /** Open books: a page turns. */
    PAGE_TURN {
        @Override
        void apply(Scene s) {
            ObjectEffects.pageTurn(s);
        }
    },

    /** Memos: lines of handwriting appear. */
    WRITING {
        @Override
        void apply(Scene s) {
            ObjectEffects.writing(s);
        }
    },

    /** Calendars: the date flips like a split-flap board. */
    DATE_FLIP {
        @Override
        void apply(Scene s) {
            ObjectEffects.dateFlip(s);
        }
    },

    /** Line charts: the line draws itself. */
    CHART_LINE {
        @Override
        void apply(Scene s) {
            ObjectEffects.chartLine(s);
        }
    },

    /** Bar charts: the bars dip and spring back up. */
    CHART_BARS {
        @Override
        void apply(Scene s) {
            ObjectEffects.chartBars(s);
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

    /**
     * Before the emoji is turned into dots: moves parts of the drawing (see
     * {@link Scene#addPart}). Most effects only paint dots afterwards and leave this empty.
     */
    void pose(Scene s) {
    }

    /** A display name: "STAR_EYES" reads "Star eyes". */
    public String title() {
        String s = name().replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** A folder's front panel: per column its top and bottom row, and the inside colour. */
    static final class Flap {
        final int[] top;
        final int[] bottom;
        /** The first inked row in each column. */
        final int[] outline;
        int left;
        int right;
        int inside;

        Flap(int size) {
            top = new int[size];
            bottom = new int[size];
            outline = new int[size];
            java.util.Arrays.fill(outline, size);
            java.util.Arrays.fill(top, -1);
            java.util.Arrays.fill(bottom, -1);
        }
    }

    /** The front panel: the large light area of the folder (the back is a shade darker). */
    static Flap flap(Regions r) {
        if (r.memo.containsKey("flap")) {
            return (Flap) r.memo.get("flap");
        }
        float brightest = 0;
        for (int i = 0; i < r.size * r.size; i++) {
            if (r.ink[i]) {
                brightest = Math.max(brightest, r.luma[i]);
            }
        }
        Flap flap = new Flap(r.size);
        long sr = 0, sg = 0, sb = 0;
        int back = 0, front = 0;
        flap.left = r.size;
        flap.right = -1;
        for (int y = 0; y < r.size; y++) {
            for (int x = 0; x < r.size; x++) {
                int i = y * r.size + x;
                if (!r.ink[i] || r.rest[i] >>> 24 < 175) {
                    continue;
                }
                flap.outline[x] = Math.min(flap.outline[x], y);
                if (r.luma[i] >= brightest - 0.12f) {
                    if (flap.top[x] < 0) {
                        flap.top[x] = y;
                    }
                    flap.bottom[x] = y;
                    flap.left = Math.min(flap.left, x);
                    flap.right = Math.max(flap.right, x);
                    front++;
                } else if (r.luma[i] > 0.35f) {
                    sr += (r.rest[i] >> 16) & 0xFF;
                    sg += (r.rest[i] >> 8) & 0xFF;
                    sb += r.rest[i] & 0xFF;
                    back++;
                }
            }
        }
        if (front < 40 || back < 10) {
            flap = null;
        } else {
            // The inside of the folder: the back panel in shadow.
            flap.inside = Scene.scale(0xFF000000 | (int) (sr / back) << 16
                    | (int) (sg / back) << 8 | (int) (sb / back), 0.72f);
        }
        r.memo.put("flap", flap);
        return flap;
    }

    /**
     * How far a folder has swung (0..1): rest, a quick swing, a hold, and back,
     * on a slow cycle; a reaction swings it at once and holds.
     */
    static float folderOpen(Scene s) {
        float swing = 0.55f;
        float hold = 1.5f;
        // A touch: open now, hold, close again; both swings eased, never a jump.
        float touched = 0;
        if (s.since < hold) {
            touched = ease(Math.min(1, s.since / swing));
        } else if (s.since < hold + swing) {
            touched = 1 - ease((s.since - hold) / swing);
        }
        float cycle = 4.6f + 1.4f * Scene.hash(s.seed, 8, 8);
        float t = (s.t + Scene.hash(s.seed, 9, 9) * cycle) % cycle;
        float rest = cycle - 2 * swing - hold;
        float own;
        if (t < rest) {
            own = 0;
        } else if (t < rest + swing) {
            own = ease((t - rest) / swing);
        } else if (t < rest + swing + hold) {
            own = 1;
        } else {
            own = 1 - ease(Math.min(1, (t - rest - swing - hold) / swing));
        }
        return Math.max(touched, own);
    }

    /** Ease in and out, like a flap with a little weight. */
    private static float ease(float x) {
        return x * x * (3 - 2 * x);
    }

    /** True for effects that belong on a face, so reactions can be expressive. */
    public boolean isFace() {
        switch (this) {
            case BLINK: case HEARTS: case TEARS: case SWEAT: case SLEEPY: case ANGRY:
            case STAR_EYES: case LOOK: case GHOSTLY: case KISS: case HALO:
                return true;
            default:
                return false;
        }
    }

    /**
     * The effect named by a spec from the emoji table, "NAME" or "NAME:a,b,…" (with anchor
     * points); anything unknown is {@link #SPARKLE}.
     */
    public static DotEffect parse(String name) {
        if (name != null && name.indexOf(':') >= 0) {
            name = name.substring(0, name.indexOf(':'));
        }
        if (name != null) {
            for (DotEffect e : values()) {
                if (e.name().equals(name)) {
                    return e;
                }
            }
        }
        return SPARKLE;
    }

    /** The anchor points of a spec ("NAME:0.2,0.5"), or null when it has none. */
    public static float[] anchor(String spec) {
        int colon = spec == null ? -1 : spec.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String[] parts = spec.substring(colon + 1).split(",");
        float[] out = new float[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                out[i] = Float.parseFloat(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    // -------------------------------------------------------------------------------------
    // Shared pieces
    // -------------------------------------------------------------------------------------

    private static final String[] HEART = {"x.x", "xxx", ".x."};

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

    /**
     * Beads of sweat, each lifted whole off the face: it swells out of the skin, hangs a moment,
     * runs down as far as the skin goes, gathering speed, and fades, leaving the skin under it.
     */
    private static void sweatDrops(Scene s) {
        Regions r = s.regions;
        int bodies = r.bodySize.length;
        if (r.waterCount == 0 || bodies == 0 || bodies > Scene.MAX_PARTS) {
            return;
        }
        for (int k = 0; k < bodies; k++) {
            if (!isDrop(r, k)) {
                return;
            }
        }
        int[] box = new int[bodies * 4];
        for (int k = 0; k < bodies; k++) {
            box[k * 4] = r.size;
            box[k * 4 + 1] = r.size;
        }
        for (int i = 0; i < r.size * r.size; i++) {
            int k = r.waterBody[i];
            if (k >= 0) {
                int x = i % r.size;
                int y = i / r.size;
                box[k * 4] = Math.min(box[k * 4], x);
                box[k * 4 + 1] = Math.min(box[k * 4 + 1], y);
                box[k * 4 + 2] = Math.max(box[k * 4 + 2], x + 1);
                box[k * 4 + 3] = Math.max(box[k * 4 + 3], y + 1);
            }
        }
        // Only the clear sweat blue: a pale or bluish face is not taken along with it.
        s.liftHue(199, 22, 0.4f);
        float period = 3.6f / (1 + 0.6f * s.boost);
        for (int k = 0; k < bodies; k++) {
            float life = cycle(s, period, k * 1.3f);
            float x0 = box[k * 4] - 1;
            float y0 = box[k * 4 + 1] - 1;
            float x1 = box[k * 4 + 2] + 1;
            float y1 = box[k * 4 + 3] + 1;
            // Down the skin as far as it goes; a bead at the jaw drips off it.
            float run = Math.min(6, Math.max(3, r.bodyRoom[k] - 1));
            float grow;
            float slide;
            float alpha;
            if (life < 0.15f) {
                grow = ObjectEffects.easeOut(life / 0.15f);
                slide = 0;
                alpha = Math.min(1, life / 0.08f);
            } else if (life < 0.35f) {
                grow = 1;
                slide = 0;
                alpha = 1;
            } else if (life < 0.85f) {
                float q = (life - 0.35f) / 0.5f;
                grow = 1;
                slide = run * (float) Math.pow(q, 1.7);
                alpha = q < 0.7f ? 1 : 1 - (q - 0.7f) / 0.3f;
            } else {
                continue;
            }
            s.addLayerPiece(1, x0, y0, x1, y1, 0, slide);
            int p = s.partCount - 1;
            if (p >= 0 && s.partLayer[p] == 1) {
                // It swells from where it clings, at its top.
                s.partPivot[p * 2] = (x0 + x1) / 2;
                s.partPivot[p * 2 + 1] = y0 + 1;
                s.partScale[p] = 0.45f + 0.55f * grow;
                s.partAlpha[p] = alpha;
            }
        }
        s.busy = true;
    }

    private static void blowKiss(Scene s) {
        float x = MusicEffects.ax(s, 0);
        float y = MusicEffects.ay(s, 1);
        float life = cycle(s, 3.2f, 0);
        if (life > 0.75f) {
            return;
        }
        float q = life / 0.75f;
        float rise = ObjectEffects.easeOut(q);
        // Out from the lips, up and to the side, swaying a little as it floats.
        float hx = x + 1 + 8 * rise + 0.7f * (float) Math.sin(q * Math.PI * 2.4f);
        float hy = y - 8 * rise;
        // Fades out before it would reach the frame's edge.
        hx = Math.min(hx, s.size - 3.5f);
        float across = 2.4f + 2.6f * ObjectEffects.easeOut(Math.min(1, q / 0.3f));
        float strength = q < 0.12f ? q / 0.12f : q > 0.6f ? (1 - q) / 0.4f : 1;
        s.heart(hx, hy, across, 0xFFF2384F, strength);
        s.busy = true;
    }

    /** Light gliding along the front of a lifted halo now and then. */
    private static void haloGlint(Scene s) {
        Regions r = s.regions;
        float x0 = r.size, x1 = -1, y0 = r.size, y1 = -1;
        for (int i = 0; i < r.size * r.size; i++) {
            if (r.ink[i] && r.sat[i] > 0.3f && Math.abs(r.hue[i] - 197) < 25) {
                x0 = Math.min(x0, i % r.size);
                x1 = Math.max(x1, i % r.size + 1);
                y0 = Math.min(y0, i / r.size);
                y1 = Math.max(y1, i / r.size + 1);
            }
        }
        float local = (s.t + 1.1f) % 4.2f;
        if (x1 < 0 || local > 1.4f) {
            return;
        }
        float q = ObjectEffects.ease(local / 1.4f);
        float a = (float) Math.PI * (1 - q);
        float bob = (float) Math.sin(s.t * Math.PI * 2 / 2.8f);
        float gx = (x0 + x1) / 2 + (float) Math.cos(a) * ((x1 - x0) / 2 - 1);
        float gy = (y0 + y1) / 2 + (float) Math.sin(a) * ((y1 - y0) / 2 - 0.8f) - 0.6f - 0.9f * bob;
        s.glint(gx, gy, 1.6f, 0xFFFFFFFF, (float) Math.sin(q * Math.PI));
        s.busy = true;
    }

    /** Where the eyes look, in turn: ahead, left, ahead, right, a little up; seconds each. */
    private static final int[][] GAZE = {{0, 0}, {-1, 0}, {0, 0}, {1, 0}, {0, -1}, {1, 0}};
    private static final float[] GAZE_HOLD = {1.4f, 0.9f, 0.7f, 1.0f, 0.8f, 0.6f};

    /**
     * Eyes with whites (👀): the pupils dart from one place to the next as real eyes do, a
     * whole dot or two at a time, never leaving the white. False if the eyes have no whites.
     */
    private static boolean glance(Scene s) {
        Regions r = s.regions;
        int[][] eyes = (int[][]) r.memo.get("eyeWhites");
        if (eyes == null) {
            eyes = eyeWhites(r);
            r.memo.put("eyeWhites", eyes);
        }
        if (eyes.length == 0) {
            return false;
        }
        float total = 0;
        for (float h : GAZE_HOLD) {
            total += h;
        }
        float t = (s.t + Scene.hash(s.seed, 3, 3) * total) % total;
        int step = 0;
        while (t > GAZE_HOLD[step]) {
            t -= GAZE_HOLD[step];
            step++;
        }
        int[] want = GAZE[step];
        // As far as every pupil can go that way, one or two dots.
        int dx = 0;
        int dy = 0;
        for (int reach = 2; reach >= 1 && dx == 0 && dy == 0; reach--) {
            if (fits(r, eyes, want[0] * reach, want[1] * reach)) {
                dx = want[0] * reach;
                dy = want[1] * reach;
            }
        }
        // eyes[0]: per dot, which eye's white it is in (or -1); eyes[1]: pupil dots; eyes[2]:
        // the colour of each eye's white.
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0 || eyes[0][ri] < 0) {
                continue;
            }
            int x = ri % r.size;
            int y = ri / r.size;
            int sx = x - dx;
            int sy = y - dy;
            int from = sx >= 0 && sy >= 0 && sx < r.size && sy < r.size ? sy * r.size + sx : -1;
            int c;
            if (from >= 0 && eyes[1][from] == 1 && eyes[0][from] == eyes[0][ri]) {
                c = r.rest[from];
            } else if (eyes[1][ri] == 1) {
                c = eyes[2][eyes[0][ri]];
            } else {
                continue;
            }
            s.dots[i] = (c & 0xFFFFFF) | (s.dots[i] & 0xFF000000);
        }
        return true;
    }

    /** True if every pupil moved by dx, dy stays inside its white. */
    private static boolean fits(Regions r, int[][] eyes, int dx, int dy) {
        for (int i = 0; i < r.size * r.size; i++) {
            if (eyes[1][i] != 1) {
                continue;
            }
            int x = i % r.size + dx;
            int y = i / r.size + dy;
            if (x < 0 || y < 0 || x >= r.size || y >= r.size
                    || eyes[0][y * r.size + x] != eyes[0][i]) {
                return false;
            }
        }
        return true;
    }

    /** The whites round the dark pupils (see glance); empty if a pupil has none. */
    private static int[][] eyeWhites(Regions r) {
        int n = r.size * r.size;
        int[] owner = new int[n];
        int[] pupil = new int[n];
        java.util.Arrays.fill(owner, -1);
        java.util.List<Integer> whites = new java.util.ArrayList<>();
        boolean[] seen = new boolean[n];
        int count = 0;
        for (int start = 0; start < n; start++) {
            if (!r.eyes[start] || seen[start]) {
                continue;
            }
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            java.util.List<Integer> comp = new java.util.ArrayList<>();
            queue.add(start);
            seen[start] = true;
            while (!queue.isEmpty()) {
                int i = queue.poll();
                comp.add(i);
                for (int j : around(r, i)) {
                    if (r.eyes[j] && !seen[j]) {
                        seen[j] = true;
                        queue.add(j);
                    }
                }
            }
            // The white: light dots joined to the pupil.
            java.util.List<Integer> white = new java.util.ArrayList<>();
            boolean[] in = new boolean[n];
            for (int i : comp) {
                in[i] = true;
            }
            queue.clear();
            for (int i : comp) {
                for (int j : around(r, i)) {
                    if (!in[j] && r.ink[j] && r.luma[j] > 0.7f && r.sat[j] < 0.25f) {
                        in[j] = true;
                        queue.add(j);
                    }
                }
            }
            while (!queue.isEmpty()) {
                int i = queue.poll();
                white.add(i);
                for (int j : around(r, i)) {
                    if (!in[j] && r.ink[j] && r.luma[j] > 0.6f && r.sat[j] < 0.25f) {
                        in[j] = true;
                        queue.add(j);
                    }
                }
            }
            if (white.size() < comp.size()) {
                return new int[0][];
            }
            // The light caught in the pupil goes with it: light dots with pupil on both sides.
            java.util.List<Integer> caught = new java.util.ArrayList<>();
            for (int i : white) {
                boolean left = false, right = false, up = false, down = false;
                int x = i % r.size;
                int y = i / r.size;
                for (int d = 1; d <= 3; d++) {
                    left |= x - d >= 0 && comp.contains(y * r.size + x - d);
                    right |= x + d < r.size && comp.contains(y * r.size + x + d);
                    up |= y - d >= 0 && comp.contains((y - d) * r.size + x);
                    down |= y + d < r.size && comp.contains((y + d) * r.size + x);
                }
                if (left && right || up && down) {
                    caught.add(i);
                }
            }
            white.removeAll(caught);
            comp.addAll(caught);
            long cr = 0, cg = 0, cb = 0;
            for (int i : white) {
                int c = r.rest[i];
                cr += (c >> 16) & 0xFF;
                cg += (c >> 8) & 0xFF;
                cb += c & 0xFF;
            }
            int m = white.size();
            whites.add(0xFF000000 | (int) (cr / m) << 16 | (int) (cg / m) << 8 | (int) (cb / m));
            for (int i : white) {
                owner[i] = count;
            }
            for (int i : comp) {
                owner[i] = count;
                pupil[i] = 1;
            }
            count++;
        }
        if (count == 0) {
            return new int[0][];
        }
        int[] colours = new int[count];
        for (int k = 0; k < count; k++) {
            colours[k] = whites.get(k);
        }
        return new int[][] {owner, pupil, colours};
    }

    private static int[] around(Regions r, int i) {
        int x = i % r.size;
        int y = i / r.size;
        int[] out = new int[8];
        int k = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx;
                int ny = y + dy;
                if ((dx != 0 || dy != 0) && nx >= 0 && ny >= 0 && nx < r.size && ny < r.size) {
                    out[k++] = ny * r.size + nx;
                }
            }
        }
        return java.util.Arrays.copyOf(out, k);
    }

    private static void fizz(Scene s, int count) {
        Regions r = s.regions;
        // Per column, the run of drink (coloured, not glass or foam): lowest and highest row.
        int[] runs = (int[]) r.memo.get("drink");
        if (runs == null) {
            runs = new int[r.size * 2];
            for (int x = 0; x < r.size; x++) {
                // The longest unbroken run in the column.
                int lo = -1;
                int hi = -1;
                for (int y = 0; y < r.size; y++) {
                    if (!drink(r, y * r.size + x)) {
                        continue;
                    }
                    int end = y;
                    while (end + 1 < r.size && drink(r, (end + 1) * r.size + x)) {
                        end++;
                    }
                    if (end - y > lo - hi) {
                        hi = y;
                        lo = end;
                    }
                    y = end;
                }
                runs[x * 2] = lo - hi >= 3 ? lo : -1;
                runs[x * 2 + 1] = hi;
            }
            r.memo.put("drink", runs);
        }
        int columns = 0;
        for (int x = 0; x < r.size; x++) {
            columns += runs[x * 2] >= 0 ? 1 : 0;
        }
        if (columns == 0) {
            SHINE.apply(s);
            return;
        }
        for (int k = 0; k < count; k++) {
            float period = 1.4f + 0.5f * Scene.hash(k, 3, s.seed);
            float life = cycle(s, period, k * 0.37f);
            int cyc = (int) ((s.t + k * 0.37f + (s.seed & 0xFF) * 0.013f) / period);
            int pick = (int) (Scene.hash(k, cyc, s.seed) * columns);
            int x = 0;
            while (runs[x * 2] < 0 || pick-- > 0) {
                x++;
            }
            int bottom = runs[x * 2] - 1;
            int top = runs[x * 2 + 1];
            int y = Math.round(bottom - (bottom - top) * life * life);
            float env = Math.min(1, life / 0.15f) * (life > 0.9f ? (1 - life) / 0.1f : 1);
            // A dot of light, on the drink only: never spilling past the glass.
            if (y >= top && drink(r, y * r.size + x)) {
                s.paintRest(x, y, Scene.alpha(0xFFFFFFF0, Math.round(230 * env)));
            }
        }
        s.busy = true;
    }

    /** A dot of the drink itself: clearly coloured, unlike glass, foam or a stand. */
    private static boolean drink(Regions r, int i) {
        return r.ink[i] && r.sat[i] > 0.4f && r.luma[i] > 0.2f;
    }

    private static void juggle(Scene s) {
        Regions r = s.regions;
        int[][] balls = (int[][]) r.memo.get("balls");
        if (balls == null) {
            balls = findBalls(r);
            r.memo.put("balls", balls);
        }
        if (balls.length < 2) {
            SHINE.apply(s);
            return;
        }
        // Take the balls out of the picture: what they hid is open air.
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri >= 0 && balls[0][ri] >= 0) {
                s.dots[i] = 0;
            }
        }
        int count = balls.length - 1;
        float top = r.size;
        float low = 0;
        for (int k = 1; k <= count; k++) {
            top = Math.min(top, balls[k][1] / 10f);
            low = Math.max(low, balls[k][1] / 10f);
        }
        float cx = (r.minX + r.maxX + 1) / 2f;
        float rx = (r.maxX - r.minX + 1) / 2f - 2.5f;
        float cy = (top + low + 3) / 2;
        float ry = Math.max(3, (low + 3 - top) / 2);
        float turn = (s.t / (2.4f / (1 + 0.8f * s.boost))) % 1;
        for (int k = 1; k <= count; k++) {
            float a = (float) (Math.PI * 2 * (turn + (k - 1f) / count));
            // Up the right, over the top, down the left: counter-clockwise on screen.
            float x = cx + rx * (float) Math.cos(a);
            float y = cy - ry * (float) Math.sin(a);
            boolean behind = Math.sin(a) < 0;
            float radius = balls[k][2] / 10f;
            s.ball(x, y, radius, balls[k][3], behind);
            if (!behind) {
                // The shine on it, up and to the left.
                s.ball(x - radius * 0.35f, y - radius * 0.35f, 0.6f, 0xFFFFFFFF, false);
            }
        }
        s.busy = true;
    }

    /**
     * The balls in the air: small, saturated, not skin-coloured blobs. Row 0: per dot, which
     * ball it is (or -1); then per ball: x, y (tenths of a dot), radius (tenths), colour.
     */
    private static int[][] findBalls(Regions r) {
        int n = r.size * r.size;
        int[] owner = new int[n];
        java.util.Arrays.fill(owner, -1);
        boolean[] seen = new boolean[n];
        java.util.List<int[]> found = new java.util.ArrayList<>();
        for (int start = 0; start < n; start++) {
            if (seen[start] || !ballDot(r, start)) {
                continue;
            }
            java.util.List<Integer> comp = new java.util.ArrayList<>();
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            queue.add(start);
            seen[start] = true;
            while (!queue.isEmpty()) {
                int i = queue.poll();
                comp.add(i);
                for (int j : around(r, i)) {
                    if (!seen[j] && ballDot(r, j)
                            && Math.abs(r.hue[j] - r.hue[start]) < 30) {
                        seen[j] = true;
                        queue.add(j);
                    }
                }
            }
            if (comp.size() < 3 || comp.size() > 16) {
                continue;
            }
            float sx = 0, sy = 0;
            int best = comp.get(0);
            for (int i : comp) {
                sx += i % r.size + 0.5f;
                sy += i / r.size + 0.5f;
                if (r.sat[i] * (1 - Math.abs(r.luma[i] - 0.5f)) > r.sat[best]
                        * (1 - Math.abs(r.luma[best] - 0.5f))) {
                    best = i;
                }
            }
            int k = found.size() + 1;
            for (int i : comp) {
                owner[i] = k - 1;
            }
            // Its own pure colour (not the highlight), a radius from its area.
            found.add(new int[] {Math.round(sx / comp.size() * 10), Math.round(sy / comp.size()
                    * 10), Math.round((float) Math.sqrt(comp.size() / Math.PI) * 10 + 2),
                    r.rest[best] | 0xFF000000});
        }
        // The rim and shine round each ball go with it: whatever touches a ball and is not
        // part of the juggler (the largest piece of the picture without the balls).
        int[] body = new int[n];
        int biggest = 0;
        int label = 0;
        int bodyLabel = -1;
        for (int start = 0; start < n; start++) {
            if (body[start] != 0 || !r.ink[start] || owner[start] >= 0) {
                continue;
            }
            label++;
            int size = 0;
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            queue.add(start);
            body[start] = label;
            while (!queue.isEmpty()) {
                int i = queue.poll();
                size++;
                for (int j : around(r, i)) {
                    if (body[j] == 0 && r.ink[j] && owner[j] < 0) {
                        body[j] = label;
                        queue.add(j);
                    }
                }
            }
            if (size > biggest) {
                biggest = size;
                bodyLabel = label;
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            int[] was = owner.clone();
            for (int i = 0; i < n; i++) {
                if (was[i] >= 0 || !r.ink[i] || body[i] == bodyLabel) {
                    continue;
                }
                for (int j : around(r, i)) {
                    if (was[j] >= 0) {
                        owner[i] = was[j];
                        break;
                    }
                }
            }
        }
        int[][] out = new int[found.size() + 1][];
        out[0] = owner;
        for (int k = 0; k < found.size(); k++) {
            out[k + 1] = found.get(k);
        }
        // Ball k's dots carry owner k - 1; rows from 1 on are balls in the same order.
        return out;
    }

    private static boolean ballDot(Regions r, int i) {
        return r.ink[i] && r.sat[i] > 0.45f && (r.hue[i] < 25 || r.hue[i] > 65)
                && r.luma[i] > 0.15f;
    }

    static int hsvToRgb(float[] hsv) {
        float h = hsv[0] / 60;
        float c = hsv[2] * hsv[1];
        float x = c * (1 - Math.abs(h % 2 - 1));
        float m = hsv[2] - c;
        float r, g, b;
        int k = (int) h;
        r = k == 0 || k == 5 ? c : k == 1 || k == 4 ? x : 0;
        g = k == 1 || k == 2 ? c : k == 0 || k == 3 ? x : 0;
        b = k == 3 || k == 4 ? c : k == 2 || k == 5 ? x : 0;
        return 0xFF000000 | Math.round((r + m) * 255) << 16 | Math.round((g + m) * 255) << 8
                | Math.round((b + m) * 255);
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

    /**
     * A soft halo of the emoji's own light round its outline, and its bright parts lifted a
     * little, by {@code k} (0..1); nothing is washed out.
     */
    static void glow(Scene s, float k) {
        Regions r = s.regions;
        k = Math.min(1, k + s.boost);
        // Its own light: the brightest colour it has, lifted toward white.
        int light = Scene.mix(r.brightest != 0 ? r.brightest : 0xFFFFFFFF, 0xFFFFFFFF, 0.25f);
        float[] near = haloDistance(r);
        for (int i = 0; i < s.dots.length; i++) {
            int ri = s.restIndex(i);
            if (ri < 0) {
                continue;
            }
            if (r.ink[ri]) {
                if (r.luma[ri] > 0.55f) {
                    s.dots[i] = Scene.alpha(Scene.mix(s.dots[i], 0xFFFFFFFF, 0.14f * k),
                            s.dots[i] >>> 24);
                }
            } else if (near[ri] < 2.6f) {
                float f = 1 - near[ri] / 2.6f;
                s.light(i, light, 0.42f * k * f * f);
            }
        }
    }

    /** Per resting dot: how far it is from the nearest inked dot (up to 3). */
    private static float[] haloDistance(Regions r) {
        float[] cached = (float[]) r.memo.get("haloDistance");
        if (cached != null) {
            return cached;
        }
        int n = r.size * r.size;
        float[] d = new float[n];
        for (int i = 0; i < n; i++) {
            if (r.ink[i]) {
                continue;
            }
            float best = 9;
            int x = i % r.size;
            int y = i / r.size;
            for (int oy = -3; oy <= 3; oy++) {
                for (int ox = -3; ox <= 3; ox++) {
                    if (r.in(x + ox, y + oy) && r.ink[(y + oy) * r.size + x + ox]) {
                        best = Math.min(best, (float) Math.sqrt(ox * ox + oy * oy));
                    }
                }
            }
            d[i] = best;
        }
        r.memo.put("haloDistance", d);
        return d;
    }

    /** Star glints just outside the emoji's outline, fading in and out. */
    static void twinkleAround(Scene s, int count) {
        Regions r = s.regions;
        int color = r.brightest != 0 ? Scene.mix(r.brightest, 0xFFFFFFFF, 0.6f) : 0xFFFFFFFF;
        for (int k = 0; k < count; k++) {
            float life = cycle(s, 1.9f, k * 0.71f);
            int cyc = (int) ((s.t + k * 0.71f + (s.seed & 0xFF) * 0.013f) / 1.9f);
            float rx = (r.maxX - r.minX) / 2f + 1.5f;
            float ry = (r.maxY - r.minY) / 2f + 1.5f;
            // Only where a whole star fits, in the clear: never cut off at the frame's edge.
            float x = -1;
            float y = -1;
            for (int tryNo = 0; tryNo < 8 && x < 0; tryNo++) {
                float angle = Scene.hash(k + 7 * tryNo, cyc, s.seed) * (float) (Math.PI * 2);
                float cx = (r.minX + r.maxX) / 2f + (float) Math.cos(angle) * rx;
                float cy = (r.minY + r.maxY) / 2f + (float) Math.sin(angle) * ry;
                int gx = Math.round(cx - 0.5f);
                int gy = Math.round(cy - 0.5f);
                boolean clear = gx >= 2 && gy >= 2 && gx < r.size - 2 && gy < r.size - 2;
                for (int dy = -2; dy <= 2 && clear; dy++) {
                    for (int dx = -2; dx <= 2 && clear; dx++) {
                        clear = !r.ink[(gy + dy) * r.size + gx + dx];
                    }
                }
                if (clear) {
                    x = cx;
                    y = cy;
                }
            }
            if (x < 0) {
                continue;
            }
            float env = (float) Math.sin(life * Math.PI);
            env *= env;
            s.glint(x, y, 0.6f + 1.4f * env, color, env);
        }
    }

    /** Glints on the emoji itself, where light would catch it: its brightest points. */
    static void glints(Scene s, int count) {
        Regions r = s.regions;
        int[] spots = glintSpots(r);
        if (spots.length == 0) {
            return;
        }
        for (int k = 0; k < count; k++) {
            float life = cycle(s, 1.6f, k * 0.83f);
            int cyc = (int) ((s.t + k * 0.83f + (s.seed & 0xFF) * 0.013f) / 1.6f);
            int spot = spots[(int) (Scene.hash(k, cyc, s.seed) * spots.length)];
            float env = (float) Math.sin(life * Math.PI);
            env *= env;
            s.glint(spot % r.size, spot / r.size, 0.5f + 1.6f * env, 0xFFFFFFFF, env);
        }
    }

    /** The emoji's highlights: its brightest inked dots, at most one per 3 x 3 block. */
    private static int[] glintSpots(Regions r) {
        int[] cached = (int[]) r.memo.get("glintSpots");
        if (cached != null) {
            return cached;
        }
        java.util.ArrayList<Integer> list = new java.util.ArrayList<>();
        for (int by = 0; by < r.size; by += 3) {
            for (int bx = 0; bx < r.size; bx += 3) {
                int best = -1;
                float bestLuma = 0.6f;
                for (int y = by; y < Math.min(r.size, by + 3); y++) {
                    for (int x = bx; x < Math.min(r.size, bx + 3); x++) {
                        int i = y * r.size + x;
                        if (r.ink[i] && r.luma[i] > bestLuma) {
                            bestLuma = r.luma[i];
                            best = i;
                        }
                    }
                }
                if (best >= 0) {
                    list.add(best);
                }
            }
        }
        // Prefer the brightest third: the true highlights.
        list.sort((p, q) -> Float.compare(r.luma[q], r.luma[p]));
        int keep = Math.max(1, Math.min(list.size(), (list.size() + 2) / 3));
        int[] spots = new int[list.isEmpty() ? 0 : keep];
        for (int i = 0; i < spots.length; i++) {
            spots[i] = list.get(i);
        }
        r.memo.put("glintSpots", spots);
        return spots;
    }

    /** Pale blue pieces: a sleeper's drawn Zs, or the bubble at a sleepy nose. */
    private static boolean sleepBlue(Regions r, int i) {
        return r.ink[i] && r.sat[i] > 0.2f && r.hue[i] > 185 && r.hue[i] < 260;
    }

    /** The drawn Zs, smallest first (they grow as they rise). */
    @SuppressWarnings("unchecked")
    private static List<int[]> drawnZs(Regions r) {
        Object cached = r.memo.get("drawnZs");
        if (cached != null) {
            return (List<int[]>) cached;
        }
        boolean[] mask = new boolean[r.size * r.size];
        for (int i = 0; i < mask.length; i++) {
            mask[i] = sleepBlue(r, i);
        }
        List<int[]> zs = new java.util.ArrayList<>();
        for (int[] c : MusicEffects.components(r, mask, true)) {
            if (c.length >= 2) {
                zs.add(c);
            }
        }
        zs.sort((a, b) -> a.length - b.length);
        r.memo.put("drawnZs", zs);
        return zs;
    }

    private static int[] zOf(Regions r, List<int[]> zs) {
        Object cached = r.memo.get("zOf");
        if (cached != null) {
            return (int[]) cached;
        }
        int[] out = new int[r.size * r.size];
        java.util.Arrays.fill(out, -1);
        for (int k = 0; k < zs.size(); k++) {
            for (int i : zs.get(k)) {
                out[i] = k;
            }
        }
        r.memo.put("zOf", out);
        return out;
    }

    /** A sleepy face's bubble: one big round blue piece low on the face. */
    private static boolean hasBubble(Regions r) {
        List<int[]> zs = drawnZs(r);
        if (zs.size() != 1) {
            return false;
        }
        int[] c = zs.get(0);
        int x0 = r.size, y0 = r.size, x1 = 0, y1 = 0;
        for (int i : c) {
            x0 = Math.min(x0, i % r.size);
            x1 = Math.max(x1, i % r.size);
            y0 = Math.min(y0, i / r.size);
            y1 = Math.max(y1, i / r.size);
        }
        float w = x1 - x0 + 1;
        float h = y1 - y0 + 1;
        // Round, filled and small: not a blanket or a cap.
        return c.length >= 12 && w / h > 0.6f && w / h < 1.7f && c.length > 0.55f * w * h
                && w < (r.maxX - r.minX) * 0.5f && MusicEffects.cy(r, c) > (r.minY + r.maxY) / 2f;
    }

    /** The bubble at a sleeping nose swells and shrinks with each slow breath. */
    static void snoreBubble(Scene s) {
        Regions r = s.regions;
        if (!hasBubble(r)) {
            return;
        }
        int[] c = drawnZs(r).get(0);
        int x0 = r.size, y0 = r.size, x1 = 0, y1 = 0;
        for (int i : c) {
            x0 = Math.min(x0, i % r.size);
            x1 = Math.max(x1, i % r.size);
            y0 = Math.min(y0, i / r.size);
            y1 = Math.max(y1, i / r.size);
        }
        float breath = 0.5f - 0.5f * (float) Math.cos(s.t * Math.PI * 2 / 3.2f);
        // It grows out of the nostril, at its upper inner corner.
        s.liftHue(215);
        s.scaleLayerPiece(1, x0 - 1, y0 - 1, x1 + 2, y1 + 2, x0 + 0.5f, y0 + 0.5f,
                0.55f + 0.5f * breath);
        s.busy = true;
    }

    /** How far a tapped button is pressed in (0..1): quick down, a held beat, a springy rise. */
    static float press(Scene s) {
        float period = 3.4f;
        float t = s.since < 0.6f ? s.since
                : (s.t + Scene.hash(s.seed, 8, 8) * period) % period;
        if (t < 0.07f) {
            return ObjectEffects.easeOut(t / 0.07f);
        }
        if (t < 0.14f) {
            return 1;
        }
        return t < 0.6f ? 1 - ObjectEffects.spring((t - 0.14f) / 0.46f) : 0;
    }

    /** A letter Z, {@code size} dots tall, centred at resting (x, y). */
    static void zed(Scene s, float x, float y, float size, int color, float strength) {
        float half = size / 2;
        s.strip(x, y - half, size, 0.6f, 0, color, strength);
        s.strip(x, y + half, size, 0.6f, 0, color, strength);
        s.strip(x, y, size * 1.3f, 0.6f, (float) (Math.PI * 0.75), color, strength);
    }

    /** The heart reds: a hue near red or pink, strongly coloured. */
    private static boolean heartRed(Regions r, int i) {
        float h = r.hue[i];
        return r.ink[i] && r.sat[i] > 0.45f && r.luma[i] > 0.25f && (h > 318 || h < 18);
    }

    /** The emoji's hearts (red or pink pieces of a fair size): per heart x0, y0, x1, y1. */
    private static float[][] heartBoxes(Regions r) {
        float[][] cached = (float[][]) r.memo.get("heartBoxes");
        if (cached != null) {
            return cached;
        }
        boolean[] mask = new boolean[r.size * r.size];
        for (int i = 0; i < mask.length; i++) {
            mask[i] = heartRed(r, i);
        }
        java.util.List<float[]> boxes = new java.util.ArrayList<>();
        for (int[] comp : MusicEffects.components(r, mask, true)) {
            if (comp.length < 4) {
                continue;
            }
            int x0 = r.size, y0 = r.size, x1 = 0, y1 = 0;
            for (int i : comp) {
                x0 = Math.min(x0, i % r.size);
                x1 = Math.max(x1, i % r.size);
                y0 = Math.min(y0, i / r.size);
                y1 = Math.max(y1, i / r.size);
            }
            boxes.add(new float[] {x0 - 0.6f, y0 - 0.6f, x1 + 1.6f, y1 + 1.6f, comp.length});
        }
        // The biggest few, so every heart can beat as a part of its own.
        boxes.sort((a, b) -> Float.compare(b[4], a[4]));
        cached = boxes.subList(0, Math.min(Scene.MAX_PARTS - 1, boxes.size()))
                .toArray(new float[0][]);
        r.memo.put("heartBoxes", cached);
        return cached;
    }

    /** One beat: a strong swell and a softer second, then rest (0..1). */
    static float beat(float t) {
        float lub = t < 0.14f ? (float) Math.sin(Math.PI * t / 0.14f) : 0;
        float dub = t > 0.22f && t < 0.36f ? 0.6f * (float) Math.sin(Math.PI * (t - 0.22f) / 0.14f)
                : 0;
        return lub + dub;
    }

    /** The emoji's hearts beat, each a moment after the last. */
    static void heartbeat(Scene s) {
        Regions r = s.regions;
        float[][] hearts = heartBoxes(r);
        if (hearts.length == 0) {
            return;
        }
        s.liftHue(350);
        // Every red piece where it rests (small ones, lips), the beating hearts over them.
        s.addLayer(1, 0, 0, 0, 0, 0, 1);
        float period = 1.25f - 0.35f * s.boost;
        for (int k = 0; k < hearts.length; k++) {
            float[] h = hearts[k];
            float t = (s.t + period - k * 0.12f) % period;
            float swell = beat(t);
            s.busy |= swell > 0;
            s.scaleLayerPiece(1, h[0], h[1], h[2], h[3], (h[0] + h[2]) / 2, (h[1] + h[3]) / 2,
                    1 + (0.22f + 0.1f * s.boost) * swell);
        }
    }

    /** Hearts rising from beside the emoji: swaying, growing a little, fading at the top. */
    static void hearts(Scene s, int count) {
        Regions r = s.regions;
        float w = r.maxX - r.minX;
        for (int k = 0; k < count; k++) {
            float life = cycle(s, 3.2f, k * (3.2f / count));
            int cyc = (int) ((s.t + k * (3.2f / count) + (s.seed & 0xFF) * 0.013f) / 3.2f);
            float side = Scene.hash(k, cyc, s.seed);
            float x0 = r.minX + w * (0.2f + 0.6f * side);
            float rise = ObjectEffects.easeOut(life);
            float x = x0 + (float) Math.sin(life * 6 + k * 2) * 1.1f * life;
            // From the upper half up to the panel's top edge: always inside the screen.
            float y0 = r.minY + (r.maxY - r.minY) * 0.62f;
            float y = y0 - rise * (y0 - 2.5f);
            float env = Math.min(1, life / 0.12f) * (life < 0.55f ? 1 : 1 - (life - 0.55f) / 0.45f);
            s.heart(x, y, 3 + 1.8f * ObjectEffects.easeOut(Math.min(1, life * 2.5f)),
                    0xFFFF3D68, env);
        }
    }

    /** Stars circling round the head, brighter as they pass in front. */
    static void dizzy(Scene s) {
        Regions r = s.regions;
        float cx = (r.minX + r.maxX + 1) / 2f;
        float cy = r.minY + (r.maxY - r.minY) * 0.08f;
        float rx = (r.maxX - r.minX) * 0.44f;
        float ry = (r.maxY - r.minY) * 0.11f;
        float speed = 2.2f + 2.5f * s.boost;
        for (int k = 0; k < 3; k++) {
            double a = s.t * speed + k * Math.PI * 2 / 3;
            float front = 0.5f + 0.5f * (float) Math.sin(a);
            s.glint(cx + (float) Math.cos(a) * rx, cy + (float) Math.sin(a) * ry,
                    1 + 1.2f * front, 0xFFFFFFFF, 0.55f + 0.45f * front);
        }
    }

    /** Streaks of rain falling from beneath the emoji's underside. */
    static void rain(Scene s, int drops) {
        Regions r = s.regions;
        for (int k = 0; k < drops; k++) {
            float period = 0.75f;
            float life = cycle(s, period, k * (period / drops) * 1.7f);
            int cyc = (int) ((s.t + k * (period / drops) * 1.7f + (s.seed & 0xFF) * 0.013f) / period);
            float fx = r.minX + 1 + Scene.hash(k, cyc, s.seed) * Math.max(1, r.maxX - r.minX - 2);
            int col = Math.max(0, Math.min(r.size - 1, Math.round(fx)));
            float from = r.bottom[col] >= 0 ? r.bottom[col] + 1 : r.maxY + 1;
            float y = from + life * (r.size + 2 - from);
            float env = Math.min(1, life / 0.15f);
            s.strip(fx - life * 0.6f, y, 1.9f, 0.45f, (float) Math.toRadians(80), 0xFF8FD3FF,
                    0.85f * env);
        }
    }

    /**
     * Rain falling from above onto the emoji (an umbrella): each drop splashes where it meets
     * the top of it, or falls on past its edge.
     */
    static void rainOn(Scene s, int drops) {
        Regions r = s.regions;
        for (int k = 0; k < drops; k++) {
            float period = 0.7f;
            float off = k * (period / drops) * 1.7f;
            float life = cycle(s, period, off);
            int cyc = (int) ((s.t + off + (s.seed & 0xFF) * 0.013f) / period);
            float fx = Scene.hash(k, cyc, s.seed) * (s.size - 1);
            int col = Math.max(0, Math.min(r.size - 1, Math.round(fx)));
            float stop = r.top[col] >= 0 ? r.top[col] - 0.5f : s.size + 2;
            float y = -2 + life * (s.size + 4);
            if (y < stop) {
                s.strip(fx, y, 1.9f, 0.45f, (float) Math.toRadians(82), 0xFF8FD3FF, 0.85f);
            } else if (y < stop + 2.5f) {
                // The splash: a little burst of spray, gone at once.
                float k2 = (y - stop) / 2.5f;
                s.blob(fx - 0.9f * k2, stop - 0.6f * k2, 0.45f, 0xFFCFEFFF, 0.8f * (1 - k2));
                s.blob(fx + 0.9f * k2, stop - 0.6f * k2, 0.45f, 0xFFCFEFFF, 0.8f * (1 - k2));
            }
        }
    }

    /** Soft flakes drifting down over the whole panel. */
    static void snow(Scene s, int flakes) {
        for (int k = 0; k < flakes; k++) {
            float period = 4f;
            float life = cycle(s, period, k * (period / flakes));
            int cyc = (int) ((s.t + k * (period / flakes) + (s.seed & 0xFF) * 0.013f) / period);
            float x = Scene.hash(k, cyc, s.seed) * s.size
                    + (float) Math.sin(s.t * 1.3f + k * 1.7f) * 1.3f;
            float big = 0.45f + 0.35f * Scene.hash(cyc, k, s.seed);
            s.blob(x, -1.5f + life * (s.size + 3), big + 0.4f, 0xFFFFFFFF,
                    0.9f * Math.min(1, life * 6) * Math.min(1, (1 - life) * 6));
        }
    }

    /** Steam: soft puffs rise from the top in curling strands, widening and thinning. */
    static void steam(Scene s) {
        Regions r = s.regions;
        float cx = (r.minX + r.maxX + 1) / 2f;
        float w = r.maxX - r.minX;
        int strands = w > 14 ? 3 : 2;
        float every = 0.3f;
        float span = 2.1f;
        for (int k = 0; k < strands; k++) {
            float sx = cx + (k - (strands - 1) / 2f) * Math.min(4.5f, w * 0.22f);
            int col = Math.max(0, Math.min(r.size - 1, Math.round(sx)));
            // Off the top of the food, or, when it fills the panel to the top, off its upper
            // part, so the steam is always seen rising.
            float top = Math.max(r.top[col] >= 0 ? r.top[col] : r.minY, 7);
            float phase = Scene.hash(k, 7, s.seed) * 6;
            for (int j = 0; j * every < span; j++) {
                float life = ((s.t + phase + j * every) % span) / span;
                float y = top - 0.5f - ObjectEffects.easeOut(life) * 8;
                float x = sx + (float) Math.sin(life * 4.5f + phase + s.t * 0.8f) * 1.6f * life;
                float env = Math.min(1, life / 0.12f) * (1 - life);
                s.blob(x, y, 0.7f + 1.5f * life, 0xFFFFFFFF, 0.7f * env);
            }
        }
    }

    /** Bubbles rising through and above the emoji, wobbling and growing, popping at the top. */
    static void bubbles(Scene s, int count) {
        Regions r = s.regions;
        for (int k = 0; k < count; k++) {
            float period = 2.6f;
            float life = cycle(s, period, k * (period / count));
            int cyc = (int) ((s.t + k * (period / count) + (s.seed & 0xFF) * 0.013f) / period);
            // Each leaves the creature's top edge and rises through the clear water above it,
            // never across the creature itself.
            float x0 = -1;
            float y0 = 0;
            for (int tryNo = 0; tryNo < 6 && x0 < 0; tryNo++) {
                int col = r.minX + 1 + (int) (Scene.hash(k + 5 * tryNo, cyc, s.seed)
                        * Math.max(1, r.maxX - r.minX - 2));
                int top = 0;
                while (top < r.size && !r.ink[top * r.size + col]) {
                    top++;
                }
                if (top >= 7 && top < r.size) {
                    x0 = col + 0.5f;
                    y0 = top - 0.5f;
                }
            }
            if (x0 < 0) {
                continue;
            }
            float y = y0 - Math.min(9, y0 - 1.5f) * (float) Math.pow(life, 1.3);
            float x = x0 + (float) Math.sin(life * 9 + k) * 0.6f;
            float radius = 0.6f + 0.5f * life;
            float env = Math.min(1, life / 0.1f);
            if (life > 0.9f) {
                // It pops: a quick swell, gone.
                float pop = (life - 0.9f) / 0.1f;
                radius += pop * 0.8f;
                env *= 1 - pop;
            }
            s.ring(x, y, radius, 0.45f, 0xFFCFF6FF, 0.75f * env);
            s.blob(x - radius * 0.45f, y - radius * 0.45f, 0.35f, 0xFFFFFFFF, 0.8f * env);
        }
    }

    /** Confetti strips falling over the panel, each tumbling so it flashes wide and thin. */
    static void confetti(Scene s, int count) {
        for (int k = 0; k < count; k++) {
            float period = 3f;
            float life = cycle(s, period, k * (period / count));
            int cyc = (int) ((s.t + k * (period / count) + (s.seed & 0xFF) * 0.013f) / period);
            float x = Scene.hash(k, cyc, s.seed) * s.size
                    + (float) Math.sin(s.t * 2.6f + k) * 1.4f;
            float y = -2 + life * (s.size + 4);
            float flip = (float) Math.abs(Math.cos(s.t * (5 + k % 3) + k));
            int color = Scene.hsv(Scene.hash(cyc, k, 5) * 360, 0.75f, 1);
            s.strip(x, y, 0.4f + 1.5f * flip, 0.7f, s.t * 2 + k, color,
                    0.6f + 0.4f * flip);
        }
    }

    /** Two bursts at a time: a flash, sparks flying out and falling, trailing, fading. */
    /** One firework: the shell rises, then the burst blooms, droops and burns out. */
    private static final float SHELL = 0.5f;
    private static final float BLOOM_TIME = 2.0f;
    private static final float FIREWORK = SHELL + BLOOM_TIME + 0.5f;

    /**
     * The drawn burst going off (see {@link Burst}). Anchors: mode (0 a firework, 1 crackling),
     * the centre x, y and reach (fractions of the emoji's width), and the kind of spark (see
     * {@link Burst}).
     */
    static void burstPose(Scene s) {
        Regions r = s.regions;
        float[] a = s.anchor;
        if (a == null || a.length < 4) {
            return;
        }
        float w = r.maxX - r.minX + 1;
        s.burstMode = Math.round(a[0]);
        s.burstSpec[0] = r.minX + a[1] * w;
        s.burstSpec[1] = r.minY + a[2] * (r.maxY - r.minY + 1);
        s.burstSpec[2] = a[3] * w;
        s.burstSpec[3] = a.length > 4 ? a[4] : 0;
        float t = (s.t + (s.seed & 0xFF) * 0.013f) % FIREWORK;
        s.burstLife = t < SHELL ? 0 : (t - SHELL) / BLOOM_TIME;
        s.burstT = s.t;
        s.busy = true;
    }

    /** The shell of a firework climbing to where it bursts, its trail glowing behind it. */
    private static void shell(Scene s) {
        float t = (s.t + (s.seed & 0xFF) * 0.013f) % FIREWORK;
        float x = s.burstSpec[0];
        float y1 = s.burstSpec[1];
        float y0 = s.regions.maxY - 1;
        if (t < SHELL) {
            float q = ObjectEffects.easeOut(t / SHELL);
            float y = y0 + (y1 - y0) * q;
            s.blob(x, y, 0.8f, 0xFFFFF0C0, 1);
            s.blob(x, y + 1.6f, 0.6f, 0xFFFFB060, 0.5f * (1 - q));
        } else if (t < SHELL + 0.12f) {
            // The flash of the burst.
            s.blob(x, y1, 2.2f, 0xFFFFFFFF, 1 - (t - SHELL) / 0.12f);
        }
    }

    static void fireworks(Scene s) {
        Regions r = s.regions;
        for (int k = 0; k < 2; k++) {
            float period = 2f;
            float life = cycle(s, period, k * period / 2);
            int cyc = (int) ((s.t + k * period / 2 + (s.seed & 0xFF) * 0.013f) / period);
            float cx = r.minX + 2 + Scene.hash(k, cyc, s.seed) * Math.max(1, r.maxX - r.minX - 4);
            float cy = r.minY + 2 + Scene.hash(cyc, k, s.seed) * (r.maxY - r.minY) * 0.5f;
            int color = Scene.hsv(Scene.hash(cyc, 3, k) * 360, 0.6f, 1);
            if (life < 0.12f) {
                s.blob(cx, cy, 1.8f, 0xFFFFFFFF, 1 - life / 0.12f);
            }
            float reach = 6 * ObjectEffects.easeOut(life);
            float fall = 2.5f * life * life;
            float env = (float) Math.pow(1 - life, 1.4);
            for (int ray = 0; ray < 10; ray++) {
                double a = ray * Math.PI / 5 + k;
                float dx = (float) Math.cos(a);
                float dy = (float) Math.sin(a);
                s.blob(cx + dx * reach, cy + dy * reach + fall, 0.6f, color, env);
                s.blob(cx + dx * reach * 0.7f, cy + dy * reach * 0.7f + fall * 0.6f, 0.45f,
                        color, 0.45f * env);
            }
        }
    }

    /**
     * Soft grey puffs drifting away, growing and thinning: from where the emoji table says
     * ({@code anchor}: per source x, y and the drift direction dx, dy), or else off its left.
     */
    static void smoke(Scene s) {
        Regions r = s.regions;
        float mid = (r.minY + r.maxY) / 2f;
        if (s.anchor != null && s.anchor.length >= 4) {
            int sources = s.anchor.length / 4;
            for (int k = 0; k < 5; k++) {
                int src = k % sources;
                float period = 2.8f;
                float life = cycle(s, period, k * period / 5);
                float x0 = ObjectEffects.edgeX(r, s.anchor[src * 4]);
                float y0 = ObjectEffects.edgeY(r, s.anchor[src * 4 + 1]);
                float dx = s.anchor[src * 4 + 2];
                float dy = s.anchor[src * 4 + 3];
                float go = ObjectEffects.easeOut(life) * 7;
                // Curling a little across its way as it goes.
                float curl = (float) Math.sin(life * 5 + k) * 0.8f * life;
                float x = x0 + dx * go - dy * curl;
                float y = y0 + dy * go + dx * curl;
                float env = Math.min(1, life / 0.15f) * (1 - life);
                s.blob(x, y, 0.8f + 1.5f * life, 0xFFEDEDED, 0.8f * env);
            }
            return;
        }
        for (int k = 0; k < 5; k++) {
            float period = 3f;
            float life = cycle(s, period, k * period / 5);
            float x = r.minX - 0.5f - ObjectEffects.easeOut(life) * 7;
            float y = mid + (k % 3 - 1) * 2.5f - life * 3 + (float) Math.sin(life * 5 + k) * 0.8f;
            float env = Math.min(1, life / 0.15f) * (1 - life);
            s.blob(x, y, 0.9f + 1.4f * life, 0xFFD8D8D8, 0.55f * env);
        }
    }

    /** Centres of the eye blobs, left to right (falls back to two guesses on a face). */
    static float[][] eyeCentres(Regions r) {
        float[][] cached = (float[][]) r.memo.get("eyeCentres");
        if (cached == null) {
            cached = findEyeCentres(r);
            r.memo.put("eyeCentres", cached);
        }
        return cached;
    }

    private static float[][] findEyeCentres(Regions r) {
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
