package com.dressence.nothinglauncherplus.system;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Full refresh rate in battery saver (setting, off by default).
 *
 * <p>Android's display mode director caps the refresh rate while battery saver is on
 * (DisplayModeDirector$SettingsObserver.updateLowPowerModeSettingLocked: with Settings.Global
 * low_power set, a global vote at the low-power-mode priority, 19, for render rates 0..60 Hz;
 * and updateLowPowerModeAllowedModesLocked: a vote at priority 18 for the display's low-power
 * modes, where the display lists them). With the setting on, those two votes are not cast;
 * everything else battery saver does stands, the low-power flag included (the brightness
 * observer still learns of it), and the refresh rate follows the user's own setting. Turning
 * the setting on or off applies at once (the director is asked again under its lock).
 */
public final class SaverRefreshRate {

    private static final String CLS_OBSERVER =
            "com.android.server.display.mode.DisplayModeDirector$SettingsObserver";
    private static final int PRIORITY_LOW_POWER_MODE = 19;
    private static final int PRIORITY_LOW_POWER_MODES = 18;

    private static volatile Object observer;

    private SaverRefreshRate() {
    }

    public static void install(ClassLoader cl, ConfigStore store) {
        Class<?> cls = XposedHelpers.findClass(CLS_OBSERVER, cl);
        // As the observer starts watching the settings (DisplayModeDirector.start, once the
        // system is far enough up to register receivers).
        XposedHelpers.findAndHookMethod(cls, "observe", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                observer = param.thisObject;
                Hooks.guard("saver refresh listen", () -> listen(
                        (Context) XposedHelpers.getObjectField(param.thisObject, "mContext"),
                        store)).run();
            }
        });
        XposedHelpers.findAndHookMethod(cls, "updateLowPowerModeSettingLocked",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (store.get().saverFullRefresh) {
                                Object director = XposedHelpers.getSurroundingThis(
                                        param.thisObject);
                                Object votes = XposedHelpers.getObjectField(director,
                                        "mVotesStorage");
                                XposedHelpers.callMethod(votes, "updateGlobalVote",
                                        PRIORITY_LOW_POWER_MODE, null);
                            }
                        } catch (Throwable t) {
                            Hooks.log("saver refresh rate: " + t);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(cls, "updateLowPowerModeAllowedModesLocked",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (store.get().saverFullRefresh) {
                                Object director = XposedHelpers.getSurroundingThis(
                                        param.thisObject);
                                XposedHelpers.callMethod(XposedHelpers.getObjectField(director,
                                        "mVotesStorage"), "removeAllVotesForPriority",
                                        PRIORITY_LOW_POWER_MODES);
                            }
                        } catch (Throwable t) {
                            Hooks.log("saver refresh modes: " + t);
                        }
                    }
                });
        Hooks.log("saver refresh rate: ready");
    }

    /** The setting changed in the module: the director decides again. */
    private static void listen(Context context, ConfigStore store) {
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                Hooks.guard("saver refresh apply", () -> {
                    store.reloadIfChanged();
                    Object o = observer;
                    if (o == null) {
                        return;
                    }
                    Object director = XposedHelpers.getSurroundingThis(o);
                    synchronized (XposedHelpers.getObjectField(director, "mLock")) {
                        XposedHelpers.callMethod(o, "updateLowPowerModeSettingLocked");
                    }
                }).run();
            }
        }, new IntentFilter(Config.ACTION_RELOAD), Context.RECEIVER_EXPORTED);
    }
}
