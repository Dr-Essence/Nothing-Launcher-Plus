package com.dressence.nothinglauncherplus.anim;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextPaint;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;

/**
 * A folder name lit on the dot panel, made of the screen's own dots.
 *
 * <p>With Nothing's NDot 57 typeface (found on the device) the letters are NDot's own 5 x 7 dot
 * shapes at the launcher's name size. That is smaller than the panel's dots allow, so the
 * panel switches to finer dots (a whole fraction of its pitch) in a strip under the name, and
 * every NDot dot lands on one of them: NDot 57 puts its dots on an exact grid, one every tenth of
 * the font size. Without the typeface the name is lit on the panel's own dots in the compact
 * {@link DotFont}.
 *
 * <p>Anything the font lacks (emoji, accents) is rasterized from the system font onto the same
 * dots, in its own colours. A name wider than the panel scrolls through it one dot at a time,
 * with a pause at the start.
 */
final class DotLabel {

    /** NDot 57: dot spacing, and first dot centre from the origin and baseline, per font px. */
    private static final float NDOT_PITCH = 0.1f;
    private static final float NDOT_LEFT = 65f / 700;
    private static final float NDOT_BOTTOM = 40f / 700;
    private static final int NDOT_ROWS = 7;
    /** Pixels per dot when tracing NDot glyphs. */
    private static final int TRACE = 4;
    /** Blank dots between the end of scrolling text and its next start. */
    private static final int LOOP_GAP = 6;
    private static final long STEP_NANOS = 90_000_000L;
    private static final long PAUSE_NANOS = 1_800_000_000L;
    /** Supersampling for characters the font lacks. */
    private static final int FALLBACK_SS = 4;

    private final Matrix matrix = new Matrix();
    private final Rect area = new Rect();
    /** The lit dots, one pixel each, inside a transparent one-dot border. */
    private Bitmap bitmap;
    private int width;
    private int rows;
    /** The name's font size in px for NDot, 0 when lit on the panel's own dots. */
    private float size;
    private int offset;
    private long start;
    /** Dots the panel had room for when last drawn (for deciding to scroll). */
    private int window = Integer.MAX_VALUE;

    // Layout on the panel, from the last draw.
    private int fine = 1;
    private int bandTop;
    private int bandRows;

