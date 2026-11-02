package com.dressence.nothinglauncherplus.sound;

import java.util.Random;

/**
 * The background music renderer: a {@link Style}'s Nothing breathing loop as the bed, and
 * generated layers that breathe with it. Pure Java (no Android), stereo, A4 = 440 Hz, every note
 * from A minor pentatonic (A C D E G, the notes of Nothing's breathing loops), so nothing clashes
 * with the bed.
 *
 * <p>The breath: the bed's own loudness over time (smoothed, 0..1), so every layer swells as the
 * loop breathes in and settles as it breathes out. Without a bed (Nothing Widgets missing), a
 * smooth breath of the style's length.
 *
 * <p>Layers, after the two references (measured: PS4 home and login menus, PS2 menu):
 * <ul>
 *   <li>pad: an open voicing (root, fifth, octave) of soft detuned tones under a filter that opens
 *       with the breath, a slight 5.5 Hz shimmer, a new chord per {@code chordsPerLoop};</li>
 *   <li>sub: the chord's root two octaves down, swelling with the breath;</li>
 *   <li>bells: single ringing tones (D5..E6) near each breath's top, some gliding down a little as
 *       they fade;</li>
 *   <li>air: noise behind a filter that opens on the in-breath and closes on the out-breath;</li>
 *   <li>rumble: low filtered noise and a low drone;</li>
 *   <li>flutter: a small cluster near 400..530 Hz, each tone trembling about 5.7 times a second,
 *       flaring for a few seconds every 15..30 s;</li>
 *   <li>glints: rare short high pings sliding down;</li>
 *   <li>a hall reverb (four feedback delay lines) under the bells, flutter, glints and pad.</li>
 * </ul>
 */
public final class Ambience {

    private static final int TABLE = 4096;
    private static final float[] SINE = new float[TABLE + 1];
    private static final int[] PENTATONIC = {0, 3, 5, 7, 10}; // A C D E G from A
    /** Chord roots (MIDI) the progression walks: A, D, G, C, E. */
    private static final int[] ROOTS = {45, 50, 43, 48, 52};
    private static final int[][] NEXT = {{1, 2, 3}, {0, 2, 4}, {3, 0, 1}, {0, 2, 4}, {0, 1, 3}};
    /** The flutter cluster: G4 A4 C5 D5 (392..587 Hz). */
    private static final int[] FLUTTER_NOTES = {67, 69, 72, 74};
    /** Glints: D6 E6 G6 A6. */
    private static final int[] GLINT_NOTES = {86, 88, 91, 93};

    static {
        for (int i = 0; i <= TABLE; i++) {
            SINE[i] = (float) Math.sin(2 * Math.PI * i / TABLE);
        }
    }

    private final float rate;
    private final Random random;
    private Style style;
    private static final float PCM = 1f / 32768;
    /** The bed, interleaved stereo 16-bit, and its breath curve (one value per block of 256). */
    private short[] bed;
    private float[] breath;
    private int bedPos;
    private long sample;

    // Pad: three notes x two detuned oscillators, crossfading from the last chord.
    private final double[] padPhase = new double[12];
    private final float[] padFreq = new float[12];
    private float padFade = 1;
    private int chord;
    private int lastChordAt = -1;
    private float padLowL;
    private float padLowR;
    private double subPhase;
    private float subFreq;

    // Bells: a few voices.
    private static final int BELLS = 6;
    private final double[] bellPhase = new double[BELLS];
    private final float[] bellFreq = new float[BELLS];
    private final float[] bellAmp = new float[BELLS];
    private final float[] bellDecay = new float[BELLS];
    private final float[] bellGlide = new float[BELLS];
    private final float[] bellPan = new float[BELLS];
    private boolean breathRising;
    private float lastBreath;

    // Air and rumble.
    private float airL1;
    private float airL2;
    private float airR1;
    private float airR2;
    private float airHighL;
    private float airHighR;
    private float rumbleL;
    private float rumbleR;
    private double dronePhase;

    // Flutter cluster.
    private static final int FLUTTER = 4;
    private final double[] flutterPhase = new double[FLUTTER];
    private final double[] tremPhase = new double[FLUTTER];
    private final float[] flutterFreq = new float[FLUTTER];
    private final float[] tremRate = new float[FLUTTER];
    private float flutterEnv;
    private long flutterUntil;
    private long nextFlutter;

