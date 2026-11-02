package com.dressence.nothinglauncherplus.systemui;

import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewParent;
import android.widget.TextView;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.NdotFont;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** The battery icon and percentage lit green, the percentage in NDot. */
final class Battery {

    private static final String CLS_BATTERY = "com.android.systemui.battery.BatteryMeterView";

    /** Light to the green, dark to dark: the battery re-lit as a whole. */
    private static final Paint GREEN_LAYER = new Paint();

    static {
        GREEN_LAYER.setColorFilter(new ColorMatrixColorFilter(new float[] {
                0.09f, 0.18f, 0.03f, 0, 0,
                0.30f, 0.59f, 0.11f, 0, 0,
                0.14f, 0.28f, 0.05f, 0, 0,
                0, 0, 0, 1, 0}));
    }

    private Battery() {
    }

    static void install(ClassLoader cl, ConfigStore store) {
        filterBatteryViews(store);
        Class<?> battery = XposedHelpers.findClass(CLS_BATTERY, cl);
        // The battery drawn green on every background, its empty part a dim green.
        XposedBridge.hookAllMethods(battery, "updateColors", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!store.get().ndotStatusBar) {
                    return;
                }
                for (int i = 0; i < param.args.length; i++) {
                    if (param.args[i] instanceof Integer) {
                        param.args[i] = i == 1 ? 0x554CFF7A : Led.GREEN;
                    }
                }
            }
        });
        XC_MethodHook percent = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                store.reloadIfChanged();
                if (store.get().ndotStatusBar) {
                    lightPercent(param.thisObject);
                }
            }
        };
        XposedBridge.hookAllMethods(battery, "updateShowPercent", percent);
        XposedBridge.hookAllMethods(battery, "updatePercentText", percent);
        XposedBridge.hookAllMethods(battery, "onDarkChanged", percent);
        XposedBridge.hookAllMethods(battery, "onAttachedToWindow", percent);
        // Battery saver: Nothing draws the battery in its warning colours (getColorProfile:
        // power save -> ColorProfile.Warning, the yellow fill), which the green filter turned
        // green. While saver is on the battery is drawn without the filter, so Nothing's
        // yellow shows (its frame and percentage stay green, given green directly); off
        // again, it is all green.
        XposedBridge.hookAllMethods(battery, "onPowerSaveChanged", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!store.get().ndotStatusBar) {
                    return;
                }
                View view = (View) param.thisObject;
                light(outermost(view));
                lightPercent(param.thisObject);
            }
        });
    }

    /** The percentage in NDot, lit to show green whether the filter is on or off. */
    private static void lightPercent(Object battery) {
        Object view;
        try {
            view = XposedHelpers.getObjectField(battery, "mBatteryPercentView");
        } catch (NoSuchFieldError e) {
            return; // Not this release's battery view.
        }
        if (view instanceof TextView) {
            // Drawn through the battery's green filter: lit white, it shows full green.
            // In battery saver the filter is off (Nothing's yellow shows): lit green.
            TextView text = (TextView) view;
            Led.lightText(text, saver(battery) ? Led.GREEN : 0xFFFFFFFF,
                    NdotFont.get(text.getContext()));
        }
    }

    /** True while battery saver is on, as the battery view knows it. */
    private static boolean saver(Object batteryView) {
        try {
            return XposedHelpers.getBooleanField(batteryView, "mPowerSaveEnabled");
        } catch (Throwable t) {
            return false;
        }
    }

    /** The outermost battery view holding {@code view} (its layer draws the whole battery). */
    private static View outermost(View view) {
        View outer = view;
        for (ViewParent p = view.getParent(); p instanceof View; p = p.getParent()) {
            if (p.getClass().getName().contains("Battery")) {
                outer = (View) p;
            }
        }
        return outer;
    }

    /** The battery's layer: the green filter, or none in battery saver. */
    private static void light(View outer) {
        View meter = meter(outer);
        outer.setLayerType(View.LAYER_TYPE_HARDWARE,
                meter != null && saver(meter) ? null : GREEN_LAYER);
    }

    /** The battery meter itself (it knows battery saver): {@code view} or one inside it. */
    private static View meter(View view) {
        if (XposedHelpers.findFieldIfExists(view.getClass(), "mPowerSaveEnabled") != null) {
            return view;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = meter(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Battery views, whatever Nothing calls them: the outermost view of System UI whose class
     * is a battery's is drawn through the green filter when it is shown.
     */
    private static void filterBatteryViews(ConfigStore store) {
        XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                View view = (View) param.thisObject;
                if (!view.getClass().getName().contains("Battery")
                        || !store.get().ndotStatusBar) {
                    return;
                }
                for (ViewParent p = view.getParent(); p instanceof View; p = p.getParent()) {
                    if (p.getClass().getName().contains("Battery")) {
                        return; // Its parts are drawn within the outer one's layer.
                    }
                }
                light(view);
            }
        });
    }
}
