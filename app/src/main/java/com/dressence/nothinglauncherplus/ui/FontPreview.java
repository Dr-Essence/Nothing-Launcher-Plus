package com.dressence.nothinglauncherplus.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.nio.ByteBuffer;

/**
 * A font shown on a small dot-matrix screen, like the drawer's folder panels: a sample text
 * set in the font, lit in the clock's glowing green on a panel of unlit dots. Text wider than
 * the screen scrolls through it a dot at a time, pausing at the start.
 */
final class FontPreview extends View {

    static final String SAMPLE = "Nothing 12:45";

    /** Dots across the screen's height, and the dot spacing. */
    private static final int ROWS = 15;
    private static final int PITCH_DP = 3;
    /** Supersampling of the text when it is turned into dots. */
    private static final int SS = 6;
    /** How much of a dot's cell the text must cover for it to light. */
    private static final float COVER = 0.42f;
    /** Blank dots between the end of scrolling text and its next start. */
    private static final int LOOP_GAP = 8;
    private static final long STEP_MS = 90;
    private static final long PAUSE_MS = 1800;

    private static final int GREEN = 0xFF4CFF7A;
    private static final int GLOW = 0xAA2BE86A;
    private static final int UNLIT = 0xFF161A17;
    private static final int GLASS = 0xFF050605;

    private final float pitch;
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF panel = new RectF();
    /** The lit dots of the text, column by column, and the blurred glow of them. */
    private boolean[][] lit = new boolean[0][ROWS];
    private Bitmap glow;
    private final int[] glowOffset = new int[2];
    private int offset;
    private boolean scrolling;

    private final Runnable step = new Runnable() {
        @Override
        public void run() {
            offset = (offset + 1) % (lit.length + LOOP_GAP);
            invalidate();
            postDelayed(this, offset == 0 ? PAUSE_MS : STEP_MS);
        }
    };

    FontPreview(Context context) {
        super(context);
        pitch = Ui.dp(context, PITCH_DP);
        glass.setColor(GLASS);
        glowPaint.setColor(GLOW);
    }

    /** Shows {@link #SAMPLE} in {@code typeface}. */
    void setTypeface(Typeface typeface) {
        lit = dots(typeface);
        glow = glowOf(lit);
        offset = 0;
        requestLayout();
        invalidate();
    }

    /**
     * The sample in {@code typeface} as lit dots: drawn finely, then a dot per cell. A dot
     * font (NDot) is drawn so each of its own dots lands on one dot of the screen: sized to
     * the screen's dot spacing, its dots centred on the screen's, each character on whole dots.
     */
    private static boolean[][] dots(Typeface typeface) {
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTypeface(typeface);
        text.setColor(Color.WHITE);
        int h = ROWS * SS;
        DotGrid grid = DotGrid.of(typeface);
        float x0;
        float baseline;
        float cover;
        if (grid != null) {
            float scale = SS / grid.pitch;
            text.setTextSize(DotGrid.REF * scale);
            x0 = SS * 1.5f - grid.x * scale;
            baseline = SS * ((ROWS - grid.rows) / 2 + 0.5f) - grid.y * scale;
            cover = 0.25f; // A round dot fills about half its cell.
        } else {
            text.setTextSize(h * 0.72f);
            x0 = SS;
            baseline = h * 0.80f;
            cover = COVER;
        }
        // Where each character starts, in dots: on whole dots for a dot font.
        int[] at = new int[SAMPLE.length()];
        int col = 0;
        for (int i = 0; i < SAMPLE.length(); i++) {
            at[i] = col;
            float advance = text.measureText(SAMPLE, i, i + 1) / SS;
            col += grid != null ? Math.max(1, Math.round(advance)) : 0;
        }
        int cols = (grid != null ? col : (int) Math.ceil(text.measureText(SAMPLE) / SS)) + 3;
        Bitmap fine = Bitmap.createBitmap(cols * SS, h, Bitmap.Config.ALPHA_8);
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
        boolean[][] out = new boolean[cols][ROWS];
        for (int x = 0; x < cols; x++) {
            for (int y = 0; y < ROWS; y++) {
                int sum = 0;
                for (int sy = 0; sy < SS; sy++) {
                    int row = (y * SS + sy) * stride + x * SS;
                    for (int sx = 0; sx < SS; sx++) {
                        sum += alpha[row + sx] & 0xFF;
                    }
                }
                out[x][y] = sum >= cover * 255 * SS * SS;
            }
        }
        return out;
    }

