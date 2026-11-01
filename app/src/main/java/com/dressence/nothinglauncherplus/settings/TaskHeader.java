package com.dressence.nothinglauncherplus.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.View;

import java.util.List;
import java.util.Locale;

/**
 * The task manager's header over Running services, in Nothing's dot-matrix look: NDot 57 text
 * and dot meters (lit dots for the share in use, dim dots for the rest, Nothing red past 80%).
 * Lines: CPU load, clocks of the two clusters; memory and swap in use; network speed down and
 * up; temperatures; the processes using the most CPU now, system ones too.
 */
final class TaskHeader extends View {

    private static final int RED = 0xFFD71921;
    private static final int LIT = Color.WHITE;
    private static final int DIM = 0x33FFFFFF;
    private static final int LABEL = 0x99FFFFFF;
    private static final int DOTS = 24;

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private final float lineHeight;
    private TaskStats stats;

    TaskHeader(Context context, Typeface ndot) {
        super(context);
        dp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1,
                context.getResources().getDisplayMetrics());
        Typeface face = ndot != null ? ndot : Typeface.MONOSPACE;
        text.setTypeface(face);
        text.setColor(LIT);
        text.setTextSize(14 * dp);
        label.setTypeface(face);
        label.setColor(LABEL);
        label.setTextSize(11 * dp);
        lineHeight = 26 * dp;
        setPadding((int) (16 * dp), (int) (12 * dp), (int) (16 * dp), (int) (8 * dp));
        setContentDescription("Task manager");
    }

    void show(TaskStats stats) {
        this.stats = stats;
        requestLayout();
        invalidate();
    }

    private int lines() {
        int n = 4; // CPU, memory, network, temperatures
        if (stats != null && stats.swapTotal > 0) {
            n++;
        }
        if (stats != null) {
            n += 1 + Math.min(5, stats.topCopy().size());
        }
        return n;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int height = (int) (getPaddingTop() + getPaddingBottom() + lines() * lineHeight);
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (stats == null) {
            return;
        }
        Context c = getContext();
        float x = getPaddingLeft();
        float right = getWidth() - getPaddingRight();
        float y = getPaddingTop() + lineHeight * 0.7f;
        float meterX = x + 64 * dp;
        float meterWidth = Math.min(right - meterX, 150 * dp);

        // CPU
        canvas.drawText("CPU", x, y, label);
        float cpu = stats.cpuPercent;
        meter(canvas, meterX, y, meterWidth, cpu < 0 ? 0 : cpu / 100f);
        canvas.drawText((cpu < 0 ? "--" : Math.round(cpu) + "%") + "  " + clocks(),
                meterX + meterWidth + 10 * dp, y, text);
        y += lineHeight;

        // Memory
        long used = stats.memTotal - stats.memAvailable;
        canvas.drawText("RAM", x, y, label);
        meter(canvas, meterX, y, meterWidth,
                stats.memTotal > 0 ? (float) used / stats.memTotal : 0);
        canvas.drawText(size(c, used) + " / " + size(c, stats.memTotal),
                meterX + meterWidth + 10 * dp, y, text);
        y += lineHeight;

        if (stats.swapTotal > 0) {
            long swapUsed = stats.swapTotal - stats.swapFree;
            canvas.drawText("SWAP", x, y, label);
            meter(canvas, meterX, y, meterWidth, (float) swapUsed / stats.swapTotal);
            canvas.drawText(size(c, swapUsed) + " / " + size(c, stats.swapTotal),
                    meterX + meterWidth + 10 * dp, y, text);
            y += lineHeight;
        }

        // Network
        canvas.drawText("NET", x, y, label);
        canvas.drawText("↓ " + speed(c, stats.rxPerSecond) + "   ↑ "
                + speed(c, stats.txPerSecond), meterX, y, text);
        y += lineHeight;

        // Temperatures
        canvas.drawText("TEMP", x, y, label);
        StringBuilder temps = new StringBuilder();
        temp(temps, "BAT", stats.batteryC);
        temp(temps, "CPU", stats.cpuC);
        temp(temps, "GPU", stats.gpuC);
        canvas.drawText(temps.length() == 0 ? "--" : temps.toString().trim(), meterX, y, text);
        y += lineHeight;

        // Top processes
        List<TaskStats.Proc> top = stats.topCopy();
        canvas.drawText("TOP CPU", x, y, label);
        y += lineHeight;
        for (TaskStats.Proc p : top) {
            String name = p.name.length() > 34 ? "…" + p.name.substring(p.name.length() - 33)
                    : p.name;
            canvas.drawText(name, x, y, text);
            String share = String.format(Locale.ROOT, "%.1f%%", p.percent);
            canvas.drawText(share, right - text.measureText(share), y, text);
            y += lineHeight;
        }
    }

    /** A row of dots, lit for the share in use. */
    private void meter(Canvas canvas, float x, float baseline, float width, float share) {
        float step = width / DOTS;
        float radius = Math.min(step * 0.32f, 3.2f * dp);
        float cy = baseline - 5 * dp;
        int lit = Math.round(Math.max(0, Math.min(1, share)) * DOTS);
        for (int i = 0; i < DOTS; i++) {
            dot.setColor(i < lit ? (share >= 0.8f ? RED : LIT) : DIM);
            canvas.drawCircle(x + step * (i + 0.5f), cy, radius, dot);
        }
    }

    private String clocks() {
        long little = 0;
        long big = 0;
        for (int i = 0; i < stats.coreKhz.length; i++) {
            if (i < 4) { // Two clusters of four (policy0, policy4)
                little = Math.max(little, stats.coreKhz[i]);
            } else {
                big = Math.max(big, stats.coreKhz[i]);
            }
        }
        return String.format(Locale.ROOT, "%.1f/%.1f GHz", little / 1e6, big / 1e6);
    }

    private static void temp(StringBuilder out, String name, float celsius) {
        if (!Float.isNaN(celsius)) {
            out.append(name).append(' ').append(Math.round(celsius)).append("°  ");
        }
    }

    private static String size(Context c, long bytes) {
        return Formatter.formatShortFileSize(c, bytes);
    }

    private static String speed(Context c, long perSecond) {
        return perSecond < 0 ? "--" : Formatter.formatShortFileSize(c, perSecond) + "/s";
    }
}
