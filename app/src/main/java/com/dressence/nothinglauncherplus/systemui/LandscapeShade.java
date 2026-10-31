package com.dressence.nothinglauncherplus.systemui;

import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.Surface;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The shade over the turned lock screen.
 *
 * <p>Swipes: System UI's falsing check (BrightLine) takes the screen's size and dpi once, from
 * the portrait display (DebuG report: width 1080, height 2400 while turned), and its
 * DistanceClassifier sets its swipe thresholds from them once. Turned, a vertical swipe runs
 * along the panel's x axis, but was measured against the y axis' dpi: a swipe and fling of 530
 * or 606 px was rejected against 617 px where the turned screen's own threshold is 496. So the
 * pull down on the lock screen was aborted as a false touch ("The drag down was aborted"),
 * System UI opened its quick settings on the lock screen instead, and the rejected swipes
 * built up a "bad history" that rejected more. While the lock screen turns, the falsing
 * check's screen size and dpi follow the display's rotation, and the distance thresholds are
 * worked out from them as System UI works them out (same settings, same formula).
 *
 * <p>Leaving: with quick settings open over the lock screen (split shade, the lock screen
 * itself showing), Nothing leaves them open whenever the panel closes (a swipe up closes it
 * and opens the bouncer) and on back: its closeQsIfPossible skips split shade while the shade
 * counts as fully expanded, which on the lock screen is whenever quick settings are. When the
 * bouncer was put away, the lock screen came back with them still open (logged: panel CLOSED,
 * then OPEN again with qsExpanded=true). There, as Nothing does when split shade goes back to
 * the lock screen (its StatusBarStateListener), they are closed.
 */
final class LandscapeShade {

    private static final String PROVIDER = "com.android.systemui.classifier.FalsingDataProvider";
    private static final String DISTANCE = "com.android.systemui.classifier.DistanceClassifier";
    private static final String PANEL = "com.android.systemui.shade.NotificationPanelViewController";
    private static final int KEYGUARD = 1;
    private static final int PANEL_CLOSED = 0;

    /** DistanceClassifier's settings: fling and swipe inches, screen fraction. */
    private static float[] factors;
    private static DisplayManager displays;

    private LandscapeShade() {
    }

