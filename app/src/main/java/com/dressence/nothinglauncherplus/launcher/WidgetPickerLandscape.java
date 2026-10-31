package com.dressence.nothinglauncherplus.launcher;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Rect;
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
 * gesture bar plus 16dp (Nothing's), 87 px up turned, over the list's second row (928..993 of
 * 1080); turned it sits as near the sheet's bottom as in portrait (16 px).
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

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!(param.args.length == 1 && param.args[0] instanceof Context)
                            || param.getResult() == null) {
                        return;
                    }
                    // Nothing makes it from the application's window size, which just after a
                    // turn can still be the other orientation's (DebuG report: a portrait-sized
                    // profile on the turned picker, its options sheet shifted to y 556). Then
                    // Nothing's constructor makes it again from the picker's own screen.
                    Context context = (Context) param.args[0];
                    boolean now = landscape(context);
                    if (isLandscape(param.getResult()) == now) {
                        return;
                    }
                    Object made = XposedHelpers.newInstance(profile, context);
                    if (isLandscape(made) != now) {
                        return;
                    }
                    PROFILES[now ? 1 : 0] = made;
                    XposedHelpers.setStaticObjectField(profile, "N", made);
                    param.setResult(made);
                } catch (Throwable t) {
                    Hooks.log("widget picker profile: " + t);
                }
            }
        });
        // Turned: Nothing's constructor works on the portrait screen, so every card and preview
        // is its portrait size; then what places them on the screen is set for the turned one.
        XposedBridge.hookAllConstructors(profile, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                BUILDING.set(store.get().allowRotation ? new int[2] : null);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                int[] screen = BUILDING.get();
                BUILDING.remove();
                if (screen == null || screen[0] == 0) {
                    return; // Portrait (or rotation off): Nothing's as it is.
                }
                try {
                    turned(param.thisObject, screen[0], screen[1]);
                } catch (Throwable t) {
                    Hooks.log("widget picker profile: " + t);
                }
            }
        });
        // The window size Nothing's constructor reads: turned, the portrait one (its sizes).
        XposedHelpers.findAndHookMethod(android.view.WindowMetrics.class, "getBounds",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        int[] screen = BUILDING.get();
                        Rect bounds = (Rect) param.getResult();
                        if (screen == null || bounds == null
                                || bounds.width() <= bounds.height()) {
                            return;
                        }
                        screen[0] = bounds.width();
                        screen[1] = bounds.height();
                        param.setResult(new Rect(0, 0, bounds.height(), bounds.width()));
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
                            // As far from the sheet's bottom as in portrait (16 px there).
                            int bottom = Math.round(16 * sheet.getResources().getDisplayMetrics()
                                    .density / 3f);
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

    /** The window size seen by Nothing's constructor while it runs (turned: width, height). */
    private static final ThreadLocal<int[]> BUILDING = new ThreadLocal<>();

    /**
     * A profile Nothing made from the portrait screen, placed on the turned one ({@code width} x
     * {@code height}): Nothing's sizes stay (cards, previews, preview cells: fractions of the
     * portrait width and height); its screen size, the options sheet (the screen's height under
     * the status bar, its slide in from it), the heights' positions and the side margins that
     * center the content are worked out for the turned screen as Nothing works them out.
     */
    private static void turned(Object p, int width, int height) {
        int portraitWidth = XposedHelpers.getIntField(p, "c");
        int shift = (width - portraitWidth) / 2;
        XposedHelpers.setIntField(p, "c", width);
        XposedHelpers.setIntField(p, "d", height);
        // Nothing's height fractions, from the turned height.
        int top = Math.round(height * 0.122826084f);
        XposedHelpers.setIntField(p, "i", top);
        ((Rect) XposedHelpers.getObjectField(p, "I")).top = top;
        float handle = height * 0.052173913f;
        XposedHelpers.setFloatField(p, "l", handle);
        XposedHelpers.setFloatField(p, "G", height * 0.3326087f);
        Insets insets = (Insets) XposedHelpers.getObjectField(p, "e");
        int sheet = height - (insets != null ? insets.top : 0);
        XposedHelpers.setIntField(p, "m", sheet);
        XposedHelpers.setFloatField(p, "n", sheet / (height - handle));
        // The side margins that center the content, for the turned width.
        for (String name : new String[] {"B", "C", "D"}) {
            Rect margins = (Rect) XposedHelpers.getObjectField(p, name);
            margins.left += shift;
            margins.right += shift;
        }
        int sides = XposedHelpers.getIntField(p, "E") + shift;
        XposedHelpers.setIntField(p, "E", sides);
        XposedHelpers.setIntField(p, "H", (width - sides * 2) / 2);
        Rect padding = (Rect) XposedHelpers.getObjectField(p, "I");
        int cells = width - (padding.left + padding.right);
        XposedHelpers.setIntField(p, "J", cells);
        XposedHelpers.setIntField(p, "K", cells);
        XposedHelpers.callMethod(p, "e");
        Hooks.log("widget picker, turned: portrait sizes (cards " + XposedHelpers.getIntField(p,
                "r") + " px), centered on " + width + "x" + height + ", options sheet " + sheet
                + " high");
    }

    private static boolean isLandscape(Object profile) {
        return XposedHelpers.getIntField(profile, "c") > XposedHelpers.getIntField(profile, "d");
    }

    private static boolean landscape(Context context) {
        return context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }
}