    /**
     * A dot font's own grid, measured from its "H": the spacing of its dots, where the first
     * dot of the left stem is centred (from the origin and baseline), and how many dots tall
     * the letter is; all at {@link #REF} px. Null for a font that is not made of dots.
     */
    private static final class DotGrid {
        static final int REF = 400;
        /** Dots of a dot font only just touch: a dip below this between them splits them. */
        private static final int SOLID = 230;

        final float pitch;
        final float x;
        final float y;
        final int rows;

        private DotGrid(float pitch, float x, float y, int rows) {
            this.pitch = pitch;
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
                    if (left < 0) {
                        left = c;
                    }
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
            float centreY = (span[0] + span[1]) / 2f;
            return new DotGrid(pitch, (first + last) / 2f - ox, centreY - oy, most);
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

    /** The soft glow round the lit dots, as an alpha mask the size of the text's dots. */
    private Bitmap glowOf(boolean[][] dots) {
        int w = Math.max(1, Math.round(dots.length * pitch));
        int h = Math.round(ROWS * pitch);
        Bitmap shape = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(shape);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = pitch * 0.42f;
        for (int x = 0; x < dots.length; x++) {
            for (int y = 0; y < ROWS; y++) {
                if (dots[x][y]) {
                    c.drawCircle((x + 0.5f) * pitch, (y + 0.5f) * pitch, r, p);
                }
            }
        }
        Paint blur = new Paint();
        blur.setMaskFilter(new BlurMaskFilter(pitch * 1.3f, BlurMaskFilter.Blur.NORMAL));
        Bitmap soft = shape.extractAlpha(blur, glowOffset);
        shape.recycle();
        return soft;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int height = Math.round((ROWS + 2) * pitch);
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), height);
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
        return Math.max(1, (int) ((getWidth() - pitch) / pitch));
    }

    /** Scrolls only when the text is wider than the screen, and only while shown. */
    private void updateScrolling() {
        boolean wanted = isAttachedToWindow() && getWidth() > 0
                && lit.length > visibleColumns();
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
        int cols = visibleColumns();
        float left = (getWidth() - cols * pitch) / 2;
        float top = pitch;
        float r = pitch * 0.42f;
        panel.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(panel, pitch * 2, pitch * 2, glass);
        // Where the text's first column is: scrolled, or centred on whole dots.
        int start = scrolling ? -offset : (cols - lit.length) / 2;
        int loop = lit.length + LOOP_GAP;
        canvas.save();
        canvas.clipRect(left, 0, left + cols * pitch, getHeight());
        if (glow != null) {
            float gx = left + start * pitch + glowOffset[0];
            float gy = top + glowOffset[1];
            canvas.drawBitmap(glow, gx, gy, glowPaint);
            if (scrolling) {
                canvas.drawBitmap(glow, gx + loop * pitch, gy, glowPaint);
            }
        }
        for (int c = 0; c < cols; c++) {
            int x = c - start;
            if (scrolling) {
                x = ((x % loop) + loop) % loop;
            }
            boolean[] column = x >= 0 && x < lit.length ? lit[x] : null;
            for (int y = 0; y < ROWS; y++) {
                dot.setColor(column != null && column[y] ? GREEN : UNLIT);
                canvas.drawCircle(left + (c + 0.5f) * pitch, top + (y + 0.5f) * pitch, r, dot);
            }
        }
        canvas.restore();
    }
}
