package com.dressence.nothinglauncherplus.launcher;

import android.view.View;

import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Nothing's long-press menus (app icons, folders: NTPopupContainer) at their portrait width
 * in landscape.
 *
 * <p>Nothing makes each menu row as wide as the screen less its margins
 * ({@code populateAndShowForView}: mContainerWidth = the popup container's width - 2 x
 * popup_container_margin), a phone-wide list in portrait. Turned, that is the long side: rows
 * about 2300 px wide ran past the screen's edge (DebuG18: "Customise / Rename" on a folder,
 * "App info / Remove / Hide icon" in one). The width is handed to Nothing's own row builders
 * (addDeepShortcutEntries, addSystemShortcutEntries; the widget row reads the field); in
 * landscape they are given the width the screen's short side gives, as in portrait, and
 * Nothing builds and places the menu as it does there. Portrait is Nothing's own.
 */
final class PopupLandscape {

    private static final String CLS_POPUP = "com.nothing.launcher.popup.NTPopupContainer";

    private PopupLandscape() {
    }

    static void install(ClassLoader cl) {
        Class<?> popup = XposedHelpers.findClass(CLS_POPUP, cl);
        XC_MethodHook portraitWidth = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Hooks.guard("landscape menu width", () -> {
                    if (param.args.length == 0 || !(param.args[0] instanceof Integer)) {
                        return;
                    }
                    View container = (View) XposedHelpers.callMethod(param.thisObject,
                            "getPopupContainer");
                    int width = container.getWidth();
                    int height = container.getHeight();
                    if (width <= height || height <= 0) {
                        return; // Portrait: Nothing's own width.
                    }
                    int margin = container.getResources().getDimensionPixelSize(
                            container.getResources().getIdentifier("popup_container_margin",
                                    "dimen", container.getContext().getPackageName()));
                    int portrait = height - 2 * margin;
                    if (portrait > 0 && (Integer) param.args[0] > portrait) {
                        param.args[0] = portrait;
                        XposedHelpers.setIntField(param.thisObject, "mContainerWidth", portrait);
                    }
                }).run();
            }
        };
        XposedBridge.hookAllMethods(popup, "addDeepShortcutEntries", portraitWidth);
        XposedBridge.hookAllMethods(popup, "addSystemShortcutEntries", portraitWidth);
        // The two are private, so ART may inline them into their caller, past the hook.
        Hooks.deoptimizeAll(popup, "populateAndShowForView");
    }
}
