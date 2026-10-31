package com.dressence.nothinglauncherplus.systemui;

import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.View;
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
    private static final String PROXIMITY = "com.android.systemui.classifier.ProximityClassifier";
    private static final String PANEL = "com.android.systemui.shade.NotificationPanelViewController";
    private static final String SCRIM_STATE = "com.android.systemui.statusbar.phone.ScrimState";
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
                    thresholds(param.thisObject, store.get().lockRotation);
                } catch (Throwable t) {
                    // The thresholds System UI made stand.
                }
            }
        };
        Class<?> distance = XposedHelpers.findClass(DISTANCE, cl);
        XposedBridge.hookAllMethods(distance, "calculateFalsingResult", thresholds);
        XposedBridge.hookAllMethods(distance, "isLongSwipe", thresholds);
        // The proximity sensor sits at the top edge in portrait: held turned, the hand holding
        // that edge covers it, and ProximityClassifier then takes any swipe that is not a long
        // one (80% of the screen) for a pocket touch (DebuG reports: percentInProximity 1.0 on
        // full swipes down and up to the PIN page, rejected; a rejected pull down left quick
        // settings open alone). Turned, the grip is not a pocket: that check passes, as it
        // does for an uncovered sensor; every other check and Nothing's pocket mode stand.
        XposedBridge.hookAllMethods(XposedHelpers.findClass(PROXIMITY, cl),
                "calculateFalsingResult", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!store.get().lockRotation || !turned()) {
                                return;
                            }
                            Class<?> result = ((java.lang.reflect.Method) param.method)
                                    .getReturnType();
                            param.setResult(XposedHelpers.callStaticMethod(result, "passed",
                                    0.5d));
                        } catch (Throwable t) {
                            // System UI's check stands.
                        }
                    }
                });

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
        pinPage(cl, store);
        tracePulls(cl, store);
        Hooks.log("landscape shade: ready");
    }

    /**
     * The PIN page's backdrop. Nothing draws it on the scrim that covers the screen: in portrait
     * (full width, mClipQsScrim) that is the notifications scrim, at Nothing's see-through
     * alpha (0.54, ScrimController's TRANSPARENT_BOUNCER_SCRIM_ALPHA) in its bouncer colour,
     * with the blur behind it, while the behind scrim is clipped to quick settings (none on the
     * PIN page). Turned (split shade, not full width) the behind scrim covers the screen and the
     * notifications scrim only the notification column, so the PIN page's backdrop was the
     * behind scrim's: opaque black (DebuG report: BOUNCER, behind alpha 1.0 tint 0xff000000,
     * notifications 0.54 in 419..1180, blur 0, as the scrims counted as opaque). While the lock
     * screen turns, BOUNCER puts the backdrop on the scrim that covers the screen there: the
     * behind scrim gets the notifications scrim's alpha and colour, and the notifications
     * scrim, which would cover only the column, none. System UI then blurs behind it as it
     * does in portrait.
     */
    private static void pinPage(ClassLoader cl, ConfigStore store) {
        Class<?> states = XposedHelpers.findClass(SCRIM_STATE, cl);
        Object bouncer = XposedHelpers.getStaticObjectField(states, "BOUNCER");
        XposedBridge.hookAllMethods(bouncer.getClass(), "prepare", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object state = param.thisObject;
                    if (!store.get().lockRotation
                            || XposedHelpers.getBooleanField(state, "mClipQsScrim")) {
                        return;
                    }
                    XposedHelpers.setFloatField(state, "mBehindAlpha",
                            XposedHelpers.getFloatField(state, "mNotifAlpha"));
                    XposedHelpers.setIntField(state, "mBehindTint",
                            XposedHelpers.getIntField(state, "mNotifTint"));
                    XposedHelpers.setFloatField(state, "mNotifAlpha", 0f);
                } catch (Throwable t) {
                    // Nothing's backdrop stands.
                }
            }
        });
    }

    /** The turned lock screen's pull downs being traced (main thread), to name what blocks one. */
    private static String pullTrace;

    /**
     * Diagnostic, while the lock screen's pull down works only from the status bar: a pull from
     * the middle reached the panel (NPVC onTouch) and never System UI's drag-down helper, which
     * sees only what the shade window intercepts (DebuG report: no "drag down has started").
     * For each touch on the turned lock screen it logs, once, what turned the window's intercept
     * off (requestDisallowInterceptTouchEvent, with its caller) and what the drag-down helper
     * made of the first move. Logged with diagnostic logging on; to go once the cause is fixed.
     */
    private static void tracePulls(ClassLoader cl, ConfigStore store) {
        XposedHelpers.findAndHookMethod(android.view.ViewGroup.class,
                "requestDisallowInterceptTouchEvent", boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!(Boolean) param.args[0] || pullTrace == null
                                || pullTrace.contains("blocked")) {
                            return;
                        }
                        StringBuilder by = new StringBuilder();
                        StackTraceElement[] stack = new Throwable().getStackTrace();
                        for (int i = 0, n = 0; i < stack.length && n < 4; i++) {
                            String c = stack[i].getClassName();
                            if (c.startsWith("de.robv") || c.startsWith("LSP")
                                    || c.contains("nothinglauncherplus") || c.startsWith("java.")
                                    || c.equals("android.view.ViewGroup")) {
                                continue;
                            }
                            by.append(n++ == 0 ? "" : " < ").append(c.substring(
                                    c.lastIndexOf('.') + 1)).append('.')
                                    .append(stack[i].getMethodName());
                        }
                        pullTrace += ", blocked by " + param.thisObject.getClass().getSimpleName()
                                + " (" + by + ")";
                    }
                });
        XposedBridge.hookAllMethods(XposedHelpers.findClass(
                "com.android.systemui.statusbar.DragDownHelper", cl), "onInterceptTouchEvent",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!store.get().lockRotation || !store.get().diagnosticLogging
                                    || !turned()) {
                                pullTrace = null;
                                return;
                            }
                            android.view.MotionEvent e = (android.view.MotionEvent) param.args[0];
                            int action = e.getActionMasked();
                            if (action == android.view.MotionEvent.ACTION_DOWN) {
                                pullTrace = "touch at " + (int) e.getRawX() + "," + (int) e.getRawY();
                            } else if (action == android.view.MotionEvent.ACTION_MOVE
                                    && pullTrace != null && !pullTrace.contains("first move")) {
                                pullTrace += ", first move seen by the drag-down helper"
                                        + (Boolean.TRUE.equals(param.getResult()) ? " (taken)" : "");
                            }
                        } catch (Throwable t) {
                            // Diagnostic only.
                        }
                    }
                });
        // Whether the shade window was asked to intercept the first move, and its answer.
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass(
                "com.android.systemui.shade.NotificationShadeWindowView", cl),
                "onInterceptTouchEvent", android.view.MotionEvent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String trace = pullTrace;
                        if (trace != null && !trace.contains("window asked")
                                && ((android.view.MotionEvent) param.args[0]).getActionMasked()
                                        == android.view.MotionEvent.ACTION_MOVE) {
                            pullTrace = trace + ", window asked on the first move: "
                                    + param.getResult();
                        }
                    }
                });
        // The gesture's end, wherever it went: the line is written once per touch.
        XposedHelpers.findAndHookMethod(android.view.ViewGroup.class, "dispatchTouchEvent",
                android.view.MotionEvent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String trace = pullTrace;
                        if (trace == null || !(param.thisObject instanceof android.view.ViewGroup)
                                || ((View) param.thisObject).getParent() instanceof View) {
                            return; // Only the window's root, once per event.
                        }
                        int action = ((android.view.MotionEvent) param.args[0]).getActionMasked();
                        if (action == android.view.MotionEvent.ACTION_DOWN) {
                            pullTrace = trace + ", taken by " + touchTargets(
                                    (android.view.ViewGroup) param.thisObject);
                            return;
                        }
                        if (action == android.view.MotionEvent.ACTION_UP
                                || action == android.view.MotionEvent.ACTION_CANCEL) {
                            pullTrace = null;
                            Hooks.log("lock screen pull: " + trace
                                    + (trace.contains("first move") ? "" : ", no move reached"
                                    + " the drag-down helper"));
                        }
                    }
                });
    }

    /** The views a touch down went to, from the window's root down (Android's touch targets). */
    private static String touchTargets(android.view.ViewGroup root) {
        StringBuilder chain = new StringBuilder();
        Object group = root;
        for (int depth = 0; depth < 12 && group instanceof android.view.ViewGroup; depth++) {
            Object target = XposedHelpers.getObjectField(group, "mFirstTouchTarget");
            if (target == null) {
                if (depth == 0) {
                    chain.append("the window itself (no view took it)");
                }
                break;
            }
            View child = (View) XposedHelpers.getObjectField(target, "child");
            String id = "";
            try {
                id = child.getId() != View.NO_ID
                        ? "#" + child.getResources().getResourceEntryName(child.getId()) : "";
            } catch (Throwable t) {
                // No name.
            }
            chain.append(depth == 0 ? "" : " > ").append(child.getClass().getSimpleName())
                    .append(id);
            group = child;
        }
        return chain.toString();
    }

    /**
     * Whether the falsing check's stored size (System UI's display metrics when it started)
     * is for the other orientation than the display has now.
     */
    private static boolean swapped(Object provider) {
        return display() != null && swapped(provider, turned());
    }

    static boolean swapped(Object provider, boolean turned) {
        boolean storedWide = XposedHelpers.getIntField(provider, "mWidthPixels")
                > XposedHelpers.getIntField(provider, "mHeightPixels");
        return turned != storedWide;
    }

    /** Whether the display is turned a quarter (landscape on this phone). */
    private static boolean turned() {
        Display display = display();
        if (display == null) {
            return false;
        }
        int rotation = display.getRotation();
        return rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
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
    static void thresholds(Object classifier, boolean lockRotation) {
        float[] f = factors();
        float width = (Integer) XposedHelpers.callMethod(classifier, "getWidthPixels");
        float height = (Integer) XposedHelpers.callMethod(classifier, "getHeightPixels");
        float xdpi = (Float) XposedHelpers.callMethod(classifier, "getXdpi");
        float ydpi = (Float) XposedHelpers.callMethod(classifier, "getYdpi");
        float verticalFling = Math.min(height * f[4], f[1] * ydpi);
        float verticalSwipe = Math.min(height * f[4], f[3] * ydpi);
        if (lockRotation && width > height) {
            // Turned, the screen is short: the same inches are almost half of it (496 px of
            // 1080, against 617 of 2400 in portrait), so a pull down from its middle was
            // rejected (DebuG report: 327 and 339 px pulls, 423 and 451 with their fling).
            // The vertical thresholds take the share of the screen's height they take in
            // portrait (the portrait height runs along the turned width, at the turned xdpi).
            float share = height / width;
            verticalFling = Math.min(width * f[4], f[1] * xdpi) * share;
            verticalSwipe = Math.min(width * f[4], f[3] * xdpi) * share;
        }
        XposedHelpers.setFloatField(classifier, "mHorizontalFlingThresholdPx",
                Math.min(width * f[4], f[0] * xdpi));
        XposedHelpers.setFloatField(classifier, "mVerticalFlingThresholdPx", verticalFling);
        XposedHelpers.setFloatField(classifier, "mHorizontalSwipeThresholdPx",
                Math.min(width * f[4], f[2] * xdpi));
        XposedHelpers.setFloatField(classifier, "mVerticalSwipeThresholdPx", verticalSwipe);
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
