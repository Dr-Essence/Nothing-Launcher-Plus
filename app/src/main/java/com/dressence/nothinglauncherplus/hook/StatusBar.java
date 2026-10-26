package com.dressence.nothinglauncherplus.hook;

import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.widget.TextView;

import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The status bar in the style of the folder screens: the clock and the battery in Nothing's
 * NDot, lit the green of the folder name panels with a soft glow round each dot, whatever the
 * wallpaper; and, as an option, the date before the time.
 */
final class StatusBar {

    private static final String CLS_CLOCK = "com.android.systemui.statusbar.policy.Clock";
    private static final String CLS_BATTERY = "com.android.systemui.battery.BatteryMeterView";
    /** The green of the folder name panels. */
    private static final int LED_GREEN = 0xFF4CFF7A;
    /** The glow round the text: the same green, soft. */
    private static final int GLOW = 0xAA2BE86A;

    /** The views drawn in green: any colour the status bar gives them is replaced. */
    private static final Map<TextView, Boolean> GREEN =
            Collections.synchronizedMap(new WeakHashMap<>());

    private StatusBar() {
    }

    static void install(ClassLoader cl, ConfigStore store) {
        // Every colour the status bar sets on our views (light or dark icons) becomes green.
        XposedHelpers.findAndHookMethod(TextView.class, "setTextColor", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (GREEN.containsKey(param.thisObject)) {
                            param.args[0] = LED_GREEN;
                        }
                    }
                });
        Hooks.install("status bar clock", () -> hookClock(cl, store));
        Hooks.install("status bar battery", () -> hookBattery(cl, store));
    }

    private static void hookClock(ClassLoader cl, ConfigStore store) {
        Class<?> clock = XposedHelpers.findClass(CLS_CLOCK, cl);
        XC_MethodHook style = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                store.reloadIfChanged();
                if (store.get().ndotStatusBar) {
                    styleText((TextView) param.thisObject);
                }
            }
        };
        XposedBridge.hookAllMethods(clock, "onAttachedToWindow", style);
        XposedBridge.hookAllMethods(clock, "onDarkChanged", style);
        XposedBridge.hookAllMethods(clock, "updateClock", style);
        // The date before the time ("27 Oct 8:53").
        XposedBridge.hookAllMethods(clock, "getSmallTime", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!store.get().statusBarDate || !(param.getResult() instanceof CharSequence)) {
                    return;
                }
                String date = DateFormat.format(
                        DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "dMMM"),
                        new Date()).toString();
                param.setResult(date + "  " + param.getResult());
            }
        });
    }

    private static void hookBattery(ClassLoader cl, ConfigStore store) {
        Class<?> battery = XposedHelpers.findClass(CLS_BATTERY, cl);
        // The battery drawn in green on every background, its empty part a dim green.
        XposedBridge.hookAllMethods(battery, "updateColors", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!store.get().ndotStatusBar) {
                    return;
                }
                for (int i = 0; i < param.args.length; i++) {
                    if (param.args[i] instanceof Integer) {
                        param.args[i] = i == 1 ? 0x554CFF7A : LED_GREEN;
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
                    return;
                }
                if (view instanceof TextView) {
                    styleText((TextView) view);
                }
            }
        };
        XposedBridge.hookAllMethods(battery, "updateShowPercent", percent);
        XposedBridge.hookAllMethods(battery, "updatePercentText", percent);
        XposedBridge.hookAllMethods(battery, "onDarkChanged", percent);
        XposedBridge.hookAllMethods(battery, "onAttachedToWindow", percent);
    }

    /** NDot, lit green, each dot glowing softly. */
    private static void styleText(TextView view) {
        Typeface ndot = NdotFont.get(view.getContext());
        if (ndot != null && view.getTypeface() != ndot) {
            view.setTypeface(ndot);
        }
        GREEN.put(view, Boolean.TRUE);
        view.setTextColor(LED_GREEN);
        float glow = view.getTextSize() * 0.28f;
        view.setShadowLayer(glow, 0, 0, GLOW);
        // The glow reaches past the text: let it show rather than be cut at the edges.
        view.setClipToOutline(false);
        if (view.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) view.getParent()).setClipChildren(false);
        }
    }
}
