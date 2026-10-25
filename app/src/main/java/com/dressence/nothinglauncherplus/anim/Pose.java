package com.dressence.nothinglauncherplus.anim;

/**
 * One frame of a motion, as view transform properties. Translations are fractions of the view
 * size and pivots fractions of its bounds, so motions look the same at any cover size.
 */
public final class Pose {
    public float tx;
    public float ty;
    public float scaleX;
    public float scaleY;
    /** Degrees, clockwise. */
    public float rotation;
    /** Degrees around the vertical axis (3D turn). */
    public float rotationY;
    /** Whole turns in degrees (e.g. 360); never scaled, so a turn always ends upright. */
    public float spin;
    /** Whole turns around the vertical axis, like a flipping coin. */
    public float spinY;
    public float alpha;
    public float pivotX;
    public float pivotY;

    public Pose() {
        reset();
    }

    public void reset() {
        tx = 0;
        ty = 0;
        scaleX = 1;
        scaleY = 1;
        rotation = 0;
        rotationY = 0;
        spin = 0;
        spinY = 0;
        alpha = 1;
        pivotX = 0.5f;
        pivotY = 0.5f;
    }

    /** True if this is the resting pose (nothing moved, nothing faded). */
    boolean isRest() {
        return tx == 0 && ty == 0 && scaleX == 1 && scaleY == 1 && rotation == 0
                && rotationY == 0 && spin == 0 && spinY == 0 && alpha == 1;
    }

    /** Scales every deviation from rest by {@code amount} (per-folder variation). */
    void amplify(float amount) {
        tx *= amount;
        ty *= amount;
        scaleX = 1 + (scaleX - 1) * amount;
        scaleY = 1 + (scaleY - 1) * amount;
        rotation *= amount;
        rotationY *= amount;
        alpha = Math.max(0, Math.min(1, 1 - (1 - alpha) * amount));
    }
}
