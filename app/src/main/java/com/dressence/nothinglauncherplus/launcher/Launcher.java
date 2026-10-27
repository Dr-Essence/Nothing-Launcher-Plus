package com.dressence.nothinglauncherplus.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Everything the module does in Nothing Launcher. */
public final class Launcher {

    private static final String CLS_LAUNCHER = "com.android.launcher3.Launcher";

    private static boolean receiverRegistered;

    private Launcher() {
    }

    public static void install(ClassLoader cl, ConfigStore store) {
        new SmartDrawer(cl, store, new FolderCovers()).install();
        Hooks.install("folder menu", () -> new FolderMenu(cl, store));
        Hooks.install("settings entry", () -> SettingsEntry.install(cl, store));
        Hooks.install("drawer background", () -> DrawerBackground.install(cl, store));
        new Landscape(cl, store).install();
        new LandscapeGrid(cl).install();
        new LandscapeSurfaces(cl, store).install();
        Hooks.install("controller", () -> Gamepad.install(cl, store));
        Hooks.install("dot-matrix icons", () -> DotIcons.install(cl, store));
        Hooks.install("scrolling app names", () -> LabelScroll.install(cl, store));
        LauncherSounds.install(cl, store);
        Hooks.install("reload receiver", () -> hookReloadReceiver(cl, store));
    }

    /** The settings app's changes, applied at once (it sends this broadcast after each). */
    private static void hookReloadReceiver(ClassLoader cl, ConfigStore store) {
        XposedHelpers.findAndHookMethod(CLS_LAUNCHER, cl, "onCreate", Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        register(((Activity) param.thisObject).getApplicationContext(), store);
                    }
                });
    }

    private static synchronized void register(Context appContext, ConfigStore store) {
        if (receiverRegistered) {
            return;
        }
        receiverRegistered = true;
        appContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Hooks.guard("settings reload", store::reloadAndNotify).run();
            }
        }, new IntentFilter(Config.ACTION_RELOAD), Context.RECEIVER_EXPORTED);
    }
}
