package com.dressence.nothinglauncherplus.anim;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import java.util.Random;

/**
 * An emoji that plays a {@link Motion}, drawn either as the system glyph or as a Nothing-style
 * {@link DotMatrix}.
 *
 * <p>As a glyph only transform properties change, which the render thread composites without
 * redrawing, so a frame costs a handful of property writes. As a dot matrix the pose is rendered
 * into the 25 x 25 grid instead and the view redraws only when a dot changes.
 *
 * <p>Every view gets its own speed, strength, pause length and phase from a seed, so two covers
 * with the same motion never move in lockstep.
 */
@SuppressLint("ViewConstructor")
public class AnimatedEmojiView extends TextView {

    private final Pose pose = new Pose();
    private Motion motion = Motion.NONE;
    private float tempo = 1;
    private float amount = 1;
    private float restScale = 1;
    private float phase;
    private boolean atRest = true;
    private DotMatrix dots;
    /** Where the emoji square sits (null: centred), and whether dots fill the whole view. */
    private Rect dotContent;
    private boolean dotPanel;
    private float dotCorner;

    public AnimatedEmojiView(Context context) {
        super(context);
        setGravity(Gravity.CENTER);
        setIncludeFontPadding(false);
        setMaxLines(1);
    }

    /** Draws as a Nothing-style dot matrix ({@code true}) or as the system emoji. */
    public void setDotMatrix(boolean on) {
        if (on == (dots != null)) {
            return;
        }
        pose.reset();
        if (on) {
            applyTransforms(pose); // The grid does the moving; the view stays put.
            int night = getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            dots = new DotMatrix(night != Configuration.UI_MODE_NIGHT_NO);
            dots.setText(getText(), getCurrentTextColor());
            dots.render(pose);
        } else {
            dots = null;
        }
        invalidate();
    }

    /**
     * Places the dot-matrix emoji at {@code content} and fills the rest of the view with unlit
     * dots on the same pitch, inside rounded corners of radius {@code corner}: the whole view
     * becomes one dot screen.
     */
    public void setDotPanel(Rect content, float corner) {
        if (content.equals(dotContent) && dotPanel && corner == dotCorner) {
            return;
        }
        dotContent = new Rect(content);
        dotPanel = true;
        dotCorner = corner;
        invalidate();
    }

    /** Shows {@code text} playing {@code motion}; the same seed always moves the same way. */
    public void setEmoji(CharSequence text, Motion motion, long seed) {
        setText(text);
        if (dots != null) {
            dots.setText(text, getCurrentTextColor());
            pose.reset();
            dots.render(pose);
            invalidate();
        }
        this.motion = motion != null ? motion : Motion.NONE;
        Random random = new Random(seed);
        tempo = 0.9f + 0.2f * random.nextFloat();
        amount = 0.88f + 0.24f * random.nextFloat();
        restScale = 0.8f + 0.7f * random.nextFloat();
        phase = random.nextFloat();
        rest();
        MotionDriver.get().wake();
    }

    public Motion motion() {
        return motion;
    }

    /**
     * A single glyph never overlaps itself, so fading it needs no offscreen layer; the dot
     * matrix draws its unlit and lit dots on top of each other, so it does.
     */
    @Override
    public boolean hasOverlappingRendering() {
        return dots != null;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        MotionDriver.get().add(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        MotionDriver.get().remove(this);
        rest();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            MotionDriver.get().wake();
        }
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible) {
            MotionDriver.get().wake();
        }
    }

    /**
     * Advances to {@code nowNanos}. Returns 0 if the emoji moved this frame, otherwise the
     * nanoseconds until its next play ({@link Long#MAX_VALUE} for a static emoji).
     */
    long step(long nowNanos, float durationScale) {
        if (motion == Motion.NONE) {
            rest();
            return Long.MAX_VALUE;
        }
        float active = motion.duration / tempo * durationScale;
        float pause = motion.rest * restScale * durationScale;
        long cycle = (long) ((active + pause) * 1e9f);
        long local = (nowNanos + (long) (phase * cycle)) % cycle;
        long activeNanos = (long) (active * 1e9f);
        if (local >= activeNanos) {
            rest();
            return cycle - local;
        }
        pose.reset();
        motion.pose(local / 1e9f * tempo / durationScale, pose);
        pose.amplify(amount);
        apply(pose);
        if (atRest) {
            atRest = false;
            requestFrameRate(true);
        }
        return 0;
    }

    /** Snaps back to the resting pose and stops asking for a high frame rate. */
    void rest() {
        if (atRest) {
            return;
        }
        atRest = true;
        pose.reset();
        apply(pose);
        requestFrameRate(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (dots != null) {
            dots.draw(canvas, getWidth(), getHeight(), dotContent, dotPanel, dotCorner);
        } else {
            super.onDraw(canvas);
        }
    }

    private void apply(Pose p) {
        if (dots != null) {
            if (dots.render(p)) {
                invalidate();
            }
            return;
        }
        applyTransforms(p);
    }

    private void applyTransforms(Pose p) {
        int w = getWidth();
        int h = getHeight();
        setPivotX(p.pivotX * w);
        setPivotY(p.pivotY * h);
        setTranslationX(p.tx * w);
        setTranslationY(p.ty * h);
        setScaleX(p.scaleX);
        setScaleY(p.scaleY);
        setRotation(p.rotation + p.spin);
        setRotationY(p.rotationY + p.spinY);
        setAlpha(p.alpha);
    }

    /**
     * While moving, ask for the panel's top refresh rate (up to 120 Hz) so motion is as smooth
     * as the display allows; at rest, drop the request so adaptive panels can slow down again.
     */
    private void requestFrameRate(boolean moving) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return;
        }
        setRequestedFrameRate(moving ? FrameRates.target(getDisplay())
                : View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT);
    }
}
