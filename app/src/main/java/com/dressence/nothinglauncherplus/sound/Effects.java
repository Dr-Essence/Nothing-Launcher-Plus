package com.dressence.nothinglauncherplus.sound;

/** Mono reverb (four combs, two all-passes, Freeverb-style) and a tape-like echo. */
final class Effects {

    private final float[][] combs;
    private final int[] combPos;
    private final float[] combFilter;
    private final float[][] allpasses;
    private final int[] allpassPos;
    private final float[] echoLine;
    private int echoPos;
    private int echoDelay;
    private float echoTone;

    float roomFeedback = 0.84f;
    float damping = 0.3f;
    float echoFeedback = 0.35f;

    Effects(float rate) {
        float scale = rate / 44100f;
        int[] combLengths = {1116, 1188, 1277, 1356};
        int[] allpassLengths = {556, 441};
        combs = new float[combLengths.length][];
        for (int i = 0; i < combLengths.length; i++) {
            combs[i] = new float[Math.max(1, (int) (combLengths[i] * scale))];
        }
        combPos = new int[combs.length];
        combFilter = new float[combs.length];
        allpasses = new float[allpassLengths.length][];
        for (int i = 0; i < allpassLengths.length; i++) {
            allpasses[i] = new float[Math.max(1, (int) (allpassLengths[i] * scale))];
        }
        allpassPos = new int[allpasses.length];
        echoLine = new float[(int) rate * 2];
        echoDelay = (int) (rate * 0.3f);
    }

    void setEchoSeconds(float seconds, float rate) {
        echoDelay = Math.max(1, Math.min(echoLine.length - 1, (int) (seconds * rate)));
    }

    float reverb(float in) {
        float out = 0;
        for (int i = 0; i < combs.length; i++) {
            float[] line = combs[i];
            int p = combPos[i];
            float y = line[p];
            combFilter[i] = y * (1 - damping) + combFilter[i] * damping;
            line[p] = in * 0.015f + combFilter[i] * roomFeedback;
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

    float echo(float in) {
        int read = echoPos - echoDelay;
        if (read < 0) {
            read += echoLine.length;
        }
        float y = echoLine[read];
        echoTone += 0.35f * (y - echoTone); // Each repeat a little darker, like tape.
        echoLine[echoPos] = in + echoTone * echoFeedback;
        echoPos = echoPos + 1 == echoLine.length ? 0 : echoPos + 1;
        return y;
    }
}
