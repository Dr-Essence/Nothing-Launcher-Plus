package com.dressence.nothinglauncherplus.sound;

/**
 * One synthesizer voice: an oscillator through an envelope and a one-pole low-pass. The
 * oscillators are the ones that defined console sound: NES-style pulse, stepped triangle and
 * LFSR noise, a detuned saw pair for pads, and two-operator FM for bells, electric piano and
 * music boxes. Voices are pooled; nothing allocates while playing.
 */
final class Voice {

    static final int SINE = 0;
    static final int PULSE = 1;
    static final int TRIANGLE = 2;
    static final int PAD = 3;
    static final int NOISE = 4;
    static final int FM = 5;

    private static final double TAU = Math.PI * 2;

    boolean active;
    int wave;
    float freq;
    float gain;
    float duty;
    /** Envelope: attack and release in seconds, decay as a time constant, sustain 0..1. */
    float attack;
    float decay;
    float sustain;
    float release;
    float vibratoDepth;
    float vibratoRate;
    /** Pitch fall per second (drum "kicks" and laser drops), as an exponent. */
    float drop;
    /** One-pole low-pass coefficient: 1 = open, smaller = darker. */
    float cutoff;
    float fmRatio;
    float fmIndex;
    float fmDecay;
    float detune;
    /** Shares of this voice sent to the reverb and the echo. */
    float reverb;
    float echo;

    private float rate;
    private double phase;
    private double phase2;
    private double modPhase;
    private int age;
    private int gate;
    private boolean releasing;
    private float env;
    private float releaseMul;
    /** Per-sample multipliers replacing exp() in the decay and FM-index curves. */
    private float decayMul;
    private float decayLevel;
    private float fmMul;
    private float fmLevel;
    private float lowpass;
    private int lfsr;
    private double noisePhase;
    private float noise;

    void reset() {
        active = false;
        wave = SINE;
        freq = 440;
        gain = 0.1f;
        duty = 0.5f;
        attack = 0.005f;
        decay = 0.3f;
        sustain = 0;
        release = 0.1f;
        vibratoDepth = 0;
        vibratoRate = 5;
        drop = 0;
        cutoff = 1;
        fmRatio = 1;
        fmIndex = 0;
        fmDecay = 0.2f;
        detune = 1.006f;
        reverb = 0.2f;
        echo = 0;
    }

    /** Starts the note for {@code seconds} (then releases) at {@code sampleRate}. */
    void start(float seconds, float sampleRate) {
        rate = sampleRate;
        phase = 0;
        phase2 = 0.37;
        modPhase = 0;
        age = 0;
        gate = Math.max(1, (int) (seconds * sampleRate));
        releasing = false;
        env = 0;
        releaseMul = (float) Math.exp(-5.0 / Math.max(1, release * sampleRate));
        decayMul = (float) Math.exp(-1.0 / Math.max(1, decay * sampleRate));
        decayLevel = 1;
        fmMul = (float) Math.exp(-1.0 / Math.max(1, fmDecay * sampleRate));
        fmLevel = 1;
        lowpass = 0;
        lfsr = 1;
        noisePhase = 0;
        noise = 1;
        active = true;
    }

    /** The next sample, or 0 once the voice has finished (it then marks itself inactive). */
    float next() {
        float t = age / rate;
        if (!releasing && age >= gate) {
            releasing = true;
        }
        if (releasing) {
            env *= releaseMul;
            if (env < 1e-4f) {
                active = false;
                return 0;
            }
        } else if (t < attack) {
            env = t / attack;
        } else {
            decayLevel *= decayMul;
            env = sustain + (1 - sustain) * decayLevel;
            if (sustain == 0 && env < 1e-4f) {
                active = false;
                return 0;
            }
        }
        age++;

        double f = freq;
        if (vibratoDepth > 0 && t > 0.2f) {
            f *= 1 + vibratoDepth * Math.sin(TAU * vibratoRate * t);
        }
        if (drop > 0) {
            f *= Math.exp(-drop * t);
        }
        double step = f / rate;
        float s;
        switch (wave) {
            case PULSE:
                s = phase < duty ? 1 : -1;
                break;
            case TRIANGLE:
                // 4-bit stepped triangle, like the NES.
                float tri = (float) (phase < 0.5 ? 4 * phase - 1 : 3 - 4 * phase);
                s = Math.round(tri * 7.5f) / 7.5f;
                break;
            case PAD:
                phase2 += step * detune;
                phase2 -= Math.floor(phase2);
                s = (float) ((2 * phase - 1) + (2 * phase2 - 1)) * 0.5f;
                break;
            case NOISE:
                noisePhase += step;
                while (noisePhase >= 1) {
                    noisePhase -= 1;
                    int bit = (lfsr ^ (lfsr >> 1)) & 1;
                    lfsr = (lfsr >> 1) | (bit << 14);
                    noise = (lfsr & 1) == 0 ? 1 : -1;
                }
                s = noise;
                break;
            case FM:
                modPhase += step * fmRatio;
                modPhase -= Math.floor(modPhase);
                fmLevel *= fmMul;
                double index = fmIndex * fmLevel;
                s = (float) Math.sin(TAU * phase + index * Math.sin(TAU * modPhase));
                break;
            default:
                s = (float) Math.sin(TAU * phase);
                break;
        }
        phase += step;
        phase -= Math.floor(phase);
        lowpass += cutoff * (s - lowpass);
        return lowpass * env * gain;
    }
}
