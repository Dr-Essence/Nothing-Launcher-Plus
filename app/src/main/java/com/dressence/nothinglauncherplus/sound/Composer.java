package com.dressence.nothinglauncherplus.sound;

import java.util.Random;

/**
 * Writes the music as it plays: key, chord progression and melody, so it never repeats the way
 * a loop does. Melodies follow classic game-music phrasing: a one-bar motif, the motif again
 * on the next chord (shifted by scale degrees, so it fits), a contrasting bar, then a cadence
 * that settles on the chord. Every 16 bars a new progression and motif, every 32 a new key.
 */
final class Composer {

    static final int[] MAJOR = {0, 2, 4, 5, 7, 9, 11};
    static final int[] LYDIAN = {0, 2, 4, 6, 7, 9, 11};

    /** One melody note: start step in the bar, length in steps, scale degrees above the chord. */
    private static final class Note {
        final int step;
        final int length;
        final int offset;

        Note(int step, int length, int offset) {
            this.step = step;
            this.length = length;
            this.offset = offset;
        }
    }

    private static final Note[] CADENCE = {new Note(0, 4, 4), new Note(4, 4, 2), new Note(8, 8, 0)};

    final Random random;
    private final Synth synth;
    private Style style;
    private int root;
    private int[] scale = MAJOR;
    private int[] progression;
    private int bar = -1;
    private int chord;
    private Note[] motif;
    private Note[] answer;

    Composer(Synth synth, Random random) {
        this.synth = synth;
        this.random = random;
    }

    void begin(Style style) {
        this.style = style;
        scale = style.scale;
        root = style.roots[random.nextInt(style.roots.length)];
        progression = style.progressions[random.nextInt(style.progressions.length)];
        bar = -1;
    }

    /** Advances to the next bar; call on step 0 of each bar. */
    void nextBar() {
        bar++;
        if (bar > 0 && bar % 32 == 0) {
            root = style.roots[random.nextInt(style.roots.length)];
        }
        if (bar > 0 && bar % 16 == 0) {
            progression = style.progressions[random.nextInt(style.progressions.length)];
        }
        if (bar % 16 == 0) {
            motif = null;
        }
        chord = progression[(bar / style.barsPerChord) % progression.length];
        if (bar % 4 == 0) {
            answer = null;
        }
    }

    int bar() {
        return bar;
    }

    /** The current chord's root as a scale degree (0 = tonic). */
    int chord() {
        return chord;
    }

    /** MIDI note of scale {@code degree} (any integer) in the octave starting at {@code base}. */
    int note(int degree, int base) {
        int octave = Math.floorDiv(degree, 7);
        int inScale = Math.floorMod(degree, 7);
        return base + root + octave * 12 + scale[inScale];
    }

    /** MIDI notes of the current chord: {@code size} stacked thirds from the chord root. */
    int[] chordNotes(int size, int base) {
        int[] notes = new int[size];
        for (int i = 0; i < size; i++) {
            notes[i] = note(chord + 2 * i, base);
        }
        return notes;
    }

    boolean chance(float p) {
        return random.nextFloat() < p;
    }

    /** Plays one note of {@code patch}; {@code steps} is the held length in 16ths. */
    void play(Patch patch, float midi, float steps, float gain) {
        Voice v = synth.voice();
        patch.apply(v);
        v.freq = Synth.hz(midi);
        v.gain *= gain;
        v.start(steps * synth.stepSeconds(), synth.rate);
    }

    /**
     * Plays the melody note due at {@code step} of this bar, if any. {@code pentatonic} keeps
     * the line on the five-note scale of 8-bit tunes; {@code sparse} favours long notes.
     */
    void melody(int step, Patch patch, int base, float gain, boolean pentatonic, boolean sparse) {
        int phrase = bar % 4;
        if (motif == null) {
            motif = line(sparse);
        }
        if (answer == null) {
            answer = line(sparse);
        }
        Note[] notes = phrase == 2 ? answer : phrase == 3 ? CADENCE : motif;
        for (Note n : notes) {
            if (n.step == step) {
                int degree = chord + n.offset;
                if (pentatonic) {
                    int d = Math.floorMod(degree, 7);
                    if (d == 3 || d == 6) {
                        degree--; // No 4th or 7th: the open, bright 8-bit sound.
                    }
                }
                play(patch, note(degree, base), n.length, gain);
            }
        }
    }

    /** A one-bar line: a rhythm, chord tones on the beat, stepwise motion between. */
    private Note[] line(boolean sparse) {
        String[] rhythms = sparse ? SPARSE_RHYTHMS : RHYTHMS;
        String rhythm = rhythms[random.nextInt(rhythms.length)];
        java.util.ArrayList<Note> notes = new java.util.ArrayList<>();
        int offset = new int[] {0, 2, 4}[random.nextInt(3)];
        for (int i = 0; i < rhythm.length(); i++) {
            if (rhythm.charAt(i) != 'x') {
                continue;
            }
            int length = 1;
            while (i + length < rhythm.length() && rhythm.charAt(i + length) == '-') {
                length++;
            }
            if (!notes.isEmpty()) {
                offset += random.nextInt(5) - 2; // Mostly steps, sometimes a skip.
                offset = Math.max(-2, Math.min(9, offset));
            }
            if (i % 4 == 0) {
                offset = nearestChordTone(offset);
            }
            notes.add(new Note(i, length, offset));
        }
        return notes.toArray(new Note[0]);
    }

    private static int nearestChordTone(int offset) {
        int best = 0;
        for (int tone : new int[] {-3, 0, 2, 4, 7, 9}) {
            if (Math.abs(tone - offset) < Math.abs(best - offset)) {
                best = tone;
            }
        }
        return best;
    }

    // x = note, - = held, . = rest; 16 steps per bar.
    private static final String[] RHYTHMS = {
        "x-x-x---x-x-x---",
        "x--x--x-x-------",
        "x-x-xx--x---x---",
        "x---x-x-x-x-x---",
        "x-xx-x--x-xx-x--",
        "x--x--x---x-x---",
    };
    private static final String[] SPARSE_RHYTHMS = {
        "x-------x-------",
        "x-----x-x-------",
        "x---------x-----",
        "x-------x---x---",
    };
}
