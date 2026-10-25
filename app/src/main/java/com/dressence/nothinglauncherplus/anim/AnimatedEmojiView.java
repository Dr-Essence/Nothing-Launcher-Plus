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
 * into the 32 x 32 grid instead and the view redraws only when a dot changes.
 *
 * <p>Every view gets its own speed, strength, pause length and phase from a seed, so two covers
 * with the same motion never move in lockstep.
 *
 * <p>As a dot matrix it can also play a {@link DotEffect} inside the dots (static on a TV, a
 * blinking face, running tears) at {@link #EFFECT_FPS}, the pace of a real LED panel, and react
 * ({@link #react()}) to a touch or the drawer opening: it plays its motion at once and its
 * effect flares for a moment.
 */
@SuppressLint("ViewConstructor")
public class AnimatedEmojiView extends TextView {

    private static final int EFFECT_FPS = 24;
    private static final long EFFECT_FRAME_NANOS = 1_000_000_000L / EFFECT_FPS;
    private static final long REACT_NANOS = 1_400_000_000L;
    /** Motions longer than this make a slow reaction; a quick one stands in. */
    private static final float QUICK_REACTION = 1.8f;

    private final Pose pose = new Pose();
    private DotEffect effect = DotEffect.NONE;
    private float[] effectAnchor;
    private int effectSeed;
    private long effectFrame = -1;
    private long reactStart;
    private Motion motion = Motion.NONE;
    private float tempo = 1;
    private float amount = 1;
    private float restScale = 1;
    private float phase;
    private boolean atRest = true;
    private DotMatrix dots;
    /** Where the emoji square sits (null: centred) and the dot screen around it (null: none). */
    private Rect dotContent;
    private Rect dotPanel;
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
     * Places the dot-matrix emoji at {@code content} and turns {@code panel} (view coordinates)
     * into one dot screen around it: whole dots edge to edge, inside rounded corners of radius
     * {@code corner}.
     */
    public void setDotPanel(Rect content, Rect panel, float corner) {
        if (content.equals(dotContent) && panel.equals(dotPanel) && corner == dotCorner) {
            return;
        }
        dotContent = new Rect(content);
        dotPanel = new Rect(panel);
        dotCorner = corner;
        invalidate();
    }

    /** Shows {@code text} playing {@code motion}; the same seed always moves the same way. */
    public void setEmoji(CharSequence text, Motion motion, long seed) {
        setEmoji(text, motion, DotEffect.NONE, seed);
    }

    /**
     * Shows {@code text} playing {@code motion} and, as a dot matrix, {@code effect} inside its
     * dots; the same seed always plays them the same way.
     */
    public void setEmoji(CharSequence text, Motion motion, DotEffect effect, long seed) {
        setEmoji(text, motion, effect, null, seed);
    }

    /** As above, with the effect's anchor points from the emoji table (see DotEffect#anchor). */
    public void setEmoji(CharSequence text, Motion motion, DotEffect effect, float[] anchor,
            long seed) {
        this.effect = effect != null ? effect : DotEffect.NONE;
        effectAnchor = anchor;
        effectSeed = (int) (seed ^ (seed >>> 32));
        effectFrame = -1;
        reactStart = 0;
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
        atRest = false;
        rest();
        MotionDriver.get().wake();
    }

    public Motion motion() {
        return motion;
    }

    /**
     * Shows a dot-matrix label at {@code area} (view coordinates), on the panel's own dots;
     * null or empty text removes it.
     */
    public void setDotLabel(CharSequence text, int color, Rect area) {
        if (dots != null) {
            dots.setLabel(text, color, area);
            invalidate();
            MotionDriver.get().wake();
        }
    }

    /** Reacts now (a touch, the drawer opening): plays its motion and flares its effect. */
    public void react() {
        reactStart = System.nanoTime();
        MotionDriver.get().wake();
    }

    /** The motion a reaction plays: the emoji's own, unless it is a slow one. */
    private Motion reaction() {
        if (motion == Motion.NONE) {
            return Motion.NONE; // Chosen to stay still: only the effect reacts.
        }
        if (motion.duration <= QUICK_REACTION) {
            return motion;
        }
        return effect.isFace() ? Motion.HAPPY : Motion.JELLY;
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
     * nanoseconds until it next needs a frame ({@link Long#MAX_VALUE} for a static emoji).
     */
    long step(long nowNanos, float durationScale) {
        pose.reset();
        boolean moving = false;
        long wait = Long.MAX_VALUE;
        Motion reaction = reaction();
        long sinceReact = nowNanos - reactStart;
        long reactActive = (long) (reaction.duration * durationScale * 1e9f);
        if (reactStart != 0 && sinceReact >= 0 && sinceReact < reactActive) {
            reaction.pose(sinceReact / 1e9f / durationScale, pose);
            pose.amplify(amount);
            moving = true;
        } else if (motion != Motion.NONE) {
            float active = motion.duration / tempo * durationScale;
            float pause = motion.rest * restScale * durationScale;
            long cycle = (long) ((active + pause) * 1e9f);
            long local = (nowNanos + (long) (phase * cycle)) % cycle;
            long activeNanos = (long) (active * 1e9f);
            if (local < activeNanos) {
                motion.pose(local / 1e9f * tempo / durationScale, pose);
                pose.amplify(amount);
                moving = true;
            } else {
                wait = cycle - local;
            }
        }
        if (dots != null) {
            if (dots.scrollLabel(nowNanos)) {
                invalidate();
            }
            wait = Math.min(wait, dots.labelWait(nowNanos));
        }
        boolean effects = dots != null && effect != DotEffect.NONE;
        if (!moving && !effects) {
            rest();
            return wait;
        }
        if (moving && atRest) {
            atRest = false;
            requestFrameRate(true);
        } else if (!moving && !atRest) {
            atRest = true; // Effects alone run at the panel's pace, not the display's.
            requestFrameRate(false);
        }
        if (dots == null) {
            applyTransforms(pose);
            return moving ? 0 : wait;
        }
        long frame = nowNanos / EFFECT_FRAME_NANOS;
        if (moving || frame != effectFrame) {
            effectFrame = frame;
            float time = (frame % (EFFECT_FPS * 3600L)) / (float) EFFECT_FPS;
            float since = reactStart == 0 ? Float.MAX_VALUE : (nowNanos - reactStart) / 1e9f;
            if (dots.render(pose, effect, effectAnchor, time, boost(nowNanos), since,
                    effectSeed)) {
                invalidate();
            }
        }
        return moving ? 0 : Math.min(wait, EFFECT_FRAME_NANOS - nowNanos % EFFECT_FRAME_NANOS);
    }

    /** How strongly the effect flares: 1 right after a reaction, easing out to 0. */
    private float boost(long nowNanos) {
        if (reactStart == 0) {
            return 0;
        }
        float k = 1 - (nowNanos - reactStart) / (float) REACT_NANOS;
        return k <= 0 ? 0 : k * (2 - k) > 1 ? 1 : k * (2 - k);
    }

    /** Snaps back to the resting pose, effect off, and stops asking for a high frame rate. */
    void rest() {
        if (atRest && effectFrame < 0) {
            return;
        }
        atRest = true;
        effectFrame = -1;
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
