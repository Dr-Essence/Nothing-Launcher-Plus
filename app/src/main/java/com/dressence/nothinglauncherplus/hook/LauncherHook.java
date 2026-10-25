package com.dressence.nothinglauncherplus.hook;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import com.dressence.nothinglauncherplus.Config;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Xposed entry point: wires the module's features into Nothing Launcher. */
public class LauncherHook implements IXposedHookLoadPackage {

    private static final String CLS_LAUNCHER = "com.android.launcher3.Launcher";

    private ConfigStore store;
    private boolean receiverRegistered;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (Config.MODULE_PKG.equals(lpparam.packageName)) {
            XposedHelpers.findAndHookMethod(Config.SETTINGS_ACTIVITY, lpparam.classLoader,
                    "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }
        if (!Config.LAUNCHER_PKG.equals(lpparam.packageName) || !lpparam.isFirstApplication) {
            return;
        }
        ClassLoader cl = lpparam.classLoader;
        store = new ConfigStore();

        new SmartDrawer(cl, store, new FolderCovers()).install();
        Hooks.install("folder menu", () -> new FolderMenu(cl, store));
        Hooks.install("settings entry", () -> SettingsEntry.install(cl, store));
        Hooks.install("drawer background", () -> DrawerBackground.install(cl, store));
        new Landscape(cl, store).install();
        Hooks.install("controller", () -> Gamepad.install(cl, store));
        Hooks.install("reload receiver", () -> hookReloadReceiver(cl));
    }

    private void hookReloadReceiver(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(CLS_LAUNCHER, cl, "onCreate", Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        registerReloadReceiver(((Activity) param.thisObject).getApplicationContext());
                    }
                });
    }

    private synchronized void registerReloadReceiver(Context appContext) {
        if (receiverRegistered) {
            return;
        }
        receiverRegistered = true;
        appContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                store.reloadAndNotify();
            }
        }, new IntentFilter(Config.ACTION_RELOAD), Context.RECEIVER_EXPORTED);
    }
}
