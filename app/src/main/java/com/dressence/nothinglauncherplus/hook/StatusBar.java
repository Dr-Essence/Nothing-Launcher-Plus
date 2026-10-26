package com.dressence.nothinglauncherplus.hook;

import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.View;
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

    /** The reach of the clock's glow, in pixels: the status icons glow as far. */
    static volatile float glowRadius;

    private StatusBar() {
    }

    static void install(ClassLoader cl, ConfigStore store) {
        // Every colour the status bar sets on our views (light or dark icons) becomes green.
        XposedHelpers.findAndHookMethod(TextView.class, "setTextColor", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Boolean green = GREEN.get(param.thisObject);
                        if (green != null) {
                            param.args[0] = green ? LED_GREEN : 0xFFFFFFFF;
                        }
                    }
                });
        // Network speed and the like set their own face as they update: it stays the plain one.
        XposedBridge.hookAllMethods(TextView.class, "setTypeface", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (plain != null && param.args.length > 0 && SMALL.containsKey(param.thisObject)
                        && param.args[0] != plain) {
                    param.args[0] = plain;
                }
            }
        });
        Hooks.install("status bar clock", () -> hookClock(cl, store));
        Hooks.install("green status icons", () -> GlowIcons.install(cl, store));
        Hooks.install("status bar battery", () -> hookBattery(cl, store));
    }

    private static void hookClock(ClassLoader cl, ConfigStore store) {
        Class<?> clock = XposedHelpers.findClass(CLS_CLOCK, cl);
        XC_MethodHook style = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                store.reloadIfChanged();
                if (store.get().ndotStatusBar) {
                    TextView clock = (TextView) param.thisObject;
                    styleText(clock, true);
                    glowRadius = clock.getTextSize() * 0.28f;
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
                if (!store.get().statusBarDate || !(param.getResult() instanceof CharSequence)
                        || inHeader((View) param.thisObject)) {
                    return;
                }
                String date = DateFormat.format(
                        DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "dMMM"),
                        new Date()).toString();
                param.setResult(date + "  " + param.getResult());
            }
        });
    }

    /**
     * True for the clock in the pulled-down quick settings header, which shows its own date:
     * any parent of it there is a header, quick settings or shade view.
     */
    private static boolean inHeader(View view) {
        for (android.view.ViewParent p = view.getParent(); p instanceof View;
                p = p.getParent()) {
            View v = (View) p;
            String name = v.getClass().getName().toLowerCase(java.util.Locale.ROOT);
            String id = "";
            if (v.getId() != View.NO_ID) {
                try {
                    id = v.getResources().getResourceEntryName(v.getId())
                            .toLowerCase(java.util.Locale.ROOT);
                } catch (RuntimeException e) {
                    // No name for this id.
                }
            }
            if (name.contains("header") || name.contains(".qs.") || name.contains("shade")
                    || id.contains("header") || id.startsWith("qs") || id.contains("shade")) {
                return true;
            }
        }
        return false;
    }

    /** Light to the folder screens' green, dark to dark: the battery icon re-lit in green. */
    private static final android.graphics.Paint GREEN_LAYER = new android.graphics.Paint();
    static {
        GREEN_LAYER.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[] {
                0.09f, 0.18f, 0.03f, 0, 0,
                0.30f, 0.59f, 0.11f, 0, 0,
                0.14f, 0.28f, 0.05f, 0, 0,
                0, 0, 0, 1, 0}));
    }

    /**
     * Battery views, whatever Nothing calls them: every view of System UI whose class is a
     * battery's is drawn through a green filter when it is shown.
     */
    private static void tintBatteries(ConfigStore store) {
        XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                View view = (View) param.thisObject;
                String name = view.getClass().getName();
                if ((name.contains("Speed") || name.contains("Traffic"))
                        && LOGGED.add(name)) {
                    Hooks.log("network speed view: " + name + " extends "
                            + view.getClass().getSuperclass().getName());
                }
                if (!name.contains("Battery")) {
                    return;
                }
                store.reloadIfChanged();
                if (!store.get().ndotStatusBar) {
                    return;
                }
                // Only the outermost battery view: its parts are drawn within its layer.
                for (android.view.ViewParent p = view.getParent(); p instanceof View;
                        p = p.getParent()) {
                    if (p.getClass().getName().contains("Battery")) {
                        return;
                    }
                }
                view.setLayerType(View.LAYER_TYPE_HARDWARE, GREEN_LAYER);
            }
        });
    }

    private static void hookBattery(ClassLoader cl, ConfigStore store) {
        tintBatteries(store);
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
                    // It is drawn through the battery's green filter: lit white, it comes out
                    // the full green.
                    styleText((TextView) view, false);
                }
            }
        };
        XposedBridge.hookAllMethods(battery, "updateShowPercent", percent);
        XposedBridge.hookAllMethods(battery, "updatePercentText", percent);
        XposedBridge.hookAllMethods(battery, "onDarkChanged", percent);
        XposedBridge.hookAllMethods(battery, "onAttachedToWindow", percent);
    }

    /** NDot, lit green (or white, for a view drawn through the green filter), glowing. */
    /** True once {@code view} has been set in green NDot. */
    static boolean styled(TextView view) {
        return GREEN.containsKey(view);
    }

    static void styleText(TextView view, boolean green) {
        styleText(view, green, NdotFont.get(view.getContext()));
    }

    /**
     * A text too small for NDot's dots (network speed's two stacked lines): green and glowing,
     * in a plain face. Roboto, read straight from its file, as the system's own sans-serif may
     * itself be NDot (the System font option).
     */
    static void styleSmallText(TextView view) {
        if (plain == null) {
            try {
                plain = Typeface.create(Typeface.createFromFile("/system/fonts/Roboto-Regular.ttf"),
                        500, false);
            } catch (RuntimeException e) {
                plain = Typeface.DEFAULT;
            }
        }
        SMALL.put(view, true);
        styleText(view, true, plain);
        Hooks.log("small status text in a plain face: " + view.getClass().getName());
    }

    private static Typeface plain;
    private static final java.util.Set<String> LOGGED =
            Collections.synchronizedSet(new java.util.HashSet<>());
    /** The small texts: kept in the plain face whatever face they set themselves. */
    private static final Map<TextView, Boolean> SMALL =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * For a small text already styled: its plain face put back if anything (a text appearance,
     * the shade opening) has changed it since. True if {@code view} is one.
     */
    static boolean keepSmall(TextView view) {
        if (!SMALL.containsKey(view)) {
            return false;
        }
        if (view.getTypeface() != plain || view.getPaint().getTypeface() != plain) {
            styleText(view, true, plain);
            view.getPaint().setTypeface(plain);
        }
        return true;
    }

    private static void styleText(TextView view, boolean green, Typeface face) {
        if (face != null && view.getTypeface() != face) {
            view.setTypeface(face);
        }
        GREEN.put(view, green);
        view.setTextColor(green ? LED_GREEN : 0xFFFFFFFF);
        float glow = view.getTextSize() * 0.28f; // As glowRadius.
        view.setShadowLayer(glow, 0, 0, green ? GLOW : 0xAAFFFFFF);
        // The glow reaches past the text: let it show rather than be cut at the edges.
        view.setClipToOutline(false);
        if (view.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) view.getParent()).setClipChildren(false);
        }
    }
}
