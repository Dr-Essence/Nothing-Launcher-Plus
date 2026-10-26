package com.dressence.nothinglauncherplus.hook;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;

/**
 * An icon lit the green of the NDot clock with the clock's soft glow round it: its own shape,
 * crisp, only its colour changed, as the clock's text is.
 */
final class GreenGlow {

    static final int GREEN = 0xFF4CFF7A;
    /** The glow: the same green, soft, as round the clock. */
    private static final int GLOW = 0xAA2BE86A;

    private static final Paint ICON = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static final Paint HALO = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static final Paint BLUR = new Paint();
    private static float blurRadius;

    static {
        ICON.setColorFilter(new PorterDuffColorFilter(GREEN, PorterDuff.Mode.SRC_IN));
        HALO.setColorFilter(new PorterDuffColorFilter(GLOW, PorterDuff.Mode.SRC_IN));
    }

    private GreenGlow() {
    }

    /** Draws {@code icon} at the canvas origin in green, glowing {@code radius} round it. */
    static void draw(Canvas canvas, Bitmap icon, float radius, float alpha) {
        int a = Math.round(255 * alpha);
        if (radius >= 1) {
            if (blurRadius != radius) {
                blurRadius = radius;
                BLUR.setMaskFilter(new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL));
            }
            int[] offset = new int[2];
            Bitmap glow = icon.extractAlpha(BLUR, offset);
            HALO.setAlpha(a);
            canvas.drawBitmap(glow, offset[0], offset[1], HALO);
            glow.recycle();
        }
        ICON.setAlpha(a);
        canvas.drawBitmap(icon, 0, 0, ICON);
    }
}
