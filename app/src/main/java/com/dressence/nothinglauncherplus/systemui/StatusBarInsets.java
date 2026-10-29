package com.dressence.nothinglauncherplus.systemui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.View;

import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The status bar keeps its side padding through screen turns.
 *
 * <p>SystemUI works the padding out (StatusBarContentInsetsProviderImpl) from two things it
 * reads separately: the display's rotation, and the screen bounds of its configuration. While
 * the screen turns these can disagree for a moment (one already turned, the other not), and a
 * padding worked out then is wrong: the clock sat against the screen's edge. It was kept in
 * the provider's cache, and the status bar (PhoneStatusBarView) fetches it again only when the
 * rotation, bounds, cutout, density or font scale it noted change, so it stayed wrong until
 * the next turn. The home screen turning (this module) makes that moment far more common.
 *
 * <p>So a padding worked out while the two disagree is not kept, and a status bar that fetched
 * one fetches again once they agree. Each such moment is logged.
 */
final class StatusBarInsets {

    private static final String CLS_PROVIDER =
            "com.android.systemui.statusbar.layout.StatusBarContentInsetsProviderImpl";
    private static final String CLS_STATUS_BAR =
            "com.android.systemui.statusbar.phone.PhoneStatusBarView";
    /** How often, and how long apart, a status bar fetches again while the two disagree. */
    private static final int RETRIES = 10;
    private static final long RETRY_MS = 100;
    private static final String RETRYING = "nlplus.insetsRetrying";

    private StatusBarInsets() {
    }

    static void install(ClassLoader cl) {
        Class<?> provider = XposedHelpers.findClassIfExists(CLS_PROVIDER, cl);
        Class<?> statusBar = XposedHelpers.findClassIfExists(CLS_STATUS_BAR, cl);
        if (provider == null || statusBar == null) {
            Hooks.log("status bar insets: classes not found");
            return;
        }
        int cache = XposedBridge.hookAllMethods(provider, "getAndSetCalculatedAreaForRotation",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Context context = (Context) XposedHelpers.getObjectField(
                                param.thisObject, "context");
                        String state = midTurn(context);
                        if (state == null) {
                            return;
                        }
                        // Worked out as SystemUI does, but not kept.
                        param.setResult(XposedHelpers.callMethod(param.thisObject,
                                "getCalculatedAreaForRotation", param.args[1], param.args[0],
                                param.args[2]));
                        Hooks.log("status bar insets: not kept for rotation " + param.args[0]
                                + " while " + state);
                    }
                }).size();
        int fetch = XposedBridge.hookAllMethods(statusBar, "updateSafeInsets",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View view = (View) param.thisObject;
                        String state = midTurn(view.getContext());
                        if (state != null && XposedHelpers.getAdditionalInstanceField(view,
                                RETRYING) == null) {
                            Hooks.log("status bar insets: fetched while " + state
                                    + ", fetching again");
                            retry(view, RETRIES);
                        }
                    }
                }).size();
        Hooks.log("status bar insets: " + cache + " cache and " + fetch + " fetch hooks");
    }

    /** Fetches the padding again once rotation and bounds agree, {@code left} tries at most. */
    private static void retry(View view, int left) {
        XposedHelpers.setAdditionalInstanceField(view, RETRYING, Boolean.TRUE);
        view.postDelayed(Hooks.guard("status bar insets retry", () -> {
            if (midTurn(view.getContext()) != null && left > 1) {
                retry(view, left - 1);
                return;
            }
            try {
                XposedHelpers.callMethod(view, "updateSafeInsets");
            } finally {
                // After the fetch, so it does not start another round (a screen that never
                // agrees, after the last try, is left as it is).
                XposedHelpers.removeAdditionalInstanceField(view, RETRYING);
            }
        }), RETRY_MS);
    }

    /**
     * Null when the display's rotation and the configuration's screen bounds agree; else what
     * each says. A phone's screen is tall at rotation 0 and 2, wide at 1 and 3.
     */
    static String midTurn(Context context) {
        if (context.getDisplay() == null) {
            return null;
        }
        int rotation = context.getDisplay().getRotation();
        Configuration config = context.getResources().getConfiguration();
        Rect bounds = (Rect) XposedHelpers.callMethod(
                XposedHelpers.getObjectField(config, "windowConfiguration"), "getMaxBounds");
        if (bounds == null || bounds.isEmpty()) {
            return null;
        }
        boolean wide = bounds.width() > bounds.height();
        boolean turned = rotation == 1 || rotation == 3;
        return wide == turned ? null
                : "display rotation " + rotation + " and screen " + bounds.width() + "x"
                        + bounds.height();
    }
}
