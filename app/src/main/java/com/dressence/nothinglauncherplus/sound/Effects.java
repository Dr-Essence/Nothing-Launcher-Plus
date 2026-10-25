package com.dressence.nothinglauncherplus.sound;

/**
 * The space around the music, in stereo: a hall reverb (Freeverb: eight combs and four
 * all-passes per side, the right side slightly longer for width, after a short pre-delay) and a
 * ping-pong echo whose repeats bounce left and right, each a little darker, like tape.
 */
final class Effects {

    private static final int[] COMBS = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    private static final int[] ALLPASSES = {556, 441, 341, 225};
    private static final int STEREO_SPREAD = 23;

    private final Side leftSide;
    private final Side rightSide;
    private final float[] preDelay;
    private int prePos;
    private final float[] echoL;
    private final float[] echoR;
    private int echoPos;
    private int echoDelay;
    private float toneL;
    private float toneR;

    float roomFeedback = 0.84f;
    float damping = 0.3f;
    float echoFeedback = 0.35f;
    float reverbL;
    float reverbR;
    float echoOutL;
    float echoOutR;

    /** One channel's combs and all-passes. */
    private static final class Side {
        final float[][] combs;
        final int[] combPos;
        final float[] combFilter;
        final float[][] allpasses;
        final int[] allpassPos;

        Side(float scale, int spread) {
            combs = new float[COMBS.length][];
            for (int i = 0; i < COMBS.length; i++) {
                combs[i] = new float[Math.max(1, (int) ((COMBS[i] + spread) * scale))];
            }
            combPos = new int[combs.length];
            combFilter = new float[combs.length];
            allpasses = new float[ALLPASSES.length][];
            for (int i = 0; i < ALLPASSES.length; i++) {
                allpasses[i] = new float[Math.max(1, (int) ((ALLPASSES[i] + spread) * scale))];
            }
            allpassPos = new int[allpasses.length];
        }

        float process(float in, float feedback, float damping) {
            float out = 0;
            for (int i = 0; i < combs.length; i++) {
                float[] line = combs[i];
                int p = combPos[i];
                float y = line[p];
                combFilter[i] = y * (1 - damping) + combFilter[i] * damping;
                line[p] = in + combFilter[i] * feedback;
                combPos[i] = p + 1 == line.length ? 0 : p + 1;
                out += y;
            }
            for (int i = 0; i < allpasses.length; i++) {
                float[] line = allpasses[i];
                int p = allpassPos[i];
                float buffered = line[p];
                line[p] = out + buffered * 0.5f;
                out = buffered - out;
                allpassPos[i] = p + 1 == line.length ? 0 : p + 1;
            }
            return out;
        }
    }

    Effects(float rate) {
        float scale = rate / 44100f;
        leftSide = new Side(scale, 0);
        rightSide = new Side(scale, STEREO_SPREAD);
        preDelay = new float[Math.max(1, (int) (rate * 0.03f))];
        echoL = new float[(int) rate * 2];
        echoR = new float[(int) rate * 2];
        echoDelay = (int) (rate * 0.3f);
    }

    void setEchoSeconds(float seconds, float rate) {
        echoDelay = Math.max(1, Math.min(echoL.length - 1, (int) (seconds * rate)));
    }

    /** Feeds one sample (mono send) and leaves the stereo hall in reverbL / reverbR. */
    void reverb(float in) {
        float delayed = preDelay[prePos];
        preDelay[prePos] = in * 0.015f;
        prePos = prePos + 1 == preDelay.length ? 0 : prePos + 1;
        reverbL = leftSide.process(delayed, roomFeedback, damping);
        reverbR = rightSide.process(delayed, roomFeedback, damping);
    }

    /** Feeds one stereo sample and leaves the ping-pong repeats in echoOutL / echoOutR. */
    void echo(float inL, float inR) {
        int read = echoPos - echoDelay;
        if (read < 0) {
            read += echoL.length;
        }
        float yl = echoL[read];
        float yr = echoR[read];
        toneL += 0.35f * (yl - toneL);
        toneR += 0.35f * (yr - toneR);
        // Each side's repeat comes back on the other side.
        echoL[echoPos] = inL + toneR * echoFeedback;
        echoR[echoPos] = inR + toneL * echoFeedback;
        echoPos = echoPos + 1 == echoL.length ? 0 : echoPos + 1;
        echoOutL = yl;
        echoOutR = yr;
    }
}
