package com.dressence.nothinglauncherplus.hook;

import android.graphics.Color;
import android.view.View;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * On phones the app drawer's background is the launcher scrim tinted with
 * {@code AllAppsState#getWorkspaceScrimColor}; scaling that colour's alpha lets the wallpaper
 * show through while the folder tiles stay opaque.
 */
final class DrawerBackground {

    private static final String CLS_ALL_APPS_STATE =
            "com.android.launcher3.uioverrides.states.AllAppsState";
    private static final String CLS_LAUNCHER = "com.android.launcher3.Launcher";
    private static final String CLS_LAUNCHER_STATE = "com.android.launcher3.LauncherState";

    static void install(ClassLoader cl, ConfigStore store) {
        Class<?> allAppsState = XposedHelpers.findClass(CLS_ALL_APPS_STATE, cl);
        Class<?> launcherClass = XposedHelpers.findClass(CLS_LAUNCHER, cl);
        XposedHelpers.findAndHookMethod(allAppsState, "getWorkspaceScrimColor", launcherClass,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        int opacity = store.get().drawerOpacity;
                        if (opacity < 100) {
                            int color = (Integer) param.getResult();
                            int alpha = Color.alpha(color) * opacity / 100;
                            param.setResult((color & 0x00FFFFFF) | (alpha << 24));
                        }
                    }
                });

        // A new opacity only reaches the scrim on the next state change; repaint an open drawer.
        Object allApps = XposedHelpers.getStaticObjectField(
                XposedHelpers.findClass(CLS_LAUNCHER_STATE, cl), "ALL_APPS");
        store.addChangeListener(() -> {
            Object launcher = XposedHelpers.getStaticObjectField(launcherClass, "ACTIVITY_TRACKER");
            launcher = XposedHelpers.callMethod(launcher, "getCreatedContext");
            if (launcher == null) {
                return;
            }
            Object state = XposedHelpers.callMethod(
                    XposedHelpers.callMethod(launcher, "getStateManager"), "getState");
            if (state == allApps) {
                View scrim = (View) XposedHelpers.callMethod(launcher, "getScrimView");
                scrim.setBackgroundColor((Integer) XposedHelpers.callMethod(
                        allApps, "getWorkspaceScrimColor", launcher));
            }
        });
    }

    private DrawerBackground() {
    }
}
