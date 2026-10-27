package com.dressence.nothinglauncherplus.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.nio.ByteBuffer;

/**
 * A font shown on a dot-matrix screen, like the drawer's folder panels: a sample text set in
 * the font, lit in the clock's glowing green over unlit dots. Text wider than the screen
 * scrolls through it a dot at a time, pausing at the start.
 *
 * <p>The screen is made for the font. A dot font (NDot) gets a screen of its own dots: its dot
 * spacing, its dot size, its number of rows, every dot of a letter on one dot of the screen,
 * so each NDot looks as it is drawn. Any other font gets a fine high-definition screen, its
 * dots small enough for the letters' own shapes to show.
 */
final class FontPreview extends View {

    static final String SAMPLE = "Nothing 12:45";

    /** The screen's height, and the dot rows around a dot font's letters. */
    private static final int HEIGHT_DP = 54;
    private static final int MARGIN_ROWS = 2;
    /** The rows of the high-definition screen for other fonts. */
    private static final int HD_ROWS = 34;
    /** Supersampling of the text when it is turned into dots. */
    private static final int SS = 6;
    /** How much of a dot's cell a letter must cover for it to light: a solid font, a dot font. */
    private static final float COVER_SOLID = 0.45f;
    private static final float COVER_DOTS = 0.25f;
    /** Blank dots between the end of scrolling text and its next start. */
    private static final int LOOP_GAP = 8;
    private static final long STEP_MS = 90;
    private static final long HD_STEP_MS = 30;
    private static final long PAUSE_MS = 1800;

    private static final int GREEN = 0xFF4CFF7A;
    private static final int GLOW = 0xAA2BE86A;
    private static final int UNLIT = 0xFF161A17;
    private static final int GLASS = 0xFF050605;

    private final float height;
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unlit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix gridShift = new Matrix();
    private final RectF panel = new RectF();

    /** The screen made for the current font: its rows, dot spacing and dot radius in px. */
    private int rows = HD_ROWS;
    private float pitch;
    private float radius;
    /** Columns of the text, and the text's lit dots with their glow, drawn once. */
    private int textColumns;
    private Bitmap lit;
    private int litPad;
    private long stepMs = STEP_MS;
    private int offset;
    private boolean scrolling;

    private final Runnable step = new Runnable() {
        @Override
        public void run() {
            offset = (offset + 1) % (textColumns + LOOP_GAP);
            invalidate();
            postDelayed(this, offset == 0 ? PAUSE_MS : stepMs);
        }
    };

    FontPreview(Context context) {
        super(context);
        height = Ui.dp(context, HEIGHT_DP);
        glass.setColor(GLASS);
    }

    /** Shows {@link #SAMPLE} in {@code typeface}, on a screen made for it. */
    void setTypeface(Typeface typeface) {
        DotGrid grid = DotGrid.of(typeface);
        if (grid != null) {
            rows = grid.rows + 2 * MARGIN_ROWS;
            pitch = wholePixels(height / rows);
            radius = pitch * Math.min(0.5f, grid.dotSize / 2);
            stepMs = STEP_MS;
        } else {
            rows = HD_ROWS;
            pitch = wholePixels(height / rows);
            radius = pitch * 0.45f;
            stepMs = HD_STEP_MS;
        }
        boolean[][] dots = dots(typeface, grid, rows);
        textColumns = dots.length;
        lit = drawLit(dots);
        unlit.setShader(unlitGrid());
        offset = 0;
        scrolling = false;
        removeCallbacks(step);
        updateScrolling();
        invalidate();
    }

    /** Dot spacing on whole pixels, so the repeated unlit dots stay on the lit ones' grid. */
    private static float wholePixels(float spacing) {
        return Math.max(2, (int) spacing);
    }

