package com.dressence.nothinglauncherplus.system;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.UserHandle;
import android.provider.Settings;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Screens the module's features open on the system's behalf. Android 15+ lets an app open
 * another app's activity only with an intent matching that activity's intent filter, and
 * Nothing's Settings refused the launcher's intent for Running services (DebuG10: START
 * result -92, "does your intent not match its declared intent-filter"); system_server is not
 * held to that. Running services is opened by its component; where that cannot start,
 * developer options, which lists it.
 */
public final class SystemShortcuts {

    private static final String CLS_POLICY = "com.android.server.policy.PhoneWindowManager";
    private static final ComponentName RUNNING_SERVICES = new ComponentName(
            "com.android.settings", "com.android.settings.Settings$DevRunningServicesActivity");
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
                Hooks.log("running services: asked");
                Hooks.guard("running services", () -> openRunningServices(context)).run();
                setResultCode(android.app.Activity.RESULT_OK); // Answered: the caller waits.
            }
        }, new IntentFilter(Config.ACTION_RUNNING_SERVICES), Context.RECEIVER_EXPORTED);
    }

    private static void openRunningServices(Context context) {
        int userId = (Integer) XposedHelpers.callStaticMethod(ActivityManager.class,
                "getCurrentUser");
        UserHandle user = UserHandle.getUserHandleForUid(userId * PER_USER_RANGE);
        long identity = Binder.clearCallingIdentity();
        try {
            try {
                start(context, new Intent().setComponent(RUNNING_SERVICES), user);
                Hooks.log("running services: opened");
            } catch (Throwable t) {
                Hooks.log("running services: " + t + "; developer options instead");
                start(context, new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                        user);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static void start(Context context, Intent intent, UserHandle user) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        XposedHelpers.callMethod(context, "startActivityAsUser", intent, user);
    }
}
