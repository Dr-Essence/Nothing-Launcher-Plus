package com.dressence.nothinglauncherplus.sound;

/**
 * One synthesizer voice, in stereo: an oscillator through an envelope and a low-pass. The
 * oscillators cover two eras of console sound: NES-style pulse, stepped triangle and LFSR noise,
 * and the lush kind of a PlayStation menu: a band-limited supersaw (four detuned saws spread
 * left and right, a natural chorus) and two-operator FM for bells, plucks and keys. Voices are
 * pooled; nothing allocates while playing.
 */
final class Voice {

    static final int SINE = 0;
    static final int PULSE = 1;
    static final int TRIANGLE = 2;
    static final int SUPERSAW = 3;
    static final int NOISE = 4;
    static final int FM = 5;

    private static final double TAU = Math.PI * 2;
    /** Supersaw spread: two oscillators per side, detuned symmetrically. */
    private static final float[] SPREAD = {-1f, -0.33f, 0.33f, 1f};

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
    /** Pitch fall per second (drum "kicks"), as an exponent. */
    float drop;
    /** One-pole low-pass coefficient: 1 = open, smaller = darker. */
    float cutoff;
    /** One-pole low-cut coefficient: 0 = off, larger = thinner (removes rumble). */
    float lowCut;
    /** Slow filter sweep: the cutoff breathes by this fraction at this rate (Hz). */
    float sweepDepth;
    float sweepRate;
    float fmRatio;
    float fmIndex;
    float fmDecay;
    /** Supersaw detune, as a frequency ratio for the outermost oscillators. */
    float detune;
    /** Supersaw stereo spread: 0 = centred, 1 = its two halves fully left and right. */
    float spread;
    /** -1 left .. 1 right. */
    float pan;
    /** Shares of this voice sent to the reverb and the echo. */
    float reverb;
    float echo;
    /** Seconds of silence before the note begins (for scheduled flourishes). */
    float delay;

    /** The last sample, per channel. */
    float left;
    float right;

    private float rate;
    private double phase;
    private final double[] sawPhases = new double[SPREAD.length];
    private double modPhase;
    private int age;
    private int waiting;
    private int gate;
    private boolean releasing;
    private float env;
    private float releaseMul;
    private float decayMul;
    private float decayLevel;
    private float fmMul;
    private float fmLevel;
    private float lowL;
    private float lowR;
    private float bassL;
    private float swept;
    private float panL;
    private float panR;
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
        lowCut = 0;
        sweepDepth = 0;
        sweepRate = 0.08f;
        fmRatio = 1;
        fmIndex = 0;
        fmDecay = 0.2f;
        detune = 1.01f;
        spread = 1;
        pan = 0;
        reverb = 0.2f;
        echo = 0;
        delay = 0;
    }

    /** Starts the note for {@code seconds} (then releases) at {@code sampleRate}. */
    void start(float seconds, float sampleRate) {
        rate = sampleRate;
        phase = 0;
        for (int i = 0; i < sawPhases.length; i++) {
            sawPhases[i] = (i * 0.618034) % 1; // Spread start phases: no initial "zap".
        }
        modPhase = 0;
        age = 0;
        waiting = (int) (delay * sampleRate);
        gate = Math.max(1, (int) (seconds * sampleRate));
        releasing = false;
        env = 0;
        releaseMul = (float) Math.exp(-5.0 / Math.max(1, release * sampleRate));
        decayMul = (float) Math.exp(-1.0 / Math.max(1, decay * sampleRate));
        decayLevel = 1;
        fmMul = (float) Math.exp(-1.0 / Math.max(1, fmDecay * sampleRate));
        fmLevel = 1;
        lowL = 0;
        lowR = 0;
        bassL = 0;
        swept = cutoff;
        double angle = (Math.max(-1, Math.min(1, pan)) + 1) * Math.PI / 4;
        panL = (float) (Math.cos(angle) * Math.sqrt(2));
        panR = (float) (Math.sin(angle) * Math.sqrt(2));
        // Seed the noise from the pitch, so two noise voices at different pitches never match.
        lfsr = 1 + (Float.floatToIntBits(freq) * 7919 & 0x3FFF);
        noisePhase = 0;
        noise = 1;
        active = true;
    }

    /** Renders the next sample into {@link #left} / {@link #right}; false once finished. */
    boolean next() {
        if (waiting > 0) {
            waiting--;
            left = right = 0;
            return true;
        }
        float t = age / rate;
        if (!releasing && age >= gate) {
            releasing = true;
        }
        if (releasing) {
            env *= releaseMul;
            if (env < 1e-4f) {
                active = false;
                left = right = 0;
                return false;
            }
        } else if (t < attack) {
            env = t / attack;
        } else {
            decayLevel *= decayMul;
            env = sustain + (1 - sustain) * decayLevel;
            if (sustain == 0 && env < 1e-4f) {
                active = false;
                left = right = 0;
                return false;
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
        if (sweepDepth > 0 && (age & 31) == 1) {
            // A slow sweep only needs updating every 32 samples.
            swept = Math.max(0.002f, Math.min(1, cutoff * (1 + sweepDepth
                    * (float) Math.sin(TAU * sweepRate * t))));
        }
        float co = sweepDepth > 0 ? swept : cutoff;
        float level = env * gain;

        if (wave == SUPERSAW) {
            float l = 0;
            float r = 0;
            for (int i = 0; i < SPREAD.length; i++) {
                double dt = step * (1 + (detune - 1) * SPREAD[i]);
                double p = sawPhases[i] + dt;
                p -= Math.floor(p);
                sawPhases[i] = p;
                float saw = (float) (2 * p - 1 - polyBlep(p, dt));
                if (i % 2 == 0) {
                    l += saw;
                } else {
                    r += saw;
                }
            }
            float mid = (l + r) * 0.5f;
            float side = (l - r) * 0.5f * spread;
            lowL += co * ((mid + side) * 0.5f - lowL);
            lowR += co * ((mid - side) * 0.5f - lowR);
            left = lowL * level;
            right = lowR * level;
            return true;
        }

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
                s = (float) Math.sin(TAU * phase + fmIndex * fmLevel * Math.sin(TAU * modPhase));
                break;
            default:
                s = (float) Math.sin(TAU * phase);
                break;
        }
        phase += step;
        phase -= Math.floor(phase);
        lowL += co * (s - lowL);
        float filtered = lowL;
        if (lowCut > 0) {
            bassL += lowCut * (filtered - bassL);
            filtered -= bassL;
        }
        float out = filtered * level;
        left = out * panL;
        right = out * panR;
        return true;
    }

    /** Band-limits a saw's jump (PolyBLEP), so supersaw pads stay smooth instead of fizzy. */
    private static double polyBlep(double t, double dt) {
        if (t < dt) {
            t /= dt;
            return t + t - t * t - 1;
        }
        if (t > 1 - dt) {
            t = (t - 1) / dt;
            return t * t + t + t + 1;
        }
        return 0;
    }
}
