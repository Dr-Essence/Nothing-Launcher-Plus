package com.dressence.nothinglauncherplus.anim;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Build;
import android.text.TextPaint;
import android.util.LruCache;
import android.util.SparseArray;

import java.util.Arrays;

/**
 * Draws an emoji as a coloured Nothing-style dot matrix: a 25 x 25 grid of round dots (the size
 * of Nothing's Glyph Matrix), each lit in the emoji's colour at one of a few brightness steps.
 *
 * <p>Motion is rendered into the grid, not applied to the finished picture: every frame the
 * emoji is posed, sampled down to 25 x 25 with 3 x 3 supersampling and quantized, so it moves
 * the way an image moves across an LED panel, dot by dot, with edge dots dimming in between for
 * smooth motion. A frame whose dots did not change is not redrawn.
 *
 * <p>Drawing is one GPU draw: an alpha-only mask of round dots, sized to the view and shared,
 * coloured by the 25 x 25 frame through a nearest-neighbour bitmap shader.
 */
final class DotMatrix {

    static final int GRID = 25;
    private static final int SUPERSAMPLE = 3;
    private static final int RENDER = GRID * SUPERSAMPLE;
    /** Source glyph resolution: 2x the supersampled grid, so posing never magnifies it. */
    private static final int GLYPH = RENDER * 2;
    /** Share of the grid the emoji fills at rest; the rest is room to move. */
    private static final float FILL = 0.8f;
    /** Dot diameter relative to the dot pitch. */
    private static final float DOT = 0.78f;
    /** Brightness steps a lit dot can take; dimmer than the first step is off. */
    private static final int LEVELS = 5;
    private static final float OFF_BELOW = 0.12f;
    /** Unlit dots stay faintly visible, like a real panel. */
    private static final int UNLIT_DARK = 0x16FFFFFF;
    private static final int UNLIT_LIGHT = 0x12000000;

    private static final LruCache<String, Bitmap> GLYPHS = new LruCache<>(48);
    private static final SparseArray<Bitmap> MASKS = new SparseArray<>();

    private final Bitmap frame = Bitmap.createBitmap(GRID, GRID, Bitmap.Config.ARGB_8888);
    private final Bitmap render = Bitmap.createBitmap(RENDER, RENDER, Bitmap.Config.ARGB_8888);
    private final Canvas renderCanvas = new Canvas(render);
    private final int[] samples = new int[RENDER * RENDER];
    private final int[] dots = new int[GRID * GRID];
    private final int[] shown = new int[GRID * GRID];
    private final Matrix pose = new Matrix();
    private final Matrix shaderMatrix = new Matrix();
    private final Paint glyphPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint litPaint = new Paint();
    private final Paint unlitPaint = new Paint();
    private Bitmap glyph;

    DotMatrix(boolean darkBackground) {
        BitmapShader shader = new BitmapShader(frame, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shader.setFilterMode(BitmapShader.FILTER_MODE_NEAREST);
        }
        litPaint.setShader(shader);
        litPaint.setFilterBitmap(false);
        unlitPaint.setColor(darkBackground ? UNLIT_DARK : UNLIT_LIGHT);
    }

    /** {@code color} is used for plain text; emoji keep their own colours. */
    void setText(CharSequence text, int color) {
        glyph = text == null || text.length() == 0 ? null : glyph(text.toString(), color);
        Arrays.fill(shown, 1); // Force the next render to count as a change.
    }

    /** Renders {@code p} into the grid; returns true if any dot changed. */
    boolean render(Pose p) {
        render.eraseColor(Color.TRANSPARENT);
        if (glyph != null && p.alpha > 0) {
            float base = RENDER * FILL / GLYPH;
            float px = p.pivotX * RENDER;
            float py = p.pivotY * RENDER;
            // A turn around the vertical axis reads as the emoji narrowing to an edge and back.
            float turn = (float) Math.cos(Math.toRadians(p.rotationY + p.spinY));
            pose.setScale(base, base);
            pose.postTranslate((RENDER - GLYPH * base) / 2f, (RENDER - GLYPH * base) / 2f);
            pose.postScale(p.scaleX * turn, p.scaleY, px, py);
            pose.postRotate(p.rotation + p.spin, px, py);
            pose.postTranslate(p.tx * RENDER, p.ty * RENDER);
            glyphPaint.setAlpha(Math.round(Math.min(1, p.alpha) * 255));
            renderCanvas.drawBitmap(glyph, pose, glyphPaint);
        }
        render.getPixels(samples, 0, RENDER, 0, 0, RENDER, RENDER);
        quantize();
        if (Arrays.equals(dots, shown)) {
            return false;
        }
        System.arraycopy(dots, 0, shown, 0, dots.length);
        frame.setPixels(dots, 0, GRID, 0, 0, GRID, GRID);
        return true;
    }