    // Glints.
    private double glintPhase;
    private float glintFreq;
    private float glintAmp;
    private float glintPan;
    /** Per-sample glide and fade of a glint (about 2.5 s to silence). */
    private final float glintGlide;
    private final float glintFade;

    // Reverb: four feedback delay lines, damped.
    private final float[][] lines = new float[4][];
    private final int[] linePos = new int[4];
    private final float[] lineLow = new float[4];
    /** The hall's damping: its echoes lose their top above about 3.3 kHz each pass. */
    private final float damping;

    // Master.
    private float muffle;
    private float masterL;
    private float masterR;

    public Ambience(float rate, Style style, long seed) {
        this.rate = rate;
        this.random = new Random(seed);
        damping = coefficient(3300);
        glintGlide = (float) Math.pow(0.99997, 48000 / rate);
        glintFade = (float) Math.pow(0.99985, 48000 / rate);
        int[] lengths = {1557, 1617, 1491, 1422};
        for (int i = 0; i < 4; i++) {
            lines[i] = new float[Math.round(lengths[i] * rate / 44100f * 2.1f)];
        }
        setStyle(style, null);
    }

    /**
     * The style and its bed (interleaved stereo 16-bit at this rate, or null to breathe on its
     * own).
     */
    public void setStyle(Style style, short[] bed) {
        this.style = style;
        this.bed = bed != null ? seamless(bed, Math.round(rate * 0.02f)) : null;
        bed = this.bed;
        bedPos = 0;
        breath = bed != null ? breathOf(bed) : null;
        chord = random.nextInt(ROOTS.length);
        setChord(chord, true);
        nextFlutter = sample + (long) (rate * (6 + random.nextFloat() * 10));
    }

    /** 0 = clear, 1 = closed down (an app launching over the home screen). */
    public void setMuffle(float amount) {
        muffle = Math.max(0, Math.min(1, amount));
    }

