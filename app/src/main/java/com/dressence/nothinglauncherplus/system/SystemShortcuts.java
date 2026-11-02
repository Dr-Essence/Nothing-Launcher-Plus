package com.dressence.nothinglauncherplus.system;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.UserHandle;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Screens the module's features open on the system's behalf.
 *
 * <p>Running services (recents' memory button): in Nothing's Settings (Settings 16.0.0) it is
 * the fragment {@code com.android.settings.applications.RunningServices}, which Developer
 * options opens in {@code SubSettings} (development_settings.xml, key running_apps). There is
 * no Settings$DevRunningServicesActivity (crDroid's way in: START -92, class not found), and
 * Settings$RunningServicesActivity shows the app list (ManageApplications has no case for it).
 * SubSettings is exported but needs LAUNCH_SUBSETTINGS (preinstalled|signature), which Nothing
 * Launcher does not ask for; the system is not held to it, and SubSettings takes any fragment
 * named in {@code :settings:show_fragment} (isValidFragment answers true). So the system opens
 * it the way Developer options does. Where that cannot resolve, nothing is opened here and the
 * caller is not answered, so it opens developer options itself.
 */
public final class SystemShortcuts {

    private static final String CLS_POLICY = "com.android.server.policy.PhoneWindowManager";
    private static final ComponentName SUB_SETTINGS =
            new ComponentName("com.android.settings", "com.android.settings.SubSettings");
    private static final String RUNNING_SERVICES =
            "com.android.settings.applications.RunningServices";
    private static final int PER_USER_RANGE = 100000;

    private SystemShortcuts() {
    }

    public static void install(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(CLS_POLICY, cl, "systemReady", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Hooks.guard("system shortcuts", () -> listen((Context)
                        XposedHelpers.getObjectField(param.thisObject, "mContext"))).run();
            }
        });
    }

    private static void listen(Context context) {
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                boolean[] opened = new boolean[1];
                Hooks.guard("running services",
                        () -> opened[0] = openRunningServices(context)).run();
                if (opened[0]) {
                    setResultCode(Activity.RESULT_OK); // The caller opens nothing else.
                }
            }
        }, new IntentFilter(Config.ACTION_RUNNING_SERVICES), Context.RECEIVER_EXPORTED);
        // The controller's Select button on the home screen: the notification panel. Nothing
        // Launcher may not expand it (EXPAND_STATUS_BAR, a SecurityException each press); the
        // system may.
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                Hooks.guard("expand notifications", () -> {
                    long identity = Binder.clearCallingIdentity();
                    try {
                        XposedHelpers.callMethod(context.getSystemService("statusbar"),
                                "expandNotificationsPanel");
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }).run();
            }
        }, new IntentFilter(Config.ACTION_EXPAND_NOTIFICATIONS), Context.RECEIVER_EXPORTED);
        // The controller's Start on a music widget: the app playing. Only the system may list
        // media sessions (MEDIA_CONTENT_CONTROL, which Nothing Launcher does not hold).
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                Hooks.guard("open media app", () -> openMediaApp(context)).run();
            }
        }, new IntentFilter(Config.ACTION_OPEN_MEDIA_APP), Context.RECEIVER_EXPORTED);
    }

    /**
     * Opens the app of the session playing (else the most recent one): its own session
     * activity (the player screen), or its launcher activity.
     */
    private static void openMediaApp(Context context) {
        long identity = Binder.clearCallingIdentity();
        try {
            MediaSessionManager sessions = context.getSystemService(MediaSessionManager.class);
            List<MediaController> controllers = sessions.getActiveSessions(null);
            MediaController chosen = null;
            for (MediaController controller : controllers) {
                PlaybackState state = controller.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    chosen = controller;
                    break;
                }
            }
            if (chosen == null && !controllers.isEmpty()) {
                chosen = controllers.get(0);
            }
            if (chosen == null) {
                Hooks.log("open media app: no media session");
                return;
            }
            PendingIntent session = chosen.getSessionActivity();
            if (session != null) {
                try {
                    ActivityOptions options = ActivityOptions.makeBasic();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        options.setPendingIntentBackgroundActivityStartMode(
                                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    }
                    session.send(context, 0, null, null, null, null, options.toBundle());
                    return;
                } catch (PendingIntent.CanceledException e) {
                    // Fall back to the app's launcher activity.
                }
            }
            Intent launch = context.getPackageManager()
                    .getLaunchIntentForPackage(chosen.getPackageName());
            if (launch == null) {
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int userId = (Integer) XposedHelpers.callStaticMethod(ActivityManager.class,
                    "getCurrentUser");
            XposedHelpers.callMethod(context, "startActivityAsUser", launch,
                    UserHandle.getUserHandleForUid(userId * PER_USER_RANGE));
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /** Running services in SubSettings, as Developer options opens it; false if it cannot. */
    private static boolean openRunningServices(Context context) {
        Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(SUB_SETTINGS)
                .putExtra(":settings:show_fragment", RUNNING_SERVICES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (context.getPackageManager().resolveActivity(intent, 0) == null) {
            Hooks.log("running services: SubSettings not found");
            return false;
        }
        int userId = (Integer) XposedHelpers.callStaticMethod(ActivityManager.class,
                "getCurrentUser");
        UserHandle user = UserHandle.getUserHandleForUid(userId * PER_USER_RANGE);
        long identity = Binder.clearCallingIdentity();
        try {
            XposedHelpers.callMethod(context, "startActivityAsUser", intent, user);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        Hooks.log("running services: opened");
        return true;
    }
}