    /** Averages each dot's 3 x 3 samples and snaps its brightness to a panel level. */
    private void quantize() {
        int n = SUPERSAMPLE * SUPERSAMPLE;
        for (int gy = 0; gy < GRID; gy++) {
            for (int gx = 0; gx < GRID; gx++) {
                int a = 0, r = 0, g = 0, b = 0;
                for (int sy = 0; sy < SUPERSAMPLE; sy++) {
                    int row = (gy * SUPERSAMPLE + sy) * RENDER + gx * SUPERSAMPLE;
                    for (int sx = 0; sx < SUPERSAMPLE; sx++) {
                        // getPixels returns unpremultiplied colour: weight it by alpha.
                        int c = samples[row + sx];
                        int ca = c >>> 24;
                        a += ca;
                        r += ((c >> 16) & 0xFF) * ca;
                        g += ((c >> 8) & 0xFF) * ca;
                        b += (c & 0xFF) * ca;
                    }
                }
                float coverage = a / (255f * n);
                int dot = 0;
                if (coverage >= OFF_BELOW) {
                    int level = Math.max(1, Math.round(coverage * LEVELS));
                    dot = Color.argb(level * 255 / LEVELS, r / a, g / a, b / a);
                }
                dots[gy * GRID + gx] = dot;
            }
        }
    }

    /** Draws the grid as the largest square that fits {@code width} x {@code height}. */
    void draw(Canvas canvas, int width, int height) {
        int side = Math.min(width, height);
        if (side <= 0) {
            return;
        }
        float left = (width - side) / 2f;
        float top = (height - side) / 2f;
        Bitmap mask = mask(side);
        shaderMatrix.setScale(side / (float) GRID, side / (float) GRID);
        shaderMatrix.postTranslate(left, top);
        litPaint.getShader().setLocalMatrix(shaderMatrix);
        canvas.drawBitmap(mask, left, top, unlitPaint);
        canvas.drawBitmap(mask, left, top, litPaint);
    }

    /** Round dots on a transparent square, alpha only; shared by every view of that size. */
    private static Bitmap mask(int side) {
        Bitmap mask = MASKS.get(side);
        if (mask != null) {
            return mask;
        }
        if (MASKS.size() > 8) {
            MASKS.clear(); // Sizes come and go with rotations; never keep many around.
        }
        mask = Bitmap.createBitmap(side, side, Bitmap.Config.ALPHA_8);
        Canvas canvas = new Canvas(mask);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float pitch = side / (float) GRID;
        float radius = pitch * DOT / 2f;
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                canvas.drawCircle((x + 0.5f) * pitch, (y + 0.5f) * pitch, radius, paint);
            }
        }
        MASKS.put(side, mask);
        return mask;
    }

    /** The emoji (or text) drawn once, fitted to a GLYPH x GLYPH square by its ink bounds. */
    private static Bitmap glyph(String text, int color) {
        String key = color + "|" + text;
        Bitmap cached = GLYPHS.get(key);
        if (cached != null) {
            return cached;
        }
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(GLYPH);
        paint.setColor(color);
        Rect bounds = new Rect();
        paint.getTextBounds(text, 0, text.length(), bounds);
        Bitmap bitmap = Bitmap.createBitmap(GLYPH, GLYPH, Bitmap.Config.ARGB_8888);
        if (!bounds.isEmpty()) {
            Canvas canvas = new Canvas(bitmap);
            float scale = GLYPH / (float) Math.max(bounds.width(), bounds.height());
            canvas.translate(GLYPH / 2f, GLYPH / 2f);
            canvas.scale(scale, scale);
            canvas.translate(-bounds.exactCenterX(), -bounds.exactCenterY());
            canvas.drawText(text, 0, 0, paint);
        }
        GLYPHS.put(key, bitmap);
        return bitmap;
    }
}