    /** Renders {@code frames} stereo frames into {@code out} (interleaved). */
    public void render(float[] out, int frames) {
        Style s = style;
        float masterK = coefficient(Math.min(rate * 0.45f, 12000f * (1 - muffle) + 350f * muffle));
        float hk = coefficient(400);
        float rk = coefficient(55);
        float padK = 0;
        float airK = 0;
        float b = 0;
        for (int i = 0; i < frames; i++, sample++) {
            // The breath moves slowly: its filters only need updating every 32 samples.
            if ((i & 31) == 0) {
                b = breathAt();
                padK = coefficient(260 + 1100 * b);
                airK = coefficient(700 + 4500 * b * b);
            }
            updateEvents(b, s);
            float l = 0;
            float r = 0;
            // Bed.
            if (bed != null) {
                l += bed[bedPos] * PCM * s.bedLevel;
                r += bed[bedPos + 1] * PCM * s.bedLevel;
                bedPos += 2;
                if (bedPos >= bed.length) {
                    bedPos = 0;
                }
            }
            // Pad: open voicing, filter and level following the breath, light shimmer.
            float pad = 0;
            float padR = 0;
            float shimmer = 1 - 0.12f * (0.5f + 0.5f * sin(sample * 5.5 / rate));
            for (int v = 0; v < 12; v++) {
                if (padFreq[v] <= 0) {
                    continue;
                }
                padPhase[v] += padFreq[v] / rate;
                float tone = sin(padPhase[v]) + 0.12f * sin(padPhase[v] * 2);
                float weight = v < 6 ? padFade : 1 - padFade;
                if ((v & 1) == 0) {
                    pad += tone * weight;
                } else {
                    padR += tone * weight;
                }
            }
            if (padFade < 1) {
                padFade = Math.min(1, padFade + 1 / (rate * 2.5f));
            }
            padLowL += padK * (pad - padLowL);
            padLowR += padK * (padR - padLowR);
            float padGain = s.padLevel * 0.085f * (0.35f + 0.65f * b) * shimmer;
            l += padLowL * padGain;
            r += padLowR * padGain;
            // Sub.
            subPhase += subFreq / rate;
            float sub = sin(subPhase) * s.subLevel * 0.10f * (0.5f + 0.5f * b);
            l += sub;
            r += sub;
            // Bells.
            float wetL = (padLowL + padLowR) * padGain * 0.3f;
            float wetR = wetL;
            for (int v = 0; v < BELLS; v++) {
                if (bellAmp[v] < 1e-4f) {
                    continue;
                }
                bellPhase[v] += bellFreq[v] / rate;
                bellFreq[v] *= bellGlide[v];
                double p = bellPhase[v];
                float tone = sin(p) + 0.28f * sin(p * 2) + 0.08f * sin(p * 3.01);
                float x = tone * bellAmp[v] * s.bellLevel * 0.08f;
                bellAmp[v] *= bellDecay[v];
                float pan = bellPan[v];
                l += x * (1 - pan) * 0.6f;
                r += x * (1 + pan) * 0.6f;
                wetL += x * (1 - pan);
                wetR += x * (1 + pan);
            }
            // Air: noise opening with the breath.
            float nl = noise();
            float nr = noise();
            airHighL += hk * (nl - airHighL);
            airHighR += hk * (nr - airHighR);
            nl -= airHighL;
            nr -= airHighR;
            airL1 += airK * (nl - airL1);
            airL2 += airK * (airL1 - airL2);
            airR1 += airK * (nr - airR1);
            airR2 += airK * (airR1 - airR2);
            float airGain = s.airLevel * 0.09f * (0.25f + 0.75f * b);
            l += airL2 * airGain;
            r += airR2 * airGain;
            // Rumble: low noise and a drone on the root.
            rumbleL += rk * (noise() - rumbleL);
            rumbleR += rk * (noise() - rumbleR);
            dronePhase += (subFreq * 0.5f) / rate;
            float drone = sin(dronePhase) * 0.35f;
            float rumbleGain = s.rumbleLevel * 0.22f * (0.6f + 0.4f * b);
            l += (rumbleL * 2.5f + drone) * rumbleGain;
            r += (rumbleR * 2.5f + drone) * rumbleGain;
            // Flutter cluster.
            float target = sample < flutterUntil ? 1 : 0;
            flutterEnv += (target - flutterEnv) * (target > flutterEnv ? 1 / (rate * 1.2f)
                    : 1 / (rate * 2.5f));
            if (flutterEnv > 1e-4f) {
                float fl = 0;
                float fr = 0;
                for (int v = 0; v < FLUTTER; v++) {
                    flutterPhase[v] += flutterFreq[v] / rate;
                    tremPhase[v] += tremRate[v] / rate;
                    float trem = 0.5f + 0.5f * sin(tremPhase[v]);
                    float x = sin(flutterPhase[v]) * trem * trem;
                    if ((v & 1) == 0) {
                        fl += x;
                    } else {
                        fr += x;
                    }
                }
                float fg = s.flutterLevel * 0.035f * flutterEnv;
                l += fl * fg;
                r += fr * fg;
                wetL += fl * fg * 1.5f;
                wetR += fr * fg * 1.5f;
            }
            // Glint.
            if (glintAmp > 1e-4f) {
                glintPhase += glintFreq / rate;
                glintFreq *= glintGlide;
                float x = sin(glintPhase) * glintAmp * s.glintLevel * 0.05f;
                glintAmp *= glintFade;
                l += x * (1 - glintPan) * 0.4f;
                r += x * (1 + glintPan) * 0.4f;
                wetL += x * (1 - glintPan) * 1.5f;
                wetR += x * (1 + glintPan) * 1.5f;
            }
            // Reverb.
            reverb(wetL, wetR);
            l += reverbL;
            r += reverbR;
            // Master: muffle filter, soft limit.
            masterL += masterK * (l - masterL);
            masterR += masterK * (r - masterR);
            out[2 * i] = soft(masterL);
            out[2 * i + 1] = soft(masterR);
        }
    }

    // ---------------------------------------------------------------------------------------