    /**
     * The sample in {@code typeface} as lit dots, column by column: drawn finely, then a dot
     * per cell. A dot font is sized so its dot spacing is the screen's, its dots centred on the
     * screen's, each letter starting on a whole dot.
     */
    private static boolean[][] dots(Typeface typeface, DotGrid grid, int rows) {
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTypeface(typeface);
        text.setColor(Color.WHITE);
        int h = rows * SS;
        float x0;
        float baseline;
        if (grid != null) {
            float scale = SS / grid.pitch;
            text.setTextSize(DotGrid.REF * scale);
            x0 = SS * 1.5f - grid.x * scale;
            baseline = SS * (MARGIN_ROWS + 0.5f) - grid.y * scale;
        } else {
            text.setTextSize(h * 0.66f);
            x0 = SS;
            baseline = h * 0.76f;
        }
        int[] at = new int[SAMPLE.length()];
        int columns = 0;
        for (int i = 0; i < SAMPLE.length(); i++) {
            at[i] = columns;
            float advance = text.measureText(SAMPLE, i, i + 1) / SS;
            columns += grid != null ? Math.max(1, Math.round(advance)) : 0;
        }
        columns = (grid != null ? columns : (int) Math.ceil(text.measureText(SAMPLE) / SS)) + 3;
        Bitmap fine = Bitmap.createBitmap(columns * SS, h, Bitmap.Config.ALPHA_8);
        Canvas canvas = new Canvas(fine);
        if (grid != null) {
            for (int i = 0; i < SAMPLE.length(); i++) {
                canvas.drawText(SAMPLE, i, i + 1, x0 + at[i] * SS, baseline, text);
            }
        } else {
            canvas.drawText(SAMPLE, x0, baseline, text);
        }
        int stride = fine.getRowBytes();
        byte[] alpha = new byte[stride * h];
        fine.copyPixelsToBuffer(ByteBuffer.wrap(alpha));
        fine.recycle();
        float cover = (grid != null ? COVER_DOTS : COVER_SOLID) * 255 * SS * SS;
        boolean[][] out = new boolean[columns][rows];
        for (int x = 0; x < columns; x++) {
            for (int y = 0; y < rows; y++) {
                int sum = 0;
                for (int sy = 0; sy < SS; sy++) {
                    int row = (y * SS + sy) * stride + x * SS;
                    for (int sx = 0; sx < SS; sx++) {
                        sum += alpha[row + sx] & 0xFF;
                    }
                }
                out[x][y] = sum >= cover;
            }
        }
        return out;
    }

