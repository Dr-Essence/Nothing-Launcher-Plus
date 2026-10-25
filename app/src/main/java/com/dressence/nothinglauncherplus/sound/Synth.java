package com.dressence.nothinglauncherplus.sound;

import java.util.Random;

/**
 * The music engine: a voice pool, stereo hall reverb and ping-pong echo, a 16th-note clock and
 * the {@link Composer} state a {@link Style} writes into. Pure Java, no Android, so it renders
 * the same on the phone as in a test. Everything is tuned to A4 = 432 Hz.
 */
final class Synth {

    static final float A4 = 432f;
    private static final int VOICES = 48;

    final float rate;
    private final Voice[] voices = new Voice[VOICES];
    private final Effects effects;
    private final Composer composer;
    private Style style;
    private double samplesToStep;
    private int step;
    private float tonedL;
    private float tonedR;
    private float muffle;

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

    /** Plays the style's opening swell now (the start-up moment of a console menu). */
    void intro() {
        style.intro(composer);
    }

    /** 0 = as composed, 1 = fully muffled: closes the master filter, like a game launching. */
    void setMuffle(float amount) {
        muffle = Math.max(0, Math.min(1, amount));
    }

    float stepSeconds() {
        return 60f / style.bpm / 4f;
    }

    static float hz(float midi) {
        return (float) (A4 * Math.pow(2, (midi - 69) / 12.0));
    }

    /** A free voice (or, if all are busy, the first slot), reset and ready to configure. */
    Voice voice() {
        for (Voice v : voices) {
            if (!v.active) {
                v.reset();
                return v;
            }
        }
        voices[0].reset(); // All busy: rare with these sparse styles.
        return voices[0];
    }

    /** Fills {@code out} with {@code frames} interleaved stereo samples in [-1, 1]. */
    void render(float[] out, int frames) {
        float stepLength = stepSeconds() * rate;
        float tone = style.tone * (1 - 0.93f * muffle);
        for (int i = 0; i < frames; i++) {
            if (samplesToStep <= 0) {
                style.step(composer, step);
                // Swing: even 16ths longer, odd ones shorter, same total.
                float swing = step % 2 == 0 ? style.swing : 1 - style.swing;
                samplesToStep += stepLength * 2 * swing;
                step++;
            }
            samplesToStep--;
            float dryL = 0;
            float dryR = 0;
            float wetIn = 0;
            float echoL = 0;
            float echoR = 0;
            for (Voice v : voices) {
                if (v.active && v.next()) {
                    dryL += v.left;
                    dryR += v.right;
                    float mono = (v.left + v.right) * 0.5f;
                    wetIn += mono * v.reverb;
                    echoL += v.left * v.echo;
                    echoR += v.right * v.echo;
                }
            }
            effects.reverb(wetIn);
            effects.echo(echoL, echoR);
            float l = (dryL + effects.reverbL * style.reverbLevel + effects.echoOutL) * style.level;
            float r = (dryR + effects.reverbR * style.reverbLevel + effects.echoOutR) * style.level;
            tonedL += tone * (l - tonedL);
            tonedR += tone * (r - tonedR);
            // Gentle soft clip keeps chords from ever crackling.
            out[2 * i] = tonedL / (1 + Math.abs(tonedL));
            out[2 * i + 1] = tonedR / (1 + Math.abs(tonedR));
        }
    }
}
