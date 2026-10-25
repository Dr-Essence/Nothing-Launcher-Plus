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

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;

/**
 * A line of text lit on a dot panel's own dots, like the lettering on a Nothing Glyph or an LED
 * sign: 5 dots tall in a {@link DotFont}, anything the font lacks (accents, emoji) rasterized
 * from the system font onto the same dots, emoji in their own colours. Text wider than the
 * panel scrolls through it one whole dot at a time, with a pause at the start.
 */
final class DotLabel {

    private static final int ROWS = DotFont.HEIGHT;
    private static final int GAP = 1;
    /** Blank dots between the end of scrolling text and its next start. */
    private static final int LOOP_GAP = 6;
    private static final long STEP_NANOS = 110_000_000L;
    private static final long PAUSE_NANOS = 1_800_000_000L;
    /** Supersampling for characters the font lacks. */
    private static final int FALLBACK_SS = 4;

    private final Matrix matrix = new Matrix();
    private final Rect area = new Rect();
    private Bitmap bitmap;
    private int width;
    private int offset;
    private long start;
    /** Columns the panel had available when last drawn (for deciding to scroll). */
    private int window = Integer.MAX_VALUE;

    void set(CharSequence text, int color, Rect where) {
        bitmap = null;
        width = 0;
        offset = 0;
        start = System.nanoTime();
        if (text == null || text.length() == 0 || where == null || where.isEmpty()) {
            return;
        }
        area.set(where);
        List<int[]> columns = new ArrayList<>();
        BreakIterator graphemes = BreakIterator.getCharacterInstance();
        String s = text.toString().trim();
        graphemes.setText(s);
        for (int from = graphemes.first(), to = graphemes.next(); to != BreakIterator.DONE;
                from = to, to = graphemes.next()) {
            String g = s.substring(from, to);
            boolean[][] glyph = g.length() == 1 ? DotFont.glyph(g.charAt(0)) : null;
            if (!columns.isEmpty()) {
                for (int k = 0; k < GAP; k++) {
                    columns.add(new int[ROWS]);
                }
            }
            if (glyph != null) {
                for (int x = 0; x < glyph[0].length; x++) {
                    int[] col = new int[ROWS];
                    for (int y = 0; y < ROWS; y++) {
                        col[y] = glyph[y][x] ? color : 0;
                    }
                    columns.add(col);
                }
            } else {
                columns.addAll(raster(g, color));
            }
        }
        width = columns.size();
        if (width == 0) {
            return;
        }
        // A transparent border, so the clamped shader leaves everything around the text dark.
        bitmap = Bitmap.createBitmap(width + 2, ROWS + 2, Bitmap.Config.ARGB_8888);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < ROWS; y++) {
                bitmap.setPixel(x + 1, y + 1, columns.get(x)[y]);
            }
        }
    }

    /** Draws one grapheme with the system font and samples it onto 7 rows of dots. */
    private static List<int[]> raster(String g, int color) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        int h = ROWS * FALLBACK_SS;
        paint.setTextSize(h * 0.95f);
        paint.setColor(color);
        Paint.FontMetrics fm = paint.getFontMetrics();
        // The whole line height fits the 7 rows, so emoji and accents are never cut off.
        float scale = h / (fm.descent - fm.ascent);
        int w = Math.max(FALLBACK_SS, (int) Math.ceil(paint.measureText(g) * scale));
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(scale, scale, 0, 0);
        c.drawText(g, 0, -fm.ascent, paint);
        int cols = (w + FALLBACK_SS - 1) / FALLBACK_SS;
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        List<int[]> out = new ArrayList<>();
        int n = FALLBACK_SS * FALLBACK_SS;
        for (int cx = 0; cx < cols; cx++) {
            int[] col = new int[ROWS];
            for (int cy = 0; cy < ROWS; cy++) {
                int a = 0, r = 0, gg = 0, bb = 0;
                for (int sy = 0; sy < FALLBACK_SS; sy++) {
                    for (int sx = 0; sx < FALLBACK_SS; sx++) {
                        int x = cx * FALLBACK_SS + sx;
                        int y = cy * FALLBACK_SS + sy;
                        if (x >= w) {
                            continue;
                        }
                        int p = px[y * w + x];
                        int pa = p >>> 24;
                        a += pa;
                        r += ((p >> 16) & 0xFF) * pa;
                        gg += ((p >> 8) & 0xFF) * pa;
                        bb += (p & 0xFF) * pa;
                    }
                }
                if (a > 255 * n * 0.3f) {
                    col[cy] = Color.argb(255, r / a, gg / a, bb / a);
                }
            }
            out.add(col);
        }
        // Trim blank columns either side.
        while (!out.isEmpty() && blank(out.get(0))) {
            out.remove(0);
        }
        while (!out.isEmpty() && blank(out.get(out.size() - 1))) {
            out.remove(out.size() - 1);
        }
        if (out.isEmpty()) {
            out.add(new int[ROWS]); // A space.
            out.add(new int[ROWS]);
        }
        return out;
    }

    private static boolean blank(int[] col) {
        for (int v : col) {
            if (v != 0) {
                return false;
            }
        }
        return true;
    }

    private boolean scrolls() {
        return bitmap != null && width > window;
    }

    boolean scroll(long now) {
        if (!scrolls()) {
            return false;
        }
        long period = PAUSE_NANOS + (width + LOOP_GAP) * STEP_NANOS;
        long t = (now - start) % period;
        int next = t < PAUSE_NANOS ? 0 : (int) ((t - PAUSE_NANOS) / STEP_NANOS);
        if (next == offset) {
            return false;
        }
        offset = next;
        return true;
    }

    long untilScroll(long now) {
        if (!scrolls()) {
            return Long.MAX_VALUE;
        }
        long period = PAUSE_NANOS + (width + LOOP_GAP) * STEP_NANOS;
        long t = (now - start) % period;
        return t < PAUSE_NANOS ? PAUSE_NANOS - t : STEP_NANOS - (t - PAUSE_NANOS) % STEP_NANOS;
    }

    /**
     * Lights the label on the panel's dots ({@code columns} wide from ({@code left},
     * {@code top}) at {@code pitch}), centred on its area, inside a one-dot margin.
     */
    void draw(Canvas canvas, Bitmap mask, Paint paint, float pitch, int columns, float left,
            float top) {
        if (bitmap == null) {
            return;
        }
        window = columns - 2;
        int row = Math.round((area.exactCenterY() - top) / pitch - ROWS / 2f);
        if (paint.getShader() == null || ((Holder) paint.getShader()).bitmap != bitmap) {
            paint.setShader(new Holder(bitmap));
        }
        canvas.save();
        canvas.clipRect(left + pitch, top + row * pitch, left + (columns - 1) * pitch,
                top + (row + ROWS) * pitch);
        if (!scrolls()) {
            int col = Math.round((area.exactCenterX() - left) / pitch - width / 2f);
            col = Math.max(1, Math.min(columns - 1 - width, col));
            place(paint, pitch, left + col * pitch, top + row * pitch);
            canvas.drawBitmap(mask, 0, 0, paint);
        } else {
            for (int copy = 0; copy < 2; copy++) {
                int col = 1 - offset + copy * (width + LOOP_GAP);
                place(paint, pitch, left + col * pitch, top + row * pitch);
                canvas.drawBitmap(mask, 0, 0, paint);
            }
        }
        canvas.restore();
    }

    /** Bitmap pixel (1, 1), the first label dot, onto the panel dot at (x, y). */
    private void place(Paint paint, float pitch, float x, float y) {
        matrix.setScale(pitch, pitch);
        matrix.postTranslate(x - pitch, y - pitch);
        paint.getShader().setLocalMatrix(matrix);
    }

    /** A nearest-neighbour shader that remembers its bitmap. */
    private static final class Holder extends BitmapShader {
        final Bitmap bitmap;

        Holder(Bitmap bitmap) {
            super(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            this.bitmap = bitmap;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setFilterMode(BitmapShader.FILTER_MODE_NEAREST);
            }
        }
    }
}
