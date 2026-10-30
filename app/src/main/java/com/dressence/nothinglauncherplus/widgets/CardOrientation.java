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
 *
 * <p>The same goes for Nothing's system screens that Nothing declares portrait-only although
 * they are built to work in any orientation (listed below, each checked in Nothing's own
 * code): they follow auto-rotate like other apps' screens, and turning restarts them in
 * landscape the ordinary way. Every other portrait-only screen opened while Home screen
 * rotation is on is written to the LSPosed log once ("portrait-only screen: …"), so further
 * ones can be checked and added from what is really opened (system apps only).
 */
public final class CardOrientation {

    private static final String CLS_ACTIVITY_RECORD = "com.android.server.wm.ActivityRecord";
    private static final int ROTATION_CHANGES = ActivityInfo.CONFIG_ORIENTATION
            | ActivityInfo.CONFIG_SCREEN_SIZE | ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE
            | ActivityInfo.CONFIG_SCREEN_LAYOUT;

    /**
     * Nothing's portrait-locked screens that work in landscape, as package/activity:
     * SystemUI's quick settings widget picker (QSWidgetActivity: a scrolling list of system
     * and app widgets under a collapsing toolbar).
     */
    private static final java.util.Set<String> SYSTEM_SCREENS = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "com.android.systemui/com.nothing.systemui.qs.widget.QSWidgetActivity"));
    /** Portrait-only screens already logged. */
    private static final java.util.Set<String> LOGGED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

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
        String screen = info.packageName + "/" + info.name;
        if (!WidgetApps.handles(info.packageName)) {
            store.reloadIfChanged();
            if (!store.get().allowRotation) {
                return;
            }
            if (SYSTEM_SCREENS.contains(screen)) {
                info.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            } else if (info.applicationInfo != null && (info.applicationInfo.flags
                    & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    && LOGGED.add(screen)) {
                // System apps only: other apps' screens are theirs to lock.
                Hooks.log("portrait-only screen: " + screen);
            }
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
