package com.dressence.nothinglauncherplus.launcher;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.xposed.ConfigStore;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Adds a "Custom folders" row under Settings > App drawer & Search > Drawer layout, right after
 * the Default / Smart (Beta) picker, which opens the module's App drawer page; and a "Nothing
 * Launcher+" row at the end of Nothing Launcher's own settings (LauncherSettingsFragment),
 * which opens the module's menu.
 */
final class SettingsEntry {

    private static final String CLS_SETTINGS_FRAGMENT =
            "com.nothing.launcher.setting.allapp.AllAppSettingsFragment";
    private static final String CLS_PREFERENCE = "androidx.preference.Preference";
    private static final String PREF_DRAWER_LAYOUT_CATEGORY = "pref_category_all_app_type";
    private static final String PREF_CUSTOM_FOLDERS = "nlplus_custom_folders";
    private static final String CLS_LAUNCHER_SETTINGS =
            "com.nothing.launcher.setting.homesettings.LauncherSettingsFragment";
    private static final String PREF_MODULE = "nlplus_module";

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
                                .setClassName(Config.MODULE_PKG, Config.SETTINGS_ACTIVITY)
                                .putExtra(Config.EXTRA_SETTINGS_PAGE, Config.PAGE_DRAWER));
                        // Appended after the Default / Smart (Beta) layout picker.
                        XposedHelpers.callMethod(group, "addPreference", pref);
                    }
                });
    }

    /** The module's own row at the end of Nothing Launcher's settings. */
    static void installModuleRow(ClassLoader cl) {
        Class<?> preferenceClass = XposedHelpers.findClass(CLS_PREFERENCE, cl);
        XposedHelpers.findAndHookMethod(CLS_LAUNCHER_SETTINGS, cl, "onCreatePreferences",
                Bundle.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object fragment = param.thisObject;
                        Object screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
                        if (screen == null || XposedHelpers.callMethod(screen, "findPreference",
                                PREF_MODULE) != null) {
                            return;
                        }
                        Context context = (Context) XposedHelpers.callMethod(
                                fragment, "requireContext");
                        Object pref = XposedHelpers.newInstance(preferenceClass, context);
                        XposedHelpers.callMethod(pref, "setKey", PREF_MODULE);
                        XposedHelpers.callMethod(pref, "setPersistent", false);
                        XposedHelpers.callMethod(pref, "setIconSpaceReserved", false);
                        XposedHelpers.callMethod(pref, "setTitle",
                                (CharSequence) "Nothing Launcher+");
                        XposedHelpers.callMethod(pref, "setSummary", (CharSequence)
                                "Folders, home screen, sound, status bar, lock screen and more");
                        XposedHelpers.callMethod(pref, "setIntent", new Intent()
                                .setClassName(Config.MODULE_PKG, Config.SETTINGS_ACTIVITY));
                        XposedHelpers.callMethod(screen, "addPreference", pref);
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