    /** The text's lit dots with their soft glow, drawn once into an image. */
    private Bitmap drawLit(boolean[][] dots) {
        float blur = Math.max(pitch * 1.3f, radius * 1.6f);
        litPad = (int) Math.ceil(blur * 2);
        int w = Math.round(dots.length * pitch) + 2 * litPad;
        int h = Math.round(rows * pitch) + 2 * litPad;
        Bitmap shape = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(shape);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int x = 0; x < dots.length; x++) {
            for (int y = 0; y < rows; y++) {
                if (dots[x][y]) {
                    c.drawCircle(litPad + (x + 0.5f) * pitch, litPad + (y + 0.5f) * pitch,
                            radius, p);
                }
            }
        }
        Paint soft = new Paint();
        soft.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
        int[] at = new int[2];
        Bitmap glow = shape.extractAlpha(soft, at);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas o = new Canvas(out);
        Paint tint = new Paint(Paint.FILTER_BITMAP_FLAG);
        tint.setColor(GLOW);
        o.drawBitmap(glow, at[0], at[1], tint);
        tint.setColor(GREEN);
        o.drawBitmap(shape, 0, 0, tint);
        glow.recycle();
        shape.recycle();
        return out;
    }

    /** One unlit dot in its cell, repeated: the whole screen's unlit dots in one draw. */
    private Shader unlitGrid() {
        int cell = (int) pitch;
        Bitmap tile = Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(UNLIT);
        new Canvas(tile).drawCircle(cell / 2f, cell / 2f, radius, p);
        BitmapShader shader = new BitmapShader(tile, Shader.TileMode.REPEAT,
                Shader.TileMode.REPEAT);
        shader.setLocalMatrix(gridShift);
        return shader;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Math.round(height));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        updateScrolling();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateScrolling();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(step);
        scrolling = false;
        super.onDetachedFromWindow();
    }

    private int visibleColumns() {
        return pitch > 0 ? Math.max(1, (int) (getWidth() / pitch)) : 1;
    }

    /** Scrolls only when the text is wider than the screen, and only while shown. */
    private void updateScrolling() {
        boolean wanted = lit != null && isAttachedToWindow() && getWidth() > 0
                && textColumns > visibleColumns();
        if (wanted != scrolling) {
            scrolling = wanted;
            removeCallbacks(step);
            offset = 0;
            if (wanted) {
                postDelayed(step, PAUSE_MS);
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (lit == null) {
            return;
        }
        int cols = visibleColumns();
        float left = (getWidth() - cols * pitch) / 2;
        float right = left + cols * pitch;
        float corner = Math.min(height / 6, pitch * 3);
        panel.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(panel, corner, corner, glass);
        float top = (getHeight() - rows * pitch) / 2;
        canvas.save();
        canvas.clipRect(left, top, right, top + rows * pitch);
        gridShift.setTranslate(left, top);
        unlit.getShader().setLocalMatrix(gridShift);
        canvas.drawRect(left, top, right, top + rows * pitch, unlit);
        // The text's first column: scrolled, or centred on whole dots.
        int start = scrolling ? -offset : (cols - textColumns) / 2;
        float x = left + start * pitch - litPad;
        canvas.drawBitmap(lit, x, top - litPad, bitmapPaint);
        if (scrolling) {
            canvas.drawBitmap(lit, x + (textColumns + LOOP_GAP) * pitch, top - litPad,
                    bitmapPaint);
        }
        canvas.restore();
    }

    /**
     * A dot font's own grid, measured from its "H" at {@link #REF} px: the spacing of its
     * dots, their size (in spacings), where the left stem's first dot is centred (from the
     * origin and baseline) and how many dots tall the letter is. Null for a font that is not
     * made of dots.
     */
    private static final class DotGrid {
        static final int REF = 400;
        /** Dots of a dot font may only just touch: a dip below this between them splits them. */
        private static final int SOLID = 230;

        final float pitch;
        final float dotSize;
        final float x;
        final float y;
        final int rows;

        private DotGrid(float pitch, float dotSize, float x, float y, int rows) {
            this.pitch = pitch;
            this.dotSize = dotSize;
            this.x = x;
            this.y = y;
            this.rows = rows;
        }

        static DotGrid of(Typeface typeface) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTypeface(typeface);
            paint.setTextSize(REF);
            int size = REF * 2;
            float ox = REF / 20f;
            float oy = REF * 1.5f;
            Bitmap glyph = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8);
            new Canvas(glyph).drawText("H", ox, oy, paint);
            int stride = glyph.getRowBytes();
            byte[] alpha = new byte[stride * size];
            glyph.copyPixelsToBuffer(ByteBuffer.wrap(alpha));
            glyph.recycle();
            int left = -1;
            int right = -1;
            for (int c = 0; c < size; c++) {
                if (runs(alpha, stride, size, c, null) > 0) {
                    left = left < 0 ? c : left;
                    right = c;
                }
            }
            if (left < 0) {
                return null;
            }
            // The left stem: the columns showing three dots or more. Its centre columns may
            // show fewer (touching dots merge there), so its middle is taken from its edges.
            int stemEnd = left + (right - left) / 3;
            int first = -1;
            int last = -1;
            int most = 0;
            int mostAt = -1;
            for (int c = left; c <= stemEnd; c++) {
                int n = runs(alpha, stride, size, c, null);
                if (n >= 3) {
                    first = first < 0 ? c : first;
                    last = c;
                    if (n > most) {
                        most = n;
                        mostAt = c;
                    }
                }
            }
            if (most < 3) {
                return null; // Solid strokes: not a dot font.
            }
            int[] span = new int[4];
            runs(alpha, stride, size, mostAt, span);
            float pitch = (span[2] - span[0]) / (float) (most - 1);
            float dotSize = (last - first + 1) / pitch;
            float centreY = (span[0] + span[1]) / 2f;
            return new DotGrid(pitch, dotSize, (first + last) / 2f - ox, centreY - oy, most);
        }

        /**
         * The number of separate dots down column {@code c}. If {@code span} is given it gets
         * the first dot's start and end rows and the last dot's start and end rows.
         */
        private static int runs(byte[] alpha, int stride, int height, int c, int[] span) {
            int count = 0;
            boolean in = false;
            for (int y = 0; y < height; y++) {
                boolean solid = (alpha[y * stride + c] & 0xFF) >= SOLID;
                if (solid && !in) {
                    count++;
                    if (span != null) {
                        if (count == 1) {
                            span[0] = y;
                        }
                        span[2] = y;
                    }
                } else if (!solid && in && span != null) {
                    if (count == 1) {
                        span[1] = y - 1;
                    }
                    span[3] = y - 1;
                }
                in = solid;
            }
            return count;
        }
    }
}
