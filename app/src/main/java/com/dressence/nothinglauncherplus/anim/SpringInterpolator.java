package com.dressence.nothinglauncherplus.anim;

import android.animation.TimeInterpolator;

/**
 * A damped spring as a {@link TimeInterpolator}, for ViewPropertyAnimator: overshoots a little
 * and settles, like iOS interface springs. The animator's duration should cover
 * {@code durationSec}, by which point the spring has come to rest.
 */
public final class SpringInterpolator implements TimeInterpolator {

    private final float durationSec;
    private final float freq;
    private final float damping;

    public SpringInterpolator(float durationSec, float freq, float damping) {
        this.durationSec = durationSec;
        this.freq = freq;
        this.damping = damping;
    }

    @Override
    public float getInterpolation(float input) {
        return input >= 1 ? 1 : Curves.spring(input * durationSec, freq, damping);
    }
}
