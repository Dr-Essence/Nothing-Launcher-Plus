package com.dressence.nothinglauncherplus.hook;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;

/**
 * A quick settings tile icon drawn in NDot dots. It wraps the tile's own icon and draws it,
 * frame by frame, as dots: the tile's tint (on, off, unavailable) still colours it, and the
 * icon's own animations (Wi-Fi turning on, the torch, rotation lock) still play, now in dots.
 */
class DotDrawable extends Drawable implements Drawable.Callback {

    /** Dots across the icon's height. */
    private static final int ROWS = 13;

    final Drawable inner;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ColorFilter filter;
    private int alpha = 255;
    private Bitmap buffer;

    DotDrawable(Drawable inner) {
        this.inner = inner;
        inner.setCallback(this);
    }

    /** The icon wrapped as dots, animated or not as it is. */
    static DotDrawable wrap(Drawable inner) {
        return inner instanceof Animatable ? new Animated(inner) : new DotDrawable(inner);
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
        inner.setBounds(0, 0, w, h);
        inner.draw(new Canvas(buffer));
        paint.setColorFilter(filter);
        canvas.save();
        canvas.translate(b.left, b.top);
        // Tinted by the tile: white dots the tint recolours. Untinted: the icon's colours.
        DotGlyph.draw(canvas, buffer, h / (float) ROWS, filter != null ? Color.WHITE : 0,
                alpha / 255f, paint);
        canvas.restore();
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

    /** An animated icon: its animation started and stopped through the dots. */
    private static final class Animated extends DotDrawable implements Animatable {

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
