package com.dressence.nothinglauncherplus.perf;

import android.os.Build;

import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Per-game "unlock 120 fps" (CMF Phone 1, games the user picks and adds to the module's LSPosed
 * scope): many games pick their frame rate from a list of known phones (the reports showed
 * two Unreal games asking the display for 60 Hz themselves, while Android, MediaTek and
 * PerfMTK all allowed 120). Inside that game only, the phone presents itself as a Samsung
 * Galaxy S24 Ultra (Snapdragon 8 Gen 3): Android's Build fields and the matching system
 * properties read through Java: maker, brand, model, codename, product and chip name. The
 * hardware and board names stay this phone's, as libraries inside the game may be chosen by
 * them. Nothing outside the game sees it. A game that picks by its
 * GPU (read from the graphics driver, which still says Mali) is not changed by this, and
 * games with online play can treat a changed device as cheating: it is off by default and
 * the settings warn about it.
 */
public final class GameUnlock {

    /** Samsung Galaxy S24 Ultra (SM-S928B, international), as its Build reports. */
    private static final Map<String, String> BUILD = new HashMap<>();
    private static final Map<String, String> PROPERTIES = new HashMap<>();

    static {
        BUILD.put("MANUFACTURER", "samsung");
        BUILD.put("BRAND", "samsung");
        BUILD.put("MODEL", "SM-S928B");
        BUILD.put("DEVICE", "e3q");
        BUILD.put("PRODUCT", "e3qxxx");
        BUILD.put("SOC_MANUFACTURER", "QTI");
        BUILD.put("SOC_MODEL", "SM8650");
        PROPERTIES.put("ro.product.manufacturer", "samsung");
        PROPERTIES.put("ro.product.brand", "samsung");
        PROPERTIES.put("ro.product.model", "SM-S928B");
        PROPERTIES.put("ro.product.device", "e3q");
        PROPERTIES.put("ro.product.name", "e3qxxx");
        PROPERTIES.put("ro.soc.manufacturer", "QTI");
        PROPERTIES.put("ro.soc.model", "SM8650");
    }

    private GameUnlock() {
    }

    /** In the game's process, before its code runs. */
    public static void install(String pkg) {
        for (Map.Entry<String, String> field : BUILD.entrySet()) {
            try {
                XposedHelpers.setStaticObjectField(Build.class, field.getKey(),
                        field.getValue());
            } catch (Throwable t) {
                // A field this Android does not have: the others still apply.
            }
        }
        Class<?> properties = XposedHelpers.findClassIfExists("android.os.SystemProperties",
                null);
        if (properties != null) {
            XposedBridge.hookAllMethods(properties, "get", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof String) {
                        String value = PROPERTIES.get(param.args[0]);
                        if (value != null) {
                            param.setResult(value);
                        }
                    }
                }
            });
        }
        Hooks.log("120 fps unlock: " + pkg + " sees a Galaxy S24 Ultra");
    }
}
