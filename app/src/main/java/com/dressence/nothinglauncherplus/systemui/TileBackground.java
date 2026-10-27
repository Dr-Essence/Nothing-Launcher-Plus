package com.dressence.nothinglauncherplus.systemui;

import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;

/**
 * A quick settings tile's background seen through a retro display: drawn as the tile draws
 * it (its shape, its state's tint, its animation from off to on), then recoloured by
 * brightness, so the dark grey of a tile that is off stays as it is and the white of a tile
 * that is on lights deep retro green, or dashboard amber.
 */
final class TileBackground extends DrawableWrapper {

    /** Brightness to colour: dark grey (0.12) kept, the white of an on tile (0.78) lit. */
    private static final ColorMatrixColorFilter GREEN = byBrightness(
            0f, 0.110f, 0.2985f, 0.087f, 0.0896f, 0.120f);
    private static final ColorMatrixColorFilter AMBER = byBrightness(
            0.4627f, 0.059f, 0.2239f, 0.0954f, -0.194f, 0.1513f);

    private final ConfigStore store;
    private final Paint paint = new Paint();
    private final RectF layer = new RectF();

    TileBackground(Drawable background, ConfigStore store) {
        super(background);
        this.store = store;
    }

    /** Each channel {@code scale * brightness + offset}. */
    private static ColorMatrixColorFilter byBrightness(float rs, float ro, float gs, float go,
            float bs, float bo) {
        return new ColorMatrixColorFilter(new float[] {
                rs * 0.299f, rs * 0.587f, rs * 0.114f, 0, ro * 255,
                gs * 0.299f, gs * 0.587f, gs * 0.114f, 0, go * 255,
                bs * 0.299f, bs * 0.587f, bs * 0.114f, 0, bo * 255,
                0, 0, 0, 1, 0});
    }

    @Override
    public void draw(Canvas canvas) {
        if (!store.get().ndotQuickSettings || QuickSettings.litAtSource) {
            super.draw(canvas);
            return;
        }
        paint.setColorFilter(store.get().qsAmber ? AMBER : GREEN);
        layer.set(getBounds());
        int save = canvas.saveLayer(layer, paint);
        try {
            super.draw(canvas);
        } finally {
            canvas.restoreToCount(save);
        }
    }
}
