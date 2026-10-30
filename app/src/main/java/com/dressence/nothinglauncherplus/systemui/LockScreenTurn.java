package com.dressence.nothinglauncherplus.systemui;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Test: the lock screen turns with the device while "Lock screen rotation (test)" is on, and
 * its landscape layout is written to the LSPosed log, for making it fit.
 *
 * <p>SystemUI's notification shade window (which holds the lock screen) follows the device
 * while the lock screen shows only if {@code KeyguardStateController
 * .isKeyguardScreenRotationAllowed()}; that is SystemUI's own switch for it (also set by the
 * lockscreen.rot_override property and config_enableLockScreenRotation), answered yes here.
 * In landscape SystemUI then uses its own split-shade lock screen (values-land), but Nothing's
 * clock and widgets have no landscape design, so each landscape layout is logged: every view
 * shown on the lock screen with where it is, and which lie off the screen or over another.
 * Nothing is changed in the layout.
 */
final class LockScreenTurn {

    private static final String CLS_KEYGUARD_STATE =
            "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl";
    private static final String CLS_SHADE_WINDOW =
            "com.android.systemui.shade.NotificationShadeWindowView";
    /** The lock screen's views, by resource name. */
    private static final String[] ROOTS = {"keyguard_root_view", "shared_notification_container"};
    private static final int MAX_DEPTH = 7;
    private static final int MAX_LINES = 90;
    /** Layouts logged per System UI run at most, so the log stays readable. */
    private static final int MAX_DUMPS = 12;

    private static int dumps;
    private static String lastLayout;

    private LockScreenTurn() {
    }

    static void install(ClassLoader cl, ConfigStore store) {
        Class<?> state = XposedHelpers.findClassIfExists(CLS_KEYGUARD_STATE, cl);
        if (state == null) {
            Hooks.log("lock screen rotation: no " + CLS_KEYGUARD_STATE);
            return;
        }
        int hooked = XposedBridge.hookAllMethods(state, "isKeyguardScreenRotationAllowed",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(param.getResult())) {
                            return;
                        }
                        store.reloadIfChanged();
                        if (store.get().lockRotation) {
                            param.setResult(true);
                        }
                    }
                }).size();
        Class<?> window = XposedHelpers.findClassIfExists(CLS_SHADE_WINDOW, cl);
        if (window != null) {
            XposedHelpers.findAndHookMethod(window, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    View root = (View) param.thisObject;
                    root.getViewTreeObserver().addOnGlobalLayoutListener(
                            Hooks.guard("lock screen layout", () -> {
                                if (store.get().lockRotation) {
                                    record(root);
                                }
                            })::run);
                }
            });
        }
        Hooks.log("lock screen rotation: " + hooked + " hooks, layout log "
                + (window != null ? "on" : "off"));
    }

    /** Logs the lock screen's layout if it shows in landscape and differs from the last one. */
    private static void record(View window) {
        if (dumps >= MAX_DUMPS || window.getResources().getConfiguration().orientation
                != Configuration.ORIENTATION_LANDSCAPE) {
            return;
        }
        StringBuilder out = new StringBuilder();
        int[] lines = {0};
        for (String name : ROOTS) {
            int id = window.getResources().getIdentifier(name, "id",
                    window.getContext().getPackageName());
            View v = id != 0 ? window.findViewById(id) : null;
            if (v != null && v.isShown()) {
                dump(window, v, 0, out, lines);
            }
        }
        if (out.length() == 0) {
            return; // No lock screen showing.
        }
        String layout = "window " + window.getWidth() + "x" + window.getHeight() + "\n" + out;
        if (layout.equals(lastLayout)) {
            return;
        }
        lastLayout = layout;
        dumps++;
        for (String line : layout.split("\n")) {
            Hooks.log("lock screen layout: " + line);
        }
    }

    private static void dump(View window, View v, int depth, StringBuilder out, int[] lines) {
        if (v.getVisibility() != View.VISIBLE || v.getWidth() <= 0 || v.getHeight() <= 0
                || v.getAlpha() == 0f || lines[0] >= MAX_LINES) {
            return;
        }
        Rect r = new Rect(0, 0, v.getWidth(), v.getHeight());
        try {
            ((ViewGroup) window).offsetDescendantRectToMyCoords(v, r);
        } catch (IllegalArgumentException notInside) {
            return;
        }
        boolean off = r.left < 0 || r.top < 0 || r.right > window.getWidth()
                || r.bottom > window.getHeight();
        for (int i = 0; i < depth; i++) {
            out.append("  ");
        }
        out.append(name(v)).append(' ').append(r.toShortString())
                .append(off ? " OFF-SCREEN" : "")
                .append(v.getScaleX() != 1f ? " scale " + v.getScaleX() : "")
                .append('\n');
        lines[0]++;
        if (depth < MAX_DEPTH && v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                dump(window, g.getChildAt(i), depth + 1, out, lines);
            }
        }
    }

    private static String name(View v) {
        String cls = v.getClass().getSimpleName();
        if (v.getId() == View.NO_ID) {
            return cls;
        }
        try {
            return v.getResources().getResourceEntryName(v.getId()) + ":" + cls;
        } catch (Resources.NotFoundException e) {
            return cls;
        }
    }
}
