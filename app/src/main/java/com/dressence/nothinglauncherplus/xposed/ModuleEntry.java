package com.dressence.nothinglauncherplus.xposed;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.launcher.Launcher;
import com.dressence.nothinglauncherplus.system.SystemFonts;
import com.dressence.nothinglauncherplus.systemui.SystemUi;
import com.dressence.nothinglauncherplus.widgets.CardOrientation;
import com.dressence.nothinglauncherplus.widgets.CustomizationPage;
import com.dressence.nothinglauncherplus.widgets.WidgetApps;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * The module's entry point (named in assets/xposed_init): which app is starting decides which
 * part of the module it gets.
 */
public final class ModuleEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        ClassLoader cl = lpparam.classLoader;
        if (Config.MODULE_PKG.equals(pkg)) {
            // The settings app shows whether the module is active.
            XposedHelpers.findAndHookMethod(Config.SETTINGS_ACTIVITY, cl, "isModuleActive",
                    XC_MethodReplacement.returnConstant(true));
        } else if ("android".equals(pkg)) {
            ConfigStore store = new ConfigStore();
            Hooks.install("system font", () -> SystemFonts.install(cl, store));
            Hooks.install("widget card orientation", () -> CardOrientation.install(cl, store));
        } else if (!lpparam.isFirstApplication) {
            return;
        } else if (Config.LAUNCHER_PKG.equals(pkg)) {
            Launcher.install(cl, new ConfigStore());
        } else if (Config.SYSTEMUI_PKG.equals(pkg)) {
            SystemUi.install(cl, new ConfigStore());
        } else if (CustomizationPage.PKG.equals(pkg)) {
            Hooks.install("customization page", () -> CustomizationPage.install(new ConfigStore()));
        } else if (WidgetApps.handles(pkg)) {
            Hooks.install("widget apps", () -> WidgetApps.install(new ConfigStore()));
        }
    }
}
