package com.dressence.nothinglauncherplus.widgets;

import android.content.pm.ActivityInfo;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Nothing's widget cards opened in the screen's orientation from the start (with Home screen
 * rotation on). Their screens are declared portrait-only, and Android applies that as a card
 * is launched, before the app runs: over a landscape home screen the Calm card first showed
 * on its side. Here, in system_server, the portrait lock is taken off as the card's activity
 * record is made, on that activity's own copy of its info and nothing else. The card also
 * handles turning itself, so a rotation does not restart it.
 */
public final class CardOrientation {

    private static final String CLS_ACTIVITY_RECORD = "com.android.server.wm.ActivityRecord";
    private static final String CUSTOMIZATION_PKG = "com.nothing.wallpaper";
    private static final String CUSTOMIZATION_ACTIVITY =
            "com.android.customization.picker.CustomizationPickerActivity";
    private static final int ROTATION_CHANGES = ActivityInfo.CONFIG_ORIENTATION
            | ActivityInfo.CONFIG_SCREEN_SIZE | ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE
            | ActivityInfo.CONFIG_SCREEN_LAYOUT;

    private CardOrientation() {
    }

    public static void install(ClassLoader cl, ConfigStore store) {
        int hooked = XposedBridge.hookAllConstructors(
                XposedHelpers.findClass(CLS_ACTIVITY_RECORD, cl), new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        for (Object arg : param.args) {
                            if (arg instanceof ActivityInfo) {
                                unlock((ActivityInfo) arg, store);
                                return;
                            }
                        }
                    }
                }).size();
        Hooks.log("widget cards: " + hooked + " activity record hooks");
    }

    private static void unlock(ActivityInfo info, ConfigStore store) {
        if (!isPortraitLock(info.screenOrientation)) {
            return;
        }
        if (CUSTOMIZATION_PKG.equals(info.packageName)
                && CUSTOMIZATION_ACTIVITY.equals(info.name)) {
            // Nothing's Customisation page (wallpaper, style, home screen), opened from the
            // home screen: it follows it, and is made again for a turn as apps are.
            store.reloadIfChanged();
            if (store.get().allowRotation) {
                info.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            }
            return;
        }
        if (!WidgetApps.handles(info.packageName)) {
            return;
        }
        store.reloadIfChanged();
        if (store.get().allowRotation) {
            info.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            // Turning keeps the card running (a breathing session, a playing photo): the app
            // relays it out instead of being restarted (see WidgetApps).
            info.configChanges |= ROTATION_CHANGES;
        }
    }

    static boolean isPortraitLock(int orientation) {
        return orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                || orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                || orientation == ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
    }
}
