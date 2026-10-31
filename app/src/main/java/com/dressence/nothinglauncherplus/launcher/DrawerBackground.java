package com.dressence.nothinglauncherplus.launcher;

import android.content.Context;
import android.graphics.Color;
import android.view.View;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * On phones the app drawer's background is the launcher scrim tinted with Nothing's drawer
 * scrim colour ({@code AllAppsState#getWorkspaceScrimColor}); scaling that colour's alpha lets
 * the wallpaper show through while the folder tiles stay opaque.
 */
final class DrawerBackground {

    private static final String CLS_ALL_APPS_STATE =
            "com.android.launcher3.uioverrides.states.AllAppsState";
    private static final String CLS_LAUNCHER = "com.android.launcher3.Launcher";
    private static final String CLS_LAUNCHER_STATE = "com.android.launcher3.LauncherState";
    /** Nothing's launcher colours (ThemeColors), and the drawer scrim colour it gives. */
    private static final String CLS_THEME_COLOURS = "p7.e";
    private static final String SCRIM_COLOUR = "nt_all_apps_scrim_color_nt_mono";

    static void install(ClassLoader cl, ConfigStore store) {
        Class<?> allAppsState = XposedHelpers.findClass(CLS_ALL_APPS_STATE, cl);
        Class<?> launcherClass = XposedHelpers.findClass(CLS_LAUNCHER, cl);
        boolean atSource = hookScrimColour(cl, store);
        if (!atSource) {
            XposedHelpers.findAndHookMethod(allAppsState, "getWorkspaceScrimColor",
                    launcherClass, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            int opacity = store.get().drawerOpacity;
                            if (opacity < 100) {
                                param.setResult(faded((Integer) param.getResult(), opacity));
                            }
                        }
                    });
        }

        // The drawer's search (Nothing's NTLauncherAllAppsContainerView) paints its own colours
        // over that scrim: its sheet (foregroundPaint), its scrim and header protection, and the
        // search results' background, all picked in updateCustomColor (and its constructor).
        // They take the drawer's opacity too, so the search does not rise as a solid black
        // panel over a see-through drawer.
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
        View results = (View) XposedHelpers.callMethod(container, "getSearchRecyclerView");
        if (results != null && results.getBackground() != null) {
            results.getBackground().mutate().setAlpha(255 * opacity / 100);
        }
        ((View) container).invalidate();
    }

    /**
     * The drawer's scrim colour where Nothing reads it (ThemeColors p7.e#e(Context):
     * nt_all_apps_scrim_color_nt_mono). Besides the drawer's background (AllAppsState), its
     * Compose search reads it directly: the search bar (DrawerSearchBar) paints it from the
     * bar's middle to its bottom, a band under the bar that rises with the keyboard (it is
     * padded by the keyboard's height), and the search page's theme background is that colour.
     * Faded here, all of them follow the drawer's opacity. The method is taken only when it
     * really gives that colour; otherwise the drawer's background is faded where
     * AllAppsState gives it, as before. False when not hooked.
     */
    private static boolean hookScrimColour(ClassLoader cl, ConfigStore store) {
        Class<?> colours = XposedHelpers.findClassIfExists(CLS_THEME_COLOURS, cl);
        java.lang.reflect.Method method = colours == null ? null
                : XposedHelpers.findMethodExactIfExists(colours, "e", Context.class);
        if (method == null || method.getReturnType() != int.class) {
            Hooks.log("drawer scrim colour: not found, background faded by its state");
            return false;
        }
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                int opacity = store.get().drawerOpacity;
                Context context = (Context) param.args[0];
                if (opacity >= 100 || context == null) {
                    return;
                }
                Hooks.guard("drawer scrim colour", () -> {
                    int id = context.getResources().getIdentifier(SCRIM_COLOUR, "color",
                            context.getPackageName());
                    int colour = (Integer) param.getResult();
                    if (id != 0 && context.getColor(id) == colour) {
                        param.setResult(faded(colour, opacity));
                    }
                }).run();
            }
        });
        return true;
    }

    private static int faded(int color, int opacity) {
        return (color & 0x00FFFFFF) | ((Color.alpha(color) * opacity / 100) << 24);
    }

    private DrawerBackground() {
    }
}
