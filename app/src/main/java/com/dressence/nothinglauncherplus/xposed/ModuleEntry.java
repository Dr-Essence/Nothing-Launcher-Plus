package com.dressence.nothinglauncherplus.xposed;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.keyboard.HeliBoardGamepad;
import com.dressence.nothinglauncherplus.launcher.Launcher;
import com.dressence.nothinglauncherplus.perf.Device;
import com.dressence.nothinglauncherplus.perf.GameUnlock;
import com.dressence.nothinglauncherplus.system.OrientationProbe;
import com.dressence.nothinglauncherplus.system.SystemFonts;
import com.dressence.nothinglauncherplus.systemui.SystemUi;
import com.dressence.nothinglauncherplus.widgets.CardOrientation;
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
            Hooks.install("orientation probe", () -> OrientationProbe.install(cl, store));
        } else if (!lpparam.isFirstApplication) {
            return;
        } else if (Config.LAUNCHER_PKG.equals(pkg)) {
            Launcher.install(cl, new ConfigStore());
        } else if (Config.SYSTEMUI_PKG.equals(pkg)) {
            SystemUi.install(cl, new ConfigStore());
        } else if (HeliBoardGamepad.PKG.equals(pkg)) {
            Hooks.install("heliboard gamepad",
                    () -> HeliBoardGamepad.install(cl, new ConfigStore()));
        } else if (WidgetApps.handles(pkg)) {
            Hooks.install("widget apps", () -> WidgetApps.install(new ConfigStore()));
        } else if (Device.isCmfPhone1()) {
            // A game the user added to the scope for the 120 fps unlock.
            Hooks.install("120 fps unlock", () -> {
                if (new ConfigStore().get().unlockGames.contains(pkg)) {
                    GameUnlock.install(pkg);
                }
            });
        }
    }
}
