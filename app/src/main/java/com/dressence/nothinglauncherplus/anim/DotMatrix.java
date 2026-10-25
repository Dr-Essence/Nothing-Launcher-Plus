package com.dressence.nothinglauncherplus.anim;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.text.TextPaint;
import android.util.LruCache;

import java.util.Arrays;

/**
 * Draws an emoji as a coloured Nothing-style dot matrix: a square of 32 x 32 round dots, each lit
 * in the emoji's colour at one of a few brightness steps. The dots can continue past the emoji
 * as a panel of unlit dots on the same pitch, filling a folder tile edge to edge, so the whole
 * tile reads as one dot screen.
 *
 * <p>Motion is rendered into the grid, not applied to the finished picture: every frame the
 * emoji is posed, sampled down to 32 x 32 with 3 x 3 supersampling and quantized, so it moves
 * the way an image moves across an LED panel, dot by dot, with edge dots dimming in between for
 * smooth motion. A frame whose dots did not change is not redrawn.
 *
 * <p>Drawing is two bitmap draws of one shared alpha-only mask of round dots: once in the faint
 * unlit colour, once coloured by the frame through a nearest-neighbour shader. The frame has a
 * transparent border, so panel dots outside the emoji square stay dark.
 */
final class DotMatrix {

    static final int GRID = 32;
    private static final int SUPERSAMPLE = 3;
    private static final int RENDER = GRID * SUPERSAMPLE;
    /** Source glyph resolution: 2x the supersampled grid, so posing never magnifies it. */
    private static final int GLYPH = RENDER * 2;
    /** Share of the grid the emoji fills at rest; the rest is room to move. */
    private static final float FILL = 0.82f;
    /** Dot diameter relative to the dot pitch. */
    private static final float DOT = 0.8f;
    /** Below this coverage a dot is off; above it, one of these opacities (crisp, not washed). */
    private static final float OFF_BELOW = 0.14f;
    private static final int[] LEVEL_ALPHA = {120, 175, 225, 255};
    /** Unlit dots stay faintly visible, like a real panel. */
    private static final int UNLIT_DARK = 0x1AFFFFFF;
    private static final int UNLIT_LIGHT = 0x14000000;

    private static final LruCache<String, Bitmap> GLYPHS = new LruCache<>(48);
    private static final LruCache<String, Bitmap> MASKS = new LruCache<>(8);

    /** The 32 x 32 frame inside a 1-dot transparent border (see class comment). */
    private final Bitmap frame =
            Bitmap.createBitmap(GRID + 2, GRID + 2, Bitmap.Config.ARGB_8888);
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
    private final Rect square = new Rect();
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
        frame.setPixels(dots, 0, GRID, 1, 1, GRID, GRID);
        return true;
    }

    /** Averages each dot's 3 x 3 samples and snaps its opacity to a panel level. */
    private void quantize() {
        int n = SUPERSAMPLE * SUPERSAMPLE;
        int levels = LEVEL_ALPHA.length;
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
                    int level = Math.min(levels, Math.max(1, Math.round(coverage * levels)));
                    dot = Color.argb(LEVEL_ALPHA[level - 1], r / a, g / a, b / a);
                }
                dots[gy * GRID + gx] = dot;
            }
        }
    }

    /**
     * Draws the emoji square at {@code content} (null: the largest centred square). With a
     * {@code panel} rectangle, that whole area becomes the screen: as many whole dots as fit,
     * centred so the margins are equal on every side, kept inside rounded corners of radius
     * {@code corner}, with the emoji square snapped onto the same grid.
     */
    void draw(Canvas canvas, int width, int height, Rect content, Rect panel, float corner) {
        if (content == null) {
            int side = Math.min(width, height);
            square.set((width - side) / 2, (height - side) / 2,
                    (width + side) / 2, (height + side) / 2);
            content = square;
        }
        if (content.width() <= 0 || width <= 0 || height <= 0) {
            return;
        }
        float pitch = content.width() / (float) GRID;
        Bitmap mask;
        float emojiLeft = content.left;
        float emojiTop = content.top;
        if (panel != null && !panel.isEmpty()) {
            int columns = (int) (panel.width() / pitch);
            int rows = (int) (panel.height() / pitch);
            float left = panel.left + (panel.width() - columns * pitch) / 2f;
            float top = panel.top + (panel.height() - rows * pitch) / 2f;
            // Snap the emoji onto the panel's dots.
            emojiLeft = left + Math.round((content.left - left) / pitch) * pitch;
            emojiTop = top + Math.round((content.top - top) / pitch) * pitch;
            mask = mask(pitch, columns, rows, left, top, width, height, panel, corner);
            canvas.drawBitmap(mask, 0, 0, unlitPaint);
            drawLit(canvas, mask, 0, 0, pitch, emojiLeft, emojiTop);
        } else {
            int side = Math.round(GRID * pitch);
            square.set(0, 0, side, side);
            mask = mask(pitch, GRID, GRID, 0, 0, side, side, square, 0);
            canvas.drawBitmap(mask, emojiLeft, emojiTop, unlitPaint);
            drawLit(canvas, mask, emojiLeft, emojiTop, pitch, emojiLeft, emojiTop);
        }
    }

    /** Frame pixel (1, 1) is the emoji square's first dot; the border around it is empty. */
    private void drawLit(Canvas canvas, Bitmap mask, float maskLeft, float maskTop, float pitch,
            float emojiLeft, float emojiTop) {
        shaderMatrix.setScale(pitch, pitch);
        shaderMatrix.postTranslate(emojiLeft - pitch, emojiTop - pitch);
        litPaint.getShader().setLocalMatrix(shaderMatrix);
        canvas.drawBitmap(mask, maskLeft, maskTop, litPaint);
    }

    /**
     * A {@code width} x {@code height} alpha mask of {@code columns} x {@code rows} round dots
     * starting at ({@code left}, {@code top}), skipping dots that would leave {@code bounds}'
     * rounded corners of radius {@code corner}. Shared by every view with the same geometry.
     */
    private static Bitmap mask(float pitch, int columns, int rows, float left, float top,
            int width, int height, Rect bounds, float corner) {
        String key = width + "x" + height + "@" + pitch + "+" + left + "," + top + "/"
                + bounds.toShortString() + "r" + corner;
        Bitmap mask = MASKS.get(key);
        if (mask != null) {
            return mask;
        }
        mask = Bitmap.createBitmap(Math.max(1, width), Math.max(1, height),
                Bitmap.Config.ALPHA_8);
        Canvas canvas = new Canvas(mask);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float radius = pitch * DOT / 2f;
        RectF inner = new RectF(bounds.left + corner, bounds.top + corner,
                bounds.right - corner, bounds.bottom - corner);
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < columns; x++) {
                float cx = left + (x + 0.5f) * pitch;
                float cy = top + (y + 0.5f) * pitch;
                if (corner > 0 && outsideRoundRect(cx, cy, radius, inner, corner)) {
                    continue;
                }
                canvas.drawCircle(cx, cy, radius, paint);
            }
        }
        MASKS.put(key, mask);
        return mask;
    }

    /** True if a dot at (cx, cy) would stick out of the rounded rectangle's corners. */
    private static boolean outsideRoundRect(float cx, float cy, float radius, RectF inner,
            float corner) {
        float dx = cx < inner.left ? inner.left - cx : cx > inner.right ? cx - inner.right : 0;
        float dy = cy < inner.top ? inner.top - cy : cy > inner.bottom ? cy - inner.bottom : 0;
        float reach = corner - radius;
        return dx * dx + dy * dy > reach * reach;
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
