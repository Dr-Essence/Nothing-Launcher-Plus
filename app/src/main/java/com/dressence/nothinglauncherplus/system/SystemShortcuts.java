package com.dressence.nothinglauncherplus.system;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.UserHandle;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Settings screens the module's features open on the system's behalf.
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
