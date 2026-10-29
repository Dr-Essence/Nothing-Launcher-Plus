package com.dressence.nothinglauncherplus.systemui;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

/**
 * Everything the module does in System UI: the status bar lit like the drawer's folder
 * screens (clock and date in green NDot, battery and icons green with a soft glow), and quick
 * settings tiles as a retro display.
 */
public final class SystemUi {

    private SystemUi() {
    }

    public static void install(ClassLoader cl, ConfigStore store) {
        Hooks.install("lit texts", Led::install);
        Hooks.install("status bar clock", () -> Clock.install(cl, store));
        Hooks.install("status bar battery", () -> Battery.install(cl, store));
        Hooks.install("green icons", () -> GreenIcons.install(store));
        Hooks.install("green texts", () -> GreenTexts.install(store));
        Hooks.install("quick settings", () -> QuickSettings.install(cl, store));
        Hooks.install("lock screen rotation", () -> KeyguardRotation.install(cl, store));
    }
}
