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
    private static final int GLOW_SCALE = 4;
    /** The dark glass behind the dots, like the screen of a Nothing dot display. */
    private static final int PLATE = 0xFF0C0C0C;

    /** Marks an icon that could not be converted. */
    private static final Dots FAILED = new Dots();
    private static final Map<Bitmap, Dots> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** Per drawable: {last drawn, power-on start} in uptime millis. */
    private static final Map<Object, long[]> SEEN = new WeakHashMap<>();
    /** Icons being converted, and the drawables to redraw when each is ready (main thread). */
    private static final Map<Bitmap, java.util.List<WeakReference<Drawable>>> PENDING =
            new WeakHashMap<>();
    private static final java.util.concurrent.ExecutorService CONVERTER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "nlplus-dot-icons");
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());
    /** FastBitmapDrawable and BitmapInfo fields, looked up once. */
    private static java.lang.reflect.Field fBitmapInfo;
    private static java.lang.reflect.Field fIcon;
    private static java.lang.reflect.Field fPaint;
    private static java.lang.reflect.Field fBadge;
    private static java.lang.reflect.Field fScale;
    private static Bitmap lowRes;
    private static final Paint DOT_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint GLOW_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static WeakReference<Activity> launcher = new WeakReference<>(null);

    private DotIcons() {
    }

    /** One icon as dots: the finished picture, its glow alone, and every dot for animating. */
    private static final class Dots {
        Bitmap full;
        Bitmap glow;
        /** The dark glass the dots sit on, in the icon's own shape. */
        Bitmap plate;
        float[] x;
        float[] y;
        int[] color;
        float radius;
        float size;
    }

    static void install(ClassLoader cl, ConfigStore store) {
        Class<?> fast = XposedHelpers.findClass(CLS_FAST_BITMAP, cl);
        Class<?> bitmapInfo = XposedHelpers.findClass("com.android.launcher3.icons.BitmapInfo", cl);
        fBitmapInfo = field(fast, "mBitmapInfo");
        fPaint = field(fast, "mPaint");
        fBadge = field(fast, "mBadge");
        fScale = field(fast, "mScale");
        fIcon = field(bitmapInfo, "icon");
        lowRes = (Bitmap) XposedHelpers.getStaticObjectField(bitmapInfo, "LOW_RES_ICON");
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
        Paint paint;
        Drawable badge;
        float scale;
        Bitmap icon;
        try {
            Object info = fBitmapInfo.get(d);
            icon = (Bitmap) fIcon.get(info);
            if (icon == null || icon.getWidth() < GRID * 2 || icon == lowRes) {
                return false;
            }
            paint = (Paint) fPaint.get(d);
            badge = (Drawable) fBadge.get(d);
            scale = fScale.getFloat(d);
        } catch (IllegalAccessException e) {
            return false;
        }
        Rect bounds = d.getBounds();

        long now = SystemClock.uptimeMillis();
        long[] seen = SEEN.get(d);
        if (seen == null) {
            seen = new long[2];
            SEEN.put(d, seen);
        }
        Dots dots = dots(icon, d);
        if (dots == FAILED) {
            return false;
        }
        if (dots == null) {
            // Still being converted: stay dark, and power on as soon as it is ready.
            seen[0] = now;
            seen[1] = Long.MAX_VALUE;
            return true;
        }
        if (seen[1] == Long.MAX_VALUE || now - seen[0] > ASLEEP_MS) {
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
        // The glass is there from the start; only the dots power on.
        GLOW_PAINT.setAlpha(alpha);
        canvas.drawBitmap(dots.plate, null, bounds, GLOW_PAINT);
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

    /** A declared field of {@code cls} or a superclass, made accessible. */
    private static java.lang.reflect.Field field(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // Try the superclass.
            }
        }
        throw new IllegalStateException("no field " + name + " in " + cls.getName());
    }

    private static Bitmap toHardware(Bitmap bitmap) {
        Bitmap hw = bitmap.copy(Bitmap.Config.HARDWARE, false);
        if (hw == null) {
            return bitmap;
        }
        bitmap.recycle();
        return hw;
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

    /**
     * {@code icon} as dots, or null while it is being made: converted once per bitmap, off the
     * main thread, after which every drawable that asked for it is redrawn.
     */
    private static Dots dots(Bitmap icon, Drawable asking) {
        Dots cached = CACHE.get(icon);
        if (cached != null) {
            return cached;
        }
        java.util.List<WeakReference<Drawable>> waiting = PENDING.get(icon);
        if (waiting != null) {
            waiting.add(new WeakReference<>(asking));
            return null;
        }
        waiting = new java.util.ArrayList<>();
        waiting.add(new WeakReference<>(asking));
        PENDING.put(icon, waiting);
        WeakReference<Bitmap> source = new WeakReference<>(icon);
        CONVERTER.execute(() -> {
            Bitmap bitmap = source.get();
            Dots made;
            try {
                made = bitmap != null && !bitmap.isRecycled() ? convert(bitmap) : FAILED;
            } catch (RuntimeException e) {
                Hooks.log("dot icon failed: " + e);
                made = FAILED; // Drawn as the normal icon from now on.
            }
            Dots result = made;
            MAIN.post(() -> {
                Bitmap key = source.get();
                if (key == null) {
                    return;
                }
                java.util.List<WeakReference<Drawable>> asked = PENDING.remove(key);
                CACHE.put(key, result);
                if (asked != null) {
                    for (WeakReference<Drawable> ref : asked) {
                        Drawable drawable = ref.get();
                        if (drawable != null) {
                            drawable.invalidateSelf();
                        }
                    }
                }
            });
        });
        return null;
    }

    /**
     * Samples the icon onto a GRID x GRID panel (average colour and coverage per cell, coverage
     * snapped to a few LED levels) and draws each lit cell as a round dot over a soft halo.
     */
    private static Dots convert(Bitmap icon) {
        if (icon.getConfig() == Bitmap.Config.HARDWARE) {
            icon = icon.copy(Bitmap.Config.ARGB_8888, false); // Pixels can't be read on the GPU.
        }
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
        // The glow is a blur anyway: a quarter of the size, stretched smoothly when drawn.
        Bitmap glowBitmap = Bitmap.createBitmap(Math.max(1, w / GLOW_SCALE),
                Math.max(1, h / GLOW_SCALE), Bitmap.Config.ARGB_8888);
        Canvas glow = new Canvas(glowBitmap);
        glow.scale(glowBitmap.getWidth() / (float) w, glowBitmap.getHeight() / (float) h);
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
        // The dark glass in the icon's own shape, so the icon is solid and the wallpaper never
        // shows between its dots.
        Bitmap plateBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint platePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        platePaint.setColorFilter(new android.graphics.PorterDuffColorFilter(PLATE,
                android.graphics.PorterDuff.Mode.SRC_IN));
        new Canvas(plateBitmap).drawBitmap(icon, 0, 0, platePaint);
        Bitmap fullBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas full = new Canvas(fullBitmap);
        full.drawBitmap(plateBitmap, 0, 0, null);
        full.drawBitmap(glowBitmap, null, new Rect(0, 0, w, h),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        paint.setShader(null);
        for (int i = 0; i < dots.x.length; i++) {
            paint.setColor(dots.color[i]);
            full.drawCircle(dots.x[i], dots.y[i], dots.radius, paint);
        }
        // Kept on the GPU: no upload when drawn, and off the app's Java heap.
        dots.full = toHardware(fullBitmap);
        dots.plate = toHardware(plateBitmap);
        dots.glow = toHardware(glowBitmap);
        return dots;
    }
}