    static void install(ClassLoader cl, ConfigStore store) {
        Class<?> provider = XposedHelpers.findClass(PROVIDER, cl);
        XC_MethodHook turned = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!store.get().lockRotation || !swapped(param.thisObject)) {
                        return;
                    }
                    Object p = param.thisObject;
                    switch (param.method.getName()) {
                        case "getWidthPixels":
                            param.setResult(XposedHelpers.getIntField(p, "mHeightPixels"));
                            break;
                        case "getHeightPixels":
                            param.setResult(XposedHelpers.getIntField(p, "mWidthPixels"));
                            break;
                        case "getXdpi":
                            param.setResult(XposedHelpers.getFloatField(p, "mYdpi"));
                            break;
                        default:
                            param.setResult(XposedHelpers.getFloatField(p, "mXdpi"));
                            break;
                    }
                } catch (Throwable t) {
                    // System UI's own values stand.
                }
            }
        };
        for (String name : new String[] {"getWidthPixels", "getHeightPixels", "getXdpi",
                "getYdpi"}) {
            XposedHelpers.findAndHookMethod(provider, name, turned);
        }
        XC_MethodHook thresholds = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    thresholds(param.thisObject);
                } catch (Throwable t) {
                    // The thresholds System UI made stand.
                }
            }
        };
        Class<?> distance = XposedHelpers.findClass(DISTANCE, cl);
        XposedBridge.hookAllMethods(distance, "calculateFalsingResult", thresholds);
        XposedBridge.hookAllMethods(distance, "isLongSwipe", thresholds);

        Class<?> panel = XposedHelpers.findClass(PANEL, cl);
        XposedHelpers.findAndHookMethod(panel, "closeQsIfPossible", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (closeOverLockScreen(param.thisObject, store, "back")) {
                    param.setResult(null);
                }
            }
        });
        XposedHelpers.findAndHookMethod(panel, "onPanelStateChanged", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if ((Integer) param.args[0] == PANEL_CLOSED) {
                            closeOverLockScreen(param.thisObject, store, "panel closed");
                        }
                    }
                });
        Hooks.log("landscape shade: ready");
    }

    /**
     * Whether the falsing check's stored size (System UI's display metrics when it started)
     * is for the other orientation than the display has now.
     */
    private static boolean swapped(Object provider) {
        Display display = display();
        if (display == null) {
            return false;
        }
        return swapped(provider, display.getRotation());
    }

    static boolean swapped(Object provider, int rotation) {
        boolean turned = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        boolean storedWide = XposedHelpers.getIntField(provider, "mWidthPixels")
                > XposedHelpers.getIntField(provider, "mHeightPixels");
        return turned != storedWide;
    }

    private static Display display() {
        if (displays == null) {
            android.app.Application app = android.app.AndroidAppHelper.currentApplication();
            if (app == null) {
                return null;
            }
            displays = app.getSystemService(DisplayManager.class);
        }
        return displays != null ? displays.getDisplay(Display.DEFAULT_DISPLAY) : null;
    }

    /** DistanceClassifier's thresholds, from the screen as it is now (its constructor's sums). */
    static void thresholds(Object classifier) {
        float[] f = factors();
        float width = (Integer) XposedHelpers.callMethod(classifier, "getWidthPixels");
        float height = (Integer) XposedHelpers.callMethod(classifier, "getHeightPixels");
        float xdpi = (Float) XposedHelpers.callMethod(classifier, "getXdpi");
        float ydpi = (Float) XposedHelpers.callMethod(classifier, "getYdpi");
        XposedHelpers.setFloatField(classifier, "mHorizontalFlingThresholdPx",
                Math.min(width * f[4], f[0] * xdpi));
        XposedHelpers.setFloatField(classifier, "mVerticalFlingThresholdPx",
                Math.min(height * f[4], f[1] * ydpi));
        XposedHelpers.setFloatField(classifier, "mHorizontalSwipeThresholdPx",
                Math.min(width * f[4], f[2] * xdpi));
        XposedHelpers.setFloatField(classifier, "mVerticalSwipeThresholdPx",
                Math.min(height * f[4], f[3] * ydpi));
    }

    /** The settings DistanceClassifier reads (its defaults when they cannot be read). */
    private static float[] factors() {
        if (factors == null) {
            // As DistanceClassifier reads them, the vertical swipe from the horizontal key.
            factors = new float[] {
                    setting("brightline_falsing_distance_horizontal_fling_threshold_in", 1f),
                    setting("brightline_falsing_distance_vertical_fling_threshold_in", 1.5f),
                    setting("brightline_falsing_distance_horizontal_swipe_threshold_in", 3f),
                    setting("brightline_falsing_distance_horizontal_swipe_threshold_in", 3f),
                    setting("brightline_falsing_distance_screen_fraction_max_distance", 0.8f)};
        }
        return factors;
    }

    private static float setting(String name, float fallback) {
        try {
            return (Float) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.provider.DeviceConfig", null), "getFloat",
                    "systemui", name, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * Quick settings open over the lock screen itself, in split shade: closed as Nothing
     * closes them when split shade goes back to the lock screen. True when closed here.
     */
    static boolean closeOverLockScreen(Object panel, ConfigStore store, String why) {
        try {
            if (!store.get().lockRotation
                    || XposedHelpers.getIntField(panel, "mBarState") != KEYGUARD
                    || !XposedHelpers.getBooleanField(panel, "mSplitShadeEnabled")) {
                return false;
            }
            Object qs = XposedHelpers.getObjectField(panel, "mQsController");
            if (qs == null || !(Boolean) XposedHelpers.callMethod(qs, "getExpanded")) {
                return false;
            }
            XposedHelpers.callMethod(qs, "closeQs");
            if (store.get().diagnosticLogging) {
                Hooks.log("landscape shade: quick settings over the lock screen closed (" + why
                        + ")");
            }
            return true;
        } catch (Throwable t) {
            Hooks.log("landscape shade: " + t);
            return false;
        }
    }
}
