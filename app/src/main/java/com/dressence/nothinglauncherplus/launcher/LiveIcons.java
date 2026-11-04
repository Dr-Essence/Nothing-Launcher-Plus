package com.dressence.nothinglauncherplus.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Live app icons: an app's icon that the launcher can only draw still is animated here, the way
 * Nothing Launcher animates its clock (which it gives to one package only, its
 * {@code clock_component_name}).
 *
 * <p>Today one icon: CMF Stereo's dot-matrix speaker, whose two sound waves pulse outwards. The
 * icon is the app's own (a speaker and two waves of white dots on black, with a red dot); this
 * class finds its dots in the launcher's bitmap of it, once and off the main thread, so it
 * follows whatever size and mask the launcher gives the icon. A bitmap that does not look like
 * that icon (themed icons, a future redesign) is drawn as the launcher draws it.
 *
 * <p>An icon is tagged when {@code BubbleTextView.setNonPendingIcon} gives a view its drawable
 * (home screen, drawer, search). Each frame is the launcher's own draw of the icon, then the
 * wave dots redrawn at their brightness. The next frame is asked for only from a draw of an
 * icon that is on screen, so the animation stops by itself when the icon scrolls away, an app
 * opens over the launcher or the screen goes off, and starts again when it is drawn again.
 * Frames follow the display's vsync, at most 60 a second.
 */
final class LiveIcons {

    private static final Set<String> PACKAGES = new HashSet<>(Arrays.asList(
            "com.tiimoo.cmfstereo.clean", "com.tiimoo.cmfstereo"));
    private static final String CLS_FAST_BITMAP =
            "com.android.launcher3.icons.FastBitmapDrawable";
    /** One pulse of both waves, inner then outer. */
    private static final float PERIOD_MS = 1800f;
    /** Never more frames than this a second. */
    private static final long MIN_FRAME_MS = 16;

    /** Tagged icon drawables (main thread). */
    private static final Set<Drawable> LIVE = Collections.newSetFromMap(new WeakHashMap<>());
    /** Drawables with a frame already asked for (main thread). */
    private static final Set<Drawable> FRAME_POSTED =
            Collections.newSetFromMap(new WeakHashMap<>());
    /** Dot layout per icon bitmap; NONE when it is not the expected icon. */
    private static final Map<Bitmap, Layout> LAYOUTS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<Bitmap> ANALYSING =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Layout NONE = new Layout(new float[0], new float[0], 0, 0, 0);
    private static final ExecutorService ANALYSER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nlplus-live-icons");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Paint ERASE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint DOT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Rect VISIBLE = new Rect();

    private static Field fBitmapInfo;
    private static Field fIcon;
    private static Field fPaint;
    private static Field fScale;

    private LiveIcons() {
    }

