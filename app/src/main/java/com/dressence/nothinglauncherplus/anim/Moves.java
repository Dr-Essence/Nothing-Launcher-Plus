package com.dressence.nothinglauncherplus.anim;

import static com.dressence.nothinglauncherplus.anim.Curves.PI;
import static com.dressence.nothinglauncherplus.anim.Curves.ease;
import static com.dressence.nothinglauncherplus.anim.Curves.easeOut;
import static com.dressence.nothinglauncherplus.anim.Curves.hump;
import static com.dressence.nothinglauncherplus.anim.Curves.sin;
import static com.dressence.nothinglauncherplus.anim.Curves.wobble;

/** Building blocks shared by several motions. All of them add to the pose, so they compose. */
final class Moves {

    private Moves() {
    }

    /**
     * A jump with anticipation: squash down for 0.12 s, leap {@code height} (fraction of the
     * size) stretched for {@code air} seconds, land squashed and settle on a spring. Before
     * {@code t = 0} it has no effect, so delayed hops can simply be called with {@code t - delay}.
     */
    static void hop(Pose p, float t, float height, float air) {
        if (t <= 0) {
            return;
        }
        final float prep = 0.12f;
        float strength = Math.min(1.3f, height / 0.12f);
        float sy;
        float sx;
        if (t < prep) {
            float s = ease(t / prep);
            sy = -0.08f * s;
            sx = 0.06f * s;
        } else if (t < prep + air) {
            float x = (t - prep) / air;
            p.ty -= height * 4 * x * (1 - x);
            float release = 1 - easeOut(Math.min(1, x * 3));
            float stretch = sin(PI * x) * strength;
            sy = -0.08f * release + 0.07f * stretch;
            sx = 0.06f * release - 0.05f * stretch;
        } else {
            float y = wobble(t - prep - air, 3.2f, 0.32f) * strength;
            sy = -0.10f * y;
            sx = 0.08f * y;
        }
        p.pivotY = 1;
        p.scaleY *= 1 + sy;
        p.scaleX *= 1 + sx;
    }

    /** Sum of {@code count} humps of width {@code width}, one every {@code every} seconds. */
    static float taps(float t, int count, float every, float width) {
        float sum = 0;
        for (int i = 0; i < count; i++) {
            sum += hump((t - i * every) / width);
        }
        return sum;
    }
}
