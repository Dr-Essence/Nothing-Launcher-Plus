package com.dressence.nothinglauncherplus.launcher;

import android.graphics.Color;
import android.view.View;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
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

        // The drawer's search (Nothing's NTLauncherAllAppsContainerView) paints its own colours
        // over that scrim: its sheet (foregroundPaint), its scrim and header protection, and the
        // search results' background, all picked in updateCustomColor (and its constructor).
        // They take the drawer's opacity too, so the search does not rise as a solid black
        // panel over a see-through drawer; so does Launcher3's strip under the search bar.
        Class<?> search = XposedHelpers.findClassIfExists(SmartDrawer.CLS_ALL_APPS_CONTAINER, cl);
        if (search != null) {
            XC_MethodHook colours = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Hooks.guard("drawer search colours", () ->
                            searchColours(param.thisObject, store.get().drawerOpacity)).run();
                }
            };
            XposedBridge.hookAllConstructors(search, colours);
            XposedBridge.hookAllMethods(search, "updateCustomColor", colours);
        }

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

    private static final String NAV_SCRIM = "nlplus.navScrimColor";

    /** Nothing's drawer search colours at the drawer's opacity ({@code opacity} percent). */
    private static void searchColours(Object container, int opacity) {
        if (opacity >= 100) {
            return;
        }
        android.graphics.Paint paint = (android.graphics.Paint) XposedHelpers.getObjectField(
                container, "foregroundPaint");
        if (paint != null) {
            paint.setColor(faded(paint.getColor(), opacity));
        }
        for (String name : new String[] {"mScrimColor", "mHeaderProtectionColor"}) {
            XposedHelpers.setIntField(container, name,
                    faded(XposedHelpers.getIntField(container, name), opacity));
        }
        // Launcher3's strip under the search bar, over the gesture bar (the navigation bar
        // scrim, mNavBarScrimPaint): set once, so faded from the colour it was given.
        android.graphics.Paint strip = (android.graphics.Paint) XposedHelpers.getObjectField(
                container, "mNavBarScrimPaint");
        if (strip != null) {
            Object given = XposedHelpers.getAdditionalInstanceField(container, NAV_SCRIM);
            if (given == null) {
                given = strip.getColor();
                XposedHelpers.setAdditionalInstanceField(container, NAV_SCRIM, given);
            }
            strip.setColor(faded((Integer) given, opacity));
        }
        View results = (View) XposedHelpers.callMethod(container, "getSearchRecyclerView");
        if (results != null && results.getBackground() != null) {
            results.getBackground().mutate().setAlpha(255 * opacity / 100);
        }
        ((View) container).invalidate();
    }

    private static int faded(int color, int opacity) {
        return (color & 0x00FFFFFF) | ((Color.alpha(color) * opacity / 100) << 24);
    }

    private DrawerBackground() {
    }
}
