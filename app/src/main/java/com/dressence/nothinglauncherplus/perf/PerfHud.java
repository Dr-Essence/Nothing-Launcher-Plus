package com.dressence.nothinglauncherplus.perf;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;

import java.util.Locale;

/**
 * The performance overlay's panel, drawn by hand: MangoHud's look (coloured row names, a frame
 * time graph under the FPS, here on a Nothing dot screen) with Scene's contents and size (CPU
 * and GPU load rings, each cluster's clock with every core's load and clock, RAM and swap,
 * DDR, temperatures), in the Ndot font.
 */
final class PerfHud extends View {

    /** MangoHud's default colours for its rows. */
    private static final int FPS = 0xFFFFFFFF;
    private static final int GPU = 0xFF2E9762;
    private static final int CPU = 0xFF2E97CB;
    private static final int RAM = 0xFFC26693;
    private static final int TEMP = 0xFFEB5B5B;
    private static final int GRAPH = 0xFF00FF00;
    private static final int DIM = 0xFF9E9E9E;
    private static final int TRACK = 0x55FFFFFF;

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float unit;
    private final float line;
    private Sample sample;

    PerfHud(Context context, Typeface font) {
        super(context);
        unit = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1,
                context.getResources().getDisplayMetrics());
        text.setTypeface(font);
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9,
                context.getResources().getDisplayMetrics()));
        line = text.getFontSpacing();
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3 * unit);
        ring.setStrokeCap(Paint.Cap.ROUND);
    }

    void show(Sample next) {
        sample = next;
        requestLayout();
        invalidate();
    }

    /** Rows of the table, for the panel's height. */
    /** FPS, GPU, CPU, two cluster headers with four cores each, RAM, DDR, SoC, BAT, PWR. */
    private static final int ROWS = 19;

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        float pad = 6 * unit;
        float rings = 40 * unit;
        float table = text.measureText("RAM 100%  swap 100% ");
        float height = Math.max(2 * rings + 4 * line, ROWS * line + 18 * unit);
        setMeasuredDimension(Math.round(pad * 3 + rings + table), Math.round(pad * 2 + height));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float pad = 6 * unit;
        fill.setColor(0xB3000000);
        box.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(box, 8 * unit, 8 * unit, fill);
        Sample s = sample;
        if (s == null) {
            text.setColor(DIM);
            canvas.drawText("Reading…", pad, pad + line, text);
            return;
        }
        float size = 40 * unit;
        float ringY = pad + size / 2 + 2 * unit;
        drawRing(canvas, pad + size / 2, ringY, size, s.cpuLoad(), CPU, "CPU");
        text.setColor(DIM);
        centred(canvas, mhz(s.maxCpuKhz()), pad + size / 2, ringY + size / 2 + line);
        float gpuY = ringY + size + 2 * line;
        drawRing(canvas, pad + size / 2, gpuY, size, s.gpuLoad, GPU, "GPU");
        text.setColor(DIM);
        centred(canvas, s.gpuKhz > 0 ? mhz(s.gpuKhz) : "—", pad + size / 2,
                gpuY + size / 2 + line);

        float x = pad * 2 + size;
        float y = pad + line;
        row(canvas, x, y, "FPS", FPS, s.fps < 0 ? "—" : String.format(Locale.ROOT,
                "%.0f  %.1fms", s.fps, s.frameMs));
        y += 2;
        drawGraph(canvas, x, y + 2 * unit, s.frameTimes);
        y += 18 * unit + line;
        row(canvas, x, y, "GPU", GPU, percent(s.gpuLoad) + "  " + degrees(s.gpuTemp)
                + (s.gpuIdle ? " idle" : ""));
        y += line;
        row(canvas, x, y, "CPU", CPU, percent(s.cpuLoad()) + "  " + degrees(s.cpuTemp));
        y += line;
        y = cluster(canvas, x, y, "0-3", s, 0);
        y = cluster(canvas, x, y, "4-7", s, 4);
        row(canvas, x, y, "RAM", RAM, percent(s.ram) + "  swap " + percent(s.swap));
        y += line;
        row(canvas, x, y, "DDR", RAM, s.ddrMhz > 0 ? s.ddrMhz + "MHz" : "—");
        y += line;
        row(canvas, x, y, "SoC", TEMP, degrees(s.socTemp));
        y += line;
        row(canvas, x, y, "BAT", TEMP, degrees(s.batteryTemp));
        y += line;
        row(canvas, x, y, "PWR", TEMP, s.watts < 0 ? "—"
                : String.format(Locale.ROOT, "%.2fW", s.watts));
    }

    private void drawRing(Canvas canvas, float cx, float cy, float size, float load, int color,
            String name) {
        float r = size / 2 - ring.getStrokeWidth();
        box.set(cx - r, cy - r, cx + r, cy + r);
        ring.setColor(TRACK);
        canvas.drawArc(box, 0, 360, false, ring);
        ring.setColor(color);
        canvas.drawArc(box, -90, 360 * Math.max(0, Math.min(1, load)), false, ring);
        text.setColor(FPS);
        centred(canvas, name, cx, cy + text.getTextSize() / 3);
    }

    /**
     * As Scene lays a cluster out: a header with the cluster's set clock, then each core's
     * load and real clock (from its CPU cycles; its cluster's if not measured). Returns the
     * line after it.
     */
    private float cluster(Canvas canvas, float x, float y, String name, Sample s, int first) {
        long khz = s.clusterKhz[first / 4];
        for (int i = first; i < first + 4 && khz == 0; i++) {
            khz = Math.max(khz, s.coreKhz[i]);
        }
        row(canvas, x, y, name, CPU, khz > 0 ? mhz(khz) : "off");
        for (int i = first; i < first + 4; i++) {
            y += line;
            row(canvas, x, y, "  " + i, DIM, s.coreKhz[i] == 0 ? "off"
                    : String.format(Locale.ROOT, "%3d%%  %dM",
                            Math.round(s.coreLoad[i] * 100),
                            (s.coreRealKhz[i] > 0 ? s.coreRealKhz[i] : s.coreKhz[i]) / 1000));
        }
        return y + line;
    }

    private void row(Canvas canvas, float x, float y, String name, int color, String value) {
        text.setColor(color);
        canvas.drawText(name, x, y, text);
        text.setColor(FPS);
        canvas.drawText(value, x + text.measureText("FPS "), y, text);
    }

    /** Columns and rows of the frame time dot screen. */
    static final int GRAPH_COLUMNS = 40;
    private static final int GRAPH_ROWS = 8;

    /**
     * The last frames' times on a Nothing dot screen: a column of dots per frame, newest on
     * the right, lit from the bottom up to its time (a full column is 50 ms or more), the
     * rest of the dots faintly lit, as on Nothing's own dot-matrix displays.
     */
    private void drawGraph(Canvas canvas, float x, float y, float[] times) {
        float width = getWidth() - x - 6 * unit;
        float height = 16 * unit;
        float pitch = Math.min(width / GRAPH_COLUMNS, height / GRAPH_ROWS);
        float radius = pitch * 0.38f;
        for (int c = 0; c < GRAPH_COLUMNS; c++) {
            // Right-aligned: the newest frame is the last column; earlier columns stay unlit.
            int frame = times.length - GRAPH_COLUMNS + c;
            float ms = frame >= 0 ? times[frame] : -1;
            // A frame that drew lights at least its bottom dot.
            int lit = ms < 0 ? 0 : Math.max(1, Math.round(GRAPH_ROWS * Math.min(1, ms / 50f)));
            for (int r = 0; r < GRAPH_ROWS; r++) {
                fill.setColor(r >= lit ? 0x26FFFFFF : ms > 34 ? TEMP : GRAPH);
                canvas.drawCircle(x + pitch * (c + 0.5f), y + height - pitch * (r + 0.5f),
                        radius, fill);
            }
        }
    }

    private void centred(Canvas canvas, String value, float cx, float y) {
        canvas.drawText(value, cx - text.measureText(value) / 2, y, text);
    }

    private static String mhz(long khz) {
        return khz / 1000 + "MHz";
    }

    private static String percent(float fraction) {
        return fraction < 0 ? "—" : Math.round(fraction * 100) + "%";
    }

    private static String degrees(float celsius) {
        return Float.isNaN(celsius) ? "—" : Math.round(celsius) + "°";
    }

    /** One second's readings. */
    static final class Sample {
        final long[] coreKhz = new long[8];
        /** The clusters' set clocks (cores 0-3, 4-7); each core's own is its measured one. */
        final long[] clusterKhz = new long[2];
        /** Each core's real clock from its CPU cycles (0 when not measured). */
        final long[] coreRealKhz = new long[8];
        final float[] coreLoad = new float[8];
        long gpuKhz;
        float gpuLoad = -1;
        float cpuTemp = Float.NaN;
        float gpuTemp = Float.NaN;
        /** The GPU is powered down: {@link #gpuTemp} is its last reading. */
        boolean gpuIdle;
        float socTemp = Float.NaN;
        float batteryTemp = Float.NaN;
        long ddrMhz;
        float ram = -1;
        float swap = -1;
        float watts = -1;
        float fps = -1;
        float frameMs;
        float[] frameTimes = new float[0];

        /** For the trip from the module app (which reads it) to System UI (which shows it). */
        android.os.Bundle toBundle() {
            android.os.Bundle b = new android.os.Bundle();
            b.putLongArray("coreKhz", coreKhz);
            b.putLongArray("clusterKhz", clusterKhz);
            b.putLongArray("coreRealKhz", coreRealKhz);
            b.putFloatArray("coreLoad", coreLoad);
            b.putLong("gpuKhz", gpuKhz);
            b.putFloat("gpuLoad", gpuLoad);
            b.putFloat("cpuTemp", cpuTemp);
            b.putFloat("gpuTemp", gpuTemp);
            b.putBoolean("gpuIdle", gpuIdle);
            b.putFloat("socTemp", socTemp);
            b.putFloat("batteryTemp", batteryTemp);
            b.putLong("ddrMhz", ddrMhz);
            b.putFloat("ram", ram);
            b.putFloat("swap", swap);
            b.putFloat("watts", watts);
            b.putFloat("fps", fps);
            b.putFloat("frameMs", frameMs);
            b.putFloatArray("frameTimes", frameTimes);
            return b;
        }

        static Sample fromBundle(android.os.Bundle b) {
            Sample s = new Sample();
            copy(b.getLongArray("coreKhz"), s.coreKhz);
            copy(b.getLongArray("clusterKhz"), s.clusterKhz);
            copy(b.getLongArray("coreRealKhz"), s.coreRealKhz);
            float[] loads = b.getFloatArray("coreLoad");
            if (loads != null) {
                System.arraycopy(loads, 0, s.coreLoad, 0, Math.min(loads.length, 8));
            }
            s.gpuKhz = b.getLong("gpuKhz");
            s.gpuLoad = b.getFloat("gpuLoad", -1);
            s.cpuTemp = b.getFloat("cpuTemp", Float.NaN);
            s.gpuTemp = b.getFloat("gpuTemp", Float.NaN);
            s.gpuIdle = b.getBoolean("gpuIdle");
            s.socTemp = b.getFloat("socTemp", Float.NaN);
            s.batteryTemp = b.getFloat("batteryTemp", Float.NaN);
            s.ddrMhz = b.getLong("ddrMhz");
            s.ram = b.getFloat("ram", -1);
            s.swap = b.getFloat("swap", -1);
            s.watts = b.getFloat("watts", -1);
            s.fps = b.getFloat("fps", -1);
            s.frameMs = b.getFloat("frameMs");
            float[] times = b.getFloatArray("frameTimes");
            s.frameTimes = times != null ? times : new float[0];
            return s;
        }

        private static void copy(long[] from, long[] to) {
            if (from != null) {
                System.arraycopy(from, 0, to, 0, Math.min(from.length, to.length));
            }
        }

        float cpuLoad() {
            float sum = 0;
            int online = 0;
            for (int i = 0; i < 8; i++) {
                if (coreKhz[i] > 0) {
                    sum += coreLoad[i];
                    online++;
                }
            }
            return online == 0 ? -1 : sum / online;
        }

        long maxCpuKhz() {
            long max = Math.max(clusterKhz[0], clusterKhz[1]);
            for (long khz : coreKhz) {
                max = Math.max(max, khz);
            }
            return max;
        }
    }
}
