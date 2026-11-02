package com.dressence.nothinglauncherplus.sound;

/**
 * One interface-sound voice: a two-operator FM tone (bells, chimes, keys, ticks) through an
 * envelope and a one-pole low-pass.
 */
final class Voice {

    private static final double TAU = Math.PI * 2;

    boolean active;
    float freq;
    float gain;
    /** Envelope: attack and release in seconds, decay as a time constant, sustain 0..1. */
    float attack;
    float decay;
    float sustain;
    float release;
    /** One-pole low-pass coefficient: 1 = open, smaller = darker. */
    float cutoff;
    float fmRatio;
    float fmIndex;
    float fmDecay;
    /** Share of this voice sent to the reverb. */
    float reverb;

    /** The last sample. */
    float out;

    private float rate;
    private double phase;
    private double modPhase;
    private int age;
    private int gate;
    private boolean releasing;
    private float env;
    private float releaseMul;
    private float decayMul;
    private float decayLevel;
    private float fmMul;
    private float fmLevel;
    private float low;

    void reset() {
        active = false;
        freq = 440;
        gain = 0.1f;
        attack = 0.005f;
        decay = 0.3f;
        sustain = 0;
        release = 0.1f;
        cutoff = 1;
        fmRatio = 1;
        fmIndex = 0;
        fmDecay = 0.2f;
        reverb = 0.2f;
    }

    /** Starts the note for {@code seconds} (then releases) at {@code sampleRate}. */
    void start(float seconds, float sampleRate) {
        rate = sampleRate;
        phase = 0;
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
        low = 0;
        active = true;
    }

    /** Renders the next sample into {@link #out}; false once finished. */
    boolean next() {
        float t = age / rate;
        if (!releasing && age >= gate) {
            releasing = true;
        }
        if (releasing) {
            env *= releaseMul;
            if (env < 1e-4f) {
                active = false;
                out = 0;
                return false;
            }
        } else if (t < attack) {
            env = t / attack;
        } else {
            decayLevel *= decayMul;
            env = sustain + (1 - sustain) * decayLevel;
            if (sustain == 0 && env < 1e-4f) {
                active = false;
                out = 0;
                return false;
            }
        }
        age++;
        double step = freq / rate;
        modPhase += step * fmRatio;
        modPhase -= Math.floor(modPhase);
        fmLevel *= fmMul;
        float s = (float) Math.sin(TAU * phase + fmIndex * fmLevel * Math.sin(TAU * modPhase));
        phase += step;
        phase -= Math.floor(phase);
        low += cutoff * (s - low);
        out = low * (env * gain);
        return true;
    }
}
