package com.dressence.nothinglauncherplus.hook;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Optional dot-matrix app icons: every icon the launcher draws (home screen, drawer, folders,
 * search) is redrawn as round Nothing dots in the icon's own colours, each lit dot glowing
 * softly. The launcher draws all of them through {@code FastBitmapDrawable#drawInternal}, so
 * one hook covers every surface; each icon bitmap is converted once and cached for as long as
 * the launcher keeps the bitmap.
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

    private static final Map<Bitmap, Bitmap> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static WeakReference<Activity> launcher = new WeakReference<>(null);

    private DotIcons() {
    }

    static void install(ClassLoader cl, ConfigStore store) {
        XposedHelpers.findAndHookMethod(CLS_FAST_BITMAP, cl, "drawInternal", Canvas.class,
                Rect.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!store.get().dotIcons) {
                            return;
                        }
                        Object info = XposedHelpers.getObjectField(param.thisObject,
                                "mBitmapInfo");
                        Bitmap icon = (Bitmap) XposedHelpers.getObjectField(info, "icon");
                        if (icon == null || icon.getWidth() < GRID * 2
                                || (Boolean) XposedHelpers.callMethod(info, "isLowRes")) {
                            return;
                        }
                        Paint paint = (Paint) XposedHelpers.getObjectField(param.thisObject,
                                "mPaint");
                        Bitmap dots = dotted(icon);
                        ((Canvas) param.args[0]).drawBitmap(dots, null, (Rect) param.args[1],
                                paint);
                        param.setResult(null);
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
    private static Bitmap dotted(Bitmap icon) {
        Bitmap cached = CACHE.get(icon);
        if (cached != null) {
            return cached;
        }
        Bitmap out = convert(icon);
        CACHE.put(icon, out);
        return out;
    }

    /**
     * Samples the icon onto a GRID x GRID panel (average colour and coverage per cell, coverage
     * snapped to a few LED levels) and draws each lit cell as a round dot over a soft halo.
     */
    static Bitmap convert(Bitmap icon) {
        int w = icon.getWidth();
        int h = icon.getHeight();
        int[] px = new int[w * h];
        icon.getPixels(px, 0, w, 0, 0, w, h);
        float pitch = w / (float) GRID;
        int rows = Math.max(1, Math.round(h / pitch));
        int[] dots = new int[GRID * rows];
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
                dots[gy * GRID + gx] = Color.argb(LEVEL_ALPHA[level - 1],
                        (int) (r / a), (int) (g / a), (int) (b / a));
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // The glow first, so the dots sit on their own light.
        float halo = pitch * 1.4f;
        for (int i = 0; i < dots.length; i++) {
            int c = dots[i];
            if (c == 0) {
                continue;
            }
            float cx = (i % GRID + 0.5f) * pitch;
            float cy = (i / GRID + 0.5f) * pitch;
            int rgb = c & 0xFFFFFF;
            paint.setShader(new RadialGradient(cx, cy, halo,
                    new int[] {rgb | 0x38000000, rgb}, null, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, halo, paint);
        }
        paint.setShader(null);
        float radius = pitch * DOT / 2;
        for (int i = 0; i < dots.length; i++) {
            int c = dots[i];
            if (c == 0) {
                continue;
            }
            paint.setColor(c);
            canvas.drawCircle((i % GRID + 0.5f) * pitch, (i / GRID + 0.5f) * pitch, radius,
                    paint);
        }
        return out;
    }
}
