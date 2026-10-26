package com.dressence.nothinglauncherplus.hook;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
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
    private static final float OFF_BELOW = 0.55f;
    private static final int[] LEVEL_ALPHA = {150, 200, 235, 255};
    /** An icon not drawn for this long powers on again when it next appears. */
    private static final long ASLEEP_MS = 800;
    private static final float POWER_ON_MS = 560;
    private static final long MAX_STAGGER_MS = 110;
    private static final int GLOW_SCALE = 4;
    /** The glass between the dots, and an unlit dot on it. */
    private static final int GLASS = 0xFF050505;
    private static final int UNLIT = 0xFF0D0D0D;

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
        /** The lit dots alone. */
        Bitmap lit;
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
            seen = new long[3];
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
            drawWithArtifacts(canvas, bounds, dots, paint, d, icon, now, seen);
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

    /** Each icon's own timing of its screen's quirks, in seconds: period and offset. */
    private static final float SCAN_EVERY = 11;
    private static final float SCAN_TIME = 1.5f;
    private static final float FLICKER_EVERY = 27;
    private static final float FLICKER_TIME = 0.16f;
    private static final float GLITCH_EVERY = 37;
    private static final float GLITCH_TIME = 0.1f;
    private static final Paint SCAN = new Paint();
    static {
        SCAN.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.SRC_IN));
    }

    /** One of an icon's quirks: how far through it is (0..1), or -1 if it is not on now. */
    private static float quirk(float t, float every, float time, int seed, int which) {
        float period = every * (0.7f + 0.6f * hash(seed, which, 1));
        float local = (t + hash(seed, which, 2) * period) % period;
        return local < time ? local / time : -1;
    }

    /** Seconds until that quirk next starts. */
    private static float untilQuirk(float t, float every, float time, int seed, int which) {
        float period = every * (0.7f + 0.6f * hash(seed, which, 1));
        float local = (t + hash(seed, which, 2) * period) % period;
        return local < time ? 0 : period - local;
    }

    private static float hash(int a, int b, int c) {
        int h = a * 0x27d4eb2d ^ b * 0x165667b1 ^ c * 0x9e3779b9;
        h ^= h >>> 15;
        h *= 0x2c1b3c6d;
        h ^= h >>> 12;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    /**
     * The icon at rest, with the quirks of an old dot screen, each icon on its own timing:
     * now and then a faint green scan line rolls down the panel, the panel flickers dim for
     * a moment, or a band of rows jumps a dot sideways and back.
     */
    private static void drawWithArtifacts(Canvas canvas, Rect bounds, Dots dots, Paint paint,
            Drawable d, Bitmap icon, long now, long[] seen) {
        int seed = System.identityHashCode(icon);
        float t = (now % 3_600_000L) / 1000f;
        float scan = quirk(t, SCAN_EVERY, SCAN_TIME, seed, 1);
        float flicker = quirk(t, FLICKER_EVERY, FLICKER_TIME, seed, 2);
        float glitch = quirk(t, GLITCH_EVERY, GLITCH_TIME, seed, 3);
        int alpha = paint.getAlpha();
        if (flicker >= 0) {
            // Two quick dips, as a tired panel does.
            float dip = (float) Math.abs(Math.sin(flicker * Math.PI * 2));
            paint.setAlpha(Math.round(alpha * (1 - 0.28f * dip)));
        }
        if (glitch >= 0) {
            float pitch = bounds.height() / (float) GRID;
            int row = Math.round(hash(seed, (int) (t / GLITCH_EVERY), 4) * (GRID - 3));
            float top = bounds.top + row * pitch;
            float bottom = top + pitch * (2 + Math.round(hash(seed, 7, 5) * 2));
            canvas.save();
            canvas.clipOutRect(bounds.left, top, bounds.right, bottom);
            canvas.drawBitmap(dots.full, null, bounds, paint);
            canvas.restore();
            canvas.save();
            canvas.clipRect(bounds.left, top, bounds.right, bottom);
            canvas.translate(pitch, 0);
            canvas.drawBitmap(dots.full, null, bounds, paint);
            canvas.restore();
        } else if (scan >= 0) {
            canvas.drawBitmap(dots.full, null, bounds, paint);
            // Only the lit dots take on the line's green, as on the emoji screens.
            int layer = canvas.saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom,
                    null);
            canvas.drawBitmap(dots.lit, null, bounds, paint);
            // The line rolls from above the panel to below it, easing in and out.
            float band = bounds.height() * 0.22f;
            float e = scan * scan * (3 - 2 * scan);
            float y = bounds.top - band + e * (bounds.height() + band * 2);
            float fade = (float) Math.sin(scan * Math.PI);
            int peak = Math.round(150 * fade) << 24 | SCAN_GREEN;
            SCAN.setShader(new android.graphics.LinearGradient(0, y - band / 2, 0, y + band / 2,
                    new int[] {SCAN_GREEN, peak, SCAN_GREEN}, null,
                    android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(bounds.left, y - band / 2, bounds.right, y + band / 2, SCAN);
            canvas.restoreToCount(layer);
        } else {
            canvas.drawBitmap(dots.full, null, bounds, paint);
        }
        paint.setAlpha(alpha);
        if (scan >= 0 || flicker >= 0 || glitch >= 0) {
            d.invalidateSelf();
            return;
        }
        // Asleep until the next quirk: one redraw is booked for it.
        float wait = Math.min(untilQuirk(t, SCAN_EVERY, SCAN_TIME, seed, 1),
                Math.min(untilQuirk(t, FLICKER_EVERY, FLICKER_TIME, seed, 2),
                        untilQuirk(t, GLITCH_EVERY, GLITCH_TIME, seed, 3)));
        long at = now + Math.max(16, Math.round(wait * 1000));
        if (seen[2] > now && seen[2] <= at) {
            return;
        }
        seen[2] = at;
        WeakReference<Drawable> ref = new WeakReference<>(d);
        MAIN.postAtTime(() -> {
            Drawable drawable = ref.get();
            if (drawable != null) {
                drawable.invalidateSelf();
            }
        }, at);
    }

    /** The scan line's colour (no alpha): the green of an old phosphor screen. */
    private static final int SCAN_GREEN = 0x4CFF8A;

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

    /** The dots drawn at 1/{@code down} size, {@code radius} each: blurred when stretched. */
    private static Bitmap bloom(Dots dots, int w, int h, int down, float radius) {
        Bitmap b = Bitmap.createBitmap(Math.max(1, w / down), Math.max(1, h / down),
                Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(b.getWidth() / (float) w, b.getHeight() / (float) h);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < dots.x.length; i++) {
            int col = dots.color[i];
            // Bright colours bloom more than dim ones.
            float luma = (0.3f * ((col >> 16) & 0xFF) + 0.59f * ((col >> 8) & 0xFF)
                    + 0.11f * (col & 0xFF)) / 255f;
            p.setColor(col & 0xFFFFFF | Math.round(255 * (0.35f + 0.65f * luma)) << 24);
            c.drawCircle(dots.x[i], dots.y[i], radius, p);
        }
        return b;
    }

    /**
     * An app with an old-style icon is drawn by the launcher shrunk onto a white plate. As dots
     * that would be a small icon in a field of white dots, so the plate is taken away (the
     * white joined to the plate's edge, not white inside the icon itself) and the icon scaled
     * up to fill the space. Any other icon is returned as it is.
     */
    private static Bitmap withoutWhitePlate(Bitmap icon) {
        int w = icon.getWidth();
        int h = icon.getHeight();
        int n = w * h;
        int[] px = new int[n];
        icon.getPixels(px, 0, w, 0, 0, w, h);
        int[] shape = px.clone();
        // The plate's edge: solid pixels next to clear ones. A white plate is white all round.
        int edge = 0;
        int white = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                if ((px[i] >>> 24) < 200) {
                    continue;
                }
                if ((px[i - 1] >>> 24) < 100 || (px[i + 1] >>> 24) < 100
                        || (px[i - w] >>> 24) < 100 || (px[i + w] >>> 24) < 100) {
                    edge++;
                    if (nearWhite(px[i])) {
                        white++;
                    }
                }
            }
        }
        if (edge < w || white < edge * 0.85f) {
            return icon;
        }
        // The plate: near-white joined to the edge.
        boolean[] plate = new boolean[n];
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            int x = i % w;
            int y = i / w;
            boolean border = x == 0 || y == 0 || x == w - 1 || y == h - 1
                    || (px[i - 1] >>> 24) < 100 || (px[i + 1] >>> 24) < 100
                    || (px[i - w] >>> 24) < 100 || (px[i + w] >>> 24) < 100;
            if (border && (px[i] >>> 24) >= 100 && nearWhite(px[i])) {
                plate[i] = true;
                queue.add(i);
            }
        }
        while (!queue.isEmpty()) {
            int i = queue.poll();
            int x = i % w;
            int[] next = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, i - w, i + w};
            for (int j : next) {
                if (j >= 0 && j < n && !plate[j] && (px[j] >>> 24) >= 100 && nearWhite(px[j])) {
                    plate[j] = true;
                    queue.add(j);
                }
            }
        }
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int i = 0; i < n; i++) {
            if (plate[i] || (px[i] >>> 24) < 100) {
                px[i] = plate[i] ? 0 : px[i];
                continue;
            }
            x0 = Math.min(x0, i % w);
            x1 = Math.max(x1, i % w);
            y0 = Math.min(y0, i / w);
            y1 = Math.max(y1, i / w);
        }
        if (x1 < 0 || Math.max(x1 - x0, y1 - y0) > w * 0.85f) {
            return icon;
        }
        // The plate's soft edge: whatever is left outside the icon's own box goes too.
        for (int i = 0; i < n; i++) {
            int x = i % w;
            int y = i / w;
            if (x < x0 || x > x1 || y < y0 || y > y1) {
                px[i] = 0;
            }
        }
        // The icon is scaled to cover the launcher's shape (the plate's outline, a circle or
        // whatever the launcher uses) and cut to it, so it is the same shape as every other
        // icon, filled edge to edge.
        int sx0 = w, sy0 = h, sx1 = -1, sy1 = -1;
        for (int i = 0; i < n; i++) {
            if ((shape[i] >>> 24) > 100) {
                sx0 = Math.min(sx0, i % w);
                sx1 = Math.max(sx1, i % w);
                sy0 = Math.min(sy0, i / w);
                sy1 = Math.max(sy1, i / w);
            }
        }
        float sw = sx1 - sx0 + 1;
        float sh = sy1 - sy0 + 1;
        float cover = Math.max(sw / (x1 - x0 + 1), sh / (y1 - y0 + 1));
        float cw = (x1 - x0 + 1) * cover;
        float ch = (y1 - y0 + 1) * cover;
        float mx = (sx0 + sx1 + 1) / 2f;
        float my = (sy0 + sy1 + 1) / 2f;
        Bitmap bare = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawBitmap(bare, new Rect(x0, y0, x1 + 1, y1 + 1),
                new android.graphics.RectF(mx - cw / 2, my - ch / 2, mx + cw / 2, my + ch / 2),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        bare.recycle();
        // Cut to the shape: the artwork covers it, so nothing inside is left see-through.
        for (int i = 0; i < n; i++) {
            shape[i] = (shape[i] & 0xFF000000) | 0xFFFFFF;
        }
        Bitmap mask = Bitmap.createBitmap(shape, w, h, Bitmap.Config.ARGB_8888);
        Paint cut = new Paint(Paint.FILTER_BITMAP_FLAG);
        cut.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.DST_IN));
        canvas.drawBitmap(mask, 0, 0, cut);
        mask.recycle();
        return out;
    }

    private static boolean nearWhite(int c) {
        return ((c >> 16) & 0xFF) > 228 && ((c >> 8) & 0xFF) > 228 && (c & 0xFF) > 228;
    }

    /**
     * Samples the icon onto a GRID x GRID panel (average colour and coverage per cell, coverage
     * snapped to a few LED levels) and draws each lit cell as a round dot over a soft halo.
     */
    private static Dots convert(Bitmap icon) {
        if (icon.getConfig() == Bitmap.Config.HARDWARE) {
            icon = icon.copy(Bitmap.Config.ARGB_8888, false); // Pixels can't be read on the GPU.
        }
        icon = withoutWhitePlate(icon);
        int w = icon.getWidth();
        int h = icon.getHeight();
        int[] px = new int[w * h];
        icon.getPixels(px, 0, w, 0, 0, w, h);
        float pitch = w / (float) GRID;
        int rows = Math.max(1, Math.round(h / pitch));
        int[] cells = new int[GRID * rows];
        // Cells inside the icon's shape that stay dark: the panel's unlit dots.
        boolean[] off = new boolean[GRID * rows];
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
                    off[gy * GRID + gx] = coverage > 0.25f;
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
        int k = 0;
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] != 0) {
                dots.x[k] = (i % GRID + 0.5f) * pitch;
                dots.y[k] = (i / GRID + 0.5f) * pitch;
                dots.color[k] = cells[i];
                k++;
            }
        }
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint smooth = new Paint(Paint.FILTER_BITMAP_FLAG);
        Rect whole = new Rect(0, 0, w, h);
        // The glass: near black in the icon's shape, with every dot of the panel showing, the
        // unlit ones a faint grey, so it reads as a little screen even where it is dark.
        Bitmap plateBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas plate = new Canvas(plateBitmap);
        Paint glass = new Paint(Paint.FILTER_BITMAP_FLAG);
        glass.setColorFilter(new android.graphics.PorterDuffColorFilter(GLASS,
                android.graphics.PorterDuff.Mode.SRC_IN));
        plate.drawBitmap(icon, 0, 0, glass);
        paint.setColor(UNLIT);
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] != 0 || off[i]) {
                plate.drawCircle((i % GRID + 0.5f) * pitch, (i / GRID + 0.5f) * pitch,
                        dots.radius, paint);
            }
        }
        // The glow: each dot's light falling dimly on the glass round it, and a faint wide
        // bloom where many lit dots are together, as on a real dot display.
        Bitmap glowBitmap = Bitmap.createBitmap(Math.max(1, w / GLOW_SCALE),
                Math.max(1, h / GLOW_SCALE), Bitmap.Config.ARGB_8888);
        Canvas glow = new Canvas(glowBitmap);
        Rect small = new Rect(0, 0, glowBitmap.getWidth(), glowBitmap.getHeight());
        glow.save();
        glow.scale(glowBitmap.getWidth() / (float) w, glowBitmap.getHeight() / (float) h);
        Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < dots.x.length; i++) {
            int rgb = dots.color[i] & 0xFFFFFF;
            halo.setShader(new android.graphics.RadialGradient(dots.x[i], dots.y[i],
                    pitch * 1.25f, new int[] {rgb | 0x46000000, rgb | 0x16000000, rgb},
                    new float[] {0, 0.55f, 1}, android.graphics.Shader.TileMode.CLAMP));
            glow.drawCircle(dots.x[i], dots.y[i], pitch * 1.25f, halo);
        }
        glow.restore();
        Bitmap wide = bloom(dots, w, h, 12, pitch * 1.5f);
        Paint add = new Paint(Paint.FILTER_BITMAP_FLAG);
        add.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.ADD));
        add.setAlpha(55);
        glow.drawBitmap(wide, null, small, add);
        wide.recycle();
        Bitmap fullBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas full = new Canvas(fullBitmap);
        full.drawBitmap(plateBitmap, 0, 0, null);
        full.drawBitmap(glowBitmap, null, whole, smooth);
        // The lit dots alone, for the scan line to tint.
        Bitmap litBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas litCanvas = new Canvas(litBitmap);
        for (int i = 0; i < dots.x.length; i++) {
            paint.setColor(dots.color[i]);
            full.drawCircle(dots.x[i], dots.y[i], dots.radius, paint);
            paint.setColor(0xFF000000 | dots.color[i]);
            litCanvas.drawCircle(dots.x[i], dots.y[i], dots.radius, paint);
        }
        // Nothing outside the icon's own shape: no dot or glow spills past its edge, however
        // the launcher scales it (opening an app zooms the icon up).
        Paint mask = new Paint(Paint.FILTER_BITMAP_FLAG);
        mask.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.DST_IN));
        full.drawBitmap(icon, 0, 0, mask);
        plate.drawBitmap(icon, 0, 0, mask);
        glow.drawBitmap(icon, null, small, mask);
        // Kept on the GPU: no upload when drawn, and off the app's Java heap.
        dots.full = toHardware(fullBitmap);
        dots.plate = toHardware(plateBitmap);
        dots.lit = toHardware(litBitmap);
        dots.glow = toHardware(glowBitmap);
        return dots;
    }
}
