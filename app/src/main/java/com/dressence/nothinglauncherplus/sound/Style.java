package com.dressence.nothinglauncherplus.sound;

/**
 * Background music styles. Each is an original generative piece in the manner of a console
 * era's menus: same kind of instruments and phrasing, new music every time. The first three are
 * the calm, low PlayStation-menu kind; the rest are brighter 8/16-bit and lounge styles. Degrees
 * are scale steps from the tonic (0 = I, 3 = IV, 4 = V, 5 = vi).
 */
public enum Style {
    /**
     * PS4-home style: one warm menu tone playing slow notes (about one every 0.7 s) that ring
     * into each other as broken chords, between F3 and G4; a phrase of eight bars, then near
     * silence for four, the way the console's theme breathes.
     */
    HOME_MENU("Home Menu", "PS4-style · one warm tone, slow ringing notes, space", 84, 0.5f,
            0.86f, 0.8f, 4, 0.3f, 2, Composer.MAJOR, new int[] {0, 2, 5, 7},
            new int[][] {{0, 3, 5, 4}, {3, 0, 4, 0}, {5, 3, 0, 4}, {0, 5, 3, 3}}, 4f, 0.6f) {
        private final int[][] shapes = {{0, 2, 4, 7}, {0, 4, 2, 4}, {4, 2, 0, 2}, {0, 2, 4, 2}};

        @Override
        void step(Composer c, int step) {
            int s = step % 16;
            if (s == 0) {
                c.nextBar();
            }
            if (c.bar() % 12 >= 8 || s % 4 != 0) {
                return; // Quarter notes only, and a rest after every phrase.
            }
            int[] shape = shapes[(c.bar() / 2) % shapes.length];
            if (c.chance(0.85f)) {
                int[] tones = c.voicing(52, shape);
                float pan = c.random.nextFloat() * 0.4f - 0.2f;
                c.play(Patch.MENU_TONE, tones[s / 4], 6, 0.7f + 0.3f * c.random.nextFloat(), pan);
            }
        }
    },

    /**
     * PS2-browser style, built from a reference recording: wide filtered noise breathing in and
     * out about every six seconds, a cluster of every note of the scale between F3 and F4 with a
     * low rumble, a slow soft ping line, and rare glints sliding down.
     */
    SYSTEM_BROWSER("System Browser", "PS2-style · breathing air, scale cluster, soft pings",
            60, 0.5f, 0.88f, 0.9f, 6, 0.4f, 2, Composer.MAJOR, new int[] {5, 7, 0, 2},
            new int[][] {{0, 5, 3, 4}, {5, 3, 0, 0}, {0, 3, 5, 4}}, 4f, 0.55f) {
        @Override
        void step(Composer c, int step) {
            int s = step % 16;
            if (s == 0) {
                c.nextBar();
                if (c.bar() % 4 == 0) {
                    // Overlapping wind layers, one per side, so the air never stops.
                    c.play(Patch.WIND, 108, 16 * 5, 1, -0.9f);
                    c.play(Patch.WIND, 109, 16 * 5, 1, 0.9f);
                }
                if (c.bar() % barsPerChord == 0) {
                    for (int note : c.voicing(53, 0, 1, 2, 4, 5, 7)) { // Scale cluster.
                        c.play(Patch.CLUSTER, note, 16 * barsPerChord - 4, 0.75f,
                                c.random.nextFloat() * 1.6f - 0.8f);
                    }
                    c.play(Patch.RUMBLE, c.note(c.chord(), 29), 16 * barsPerChord, 1);
                }
            }
            if (c.bar() % 8 >= 4 && s % 4 == 0 && c.chance(0.5f)) {
                int[] pings = c.voicing(79, 0, 2, 4, 7);
                c.play(Patch.PING, pings[c.random.nextInt(pings.length)], 2, 1,
                        c.random.nextFloat() * 1.4f - 0.7f);
            }
            if (c.chance(0.015f)) {
                int[] high = c.voicing(96, 0, 2, 4);
                c.play(Patch.GLINT, high[c.random.nextInt(3)], 3, 1,
                        c.random.nextFloat() * 1.4f - 0.7f);
            }
        }
    },

