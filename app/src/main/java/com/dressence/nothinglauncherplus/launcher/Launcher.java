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
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;

/** Everything the module does in Nothing Launcher. */
public final class Launcher {

    private static final String CLS_LAUNCHER = "com.android.launcher3.Launcher";
    private static final String CLS_LOADER_TASK = "com.android.launcher3.model.LoaderTask";
    /** The longest the loader waits for an idle main thread between its steps. */
    private static final int IDLE_WAIT_SECONDS = 3;

    private static boolean receiverRegistered;

    private Launcher() {
    }

    public static void install(ClassLoader cl, ConfigStore store) {
        new SmartDrawer(cl, store, new FolderCovers()).install();
        Hooks.install("folder menu", () -> new FolderMenu(cl, store));
        Hooks.install("settings entry", () -> SettingsEntry.install(cl, store));
        Hooks.install("settings module row", () -> SettingsEntry.installModuleRow(cl));
        Hooks.install("drawer background", () -> DrawerBackground.install(cl, store));
        new Landscape(cl, store).install();
        new LandscapeGrid(cl).install();
        new LandscapeSurfaces(cl, store).install();
        Hooks.install("landscape folder sheet", () -> new FolderSheetLandscape(cl).install());
        Hooks.install("controller", () -> Gamepad.install(cl, store));
        Hooks.install("dot-matrix icons", () -> DotIcons.install(cl, store));
        Hooks.install("scrolling app names", () -> LabelScroll.install(cl, store));
        LauncherSounds.install(cl, store);
        Hooks.install("reload receiver", () -> hookReloadReceiver(cl, store));
        Hooks.install("loader idle wait", () -> hookLoaderIdleWait(cl));
    }

    /**
     * Between its steps (home screen, then apps, shortcuts and widgets) the launcher's loader
     * waits for the main thread to go idle, with no time limit. While anything on screen redraws
     * every frame the main thread never does, and apps and widgets stayed unloaded, the widget
     * list empty, until the launcher went to the background. The wait is kept, for at most a
     * few seconds.
     */
    private static void hookLoaderIdleWait(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(CLS_LOADER_TASK, cl, "waitForIdle",
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        Object task = param.thisObject;
                        synchronized (task) {
                            Object lock = XposedHelpers.callMethod(XposedHelpers.getObjectField(
                                    task, "mLauncherBinder"), "newIdleLock", task);
                            for (int i = 0; i < IDLE_WAIT_SECONDS
                                    && !XposedHelpers.getBooleanField(task, "mStopped")
                                    && (Boolean) XposedHelpers.callMethod(lock, "awaitLocked",
                                            1000L); i++) {
                                // Still not idle: wait another second.
                            }
                        }
                        return null;
                    }
                });
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
