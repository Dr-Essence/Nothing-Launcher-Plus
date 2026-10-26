package com.dressence.nothinglauncherplus.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Typeface;

import com.dressence.nothinglauncherplus.Config;

import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The chosen system font applied the way Android applies its own fonts: in system_server,
 * where the font manager builds the one system font map every app receives when it starts.
 * The sans-serif families and the default in that map become the chosen font, and when the
 * choice changes the map is rebuilt at once, so every app opened from then on draws in it,
 * with no reboot and no per-app setup (once the module is loaded into the system).
 */
final class SystemFonts {

    private static final String CLS_SERVICE =
            "com.android.server.graphics.fonts.FontManagerService";
    private static final String WIDGETS_PKG = "com.nothing.hearthstone";

    private static Object service;
    private static Context context;

    private SystemFonts() {
    }

    static void install(ClassLoader cl, ConfigStore store) {
        Class<?> manager = XposedHelpers.findClass(CLS_SERVICE, cl);
        XposedBridge.hookAllConstructors(manager, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                service = param.thisObject;
                for (Object arg : param.args) {
                    if (arg instanceof Context) {
                        context = (Context) arg;
                    }
                }
                listen(store);
            }
        });
        // The map every app is given: its sans-serif families and default become the font.
        XposedHelpers.findAndHookMethod(Typeface.class, "serializeFontMap", Map.class,
                new XC_MethodHook() {
                    @Override
                    @SuppressWarnings("unchecked")
                    protected void beforeHookedMethod(MethodHookParam param) {
                        store.reloadIfChanged();
                        String spec = store.get().systemFont;
                        if (spec == null || spec.isEmpty()) {
                            return;
                        }
                        Typeface font = load(spec);
                        if (font == null) {
                            Hooks.log("system font unavailable in system: " + spec);
                            return;
                        }
                        Map<String, Typeface> map = (Map<String, Typeface>) param.args[0];
                        int swapped = 0;
                        for (Map.Entry<String, Typeface> e : map.entrySet()) {
                            if (sans(e.getKey())) {
                                Typeface was = e.getValue();
                                e.setValue(Typeface.create(font,
                                        was != null ? was.getStyle() : Typeface.NORMAL));
                                swapped++;
                            }
                        }
                        Hooks.log("system font " + spec + " in " + swapped + " families");
                    }
                });
    }

    /** Families of the default text font, under every name apps ask for it by. */
    static boolean sans(String name) {
        return name.startsWith("sans-serif") || name.startsWith("google-sans")
                || name.startsWith("roboto") || name.contains("ntype")
                || name.equals("system-ui");
    }

    /** Rebuilds the map when the choice changes (the settings send this broadcast). */
    private static void listen(ConfigStore store) {
        if (context == null) {
            return;
        }
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                store.reloadAndNotify();
                rebuild();
            }
        }, new IntentFilter(Config.ACTION_FONT), Context.RECEIVER_EXPORTED);
    }

    private static void rebuild() {
        if (service == null) {
            return;
        }
        for (String method : new String[] {"updateSerializedFontMap",
                "updateFontMap", "updateSerializedFontMapLocked"}) {
            try {
                XposedHelpers.callMethod(service, method);
                Hooks.log("system font map rebuilt");
                return;
            } catch (Throwable t) {
                // Not this release's name: try the next.
            }
        }
        Hooks.log("system font map could not be rebuilt on this release");
    }

    private static Typeface load(String spec) {
        try {
            if (spec.startsWith("file:")) {
                return Typeface.createFromFile(spec.substring(5));
            }
            if (spec.startsWith("res:") && context != null) {
                Resources res = context.createPackageContext(WIDGETS_PKG, 0).getResources();
                int id = res.getIdentifier(spec.substring(4), "font", WIDGETS_PKG);
                return id == 0 ? null : res.getFont(id);
            }
        } catch (Exception e) {
            Hooks.log("system font failed to load in system: " + e);
        }
        return null;
    }
}
