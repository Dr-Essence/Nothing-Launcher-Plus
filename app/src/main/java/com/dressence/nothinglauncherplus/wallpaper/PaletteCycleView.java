package com.dressence.nothinglauncherplus.wallpaper;

import android.graphics.Rect;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * What Palette Cycle (2.3.1) shows of its pictures, and its colour cycling over time.
 *
 * <p>Framing: Palette Cycle draws a window of the picture (getOffsetImage: its saved pan,
 * moved sideways by parallax) stretched over the screen. That window is adjusted as it is
 * drawn, Palette Cycle's own pan and settings left as they are:
 * <ul>
 *   <li>in landscape its height on the picture follows the module's slider (top 0 to bottom
 *   100; Palette Cycle kept the portrait pan, usually the top, hiding water at the bottom);
 *   portrait is Palette Cycle's own;</li>
 *   <li>Monolith Plains and Winter Manor (VW3BASIC, V12BASIC) are only 345x455 scenes on a
 *   640x480 sheet in the web demo ("missing 640x480 LBM for december", its scenes.js): the
 *   rest is flat beige and black filler. The window is kept inside the real scene (3,24 to
 *   348,479; zooming in as little as needed), so the filler is never drawn.</li>
 * </ul>
 *
 * <p>Cycling: 2.3.1 works the cycles' time in single-precision floats, which lose steps once
 * the wallpaper has run a few hours (milliseconds past 2^24), so the colours stutter and
 * stop. Its author moved it to doubles on 2026-10-31 (commit 8fbddbb, "use more precise time
 * tracking", unreleased); Cycle.getCycleAmount does the same here.
 */
final class PaletteCycleView {

    private static final String CLS_CHANGING =
            "rak.pixellwp.cycling.wallpaperService.ImageChangingKt";
    private static final String CLS_CYCLE = "rak.pixellwp.cycling.models.Cycle";
    private static final Rect SCENE_ONLY = new Rect(3, 24, 348, 479);

    private PaletteCycleView() {
    }

    static void install(ClassLoader cl, ConfigStore store) {
        XposedBridge.hookAllMethods(XposedHelpers.findClass(CLS_CHANGING, cl), "getOffsetImage",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object result = param.getResult();
                        if (!(result instanceof Rect)) {
                            return;
                        }
                        try {
                            param.setResult(frame(param.args[0], (Rect) result,
                                    store.get().paletteCycleLandscapeY));
                        } catch (Throwable t) {
                            // Palette Cycle's own window stands.
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(CLS_CYCLE, cl, "getCycleAmount", int.class,
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        return cycleAmount(param.thisObject, (Integer) param.args[0]);
                    }
                });
        Hooks.log("palette cycle view: ready");
    }

    /** The window to draw, from Palette Cycle's own {@code window}. */
    private static Rect frame(Object engine, Rect window, int landscapeY) {
        Object image = XposedHelpers.getObjectField(
                XposedHelpers.getObjectField(engine, "drawRunner"), "image");
        int imageW = (Integer) XposedHelpers.callMethod(image, "getImageWidth");
        int imageH = (Integer) XposedHelpers.callMethod(image, "getImageHeight");
        Rect screen = (Rect) XposedHelpers.getObjectField(engine, "screenDimensions");
        Object info = XposedHelpers.getObjectField(engine, "currentImage");
        String picture = info == null ? "" : (String) XposedHelpers.getObjectField(info,
                "imageId");
        boolean sceneOnly = ("VW3BASIC".equals(picture) || "V12BASIC".equals(picture))
                && imageW == 640 && imageH == 480;
        boolean landscape = screen != null && screen.width() > screen.height();
        if (!sceneOnly && !landscape) {
            return window; // Portrait: Palette Cycle's own.
        }
        Rect area = sceneOnly ? SCENE_ONLY : new Rect(0, 0, imageW, imageH);
        float w = window.width();
        float h = window.height();
        float k = Math.min(1f, Math.min(area.width() / w, area.height() / h));
        w *= k;
        h *= k;
        // Where Palette Cycle's window sits across the picture, kept across the area.
        float fx = imageW > window.width()
                ? (float) window.left / (imageW - window.width()) : 0.5f;
        float fy = landscape ? landscapeY / 100f
                : imageH > window.height() ? (float) window.top / (imageH - window.height())
                : 0.5f;
        fx = Math.max(0f, Math.min(1f, fx));
        fy = Math.max(0f, Math.min(1f, fy));
        int left = Math.round(area.left + fx * (area.width() - w));
        int top = Math.round(area.top + fy * (area.height() - h));
        return new Rect(left, top, left + Math.round(w), top + Math.round(h));
    }

    /** Cycle.getCycleAmount in doubles, as the author's fix does. */
    private static float cycleAmount(Object cycle, int timePassed) {
        int rate = XposedHelpers.getIntField(cycle, "rate");
        int reverse = XposedHelpers.getIntField(cycle, "reverse");
        int low = XposedHelpers.getIntField(cycle, "low");
        int high = XposedHelpers.getIntField(cycle, "high");
        int size = high - low + 1;
        double t = timePassed / (1000 / (rate / 280.0));
        double amount = 0;
        if (reverse < 3) {
            amount = mod(t, size);
        } else if (reverse == 3) {
            amount = mod(t, size * 2);
            if (amount >= size) {
                amount = size * 2 - amount;
            }
        } else if (reverse < 6) {
            amount = mod(t, size);
            amount = Math.sin(amount * Math.PI * 2 / size) + 1;
            if (reverse == 4) {
                amount *= size / 4;
            } else if (reverse == 5) {
                amount *= size / 2;
            }
        }
        return (float) amount;
    }

    private static double mod(double a, int b) {
        return (Math.floor(a * 100) % Math.floor(b * 100.0)) / 100;
    }
}
