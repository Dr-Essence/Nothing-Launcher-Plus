package com.dressence.nothinglauncherplus.hook;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.dressence.nothinglauncherplus.Config;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Adds a "Custom folders" row under Settings > App drawer & Search > Drawer layout, right after
 * the Default / Smart (Beta) picker. It opens the module's settings.
 */
final class SettingsEntry {

    private static final String CLS_SETTINGS_FRAGMENT =
            "com.nothing.launcher.setting.allapp.AllAppSettingsFragment";
    private static final String CLS_PREFERENCE = "androidx.preference.Preference";
    private static final String PREF_DRAWER_LAYOUT_CATEGORY = "pref_category_all_app_type";
    private static final String PREF_CUSTOM_FOLDERS = "nlplus_custom_folders";

    static void install(ClassLoader cl, ConfigStore store) {
        Class<?> preferenceClass = XposedHelpers.findClass(CLS_PREFERENCE, cl);
        XposedHelpers.findAndHookMethod(CLS_SETTINGS_FRAGMENT, cl, "onCreatePreferences",
                Bundle.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object fragment = param.thisObject;
                        Object group = XposedHelpers.callMethod(fragment, "findPreference",
                                PREF_DRAWER_LAYOUT_CATEGORY);
                        if (group == null) {
                            group = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
                        }
                        if (group == null || XposedHelpers.callMethod(group, "findPreference",
                                PREF_CUSTOM_FOLDERS) != null) {
                            return;
                        }
                        Context context = (Context) XposedHelpers.callMethod(
                                fragment, "requireContext");
                        store.reloadIfChanged();

                        Object pref = XposedHelpers.newInstance(preferenceClass, context);
                        XposedHelpers.callMethod(pref, "setKey", PREF_CUSTOM_FOLDERS);
                        XposedHelpers.callMethod(pref, "setPersistent", false);
                        XposedHelpers.callMethod(pref, "setIconSpaceReserved", false);
                        XposedHelpers.callMethod(pref, "setTitle", (CharSequence) "Custom folders");
                        XposedHelpers.callMethod(pref, "setSummary",
                                (CharSequence) summary(store.get()));
                        XposedHelpers.callMethod(pref, "setIntent", new Intent()
                                .setClassName(Config.MODULE_PKG, Config.SETTINGS_ACTIVITY));
                        // Appended after the Default / Smart (Beta) layout picker.
                        XposedHelpers.callMethod(group, "addPreference", pref);
                    }
                });
    }

    private static String summary(Config c) {
        int n = c.folders.size();
        String count = n == 0 ? "No custom folders yet"
                : n == 1 ? "1 custom folder" : n + " custom folders";
        return count + " · shown in the Smart (Beta) layout";
    }

    private SettingsEntry() {
    }
}
