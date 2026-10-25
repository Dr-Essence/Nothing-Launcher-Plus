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
import android.graphics.Typeface;
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

    /** The emoji at rest, the regions effects work on, and the frame they paint. */
    private final int[] restDots = new int[GRID * GRID];
    private final Scene scene = new Scene(GRID);
    private final Matrix restPose = new Matrix();
    private final Matrix restInverse = new Matrix();
    private final Matrix forward = new Matrix();
    private final Matrix inverse = new Matrix();
    private final float[] values = new float[9];
    private final float[] forwardMap = new float[6];
    private final float[] inverseMap = new float[6];
    private static final float[] IDENTITY = {1, 0, 0, 0, 1, 0};

    /** The label, lit on its own screen of finer dots (see {@link #setLabel}). */
    private final DotLabel label = new DotLabel();
    private final Paint labelPaint = new Paint();
    private static final int NOTHING_RED = 0xFFD71921;
    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ledPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    DotMatrix(boolean darkBackground) {
        BitmapShader shader = new BitmapShader(frame, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shader.setFilterMode(BitmapShader.FILTER_MODE_NEAREST);
        }
        litPaint.setShader(shader);
        litPaint.setFilterBitmap(false);
        unlitPaint.setColor(darkBackground ? UNLIT_DARK : UNLIT_LIGHT);
        labelPaint.setFilterBitmap(false);
        // Dark glass on the tile in dark mode; a faint smoked glass in light mode.
        glassPaint.setColor(darkBackground ? 0xF0000000 : 0x1A000000);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setColor(darkBackground ? 0x2EFFFFFF : 0x24000000);
        float base = RENDER * FILL / GLYPH;
        restPose.setScale(base, base);
        restPose.postTranslate((RENDER - GLYPH * base) / 2f, (RENDER - GLYPH * base) / 2f);
        restPose.invert(restInverse);
    }

    /** {@code color} is used for plain text; emoji keep their own colours. */
    void setText(CharSequence text, int color) {
        glyph = text == null || text.length() == 0 ? null : glyph(text.toString(), color);
        // The resting frame, once: what effects find their regions in and paint over.
        rasterize(null);
        System.arraycopy(dots, 0, restDots, 0, dots.length);
        scene.regions = glyph != null ? new Regions(restDots, GRID) : null;
        Arrays.fill(shown, 1); // Force the next render to count as a change.
    }

    /** Renders {@code p} into the grid; returns true if any dot changed. */
    boolean render(Pose p) {
        return render(p, DotEffect.NONE, null, 0, 0, Float.MAX_VALUE, 0);
    }

    /**
     * Renders {@code p} with {@code effect} (placed by {@code anchor}, if it takes one) playing
     * on top at {@code time} seconds, flared by
     * {@code boost} (0..1) {@code since} seconds after a reaction; returns true if any dot
     * changed.
     */
    boolean render(Pose p, DotEffect effect, float[] anchor, float time, float boost,
            float since, int seed) {
        boolean still = p.isRest();
        if (still) {
            System.arraycopy(restDots, 0, dots, 0, dots.length);
        } else {
            rasterize(p);
        }
        if (effect != DotEffect.NONE && scene.regions != null) {
            System.arraycopy(dots, 0, scene.dots, 0, dots.length);
            if (still) {
                scene.map(IDENTITY, IDENTITY);
            } else {
                mapScene();
            }
            scene.t = time;
            scene.anchor = anchor;
            scene.boost = boost;
            scene.since = since;
            scene.seed = seed;
            effect.apply(scene);
            System.arraycopy(scene.dots, 0, dots, 0, dots.length);
        }
        if (Arrays.equals(dots, shown)) {
            return false;
        }
        System.arraycopy(dots, 0, shown, 0, dots.length);
        frame.setPixels(dots, 0, GRID, 1, 1, GRID, GRID);
        return true;
    }

    /** Draws the glyph posed by {@code p} (null: at rest) and samples it into the dots. */
    private void rasterize(Pose p) {
        render.eraseColor(Color.TRANSPARENT);
        if (glyph != null && (p == null || p.alpha > 0)) {
            pose.set(restPose);
            if (p != null) {
                float px = p.pivotX * RENDER;
                float py = p.pivotY * RENDER;
                // A turn around the vertical axis reads as the emoji narrowing to an edge.
                float turn = (float) Math.cos(Math.toRadians(p.rotationY + p.spinY));
                pose.postScale(p.scaleX * turn, p.scaleY, px, py);
                pose.postRotate(p.rotation + p.spin, px, py);
                pose.postTranslate(p.tx * RENDER, p.ty * RENDER);
            }
            glyphPaint.setAlpha(p == null ? 255 : Math.round(Math.min(1, p.alpha) * 255));
            renderCanvas.drawBitmap(glyph, pose, glyphPaint);
        }
        render.getPixels(samples, 0, RENDER, 0, 0, RENDER, RENDER);
        quantize();
    }

    /**
     * Tells the effect how the posed frame relates to the resting one, in dots: forward takes a
     * resting dot to where it is shown now, the inverse takes a shown dot back to its resting
     * spot. Both are the glyph pose relative to the rest pose, measured in grid units.
     */
    private void mapScene() {
        forward.set(restInverse);
        forward.preScale(SUPERSAMPLE, SUPERSAMPLE);
        forward.postConcat(pose);
        forward.postScale(1f / SUPERSAMPLE, 1f / SUPERSAMPLE);
        if (!forward.invert(inverse)) {
            scene.map(null, null); // Turned edge-on: nothing to paint on.
            return;
        }
        forward.getValues(values);
        System.arraycopy(values, 0, forwardMap, 0, 6);
        inverse.getValues(values);
        System.arraycopy(values, 0, inverseMap, 0, 6);
        scene.map(inverseMap, forwardMap);
    }

    /**
     * Shows {@code text} at {@code area} (view coordinates, usually where the hidden folder name
     * sits), in {@code color}: in {@code typeface} at {@code size} px, or in dots when the
     * typeface is null; null text removes it.
     */
    void setLabel(CharSequence text, int color, Rect area, Typeface typeface, float size) {
        label.set(text, color, area, typeface, size);
    }

    /** Advances a label too long for the panel by one dot when due; true if it moved. */
    boolean scrollLabel(long nowNanos) {
        return label.scroll(nowNanos);
    }

    /** Nanoseconds until the label next scrolls, or {@link Long#MAX_VALUE} if it fits. */
    long labelWait(long nowNanos) {
        return label.untilScroll(nowNanos);
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
     * {@code panel} rectangle (rounded by {@code corner}), that area becomes a small device: its
     * edge is the bezel, and inside sit screens of black glass with a hairline rim. The emoji
     * gets the main screen (as many whole dots as fit, the emoji snapped onto them); a label
     * gets its own pill-shaped screen of finer dots where the name sits, with a red status dot.
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
        if (panel == null || panel.isEmpty()) {
            int side = Math.round(GRID * pitch);
            square.set(0, 0, side, side);
            Bitmap mask = mask(width, height, new Grid(pitch, GRID, GRID, content.left,
                    content.top, new RectF(content), 0));
            canvas.drawBitmap(mask, 0, 0, unlitPaint);
            drawLit(canvas, mask, 0, 0, pitch, content.left, content.top);
            return;
        }
        float bezel = Math.max(pitch * 1.2f, Math.min(panel.width(), panel.height()) * 0.045f);
        float inner = Math.max(pitch, corner - bezel);
        // The name's screen: a pill just tall enough for the label, centred on the name.
        Grid name = null;
        if (label.present()) {
            float dot = label.dot(pitch);
            int rows = label.rows() + 2;
            // Half a dot of glass all round the dots, so none touches the rim.
            float h = (rows + 1) * dot;
            float top = Math.max(panel.top + bezel, label.centerY() - h / 2);
            RectF pill = new RectF(panel.left + bezel, top, panel.right - bezel, top + h);
            // Dots run out to the pill's round ends, following the curve inside the rim.
            int cols = (int) ((pill.width() - dot) / dot);
            name = new Grid(dot, cols, rows, pill.left + (pill.width() - cols * dot) / 2,
                    top + dot / 2, pill, h / 2);
        }
        // The emoji's screen fills the rest of the face.
        RectF glass = new RectF(panel.left + bezel,
                name != null ? name.bounds.bottom + bezel * 0.6f : panel.top + bezel,
                panel.right - bezel, panel.bottom - bezel);
        // The emoji fits inside the glass with a dot to spare all round.
        float side = Math.min(content.width(),
                Math.min(glass.width(), glass.height()) - 2 * pitch);
        if (side <= 0) {
            return;
        }
        pitch = side / GRID;
        // At least half a dot of glass between the outer dots and the rim.
        int columns = (int) ((glass.width() - pitch) / pitch);
        int rows = (int) ((glass.height() - pitch) / pitch);
        float left = glass.left + (glass.width() - columns * pitch) / 2f;
        float top = glass.top + (glass.height() - rows * pitch) / 2f;
        Grid screen = new Grid(pitch, columns, rows, left, top, glass, inner);
        // The emoji square, centred where the cover would be, kept on the glass and on its dots.
        float cx = Math.max(glass.left + side / 2, Math.min(glass.right - side / 2,
                content.exactCenterX()));
        float cy = Math.max(glass.top + side / 2, Math.min(glass.bottom - side / 2,
                content.exactCenterY()));
        float emojiLeft = left + Math.round((cx - side / 2 - left) / pitch) * pitch;
        float emojiTop = top + Math.round((cy - side / 2 - top) / pitch) * pitch;

        drawGlass(canvas, screen);
        if (name != null) {
            drawGlass(canvas, name);
        }
        Bitmap mask = name != null ? mask(width, height, screen, name) : mask(width, height, screen);
        canvas.drawBitmap(mask, 0, 0, unlitPaint);
        drawLit(canvas, mask, 0, 0, pitch, emojiLeft, emojiTop);
        if (name != null) {
            // Text stays clear of the round ends; the status dot sits just inside the right one.
            int margin = (int) Math.ceil(name.rows / 2f);
            label.draw(canvas, mask, labelPaint, name.pitch, name.left, name.top, name.columns,
                    margin, name.bounds);
            ledPaint.setColor(NOTHING_RED);
            canvas.drawCircle(name.left + (name.columns - margin + 0.5f) * name.pitch,
                    name.top + name.rows * name.pitch / 2f, name.pitch * 0.6f, ledPaint);
        }
    }

    /** Black glass with a hairline rim: what makes a dot grid read as a screen. */
    private void drawGlass(Canvas canvas, Grid grid) {
        canvas.drawRoundRect(grid.bounds, grid.corner, grid.corner, glassPaint);
        rimPaint.setStrokeWidth(Math.max(1, grid.pitch * 0.12f));
        canvas.drawRoundRect(grid.bounds, grid.corner, grid.corner, rimPaint);
    }

    /** A grid of dots inside a rounded rectangle. */
    static final class Grid {
        final float pitch;
        final int columns;
        final int rows;
        final float left;
        final float top;
        final RectF bounds;
        final float corner;

        Grid(float pitch, int columns, int rows, float left, float top, RectF bounds,
                float corner) {
            this.pitch = pitch;
            this.columns = columns;
            this.rows = rows;
            this.left = left;
            this.top = top;
            this.bounds = bounds;
            this.corner = corner;
        }

        String key() {
            return pitch + ":" + columns + "x" + rows + "@" + left + "," + top + "/"
                    + bounds.toShortString() + "r" + corner;
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
     * A {@code width} x {@code height} alpha mask of round dots on each grid, skipping dots
     * that would leave its rounded rectangle. Shared by every view with the same geometry.
     */
    private static Bitmap mask(int width, int height, Grid... grids) {
        StringBuilder key = new StringBuilder().append(width).append('x').append(height);
        for (Grid g : grids) {
            key.append('|').append(g.key());
        }
        Bitmap mask = MASKS.get(key.toString());
        if (mask != null) {
            return mask;
        }
        mask = Bitmap.createBitmap(Math.max(1, width), Math.max(1, height),
                Bitmap.Config.ALPHA_8);
        Canvas canvas = new Canvas(mask);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (Grid g : grids) {
            float radius = g.pitch * DOT / 2f;
            // Dots keep clear of the rim: they must fit inside the glass shrunk by this.
            float inset = g.pitch * 0.25f;
            RectF glass = new RectF(g.bounds);
            glass.inset(inset, inset);
            float corner = Math.max(0, Math.min(g.corner - inset,
                    Math.min(glass.width(), glass.height()) / 2));
            RectF inner = new RectF(glass.left + corner, glass.top + corner,
                    glass.right - corner, glass.bottom - corner);
            for (int y = 0; y < g.rows; y++) {
                for (int x = 0; x < g.columns; x++) {
                    float cx = g.left + (x + 0.5f) * g.pitch;
                    float cy = g.top + (y + 0.5f) * g.pitch;
                    if (outsideRoundRect(cx, cy, radius, inner, Math.max(corner, radius))) {
                        continue;
                    }
                    canvas.drawCircle(cx, cy, radius, paint);
                }
            }
        }
        MASKS.put(key.toString(), mask);
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
