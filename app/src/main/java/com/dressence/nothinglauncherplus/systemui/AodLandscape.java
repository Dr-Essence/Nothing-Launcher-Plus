package com.dressence.nothinglauncherplus.systemui;

import android.view.View;
import android.view.ViewGroup;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The always-on display on the turned lock screen. Its burn-in movement (AodBurnInViewModel,
 * applied by KeyguardRootViewBinder as translationY to the burn-in layer, the large clock, the
 * date slice, the AOD notification icons and Nothing's widget container) moves those views
 * down within Nothing's burn_in_prevention_offset_y (100dp; AOSP's is far smaller), sized for
 * the portrait screen. Turned, the lock screen's content already fills the height (DebuG6:
 * clock, date and widgets moved 416 px down, the widgets cut off at the bottom edge). In
 * landscape the movement's vertical offset is kept, where the BurnInModel is made, within the
 * room those views leave in the lock screen, so they keep drifting against burn-in without
 * leaving the screen. Portrait is unchanged.
 */
final class AodLandscape {

    private static final String MODEL = "com.android.systemui.keyguard.shared.model.BurnInModel";
    private static final String ROOT = "com.android.systemui.keyguard.ui.view.KeyguardRootView";
    /** The views the burn-in movement moves (KeyguardRootViewBinder), by their ids. */
    private static final String[] MOVED = {"lockscreen_clock_view", "keyguard_slice_view",
            "aod_notification_icon_container", "nt_widget_host_view_container"};

    private static volatile WeakReference<View> root = new WeakReference<>(null);
    private static volatile String lastLog;

    private AodLandscape() {
    }

    static void install(ClassLoader cl, ConfigStore store) {
        XposedBridge.hookAllConstructors(XposedHelpers.findClass(ROOT, cl), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                root = new WeakReference<>((View) param.thisObject);
            }
        });
        XposedHelpers.findAndHookConstructor(XposedHelpers.findClass(MODEL, cl), int.class,
                int.class, float.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int y = (Integer) param.args[1];
                        if (y == 0) {
                            return;
                        }
                        try {
                            if (!store.get().lockRotation) {
                                return;
                            }
                            View view = root.get();
                            int[] room = room(view);
                            if (room == null) {
                                return;
                            }
                            int kept = Math.max(-room[0], Math.min(room[1], y));
                            if (kept != y) {
                                param.args[1] = kept;
                                String line = "always-on display: burn-in offset " + y
                                        + " kept to " + kept + " (room up " + room[0]
                                        + ", down " + room[1] + ")";
                                if (store.get().diagnosticLogging && !line.equals(lastLog)) {
                                    lastLog = line;
                                    Hooks.log(line);
                                }
                            }
                        } catch (Throwable t) {
                            // Nothing's offset stands.
                        }
                    }
                });
        Hooks.log("always-on display: ready");
    }

    /**
     * In landscape, how far the moved views can go up and down and stay inside the lock
     * screen (px; up to its top, down to its bottom); null when not turned or not laid out.
     */
    static int[] room(View view) {
        if (!(view instanceof ViewGroup) || view.getWidth() <= view.getHeight()
                || view.getHeight() <= 0) {
            return null;
        }
        int top = Integer.MAX_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (String name : MOVED) {
            int id = view.getResources().getIdentifier(name, "id",
                    view.getContext().getPackageName());
            View v = id != 0 ? view.findViewById(id) : null;
            if (v == null || v.getVisibility() != View.VISIBLE || v.getHeight() <= 0) {
                continue;
            }
            top = Math.min(top, v.getTop());
            bottom = Math.max(bottom, v.getBottom());
        }
        if (top == Integer.MAX_VALUE) {
            return null;
        }
        return new int[] {Math.max(0, top), Math.max(0, view.getHeight() - bottom)};
    }
}