    static void install(ClassLoader cl) {
        Class<?> fast = XposedHelpers.findClass(CLS_FAST_BITMAP, cl);
        Class<?> bitmapInfo = XposedHelpers.findClass("com.android.launcher3.icons.BitmapInfo", cl);
        fBitmapInfo = field(fast, "mBitmapInfo");
        fPaint = field(fast, "mPaint");
        fScale = field(fast, "mScale");
        fIcon = field(bitmapInfo, "icon");
        ERASE.setColor(Color.BLACK);
        DOT.setColor(Color.WHITE);

        XposedHelpers.findAndHookMethod("com.android.launcher3.BubbleTextView", cl,
                "setNonPendingIcon",
                XposedHelpers.findClass("com.android.launcher3.model.data.ItemInfoWithIcon", cl),
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object icon = XposedHelpers.getObjectField(param.thisObject, "mIcon");
                            Object pkg = XposedHelpers.callMethod(param.args[0], "getTargetPackage");
                            if (icon instanceof Drawable && pkg != null && PACKAGES.contains(pkg)) {
                                LIVE.add((Drawable) icon);
                            }
                        } catch (Throwable t) {
                            Hooks.log("live icons tag failed: " + t);
                        }
                    }
                });

        Hooks.deoptimizeAll(fast, "draw");
        // Before the dot-matrix icons hook (default priority 50): a live icon is drawn here and
        // the call ends, so it is never converted to a dot screen of its own.
        XposedHelpers.findAndHookMethod(fast, "draw", Canvas.class, new XC_MethodHook(60) {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Drawable d = (Drawable) param.thisObject;
                if (!LIVE.contains(d)) {
                    return;
                }
                try {
                    if (draw(param, d, (Canvas) param.args[0])) {
                        param.setResult(null);
                    }
                } catch (Throwable t) {
                    LIVE.remove(d);
                    Hooks.log("live icons draw failed: " + t);
                }
            }
        });
    }

    /** Draws {@code d} with its waves at this instant; false to leave it to the launcher. */
    private static boolean draw(XC_MethodHook.MethodHookParam param, Drawable d, Canvas canvas)
            throws Throwable {
        Object info = fBitmapInfo.get(d);
        Bitmap icon = info != null ? (Bitmap) fIcon.get(info) : null;
        if (icon == null || icon.isRecycled()) {
            return false;
        }
        Layout layout = LAYOUTS.get(icon);
        if (layout == null) {
            analyseLater(icon, d);
            return false;
        }
        if (layout == NONE) {
            return false;
        }
        // The launcher's own drawing of the icon (badge, press scale, fade included).
        XposedBridge.invokeOriginalMethod(param.method, d, param.args);

        Rect b = d.getBounds();
        if (b.isEmpty()) {
            return true;
        }
        Paint paint = (Paint) fPaint.get(d);
        int alpha = paint != null ? paint.getAlpha() : 255;
        float scale = fScale.getFloat(d);
        float sx = b.width() / (float) icon.getWidth();
        float sy = b.height() / (float) icon.getHeight();
        float t = (SystemClock.uptimeMillis() % (long) PERIOD_MS) / PERIOD_MS;

        int save = canvas.save();
        if (scale != 1f) {
            canvas.scale(scale, scale, b.exactCenterX(), b.exactCenterY());
        }
        canvas.translate(b.left, b.top);
        canvas.scale(sx, sy);
        ERASE.setAlpha(alpha);
        wave(canvas, layout.inner, layout.r, glow(t, 0.22f), alpha);
        wave(canvas, layout.outer, layout.r, glow(t, 0.42f), alpha);
        canvas.restoreToCount(save);

        nextFrame(d);
        return true;
    }

    private static void wave(Canvas canvas, float[] xy, float r, float level, int alpha) {
        DOT.setAlpha(Math.round(alpha * level));
        for (int i = 0; i < xy.length; i += 2) {
            canvas.drawCircle(xy[i], xy[i + 1], r * 1.35f, ERASE);
            canvas.drawCircle(xy[i], xy[i + 1], r, DOT);
        }
    }

    /**
     * A wave's brightness at phase {@code t}: dim at rest, swelling to full and back as the
     * pulse passes (a smooth bell, no edges), so the two waves read as sound moving outwards.
     */
    private static float glow(float t, float centre) {
        float d = t - centre;
        if (d > 0.5f) d -= 1f;
        if (d < -0.5f) d += 1f;
        float bell = (float) Math.exp(-(d * d) / (2 * 0.085f * 0.085f));
        return 0.28f + 0.72f * bell;
    }

    /** One more frame for {@code d}, on the next vsync, if it is on screen. */
    private static void nextFrame(Drawable d) {
        if (FRAME_POSTED.contains(d) || !onScreen(d)) {
            return;
        }
        FRAME_POSTED.add(d);
        long posted = SystemClock.uptimeMillis();
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                long waited = SystemClock.uptimeMillis() - posted;
                if (waited < MIN_FRAME_MS) {
                    // A faster panel: skip to the next vsync instead of drawing twice as often.
                    Choreographer.getInstance().postFrameCallback(this);
                    return;
                }
                FRAME_POSTED.remove(d);
                if (LIVE.contains(d)) {
                    d.invalidateSelf();
                }
            }
        });
    }

    /** True if {@code d}'s view is on the display; false if it has none or is hidden. */
    private static boolean onScreen(Drawable d) {
        Drawable.Callback callback = d.getCallback();
        if (!(callback instanceof View)) {
            return false;
        }
        View view = (View) callback;
        return view.isShown() && view.getGlobalVisibleRect(VISIBLE)
                && view.getAlpha() > 0.01f && view.getWindowVisibility() == View.VISIBLE;
    }

    private static void analyseLater(Bitmap icon, Drawable d) {
        if (!ANALYSING.add(icon)) {
            return;
        }
        ANALYSER.execute(() -> {
            Layout layout;
            try {
                layout = analyse(icon);
            } catch (Throwable t) {
                Hooks.log("live icons analyse failed: " + t);
                layout = NONE;
            }
            LAYOUTS.put(icon, layout);
            ANALYSING.remove(icon);
            if (layout != NONE) {
                MAIN.post(Hooks.guard("live icons ready", d::invalidateSelf));
            }
        });
    }

    /**
     * Finds the icon's dots: white blobs (the speaker, the inner and the outer wave) and one
     * red blob, on a black ground. Columns are counted in dot pitches from the leftmost dot:
     * the speaker is columns 0-5, the inner wave 7-8, the outer wave 9-11 (the app's drawing).
     * Anything else is not this icon.
     */
    static Layout analyse(Bitmap icon) {
        int w = icon.getWidth(), h = icon.getHeight();
        if (w < 48 || h < 48) {
            return NONE;
        }
        int[] px = new int[w * h];
        icon.getPixels(px, 0, w, 0, 0, w, h);
        byte[] kind = new byte[w * h]; // 1 white, 2 red
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int a = c >>> 24, r = (c >> 16) & 255, g = (c >> 8) & 255, bl = c & 255;
            if (a < 200) continue;
            if (r > 170 && g > 170 && bl > 170) kind[i] = 1;
            else if (r > 150 && g < 90 && bl < 90) kind[i] = 2;
        }
        List<float[]> white = new ArrayList<>();
        int reds = 0;
        int[] stack = new int[w * h];
        for (int i = 0; i < kind.length; i++) {
            if (kind[i] == 0) continue;
            byte k = kind[i];
            long sx = 0, sy = 0;
            int n = 0, top = 0;
            stack[top++] = i;
            kind[i] = 0;
            while (top > 0) {
                int p = stack[--top];
                int x = p % w, y = p / w;
                sx += x; sy += y; n++;
                if (x > 0 && kind[p - 1] == k) { kind[p - 1] = 0; stack[top++] = p - 1; }
                if (x < w - 1 && kind[p + 1] == k) { kind[p + 1] = 0; stack[top++] = p + 1; }
                if (y > 0 && kind[p - w] == k) { kind[p - w] = 0; stack[top++] = p - w; }
                if (y < h - 1 && kind[p + w] == k) { kind[p + w] = 0; stack[top++] = p + w; }
            }
            if (n < 3) continue;
            if (k == 2) reds++;
            else white.add(new float[] {sx / (float) n, sy / (float) n, n});
        }
        if (reds != 1 || white.size() < 55 || white.size() > 70) {
            return NONE;
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        double area = 0;
        for (float[] dot : white) {
            minX = Math.min(minX, dot[0]);
            minY = Math.min(minY, dot[1]);
            maxY = Math.max(maxY, dot[1]);
            area += dot[2];
        }
        // 13 rows: the speaker spans the full height.
        float pitch = (maxY - minY) / 12f;
        if (pitch < 2f) {
            return NONE;
        }
        List<Float> inner = new ArrayList<>(), outer = new ArrayList<>();
        int speaker = 0;
        for (float[] dot : white) {
            int col = Math.round((dot[0] - minX) / pitch);
            if (col <= 5) speaker++;
            else if (col == 7 || col == 8) { inner.add(dot[0]); inner.add(dot[1]); }
            else if (col >= 9 && col <= 11) { outer.add(dot[0]); outer.add(dot[1]); }
            else return NONE;
        }
        if (speaker != 48 || inner.size() != 2 * 5 || outer.size() != 2 * 9) {
            return NONE;
        }
        float r = (float) Math.sqrt(area / white.size() / Math.PI);
        return new Layout(toArray(inner), toArray(outer), r, w, h);
    }

    private static float[] toArray(List<Float> list) {
        float[] a = new float[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    private static Field field(Class<?> cls, String name) {
        Field f = XposedHelpers.findField(cls, name);
        f.setAccessible(true);
        return f;
    }

    /** Wave dot centres (x, y pairs) and radius, in icon bitmap pixels. */
    static final class Layout {
        final float[] inner;
        final float[] outer;
        final float r;
        final int w;
        final int h;

        Layout(float[] inner, float[] outer, float r, int w, int h) {
            this.inner = inner;
            this.outer = outer;
            this.r = r;
            this.w = w;
            this.h = h;
        }
    }
}
