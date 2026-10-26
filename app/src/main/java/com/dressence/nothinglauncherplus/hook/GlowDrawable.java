package com.dressence.nothinglauncherplus.hook;

import android.graphics.Bitmap;
import android.graphics.BlendModeColorFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;

/**
 * A quick settings tile icon lit the clock's green with its soft glow. It wraps the tile's own
 * icon and draws it frame by frame, so the icon's animations (Wi-Fi turning on, the torch,
 * rotation lock) still play, in green.
 */
class GlowDrawable extends Drawable implements Drawable.Callback {

    /**
     * The glow's reach, in icon heights. Compose clips an icon to its bounds, so the icon is
     * drawn a little in from them ({@link #INSET}) to leave the glow room to show.
     */
    private static final float GLOW = 0.08f;
    private static final float INSET = 0.09f;

    final Drawable inner;
    private ColorFilter filter;
    private int alpha = 255;
    private Bitmap buffer;

    GlowDrawable(Drawable inner) {
        this.inner = inner;
        inner.setCallback(this);
    }

    /** The icon wrapped in green, animated or not as it is. */
    static GlowDrawable wrap(Drawable inner) {
        return inner instanceof Animatable ? new Animated(inner) : new GlowDrawable(inner);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        int w = b.width();
        int h = b.height();
        if (w <= 0 || h <= 0 || w > 1024 || h > 1024) {
            return;
        }
        if (buffer == null || buffer.getWidth() != w || buffer.getHeight() != h) {
            buffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        buffer.eraseColor(Color.TRANSPARENT);
        int inset = Math.round(h * INSET);
        inner.setBounds(inset, inset, w - inset, h - inset);
        inner.draw(new Canvas(buffer));
        canvas.save();
        canvas.translate(b.left, b.top);
        // Lit the tile's tint when it is one of ours (green, or amber on a tile that is on);
        // any other tint the theme gives (dark on a white tile) is drawn green.
        GreenGlow.draw(canvas, buffer, h * GLOW, alpha / 255f, true, lit(filter));
        canvas.restore();
    }

    /** The colour to light the icon for tint {@code f}: a vivid tint as is, else green. */
    private static int lit(ColorFilter f) {
        int c;
        if (f instanceof BlendModeColorFilter) {
            c = ((BlendModeColorFilter) f).getColor();
        } else if (f instanceof PorterDuffColorFilter) {
            try {
                c = (Integer) PorterDuffColorFilter.class.getMethod("getColor").invoke(f);
            } catch (ReflectiveOperationException e) {
                return GreenGlow.GREEN;
            }
        } else {
            return GreenGlow.GREEN;
        }
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        return hsv[1] > 0.5f && hsv[2] > 0.6f ? c | 0xFF000000 : GreenGlow.GREEN;
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        filter = colorFilter;
        invalidateSelf();
    }

    @Override
    public ColorFilter getColorFilter() {
        return filter;
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return inner.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return inner.getIntrinsicHeight();
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        inner.setVisible(visible, restart);
        return super.setVisible(visible, restart);
    }

    @Override
    protected boolean onStateChange(int[] state) {
        return inner.setState(state);
    }

    @Override
    public boolean isStateful() {
        return inner.isStateful();
    }

    @Override
    protected boolean onLevelChange(int level) {
        return inner.setLevel(level);
    }

    @Override
    public void invalidateDrawable(Drawable who) {
        invalidateSelf();
    }

    @Override
    public void scheduleDrawable(Drawable who, Runnable what, long when) {
        scheduleSelf(what, when);
    }

    @Override
    public void unscheduleDrawable(Drawable who, Runnable what) {
        unscheduleSelf(what);
    }

    /** An animated icon: its animation started and stopped through the wrapper. */
    private static final class Animated extends GlowDrawable implements Animatable {

        Animated(Drawable inner) {
            super(inner);
        }

        @Override
        public void start() {
            ((Animatable) inner).start();
        }

        @Override
        public void stop() {
            ((Animatable) inner).stop();
        }

        @Override
        public boolean isRunning() {
            return ((Animatable) inner).isRunning();
        }
    }
}