    /** Chords, bells, flutter and glints, timed by the breath. */
    private void updateEvents(float b, Style s) {
        // A new chord at the start of each chord span of the loop.
        int loopFrames = bed != null ? bed.length / 2 : (int) (s.breathSeconds * rate);
        int span = Math.max(1, (int) (loopFrames / s.chordsPerLoop));
        int position = bed != null ? bedPos / 2 : (int) (sample % loopFrames);
        int index = position / span;
        if (index != lastChordAt && position % span < 4) {
            lastChordAt = index;
            int[] options = NEXT[chord];
            setChord(options[random.nextInt(options.length)], false);
        }
        // Bells near the top of the in-breath.
        boolean rising = b > lastBreath + 1e-5f;
        if (breathRising && !rising && b > 0.5f && random.nextFloat() < 0.55f + s.bellLevel * 0.4f) {
            ring(random.nextFloat() < 0.35f);
            if (random.nextFloat() < s.bellLevel * 0.5f) {
                ring(false);
            }
        }
        breathRising = rising;
        lastBreath = b;
        // Flutter flares.
        if (sample >= nextFlutter) {
            flutterUntil = sample + (long) (rate * (3 + random.nextFloat() * 5));
            nextFlutter = flutterUntil + (long) (rate * (15 + random.nextFloat() * 15));
            for (int v = 0; v < FLUTTER; v++) {
                flutterFreq[v] = midi(FLUTTER_NOTES[v]) * (1 + (random.nextFloat() - 0.5f) * 0.006f);
                tremRate[v] = 5.2f + random.nextFloat();
            }
        }
        // Rare glints.
        if (glintAmp < 1e-3f && random.nextFloat() < s.glintLevel * 0.6f / rate) {
            glintFreq = midi(GLINT_NOTES[random.nextInt(GLINT_NOTES.length)]);
            glintAmp = 1;
            glintPan = random.nextFloat() * 1.4f - 0.7f;
        }
    }

    /** The pad and sub take chord {@code index}; the old one fades out under the new. */
    private void setChord(int index, boolean at) {
        chord = index;
        int root = ROOTS[index];
        int[] voicing = {root, root + 7, root + 12};
        for (int n = 0; n < 3; n++) {
            // Old voices (6..11) take the current ones' place, the new chord goes to 0..5.
            padFreq[6 + 2 * n] = at ? 0 : padFreq[2 * n];
            padFreq[7 + 2 * n] = at ? 0 : padFreq[2 * n + 1];
            padPhase[6 + 2 * n] = padPhase[2 * n];
            padPhase[7 + 2 * n] = padPhase[2 * n + 1];
            float f = midi(voicing[n]);
            padFreq[2 * n] = f * 0.9977f;
            padFreq[2 * n + 1] = f * 1.0023f;
        }
        padFade = at ? 1 : 0;
        subFreq = midi(root - 12);
    }

    /** A bell: a pentatonic tone from D5 to E6 (PS4's upper register), sometimes gliding. */
    private void ring(boolean glide) {
        int v = 0;
        for (int i = 1; i < BELLS; i++) {
            if (bellAmp[i] < bellAmp[v]) {
                v = i;
            }
        }
        int octave = 72 + 12 * random.nextInt(2);
        int note = 69 + PENTATONIC[random.nextInt(PENTATONIC.length)] + (octave - 72);
        if (note < 74) {
            note += 12;
        }
        bellFreq[v] = midi(Math.min(note, 88));
        bellPhase[v] = 0;
        bellAmp[v] = 0.6f + 0.4f * random.nextFloat();
        float seconds = 3 + random.nextFloat() * 3;
        bellDecay[v] = (float) Math.pow(0.001, 1.0 / (seconds * rate));
        bellGlide[v] = glide ? (float) Math.pow(0.97, 1.0 / (seconds * rate)) : 1f;
        bellPan[v] = random.nextFloat() * 1.2f - 0.6f;
    }

    /**
     * The loop with its last {@code fade} frames cross-faded into its first, so the join has no
     * step (Calm's decoded loop ends 0.04 away from where it starts: a click).
     */
    static short[] seamless(short[] bed, int fade) {
        int frames = bed.length / 2;
        if (frames < fade * 4) {
            return bed;
        }
        short[] out = new short[(frames - fade) * 2];
        System.arraycopy(bed, 0, out, 0, out.length);
        for (int i = 0; i < fade; i++) {
            double t = (i + 0.5) / fade;
            float in = (float) Math.sin(t * Math.PI / 2);
            float outGain = (float) Math.cos(t * Math.PI / 2);
            int tail = (frames - fade + i) * 2;
            out[2 * i] = (short) Math.round(bed[2 * i] * in + bed[tail] * outGain);
            out[2 * i + 1] = (short) Math.round(bed[2 * i + 1] * in + bed[tail + 1] * outGain);
        }
        return out;
    }

