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
    private static final String TURN_LIFECYCLE = "nlplus.turnLifecycle";

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
        Hooks.install("landscape widget picker", () -> new WidgetPickerLandscape(cl, store).install());
        Hooks.install("lifecycle after a turn", () -> hookTurnLifecycle(cl));
        Hooks.install("home shown", () -> hookHomeShown(cl));
        Hooks.install("controller", () -> Gamepad.install(cl, store));
        Hooks.install("recents memory", () -> RecentsRam.install(cl, store));
        Hooks.install("dot-matrix icons", () -> DotIcons.install(cl, store));
        Hooks.install("scrolling app names", () -> LabelScroll.install(cl, store));
        LauncherSounds.install(cl, store);
        Hooks.install("reload receiver", () -> hookReloadReceiver(cl, store));
        Hooks.install("loader idle wait", () -> hookLoaderIdleWait(cl));
    }

    /**
     * Turned in place (the launcher handles a rotation itself, Launcher.onHandleConfigurationChanged),
     * Launcher3 saves its state with onSaveInstanceState(new Bundle()), and androidx's
     * ComponentActivity.onSaveInstanceState drops the activity's lifecycle to CREATED on any
     * save: ON_PAUSE and ON_STOP while the launcher is still resumed on screen, with nothing to
     * raise it again until it really pauses and resumes. Nothing's Compose views collect their
     * state only while STARTED (collectAsStateWithLifecycle): after a turn the drawer search
     * took typing but its results never showed (DebuG9: lifecycle CREATED at the turn, no
     * ON_START until the launcher was left). The save stays; the lifecycle is put back where
     * it was before it.
     */
    private static void hookTurnLifecycle(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(CLS_LAUNCHER, cl, "onHandleConfigurationChanged",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Hooks.guard("lifecycle before a turn", () ->
                                XposedHelpers.setAdditionalInstanceField(param.thisObject,
                                        TURN_LIFECYCLE, XposedHelpers.callMethod(
                                                XposedHelpers.callMethod(param.thisObject,
                                                        "getLifecycle"),
                                                "getCurrentState"))).run();
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Hooks.guard("lifecycle after a turn", () -> {
                            Object before = XposedHelpers.removeAdditionalInstanceField(
                                    param.thisObject, TURN_LIFECYCLE);
                            Object lifecycle = XposedHelpers.callMethod(param.thisObject,
                                    "getLifecycle");
                            Object now = XposedHelpers.callMethod(lifecycle, "getCurrentState");
                            if (before instanceof Enum && now instanceof Enum
                                    && ((Enum<?>) now).ordinal() < ((Enum<?>) before).ordinal()
                                    && !((Activity) param.thisObject).isFinishing()) {
                                XposedHelpers.callMethod(lifecycle, "setCurrentState", before);
                            }
                        }).run();
                    }
                });
    }

    /** Live wallpapers whose sounds play only while the launcher is on screen. */
    private static final String[] SOUND_WALLPAPERS = {
            "com.pixfabrik.livingworlds", "rak.pixellwp"};

    /**
     * Tells the live wallpapers with sounds (Living Worlds, Palette Cycle) when the launcher
     * (home, app drawer, recents) is on screen or left, so their sounds play there only.
     */
    private static void hookHomeShown(ClassLoader cl) {
        for (String method : new String[] {"onResume", "onPause"}) {
            boolean shown = "onResume".equals(method);
            XposedHelpers.findAndHookMethod(CLS_LAUNCHER, cl, method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Hooks.guard("home shown", () -> {
                        Activity launcher = (Activity) param.thisObject;
                        for (String pkg : SOUND_WALLPAPERS) {
                            launcher.sendBroadcast(new Intent(Config.ACTION_HOME_SHOWN)
                                    .setPackage(pkg).putExtra(Config.EXTRA_SHOWN, shown));
                        }
                    }).run();
                }
            });
        }
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
                        Context app = ((Activity) param.thisObject).getApplicationContext();
                        register(app, store);
                        HomeRestore.register(app, cl);
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
