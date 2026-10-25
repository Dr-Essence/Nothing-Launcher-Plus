package com.dressence.nothinglauncherplus.sound;

import java.util.Random;

/**
 * The music engine: a voice pool, reverb and echo, a 16th-note clock and the {@link Composer}
 * state a {@link Style} writes into. Pure Java, no Android, so it renders the same on the phone
 * as in a test. Everything is tuned to A4 = 432 Hz.
 */
final class Synth {

    static final float A4 = 432f;
    private static final int VOICES = 40;

    final float rate;
    private final Voice[] voices = new Voice[VOICES];
    private final Effects effects;
    private final Composer composer;
    private Style style;
    private double samplesToStep;
    private int step;

    Synth(float rate, Style style, long seed) {
        this.rate = rate;
        for (int i = 0; i < VOICES; i++) {
            voices[i] = new Voice();
            voices[i].reset();
        }
        effects = new Effects(rate);
        composer = new Composer(this, new Random(seed));
        setStyle(style);
    }

    void setStyle(Style style) {
        this.style = style;
        effects.roomFeedback = style.room;
        effects.setEchoSeconds(style.echoSteps * stepSeconds(), rate);
        effects.echoFeedback = style.echoFeedback;
        composer.begin(style);
        step = 0;
        samplesToStep = 0;
    }

    float stepSeconds() {
        return 60f / style.bpm / 4f;
    }

    static float hz(float midi) {
        return (float) (A4 * Math.pow(2, (midi - 69) / 12.0));
    }

    /** A free voice (or the quietest busy one), reset and ready to configure. */
    Voice voice() {
        Voice quietest = voices[0];
        for (Voice v : voices) {
            if (!v.active) {
                v.reset();
                return v;
            }
        }
        quietest.reset(); // All busy: steal the oldest slot; rare with sparse styles.
        return quietest;
    }

    /** Fills {@code out} with mono samples in [-1, 1]. */
    void render(float[] out, int count) {
        float stepLength = stepSeconds() * rate;
        for (int i = 0; i < count; i++) {
            if (samplesToStep <= 0) {
                style.step(composer, step);
                // Swing: even 16ths longer, odd ones shorter, same total.
                float swing = step % 2 == 0 ? style.swing : 1 - style.swing;
                samplesToStep += stepLength * 2 * swing;
                step++;
            }
            samplesToStep--;
            float dry = 0;
            float wetIn = 0;
            float echoIn = 0;
            for (Voice v : voices) {
                if (v.active) {
                    float s = v.next();
                    dry += s;
                    wetIn += s * v.reverb;
                    echoIn += s * v.echo;
                }
            }
            float mixed = (dry + effects.reverb(wetIn) * style.reverbLevel + effects.echo(echoIn))
                    * style.level;
            // Gentle soft clip keeps chords from ever crackling.
            out[i] = mixed / (1 + Math.abs(mixed));
        }
    }
}
