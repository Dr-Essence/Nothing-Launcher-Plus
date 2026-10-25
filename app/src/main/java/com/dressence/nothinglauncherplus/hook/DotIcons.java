package com.dressence.nothinglauncherplus.hook;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Optional dot-matrix app icons: every icon the launcher draws (home screen, app drawer, icons
 * in drawer folders and their tile previews, search) is redrawn as round Nothing dots in the
 * icon's own colours, each lit dot glowing softly.
 *
 * <p>All of them are {@code FastBitmapDrawable}s, so one hook on its {@code draw} covers every
 * surface ({@code drawInternal} is compiled into {@code draw} on some paths, so it is not
 * enough). Each icon bitmap is converted once and cached.
 *
 * <p>When icons come into view (the drawer opening, going home, a folder opening) each one powers
 * on like a little screen: its dots light up from the centre outwards on a spring, each with a
 * brief white LED flash settling into its colour, while the glow fades up underneath. Icons
 * start a few milliseconds apart, so a page shimmers on rather than snapping. At rest an icon is
 * one bitmap draw.
 */
final class DotIcons {

    private static final String CLS_FAST_BITMAP =
            "com.android.launcher3.icons.FastBitmapDrawable";
    /** Dots across an icon. */
    private static final int GRID = 22;
    /** Dot diameter relative to the pitch. */
    private static final float DOT = 0.8f;
    /** Below this coverage a cell stays dark; above it, one of these opacities. */
    private static final float OFF_BELOW = 0.3f;
    private static final int[] LEVEL_ALPHA = {150, 200, 235, 255};
    /** An icon not drawn for this long powers on again when it next appears. */
    private static final long ASLEEP_MS = 800;
    private static final float POWER_ON_MS = 560;
    private static final long MAX_STAGGER_MS = 110;

    private static final Map<Bitmap, Dots> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** Per drawable: {last drawn, power-on start} in uptime millis. */
    private static final Map<Object, long[]> SEEN = new WeakHashMap<>();
    private static final Paint DOT_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint GLOW_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static WeakReference<Activity> launcher = new WeakReference<>(null);

    private DotIcons() {
    }

    /** One icon as dots: the finished picture, its glow alone, and every dot for animating. */
    private static final class Dots {
        Bitmap full;
        Bitmap glow;
        float[] x;
        float[] y;
        int[] color;
        float radius;
        float size;
    }

    static void install(ClassLoader cl, ConfigStore store) {
        Class<?> fast = XposedHelpers.findClass(CLS_FAST_BITMAP, cl);
        // Keep draw and drawInternal un-inlined so every caller goes through the hook.
        Hooks.deoptimizeAll(fast, "draw");
        Hooks.deoptimizeAll(fast, "drawInternal");
        XposedHelpers.findAndHookMethod(fast, "draw", Canvas.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (store.get().dotIcons && draw((Drawable) param.thisObject,
                                (Canvas) param.args[0])) {
                            param.setResult(null);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod("com.android.launcher3.Launcher", cl, "onCreate",
                Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        launcher = new WeakReference<>((Activity) param.thisObject);
                    }
                });
        // Switching the option redraws every icon on screen.
        boolean[] last = {store.get().dotIcons};
        store.addChangeListener(() -> {
            boolean now = store.get().dotIcons;
            Activity activity = launcher.get();
            if (now == last[0] || activity == null) {
                return;
            }
            last[0] = now;
            activity.runOnUiThread(() -> invalidateAll(activity.getWindow().getDecorView()));
        });
    }

    /** Draws {@code d} as dots, as FastBitmapDrawable#draw would; false to draw it normally. */
    private static boolean draw(Drawable d, Canvas canvas) {
        String cls = d.getClass().getName();
        // The live clock, install progress and loading placeholders draw extras of their own.
        if (cls.contains("Clock") || cls.contains("Preload") || cls.contains("PlaceHolder")) {
            return false;
        }
        Object info = XposedHelpers.getObjectField(d, "mBitmapInfo");
        Bitmap icon = (Bitmap) XposedHelpers.getObjectField(info, "icon");
        if (icon == null || icon.getWidth() < GRID * 2
                || (Boolean) XposedHelpers.callMethod(info, "isLowRes")) {
            return false;
        }
        Dots dots = dots(icon);
        Paint paint = (Paint) XposedHelpers.getObjectField(d, "mPaint");
        Drawable badge = (Drawable) XposedHelpers.getObjectField(d, "mBadge");
        float scale = XposedHelpers.getFloatField(d, "mScale");
        Rect bounds = d.getBounds();

        long now = SystemClock.uptimeMillis();
        long[] seen = SEEN.get(d);
        if (seen == null) {
            seen = new long[2];
            SEEN.put(d, seen);
        }
        if (now - seen[0] > ASLEEP_MS) {
            seen[1] = now + (System.identityHashCode(d) & 0x7FFFFFFF) % MAX_STAGGER_MS;
        }
        seen[0] = now;
        float t = (now - seen[1]) / POWER_ON_MS;

        int save = canvas.save();
        if (scale != 1) {
            canvas.scale(scale, scale, bounds.exactCenterX(), bounds.exactCenterY());
        }
        if (t >= 1) {
            canvas.drawBitmap(dots.full, null, bounds, paint);
        } else {
            powerOn(canvas, bounds, dots, Math.max(0, t), paint.getAlpha());
            d.invalidateSelf();
        }
        if (badge != null) {
            badge.draw(canvas);
        }
        canvas.restoreToCount(save);
        return true;
    }

