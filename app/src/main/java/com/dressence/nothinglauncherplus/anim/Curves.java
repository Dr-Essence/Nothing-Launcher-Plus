package com.dressence.nothinglauncherplus.anim;

/**
 * Allocation-free timing curves. Springs follow the damped harmonic oscillator, the same model
 * iOS uses for its interface animations, which gives the overshoot-and-settle feel.
 */
final class Curves {

    static final float PI = (float) Math.PI;
    static final float TAU = 2 * PI;

    private Curves() {
    }

    static float clamp01(float x) {
        return x < 0 ? 0 : x > 1 ? 1 : x;
    }

    /** Progress of {@code t} through [a, b], clamped to [0, 1]. */
    static float seg(float t, float a, float b) {
        return clamp01((t - a) / (b - a));
    }

    /** Cubic ease-in-out. */
    static float ease(float x) {
        x = clamp01(x);
        if (x < 0.5f) {
            return 4 * x * x * x;
        }
        float f = -2 * x + 2;
        return 1 - f * f * f / 2;
    }

    static float easeOut(float x) {
        x = 1 - clamp01(x);
        return 1 - x * x * x;
    }

    static float easeIn(float x) {
        x = clamp01(x);
        return x * x * x;
    }

    /** A hump 0 → 1 → 0 over [0, 1] with zero slope at both ends; 0 outside. */
    static float hump(float x) {
        if (x <= 0 || x >= 1) {
            return 0;
        }
        float s = (float) Math.sin(PI * x);
        return s * s;
    }

    static float sin(float x) {
        return (float) Math.sin(x);
    }

    static float cos(float x) {
        return (float) Math.cos(x);
    }

    /**
     * Step response of a spring released at {@code t = 0}: 0 before, then rises to 1 with an
     * overshoot set by {@code damping} (1 = no overshoot).
     */
    static float spring(float t, float freq, float damping) {
        if (t <= 0) {
            return 0;
        }
        float w = TAU * freq;
        if (damping >= 1) {
            return 1 - (1 + w * t) * (float) Math.exp(-w * t);
        }
        float wd = w * (float) Math.sqrt(1 - damping * damping);
        float e = (float) Math.exp(-damping * w * t);
        return 1 - e * (cos(wd * t) + damping * w / wd * sin(wd * t));
    }

    /**
     * Damped oscillation kicked at {@code t = 0}: starts at 0, swings to about ±1 and dies out.
     * Normalised so the first peak is 1.
     */
    static float wobble(float t, float freq, float damping) {
        if (t <= 0) {
            return 0;
        }
        float w = TAU * freq;
        float wd = w * (float) Math.sqrt(1 - damping * damping);
        float peakT = (float) Math.atan2(wd, damping * w) / wd;
        float peak = (float) Math.exp(-damping * w * peakT) * sin(wd * peakT);
        return (float) Math.exp(-damping * w * t) * sin(wd * t) / peak;
    }

    /**
     * Keyframes {@code {t0, v0, t1, v1, ...}} joined by ease-in-out segments; holds the first
     * and last values outside the range.
     */
    static float keys(float t, float[] kf) {
        if (t <= kf[0]) {
            return kf[1];
        }
        for (int i = 2; i < kf.length; i += 2) {
            if (t <= kf[i]) {
                float x = ease((t - kf[i - 2]) / (kf[i] - kf[i - 2]));
                return kf[i - 1] + (kf[i + 1] - kf[i - 1]) * x;
            }
        }
        return kf[kf.length - 1];
    }

    /** Fades in over {@code in} seconds and out over the last {@code out} seconds of {@code d}. */
    static float envelope(float t, float d, float in, float out) {
        return Math.min(ease(t / in), ease((d - t) / out));
    }

    /** Smooth pseudo-random signal in about [-1, 1] (incommensurate sines, no allocation). */
    static float noise(float t, float seed) {
        return 0.5f * sin(t * 7.3f + seed)
                + 0.3f * sin(t * 13.1f + seed * 1.7f)
                + 0.2f * sin(t * 23.7f + seed * 2.9f);
    }
}
