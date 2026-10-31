package com.dressence.nothinglauncherplus.launcher;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.view.View;
import android.view.ViewGroup;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Nothing's widget picker (NtWidgetsFullSheet) on the turned home screen.
 *
 * <p>Its sizes come from one profile ({@code za.a0}): fractions of the screen's width and
 * height, worked out once from the window's size when the picker is first opened and kept for
 * the launcher's life ({@code a0.N}). A profile made in one orientation was used in the other,
 * and Nothing's options sheet (a widget's previews and sizes, NtWidgetPickerDetails) is half
 * the screen's height ({@code m}), which in portrait is 1230 px and turned 568: its previews
 * ran off the bottom (DebuG report: sheet from y 556, previews to 1080, sizes and dots off the
 * screen). Here Nothing keeps one profile for each orientation, each made by Nothing's own
 * constructor for that orientation, and turned, the options sheet takes the screen's height
 * under the status bar, as the picker's list does. The list's page indicator sits over the
 * gesture bar plus 16dp (Nothing's); turned it takes the gesture bar's room only, at the
 * sheet's bottom instead of over its second row of widgets (928..993 of 1080).
 */
final class WidgetPickerLandscape {

    private static final String PROFILE = "za.a0";
    private static final String SHEET = "com.nothing.launcher.widgets.view.NtWidgetsFullSheet";

    /** Nothing's picker profile for each orientation, made by Nothing (portrait, landscape). */
    private static final Object[] PROFILES = new Object[2];

    private final ClassLoader cl;
    private final ConfigStore store;

    WidgetPickerLandscape(ClassLoader cl, ConfigStore store) {
        this.cl = cl;
        this.store = store;
    }

    void install() {
        Class<?> profile = XposedHelpers.findClass(PROFILE, cl);
        Class<?> cache = XposedHelpers.findClass(PROFILE + "$a", cl);
        // The cached profile, for the orientation the picker opens in.
        XposedBridge.hookAllMethods(cache, "c", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!(param.args.length == 1 && param.args[0] instanceof Context)) {
                        return;
                    }
                    int now = landscape((Context) param.args[0]) ? 1 : 0;
                    Object cached = XposedHelpers.getStaticObjectField(profile, "N");
                    if (cached == null) {
                        return;
                    }
                    int made = isLandscape(cached) ? 1 : 0;
                    if (made == now) {
                        return;
                    }
                    PROFILES[made] = cached;
                    // Nothing makes the one for now when there is none yet.
                    XposedHelpers.setStaticObjectField(profile, "N", PROFILES[now]);
                } catch (Throwable t) {
                    Hooks.log("widget picker profile: " + t);
                }
            }
        });
        // Turned: the options sheet as tall as the screen under the status bar.
        XposedBridge.hookAllConstructors(profile, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object p = param.thisObject;
                    if (!store.get().allowRotation || !isLandscape(p)) {
                        return;
                    }
                    int height = XposedHelpers.getIntField(p, "d");
                    float handle = XposedHelpers.getFloatField(p, "l");
                    Insets insets = (Insets) XposedHelpers.getObjectField(p, "e");
                    int sheet = height - (insets != null ? insets.top : 0);
                    XposedHelpers.setIntField(p, "m", sheet);
                    XposedHelpers.setFloatField(p, "n", sheet / (height - handle));
                    Hooks.log("widget picker, turned: options sheet " + sheet + " high (Nothing's "
                            + (int) ((height + handle) * 0.5f) + ")");
                } catch (Throwable t) {
                    Hooks.log("widget picker profile: " + t);
                }
            }
        });
        // Turned: the list's page indicator at the sheet's bottom, over the gesture bar.
        XposedBridge.hookAllMethods(XposedHelpers.findClass(SHEET, cl), "setInsets",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            View sheet = (View) param.thisObject;
                            if (!store.get().allowRotation || !landscape(sheet.getContext())) {
                                return;
                            }
                            View indicator = (View) XposedHelpers.callMethod(sheet,
                                    "getPageIndicator");
                            if (indicator == null || !(indicator.getLayoutParams()
                                    instanceof ViewGroup.MarginLayoutParams)) {
                                return;
                            }
                            ViewGroup.MarginLayoutParams lp =
                                    (ViewGroup.MarginLayoutParams) indicator.getLayoutParams();
                            int bottom = XposedHelpers.getIntField(sheet, "mBottomPadding");
                            if (lp.bottomMargin == bottom) {
                                return;
                            }
                            lp.bottomMargin = bottom;
                            XposedHelpers.setIntField(sheet, "pageIndicatorDisappearedDistance",
                                    bottom + XposedHelpers.getIntField(sheet, "indicatorHeight"));
                            indicator.setLayoutParams(lp);
                        } catch (Throwable t) {
                            Hooks.log("widget picker indicator: " + t);
                        }
                    }
                });
        Hooks.log("widget picker landscape: ready");
    }

    private static boolean isLandscape(Object profile) {
        return XposedHelpers.getIntField(profile, "c") > XposedHelpers.getIntField(profile, "d");
    }

    private static boolean landscape(Context context) {
        return context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }
}
