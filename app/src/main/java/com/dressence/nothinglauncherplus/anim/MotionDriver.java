package com.dressence.nothinglauncherplus.anim;

import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.os.Build;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * One vsync callback for every animated emoji in the process (main thread only).
 *
 * <ul>
 *   <li>Frames are requested only while at least one visible emoji is mid-play; Choreographer
 *       then ticks at whatever rate the panel runs, and motions are time-based, so they look
 *       the same at 60, 90 or 120 Hz.</li>
 *   <li>During pauses it sleeps until the next play starts.</li>
 *   <li>Emojis that are off-screen, faded out, in a hidden window or in a closed drawer are
 *       skipped; with none on screen it checks back four times a second, and with no visible
 *       window at all it stops until a view wakes it.</li>
 *   <li>The system "animation scale" developer setting is honoured; 0 turns motion off.</li>
 * </ul>
 */
public final class MotionDriver implements Choreographer.FrameCallback {

    private static final long OFFSCREEN_POLL_MS = 250;
    private static final long STAGGER_MS = 70;
    private static MotionDriver instance;

    /**
     * Weakly held: a list's recycled cells can leave a screen without ever being told they
     * were detached, and must not keep that screen alive.
     */
    private final ArrayList<WeakReference<AnimatedEmojiView>> views = new ArrayList<>();
    private final Rect visible = new Rect();
    private boolean scheduled;

    public static MotionDriver get() {
        if (instance == null) {
            instance = new MotionDriver();
        }
        return instance;
    }

    private MotionDriver() {
    }

    void add(AnimatedEmojiView view) {
        if (indexOf(view) < 0) {
            views.add(new WeakReference<>(view));
        }
        wake();
    }

    void remove(AnimatedEmojiView view) {
        int i = indexOf(view);
        if (i >= 0) {
            views.remove(i);
        }
    }

    /** Index of {@code view}, dropping collected entries on the way. */
    private int indexOf(AnimatedEmojiView view) {
        for (int i = views.size() - 1; i >= 0; i--) {
            AnimatedEmojiView v = views.get(i).get();
            if (v == null) {
                views.remove(i);
            } else if (v == view) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Makes every emoji on screen react, after {@code delayMs}, staggered left to right and top
     * to bottom so the reaction ripples across them.
     */
    public void reactAll(long delayMs) {
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.postDelayed(() -> {
            ArrayList<AnimatedEmojiView> shown = new ArrayList<>();
            for (WeakReference<AnimatedEmojiView> ref : views) {
                AnimatedEmojiView view = ref.get();
                if (view != null && view.isShown() && isOnScreen(view)) {
                    shown.add(view);
                }
            }
            int[] a = new int[2];
            int[] b = new int[2];
            shown.sort((x, y) -> {
                x.getLocationOnScreen(a);
                y.getLocationOnScreen(b);
                return a[1] / 64 != b[1] / 64 ? Integer.compare(a[1], b[1])
                        : Integer.compare(a[0], b[0]);
            });
            for (int i = 0; i < shown.size(); i++) {
                AnimatedEmojiView view = shown.get(i);
                handler.postDelayed(view::react, i * STAGGER_MS);
            }
        }, delayMs);
    }

    /** Re-evaluates on the next frame (e.g. something became visible). */
    void wake() {
        if (views.isEmpty()) {
            return;
        }
        Choreographer choreographer = Choreographer.getInstance();
        if (scheduled) {
            choreographer.removeFrameCallback(this);
        }
        scheduled = true;
        choreographer.postFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        scheduled = false;
        float scale = durationScale();
        boolean animating = false;
        boolean onScreen = false;
        boolean windowVisible = false;
        long nextPlay = Long.MAX_VALUE;

        for (int i = views.size() - 1; i >= 0; i--) {
            AnimatedEmojiView view = views.get(i).get();
            if (view == null) {
                views.remove(i);
                continue;
            }
            if (view.getWindowVisibility() != View.VISIBLE || !view.isShown()) {
                view.rest();
                continue;
            }
            windowVisible = true;
            if (scale == 0 || !isOnScreen(view)) {
                view.rest();
                continue;
            }
            onScreen = true;
            long wait = view.step(frameTimeNanos, scale);
            if (wait == 0) {
                animating = true;
            } else {
                nextPlay = Math.min(nextPlay, wait);
            }
        }

        long delayMs;
        if (animating) {
            delayMs = 0;
        } else if (onScreen && nextPlay != Long.MAX_VALUE) {
            delayMs = Math.max(1, nextPlay / 1_000_000L);
        } else if (windowVisible) {
            delayMs = OFFSCREEN_POLL_MS;
        } else {
            return; // Nothing visible: sleep until a view wakes us.
        }
        scheduled = true;
        Choreographer choreographer = Choreographer.getInstance();
        if (delayMs == 0) {
            choreographer.postFrameCallback(this);
        } else {
            choreographer.postFrameCallbackDelayed(this, delayMs);
        }
    }

    /** Visible on the display and not faded out by any ancestor. */
    private boolean isOnScreen(View view) {
        if (!view.getGlobalVisibleRect(visible)) {
            return false;
        }
        float alpha = 1;
        View v = view;
        while (true) {
            alpha *= v.getAlpha();
            if (alpha < 0.01f) {
                return false;
            }
            ViewParent parent = v.getParent();
            if (!(parent instanceof View)) {
                return true;
            }
            v = (View) parent;
        }
    }

    private static float durationScale() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ValueAnimator.getDurationScale();
        }
        return ValueAnimator.areAnimatorsEnabled() ? 1 : 0;
    }
}
