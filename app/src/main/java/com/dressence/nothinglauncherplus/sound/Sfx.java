package com.dressence.nothinglauncherplus.sound;

/**
 * Interface sounds in the spirit of console menus: soft glassy ticks for moving, a rising
 * two-note chime for opening, falling for closing, a little fanfare for launching. All
 * synthesized (and tuned to 432 Hz) once, then played from memory.
 */
public enum Sfx {

    /** Focus moved (controller) or a page turned. */
    MOVE(new float[][] {{96, 0, 1}}, Patch.CHIME, 0.09f, 0.55f),
    PAGE(new float[][] {{91, 0, 0.8f}}, Patch.CHIME, 0.12f, 0.45f),
    OPEN_DRAWER(new float[][] {{79, 0, 0.8f}, {86, 0.07f, 1}}, Patch.BELL, 0.25f, 0.6f),
    CLOSE_DRAWER(new float[][] {{86, 0, 0.8f}, {79, 0.07f, 0.9f}}, Patch.BELL, 0.25f, 0.55f),
    OPEN_FOLDER(new float[][] {{84, 0, 0.8f}, {91, 0.05f, 1}}, Patch.CHIME, 0.18f, 0.7f),
    CLOSE_FOLDER(new float[][] {{91, 0, 0.7f}, {84, 0.05f, 0.8f}}, Patch.CHIME, 0.18f, 0.6f),
    LAUNCH(new float[][] {{72, 0, 0.7f}, {79, 0.05f, 0.8f}, {84, 0.1f, 1}, {91, 0.15f, 0.6f}},
            Patch.BELL, 0.3f, 0.6f),
    MENU(new float[][] {{60, 0, 0.9f}, {67, 0.04f, 0.7f}}, Patch.E_PIANO, 0.2f, 0.9f);

    static final float RATE = 24000;
    private static final float TAIL_SECONDS = 0.7f;

    /** Notes as {MIDI note, start seconds, level}. */
    private final float[][] notes;
    private final Patch patch;
    private final float noteSeconds;
    private final float level;

    Sfx(float[][] notes, Patch patch, float noteSeconds, float level) {
        this.notes = notes;
        this.patch = patch;
        this.noteSeconds = noteSeconds;
        this.level = level;
    }

    /** The sound as 16-bit mono PCM at {@link #RATE}, with a short reverb tail. */
    short[] render() {
        float last = notes[notes.length - 1][1];
        int length = (int) ((last + noteSeconds + TAIL_SECONDS) * RATE);
        Voice[] voices = new Voice[notes.length];
        int[] starts = new int[notes.length];
        for (int i = 0; i < notes.length; i++) {
            Voice v = new Voice();
            v.reset();
            patch.apply(v);
            v.freq = Synth.hz(notes[i][0]);
            v.gain *= notes[i][2] * level * 4;
            v.echo = 0;
            starts[i] = (int) (notes[i][1] * RATE);
            voices[i] = v;
        }
        Effects effects = new Effects(RATE);
        effects.roomFeedback = 0.78f;
        short[] pcm = new short[length];
        for (int n = 0; n < length; n++) {
            float dry = 0;
            float wet = 0;
            for (int i = 0; i < voices.length; i++) {
                if (n == starts[i]) {
                    voices[i].start(noteSeconds, RATE);
                }
                if (voices[i].active) {
                    float s = voices[i].next();
                    dry += s;
                    wet += s * voices[i].reverb;
                }
            }
            float mixed = dry + effects.reverb(wet) * 0.6f;
            mixed = mixed / (1 + Math.abs(mixed));
            // Short fade at the very end so the tail never clicks.
            float fade = Math.min(1, (length - n) / (0.05f * RATE));
            pcm[n] = (short) Math.round(mixed * fade * 32767);
        }
        return pcm;
    }
}