    /**
     * Shows {@code value} centred on {@code where}: in {@code typeface} (NDot) sized
     * {@code size} px if there is one, else in the compact dot font.
     */
    void set(CharSequence value, int color, Rect where, Typeface typeface, float size) {
        bitmap = null;
        width = 0;
        offset = 0;
        start = System.nanoTime();
        this.size = typeface != null && size > 0 ? size : 0;
        if (value == null || value.length() == 0 || where == null || where.isEmpty()) {
            return;
        }
        area.set(where);
        rows = this.size > 0 ? NDOT_ROWS : DotFont.HEIGHT;
        TextPaint ndot = null;
        if (this.size > 0) {
            ndot = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            ndot.setTypeface(typeface);
            ndot.setTextSize(TRACE / NDOT_PITCH);
            ndot.setColor(color);
        }
        List<int[]> columns = new ArrayList<>();
        BreakIterator graphemes = BreakIterator.getCharacterInstance();
        String s = value.toString().trim();
        graphemes.setText(s);
        for (int from = graphemes.first(), to = graphemes.next(); to != BreakIterator.DONE;
                from = to, to = graphemes.next()) {
            String g = s.substring(from, to);
            boolean plain = g.length() == 1 && g.charAt(0) < 0x2000;
            if (ndot != null && plain) {
                columns.addAll(trace(g, ndot));
                continue;
            }
            boolean[][] glyph = ndot == null && g.length() == 1 ? DotFont.glyph(g.charAt(0)) : null;
            if (!columns.isEmpty() && (ndot == null || !plain)) {
                columns.add(new int[rows]); // Letter spacing (NDot's advance includes it).
            }
            if (glyph != null) {
                for (int x = 0; x < glyph[0].length; x++) {
                    int[] col = new int[rows];
                    for (int y = 0; y < rows; y++) {
                        col[y] = glyph[y][x] ? color : 0;
                    }
                    columns.add(col);
                }
            } else {
                columns.addAll(raster(g, color, rows));
                if (ndot != null) {
                    columns.add(new int[rows]);
                }
            }
        }
        while (!columns.isEmpty() && blank(columns.get(columns.size() - 1))) {
            columns.remove(columns.size() - 1);
        }
        width = columns.size();
        if (width == 0) {
            return;
        }
        // A transparent border, so the clamped shader leaves everything around the text dark.
        bitmap = Bitmap.createBitmap(width + 2, rows + 2, Bitmap.Config.ARGB_8888);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < rows; y++) {
                bitmap.setPixel(x + 1, y + 1, columns.get(x)[y]);
            }
        }
    }

    /**
     * One NDot character, traced dot by dot: drawn so its dots fall on the centres of
     * {@link #TRACE}-pixel cells, then each cell is on if its centre is.
     */
    private static List<int[]> trace(String c, TextPaint paint) {
        float fontSize = paint.getTextSize();
        int cols = Math.max(1, Math.round(paint.measureText(c) / TRACE));
        Bitmap b = Bitmap.createBitmap(cols * TRACE, NDOT_ROWS * TRACE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        float half = TRACE / 2f;
        canvas.drawText(c, half - NDOT_LEFT * fontSize,
                (NDOT_ROWS - 1) * TRACE + half + NDOT_BOTTOM * fontSize, paint);
        List<int[]> out = new ArrayList<>();
        for (int x = 0; x < cols; x++) {
            int[] col = new int[NDOT_ROWS];
            for (int y = 0; y < NDOT_ROWS; y++) {
                int p = b.getPixel(x * TRACE + TRACE / 2, y * TRACE + TRACE / 2);
                if ((p >>> 24) > 100) {
                    col[y] = p | 0xFF000000;
                }
            }
            out.add(col);
        }
        b.recycle();
        return out;
    }

    /** Draws one grapheme with the system font and samples it onto {@code rows} of dots. */
    private static List<int[]> raster(String g, int color, int rows) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        int h = rows * FALLBACK_SS;
        paint.setTextSize(h * 0.95f);
        paint.setColor(color);
        Paint.FontMetrics fm = paint.getFontMetrics();
        // The whole line height fits the rows, so emoji and accents are never cut off.
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
            int[] col = new int[rows];
            for (int cy = 0; cy < rows; cy++) {
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
        b.recycle();
        // Trim blank columns either side.
        while (!out.isEmpty() && blank(out.get(0))) {
            out.remove(0);
        }
        while (!out.isEmpty() && blank(out.get(out.size() - 1))) {
            out.remove(out.size() - 1);
        }
        if (out.isEmpty()) {
            out.add(new int[rows]); // A space.
            out.add(new int[rows]);
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

    // -------------------------------------------------------------------------------------
    // Layout and drawing
    // -------------------------------------------------------------------------------------

    /**
     * Works out where the label sits on a panel of {@code pitch}-sized dots starting at
     * {@code top}: how many finer dots fit in one panel dot (1 when the label uses the panel's
     * own dots) and which panel rows it takes. Returns {first row, row count, fineness} when the
     * panel should switch to finer dots in those rows, else null.
     */
    int[] layout(float pitch, float top) {
        if (bitmap == null) {
            return null;
        }
        fine = size > 0 ? Math.max(1, Math.round(pitch / (size * NDOT_PITCH))) : 1;
        // The text plus a fine dot of margin above and below, in whole panel rows.
        bandRows = (rows + 2 + fine - 1) / fine;
        bandTop = Math.round((area.exactCenterY() - top) / pitch - bandRows / 2f);
        return fine > 1 ? new int[] {bandTop, bandRows, fine} : null;
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
     * {@code top}) at {@code pitch}; finer dots in its strip when {@link #layout} asked for
     * them), centred on its area, inside a one-dot margin.
     */
    void draw(Canvas canvas, Bitmap mask, Paint paint, float pitch, int columns, float left,
            float top) {
        if (bitmap == null) {
            return;
        }
        float dot = pitch / fine;
        window = (columns - 2) * fine;
        int row = bandTop * fine + (bandRows * fine - rows) / 2;
        if (paint.getShader() == null || ((Holder) paint.getShader()).bitmap != bitmap) {
            paint.setShader(new Holder(bitmap));
        }
        canvas.save();
        canvas.clipRect(left + pitch, top + row * dot, left + (columns - 1) * pitch,
                top + (row + rows) * dot);
        if (!scrolls()) {
            int col = Math.round((area.exactCenterX() - left) / dot - width / 2f);
            col = Math.max(fine, Math.min(columns * fine - fine - width, col));
            place(paint, dot, left + col * dot, top + row * dot);
            canvas.drawBitmap(mask, 0, 0, paint);
        } else {
            for (int copy = 0; copy < 2; copy++) {
                int col = fine - offset + copy * (width + LOOP_GAP);
                place(paint, dot, left + col * dot, top + row * dot);
                canvas.drawBitmap(mask, 0, 0, paint);
            }
        }
        canvas.restore();
    }

    /** Bitmap pixel (1, 1), the first label dot, onto the panel dot at (x, y). */
    private void place(Paint paint, float dot, float x, float y) {
        matrix.setScale(dot, dot);
        matrix.postTranslate(x - dot, y - dot);
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
