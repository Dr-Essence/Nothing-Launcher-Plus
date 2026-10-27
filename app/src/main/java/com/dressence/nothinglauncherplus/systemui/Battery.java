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
                if (!store.get().ndotStatusBar) {
                    return;
                }
                Object view;
                try {
                    view = XposedHelpers.getObjectField(param.thisObject, "mBatteryPercentView");
                } catch (NoSuchFieldError e) {
                    return; // Not this release's battery view.
                }
                if (view instanceof TextView) {
                    // Drawn through the battery's green filter: lit white, it shows full green.
                    TextView text = (TextView) view;
                    Led.lightText(text, 0xFFFFFFFF, NdotFont.get(text.getContext()));
                }
            }
        };
        XposedBridge.hookAllMethods(battery, "updateShowPercent", percent);
        XposedBridge.hookAllMethods(battery, "updatePercentText", percent);
        XposedBridge.hookAllMethods(battery, "onDarkChanged", percent);
        XposedBridge.hookAllMethods(battery, "onAttachedToWindow", percent);
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
                view.setLayerType(View.LAYER_TYPE_HARDWARE, GREEN_LAYER);
            }
        });
    }
}
