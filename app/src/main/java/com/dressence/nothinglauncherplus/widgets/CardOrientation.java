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
 * landscape the ordinary way, while "System screens in landscape" is on. Every other
 * portrait-only screen opened then is written to the LSPosed log once (with diagnostic logging
 * on: "portrait-only screen: …"), so further
 * ones can be checked and added from what is really opened (system apps only).
 *
 * <p>Nothing Launcher's home screen too (with Home screen rotation on): its manifest declares
 * it portrait, and Nothing's own RotationHelper takes that off (setRequestedOrientation -1,
 * auto-rotate respected) only once the home screen runs, after its first frame. So each time
 * the launcher started (a reboot, a restart) with the phone on its side, Android opened it in
 * portrait, drew the portrait home screen, then turned the screen and laid it out again
 * (DebuG16: opened at rotation 0, -1 asked 0.3 s later, turned at 05:54:36.0). Given the value
 * Nothing's helper asks for from the start, the home screen opens in the orientation it ends
 * in and is laid out once.
 */
public final class CardOrientation {

    private static final String CLS_ACTIVITY_RECORD = "com.android.server.wm.ActivityRecord";
    private static final int ROTATION_CHANGES = ActivityInfo.CONFIG_ORIENTATION
            | ActivityInfo.CONFIG_SCREEN_SIZE | ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE
            | ActivityInfo.CONFIG_SCREEN_LAYOUT;

    /**
     * Nothing's portrait-locked screens that work in landscape, as package/activity:
     * SystemUI's quick settings widget picker (QSWidgetActivity: a scrolling list of system
     * and app widgets under a collapsing toolbar), and Nothing Launcher's settings pages opened
     * from the home screen's menu (Home settings, App drawer settings, Customisation: each a
     * preference list in its BaseSettingsActivity, like Settings' pages; DebuG21 logged Home
     * settings turning the phone to portrait). The launcher's preview pages (icon pack, screen
     * layout: BaseHomePreviewActivity, a portrait home screen drawn small) keep the lock.
     */
    private static final java.util.Set<String> SYSTEM_SCREENS = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "com.android.systemui/com.nothing.systemui.qs.widget.QSWidgetActivity",
                    "com.nothing.launcher/com.nothing.launcher.setting.homesettings"
                            + ".HomeSettingsActivity",
                    "com.nothing.launcher/com.nothing.launcher.setting.allapp"
                            + ".AllAppSettingsActivity",
                    "com.nothing.launcher/com.nothing.launcher.setting.wallpaper"
                            + ".CustomisationSettingsActivity"));
    /**
     * Apps whose portrait-locked screens all work in landscape: Settings (its pages are lists,
     * and Nothing locks them all; the reports showed SettingsHomepageActivity and SubSettings
     * asking for portrait). Their screens named with a part of {@link #KEEP_PORTRAIT} keep
     * Nothing's lock.
     */
    private static final java.util.Set<String> SYSTEM_APPS = new java.util.HashSet<>(
            java.util.Arrays.asList("com.android.settings"));
    /**
     * Screens left portrait: PIN / password / pattern and lock confirmation, fingerprint and
     * face set-up (drawn around the sensor and camera as they sit in portrait), first set-up,
     * and the home shown while the phone starts.
     */
    private static final String[] KEEP_PORTRAIT = {"Password", "ConfirmLock", "ChooseLock",
            "ConfirmDeviceCredential", "Fingerprint", "Biometric", "Face", "Setup",
            "FallbackHome"};
    /** Nothing Launcher's home screen activity (its manifest: screenOrientation portrait). */
    private static final String HOME = com.dressence.nothinglauncherplus.Config.LAUNCHER_PKG
            + "/com.android.searchlauncher.SearchLauncher";
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
        if (HOME.equals(screen)) {
            store.reloadIfChanged();
            if (store.get().allowRotation) {
                info.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            }
            return;
        }
        if (!WidgetApps.handles(info.packageName)) {
            store.reloadIfChanged();
            if (!store.get().systemScreens) {
                return;
            }
            if (SYSTEM_SCREENS.contains(screen) || (SYSTEM_APPS.contains(info.packageName)
                    && !keepsPortrait(info.name))) {
                info.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            } else if (info.applicationInfo != null && (info.applicationInfo.flags
                    & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    && store.get().diagnosticLogging && LOGGED.add(screen)) {
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

    private static boolean keepsPortrait(String activity) {
        for (String part : KEEP_PORTRAIT) {
            if (activity.contains(part)) {
                return true;
            }
        }
        return false;
    }

    static boolean isPortraitLock(int orientation) {
        return orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                || orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                || orientation == ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
    }
}
