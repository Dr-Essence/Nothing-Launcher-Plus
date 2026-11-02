package com.dressence.nothinglauncherplus.launcher;

import android.view.View;

import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Nothing's long-press menus (app icons, folders: NTPopupContainer) in landscape: their
 * portrait width, placed by the item held, and the held icon's copy over the icon.
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
    private static final String CLS_ARROW = "com.nothing.launcher.popup.NTArrowPopup";
    private static final String CLS_BUBBLE = "com.android.launcher3.BubbleTextView";

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
                        // The menu itself and its shortcut grid are match_parent in Nothing's
                        // layout (nt_popup_container): the grid's background spanned the
                        // turned screen behind the narrower rows (DebuG19). The menu is
                        // as wide as its rows.
                        android.view.ViewGroup.LayoutParams lp =
                                ((View) param.thisObject).getLayoutParams();
                        if (lp != null && lp.width != portrait) {
                            lp.width = portrait;
                        }
                    }
                }).run();
            }
        };
        XposedBridge.hookAllMethods(popup, "addDeepShortcutEntries", portraitWidth);
        XposedBridge.hookAllMethods(popup, "addSystemShortcutEntries", portraitWidth);
        // The two are private, so ART may inline them into their caller, past the hook.
        Hooks.deoptimizeAll(popup, "populateAndShowForView");

        Class<?> arrow = XposedHelpers.findClass(CLS_ARROW, cl);
        // Across: Nothing places its menu at the screen's left inset (orientAboutObject:
        // setX(insets.left)), right for a list as wide as the screen. At the portrait width in
        // landscape that left it at the far left, away from the item held (DebuG19: a folder
        // icon's menu over the left page edge, an app's in a folder far from it). In its own
        // placement, landscape only: centred on the item, inside the screen; above or below
        // stays Nothing's choice.
        XposedHelpers.findAndHookMethod(arrow, "orientAboutObject", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Hooks.guard("landscape menu place", () -> {
                    View menu = (View) param.thisObject;
                    View container = (View) XposedHelpers.callMethod(menu, "getPopupContainer");
                    int width = container.getWidth();
                    if (width <= container.getHeight()) {
                        return;
                    }
                    android.graphics.Rect target = (android.graphics.Rect)
                            XposedHelpers.getObjectField(menu, "mTempRect");
                    android.graphics.Rect insets = (android.graphics.Rect)
                            XposedHelpers.callMethod(container, "getInsets");
                    int menuWidth = menu.getMeasuredWidth();
                    int left = insets.left;
                    int right = width - insets.right - menuWidth;
                    if (target == null || menuWidth <= 0 || right < left) {
                        return;
                    }
                    menu.setX(Math.max(left, Math.min(right, target.centerX() - menuWidth / 2)));
                }).run();
            }
        });
        // The held icon's highlighted copy: Nothing places it at the icon less the drag
        // layer's top inset, which the layer adds to its children's margins; the layer adds
        // the left inset too (InsettableFrameLayout.setFrameLayoutChildInsets), which Nothing
        // does not take back. In portrait that inset is 0; turned, it is the camera cutout's
        // side, and the copy showed beside the icon, a second icon (DebuG19). Taken back the
        // same way, as the copy is made.
        XposedHelpers.findAndHookMethod(arrow, "createHighlightView",
                XposedHelpers.findClass(CLS_BUBBLE, cl), new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Hooks.guard("held icon copy", () -> {
                            View copy = (View) param.getResult();
                            if (copy == null) {
                                return;
                            }
                            View container = (View) XposedHelpers.callMethod(param.thisObject,
                                    "getPopupContainer");
                            android.graphics.Rect insets = (android.graphics.Rect)
                                    XposedHelpers.callMethod(container, "getInsets");
                            copy.setTranslationX(copy.getTranslationX() - insets.left);
                        }).run();
                    }
                });
    }
}