    /** Early-2000s console ambient menu: lydian pads, glass bells, deep drone. */
    MEMORY_CARD("Memory Card", "PS2-era memory screen · glass bells, lydian pads", 60, 0.5f,
            0.9f, 1.4f, 6, 0.45f, 2, Composer.LYDIAN, new int[] {0, 2, 4, 7},
            new int[][] {{0, 1, 0, 1}, {0, 1, 5, 3}, {0, 5, 1, 4}}, 2.58f, 0.3f) {
        @Override
        void step(Composer c, int step) {
            int s = step % 16;
            if (s == 0) {
                c.nextBar();
                if (c.bar() % barsPerChord == 0) {
                    int[] chord = c.chordNotes(4, 48);
                    for (int note : chord) {
                        c.play(Patch.PAD, note, 30, 0.7f);
                    }
                    c.play(Patch.DRONE, c.note(c.chord(), 36), 30, 1);
                }
            }
            if (c.chance(0.11f)) {
                int degree = c.chord() + 2 * c.random.nextInt(5);
                c.play(Patch.BELL, c.note(degree, 60), 2, 0.6f + 0.4f * c.random.nextFloat());
            }
            if (s == 8 && c.chance(0.15f)) {
                int[] up = c.chordNotes(4, 72);
                for (int i = 0; i < up.length; i++) {
                    c.play(Patch.CHIME, up[i], 1, 0.7f - 0.1f * i);
                }
            }
        }
    },

    /** Late-90s new-age menu by the sea: shimmering arpeggios, surf, flute. */
    CRYSTAL_SHORE("Crystal Shore", "90s console new-age · shimmer, surf, flute", 84, 0.5f,
            0.87f, 1.0f, 3, 0.4f, 1, Composer.MAJOR, new int[] {0, 2, 5, 9},
            new int[][] {{0, 4, 5, 3}, {3, 4, 0, 0}, {5, 3, 0, 4}, {0, 3, 5, 4}}, 1.25f, 0.45f) {
        @Override
        void step(Composer c, int step) {
            int s = step % 16;
            if (s == 0) {
                c.nextBar();
                for (int note : c.chordNotes(3, 55)) {
                    c.play(Patch.PAD, note, 15, 0.6f);
                }
                if (c.bar() % 2 == 0) {
                    c.play(Patch.SURF, 100, 24, 1);
                }
            }
            int[] tones = c.chordNotes(5, 72); // Root, 3rd, 5th, 7th, 9th.
            int[] order = {0, 2, 4, 1, 3, 4, 2, 1};
            c.play(Patch.CHIME, tones[order[s % 8]], 1, s % 4 == 0 ? 1 : 0.65f);
            if (s == 0 || s == 10) {
                c.play(Patch.SINE_BASS, c.note(c.chord(), 36), s == 0 ? 8 : 5, 0.7f);
            }
            if (c.bar() % 8 >= 4) {
                c.melody(s, Patch.FLUTE, 72, 0.8f, true, true);
            }
        }
    },

    /** Console shop / lounge: swung electric piano, walking bass, vibes, brushes. */
    SHOP_LOUNGE("Shop Lounge", "console shop jazz · e-piano, walking bass, vibes", 104, 0.64f,
            0.82f, 0.6f, 3, 0.2f, 1, Composer.MAJOR, new int[] {0, 3, 5, 8},
            new int[][] {{3, 2, 1, 0}, {1, 4, 0, 5}, {0, 5, 1, 4}, {0, 3, 2, 5}}, 0.86f, 0.55f) {
        @Override
        void step(Composer c, int step) {
            int s = step % 16;
            if (s == 0) {
                c.nextBar();
            }
            if (s == 0 || s == 6 || (s == 10 && c.chance(0.6f))) {
                int[] chord = c.chordNotes(c.chance(0.4f) ? 5 : 4, 55);
                for (int i = 1; i < chord.length; i++) {
                    c.play(Patch.E_PIANO, chord[i], s == 0 ? 4 : 2, 1);
                }
            }
            if (s % 4 == 0) {
                // Walking bass: root, chord tone, scale tone, then a step towards the next root.
                int[] walk = {0, 2, 4, 5};
                c.play(Patch.SINE_BASS, c.note(c.chord() + walk[s / 4], 36), 3.5f, 0.9f);
            }
            if (s == 4 || s == 12) {
                c.play(Patch.BRUSH, 96, 2, 0.7f);
            }
            if (s % 2 == 0) {
                c.play(Patch.HAT, 120, 1, s % 4 == 2 ? 0.5f : 0.3f);
            }
            if (c.bar() % 4 != 0) {
                c.melody(s, Patch.VIBES, 72, 0.9f, false, false);
            }
        }
    },