    /** The breath now, 0..1. */
    private float breathAt() {
        if (breath != null) {
            int frame = bedPos / 2;
            float x = frame / 256f;
            int i = (int) x;
            float t = x - i;
            float a = breath[i % breath.length];
            float c = breath[(i + 1) % breath.length];
            return a + (c - a) * t;
        }
        double phase = (sample / (double) rate) / style.breathSeconds;
        float v = sin(phase * 2) * 0.5f + 0.5f; // Two breaths per loop.
        return v * v;
    }

    /** The bed's loudness per 256 frames, smoothed over about 0.4 s, scaled to 0..1. */
    static float[] breathOf(short[] bed) {
        int frames = bed.length / 2;
        int blocks = Math.max(1, frames / 256);
        float[] env = new float[blocks];
        for (int k = 0; k < blocks; k++) {
            double sum = 0;
            for (int j = k * 256; j < (k + 1) * 256 && j < frames; j++) {
                float m = (bed[2 * j] + bed[2 * j + 1]) * 0.5f * PCM;
                sum += m * m;
            }
            env[k] = (float) Math.sqrt(sum / 256);
        }
        // Smooth both ways round the loop (it is a loop: no edge).
        float[] smooth = env.clone();
        float a = 0.03f;
        for (int pass = 0; pass < 2; pass++) {
            float y = smooth[blocks - 1];
            for (int k = 0; k < blocks; k++) {
                y += a * (smooth[k] - y);
                smooth[k] = y;
            }
            y = smooth[0];
            for (int k = blocks - 1; k >= 0; k--) {
                y += a * (smooth[k] - y);
                smooth[k] = y;
            }
        }
        float[] sorted = smooth.clone();
        java.util.Arrays.sort(sorted);
        float lo = sorted[(int) (blocks * 0.05f)];
        float hi = sorted[Math.min(blocks - 1, (int) (blocks * 0.95f))];
        float[] out = new float[blocks];
        for (int k = 0; k < blocks; k++) {
            float v = hi > lo ? (smooth[k] - lo) / (hi - lo) : 0.5f;
            out[k] = Math.max(0, Math.min(1, v));
        }
        return out;
    }

    private float reverbL;
    private float reverbR;

    /** One sample through the hall; leaves it in {@link #reverbL} / {@link #reverbR}. */
    private void reverb(float inL, float inR) {
        float a = lines[0][linePos[0]];
        float b = lines[1][linePos[1]];
        float c = lines[2][linePos[2]];
        float d = lines[3][linePos[3]];
        // Householder mix (each tap minus half the sum), damped.
        float half = (a + b + c + d) * 0.5f;
        feed(0, a - half, inL);
        feed(1, b - half, inR);
        feed(2, c - half, inL);
        feed(3, d - half, inR);
        reverbL = (a + c) * 0.35f;
        reverbR = (b + d) * 0.35f;
    }

    private void feed(int i, float tap, float input) {
        lineLow[i] += damping * (tap - lineLow[i]);
        lines[i][linePos[i]] = lineLow[i] * 0.82f + input * 0.25f;
        linePos[i] = linePos[i] + 1 == lines[i].length ? 0 : linePos[i] + 1;
    }

    private float coefficient(float cutoff) {
        return (float) (1 - Math.exp(-2 * Math.PI * cutoff / rate));
    }

    private static float midi(int note) {
        return (float) (440 * Math.pow(2, (note - 69) / 12.0));
    }

    private static float sin(double phase) {
        double p = phase - Math.floor(phase);
        double x = p * TABLE;
        int i = (int) x;
        float t = (float) (x - i);
        return SINE[i] + (SINE[i + 1] - SINE[i]) * t;
    }

    private int noiseState = 0x1234567;

    private float noise() {
        noiseState ^= noiseState << 13;
        noiseState ^= noiseState >>> 17;
        noiseState ^= noiseState << 5;
        return noiseState * (1f / Integer.MAX_VALUE);
    }

    private static float soft(float x) {
        if (x > 0.9f) {
            return 0.9f + 0.1f * (float) Math.tanh((x - 0.9f) * 10);
        }
        if (x < -0.9f) {
            return -0.9f + 0.1f * (float) Math.tanh((x + 0.9f) * 10);
        }
        return x;
    }
}