    /**
     * One frame of an icon powering on ({@code t} 0..1): the glow fades up, and each dot, from
     * the centre outwards, grows in on a spring with a white flash that settles into its colour.
     */
    private static void powerOn(Canvas canvas, Rect bounds, Dots dots, float t, int alpha) {
        float k = bounds.width() / dots.size;
        GLOW_PAINT.setAlpha(Math.round(alpha * ease(t)));
        canvas.drawBitmap(dots.glow, null, bounds, GLOW_PAINT);
        float half = dots.size / 2;
        for (int i = 0; i < dots.x.length; i++) {
            float dx = dots.x[i] - half;
            float dy = dots.y[i] - half;
            float reach = (float) Math.sqrt(dx * dx + dy * dy) / (half * 1.42f);
            float local = (t - reach * 0.45f) / 0.55f;
            if (local <= 0) {
                continue;
            }
            float s = spring(Math.min(1, local));
            int c = dots.color[i];
            float flash = Math.max(0, 1 - local * 1.6f);
            int rgb = mix(c, 0xFFFFFF, 0.65f * flash);
            DOT_PAINT.setColor(rgb);
            DOT_PAINT.setAlpha(Math.round((c >>> 24) * Math.min(1, s * 1.15f) * alpha / 255f));
            canvas.drawCircle(bounds.left + dots.x[i] * k, bounds.top + dots.y[i] * k,
                    dots.radius * k * (0.35f + 0.65f * Math.min(1.08f, s)), DOT_PAINT);
        }
    }

    /** A critically damped spring from 0 to 1, with a whisper of overshoot. */
    private static float spring(float x) {
        double w = 9;
        return (float) (1 - (1 + w * x * 0.85) * Math.exp(-w * x));
    }

    private static float ease(float x) {
        float inv = 1 - Math.min(1, x);
        return 1 - inv * inv * inv;
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - f) + ((b >> 16) & 0xFF) * f);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - f) + ((b >> 8) & 0xFF) * f);
        int bl = Math.round((a & 0xFF) * (1 - f) + (b & 0xFF) * f);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    private static void invalidateAll(View view) {
        view.invalidate();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                invalidateAll(group.getChildAt(i));
            }
        }
    }

    /** {@code icon} as dots, made once per bitmap. */
    private static Dots dots(Bitmap icon) {
        Dots cached = CACHE.get(icon);
        if (cached != null) {
            return cached;
        }
        Dots out = convert(icon);
        CACHE.put(icon, out);
        return out;
    }

    /**
     * Samples the icon onto a GRID x GRID panel (average colour and coverage per cell, coverage
     * snapped to a few LED levels) and draws each lit cell as a round dot over a soft halo.
     */
    private static Dots convert(Bitmap icon) {
        int w = icon.getWidth();
        int h = icon.getHeight();
        int[] px = new int[w * h];
        icon.getPixels(px, 0, w, 0, 0, w, h);
        float pitch = w / (float) GRID;
        int rows = Math.max(1, Math.round(h / pitch));
        int[] cells = new int[GRID * rows];
        int lit = 0;
        for (int gy = 0; gy < rows; gy++) {
            int y0 = Math.round(gy * pitch);
            int y1 = Math.min(h, Math.round((gy + 1) * pitch));
            for (int gx = 0; gx < GRID; gx++) {
                int x0 = Math.round(gx * pitch);
                int x1 = Math.min(w, Math.round((gx + 1) * pitch));
                long a = 0, r = 0, g = 0, b = 0;
                int n = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int c = px[y * w + x];
                        int ca = c >>> 24;
                        a += ca;
                        r += ((c >> 16) & 0xFF) * ca;
                        g += ((c >> 8) & 0xFF) * ca;
                        b += (c & 0xFF) * ca;
                        n++;
                    }
                }
                if (n == 0 || a == 0) {
                    continue;
                }
                float coverage = a / (255f * n);
                if (coverage < OFF_BELOW) {
                    continue;
                }
                int level = Math.min(LEVEL_ALPHA.length,
                        Math.max(1, Math.round(coverage * LEVEL_ALPHA.length)));
                cells[gy * GRID + gx] = Color.argb(LEVEL_ALPHA[level - 1],
                        (int) (r / a), (int) (g / a), (int) (b / a));
                lit++;
            }
        }
        Dots dots = new Dots();
        dots.size = w;
        dots.radius = pitch * DOT / 2;
        dots.x = new float[lit];
        dots.y = new float[lit];
        dots.color = new int[lit];
        dots.glow = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas glow = new Canvas(dots.glow);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float halo = pitch * 1.5f;
        int k = 0;
        for (int i = 0; i < cells.length; i++) {
            int c = cells[i];
            if (c == 0) {
                continue;
            }
            float cx = (i % GRID + 0.5f) * pitch;
            float cy = (i / GRID + 0.5f) * pitch;
            dots.x[k] = cx;
            dots.y[k] = cy;
            dots.color[k] = c;
            k++;
            int rgb = c & 0xFFFFFF;
            paint.setShader(new RadialGradient(cx, cy, halo,
                    new int[] {rgb | 0x40000000, rgb | 0x14000000, rgb},
                    new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
            glow.drawCircle(cx, cy, halo, paint);
        }
        dots.full = dots.glow.copy(Bitmap.Config.ARGB_8888, true);
        Canvas full = new Canvas(dots.full);
        paint.setShader(null);
        for (int i = 0; i < dots.x.length; i++) {
            paint.setColor(dots.color[i]);
            full.drawCircle(dots.x[i], dots.y[i], dots.radius, paint);
        }
        return dots;
    }
}