    /** 16-bit lullaby: music box arpeggios, soft pad, airy flute. */
    STARLIGHT("Starlight Lullaby", "16-bit dream · music box, pad, flute", 70, 0.5f,
            0.86f, 0.9f, 3, 0.4f, 1, Composer.MAJOR, new int[] {0, 3, 5, 7},
            new int[][] {{0, 3, 5, 4}, {0, 2, 3, 4}, {3, 0, 4, 5}, {0, 5, 1, 4}}, 1.09f, 0.6f) {
        @Override
        void step(Composer c, int step) {
            int s = step % 16;
            if (s == 0) {
                c.nextBar();
                for (int note : c.chordNotes(3, 48)) {
                    c.play(Patch.PAD, note, 15, 0.8f);
                }
                c.play(Patch.SINE_BASS, c.note(c.chord(), 36), 6, 0.6f);
            }
            if (s % 2 == 0) {
                int[] up = c.chordNotes(4, 72);
                int[] order = {0, 1, 2, 3, 2, 1, 2, 1};
                c.play(Patch.MUSIC_BOX, up[order[s / 2]], 2, 1);
            }
            if (c.bar() % 8 >= 4) {
                c.melody(s, Patch.FLUTE, 72, 0.9f, false, true);
            }
        }
    },

    /** 8-bit overworld: pulse lead, triangle bass, soft noise hats. */
    PIXEL_MEADOW("Pixel Meadow", "8-bit overworld · pulse lead, triangle bass", 96, 0.5f,
            0.8f, 0.5f, 3, 0.3f, 1, Composer.MAJOR, new int[] {0, 2, 5, 7},
            new int[][] {{0, 5, 3, 4}, {0, 4, 5, 3}, {3, 4, 2, 5}, {0, 3, 0, 4}}, 0.74f, 1f) {
        @Override
        void step(Composer c, int step) {
            int s = step % 16;
            if (s == 0) {
                c.nextBar();
            }
            c.melody(s, Patch.PULSE_LEAD, 60, 1, true, false);
            if (s % 2 == 0) {
                int[] chord = c.chordNotes(3, 60);
                c.play(Patch.PULSE_ARP, chord[(s / 2) % 3], 1.5f, 1);
            }
            if (s % 4 == 0) {
                int[] bass = {0, 4, 7, 4}; // Root, fifth, octave, fifth.
                c.play(Patch.TRIANGLE_BASS, c.note(c.chord() + bass[s / 4], 36), 3, 1);
            }
            if (s % 4 == 2) {
                c.play(Patch.HAT, 118, 1, 1);
            }
            if (s == 0 || s == 8) {
                c.play(Patch.KICK, 50, 1, 1);
            }
        }
    };
    public final String title;
    public final String summary;
    final float bpm;
    /** Share of each 8th taken by its first 16th: 0.5 straight, about 0.64 swung. */
    final float swing;
    final float room;
    final float reverbLevel;
    final int echoSteps;
    final float echoFeedback;
    final int barsPerChord;
    final int[] scale;
    final int[] roots;
    final int[][] progressions;
    /** Output gain, so every style plays at about the same loudness. */
    final float level;
    /** Master low-pass (1 = open): how dark the style is; the console-menu ones are kept low. */
    final float tone;

    Style(String title, String summary, float bpm, float swing, float room, float reverbLevel,
            int echoSteps, float echoFeedback, int barsPerChord, int[] scale, int[] roots,
            int[][] progressions, float level, float tone) {
        this.title = title;
        this.summary = summary;
        this.bpm = bpm;
        this.swing = swing;
        this.room = room;
        this.reverbLevel = reverbLevel;
        this.echoSteps = echoSteps;
        this.echoFeedback = echoFeedback;
        this.barsPerChord = barsPerChord;
        this.scale = scale;
        this.roots = roots;
        this.progressions = progressions;
        this.level = level;
        this.tone = tone;
    }

    /** Called on every 16th note; step 0 of each bar must call {@link Composer#nextBar}. */
    abstract void step(Composer c, int step);

    /** The opening when the music starts after a long silence: the menu tone swelling in. */
    void intro(Composer c) {
        for (int note : c.voicing(55, 0, 2, 4)) {
            c.play(Patch.CLUSTER, note, 24, 0.7f);
        }
    }


    public static Style parse(String name) {
        for (Style s : values()) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return HOME_MENU;
    }
}
