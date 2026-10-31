package com.dressence.nothinglauncherplus.system;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.util.List;
import java.util.function.Consumer;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Gaming launcher while a controller is connected: the home app (Android's HOME role) becomes
 * the launcher picked in the settings when a game controller connects (USB or Bluetooth), and
 * goes back to the one it was when the last controller disconnects, or the setting is turned
 * off. It is Android's own home choice that changes, as when the user picks a home app, so the
 * home button, the home gesture (Launcher3's Quickstep follows the default home and hands it
 * over) and everything else that opens home agree. Nothing is changed while no controller is
 * connected; the home app it replaced is kept in a global setting, so a restart with no
 * controller puts it back, and it is put back only while the gaming launcher is still home (a
 * home app the user picked meanwhile stays).
 *
 * <p>The controller's own home button (PS, Xbox, Guide: {@code KEYCODE_BUTTON_MODE}), which
 * Android's window policy passes to the app in front, opens home while it is on.
 */
public final class ControllerHome {

    private static final String CLS_POLICY = "com.android.server.policy.PhoneWindowManager";
    private static final String ROLE_HOME = "android.app.role.HOME";
    /** The home app the gaming launcher replaced, while it is home. */
    private static final String SAVED = "nlplus_home_before_controller";
    /** A controller's connection settles (Bluetooth controllers can drop and come back). */
    private static final long SETTLE_MS = 1500;
    /** Uids per user (UserHandle.PER_USER_RANGE). */
    private static final int PER_USER_RANGE = 100000;

    private static Context context;
    private static Handler handler;
    private static ConfigStore store;
    private static volatile boolean active;

    private ControllerHome() {
    }

    public static void install(ClassLoader cl, ConfigStore configStore) {
        store = configStore;
        XposedHelpers.findAndHookMethod(CLS_POLICY, cl, "systemReady",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (context != null) {
                            return;
                        }
                        Hooks.guard("controller home start", () -> start((Context)
                                XposedHelpers.getObjectField(param.thisObject, "mContext")))
                                .run();
                    }
                });
        XposedHelpers.findAndHookMethod(CLS_POLICY, cl, "interceptKeyBeforeDispatching",
                android.os.IBinder.class, KeyEvent.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        KeyEvent event = (KeyEvent) param.args[1];
                        if (!active || event.getKeyCode() != KeyEvent.KEYCODE_BUTTON_MODE) {
                            return;
                        }
                        try {
                            homeButton(param, event);
                        } catch (Throwable t) {
                            Hooks.log("controller home button failed: " + t);
                        }
                    }
                });
        Hooks.log("controller home: ready");
    }

    private static void homeButton(XC_MethodHook.MethodHookParam param, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            Object policy = param.thisObject;
            int display = (Integer) XposedHelpers.callMethod(event, "getDisplayId");
            Handler policyHandler = (Handler) XposedHelpers.getObjectField(policy, "mHandler");
            policyHandler.post(Hooks.guard("controller home button", () ->
                    XposedHelpers.callMethod(policy, "launchHomeFromHotKey", display)));
        }
        param.setResult(-1L); // Consumed: the app in front does not get it.
    }

    private static void start(Context system) {
        context = system;
        HandlerThread thread = new HandlerThread("nlplus-controller-home");
        thread.start();
        handler = new Handler(thread.getLooper());
        InputManager input = context.getSystemService(InputManager.class);
        input.registerInputDeviceListener(new InputManager.InputDeviceListener() {
            @Override
            public void onInputDeviceAdded(int deviceId) {
                settle();
            }

            @Override
            public void onInputDeviceRemoved(int deviceId) {
                settle();
            }

            @Override
            public void onInputDeviceChanged(int deviceId) {
                settle();
            }
        }, handler);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                Hooks.guard("controller home settings", () -> {
                    store.reloadIfChanged();
                    settle();
                }).run();
            }
        };
        IntentFilter settings = new IntentFilter(Config.ACTION_RELOAD);
        context.registerReceiver(receiver, settings, null, handler, Context.RECEIVER_EXPORTED);
        IntentFilter boot = new IntentFilter(Intent.ACTION_BOOT_COMPLETED);
        boot.addAction("android.intent.action.USER_SWITCHED"); // Intent.ACTION_USER_SWITCHED
        context.registerReceiver(receiver, boot, null, handler, Context.RECEIVER_NOT_EXPORTED);
    }

    private static void settle() {
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(Hooks.guard("controller home", ControllerHome::apply), SETTLE_MS);
    }

    /** The home app for whether a controller is connected now (on the handler's thread). */
    private static void apply() {
        Config config = store.get();
        String gaming = config.controllerHomeApp;
        boolean want = config.controllerHome && !gaming.isEmpty() && controllerConnected();
        active = want;
        int userId = (Integer) XposedHelpers.callStaticMethod(ActivityManager.class,
                "getCurrentUser");
        UserHandle user = UserHandle.getUserHandleForUid(userId * PER_USER_RANGE);
        Object roles = context.getSystemService("role");
        String home = holder(roles, user);
        // "previous home/gaming launcher set in its place", while the gaming launcher is home.
        String saved = Settings.Global.getString(context.getContentResolver(), SAVED);
        String previous = saved == null ? null : saved.substring(0, saved.indexOf('/'));
        String placed = saved == null ? null : saved.substring(saved.indexOf('/') + 1);
        if (home == null) {
            return;
        }
        if (want) {
            if (home.equals(gaming)) {
                return; // Already home (set here, or the user's own choice).
            }
            if (previous == null || !home.equals(placed)) {
                previous = home; // Not one set here: the user's home, to put back.
            }
            save(previous + "/" + gaming);
            setHome(roles, gaming, user, home);
        } else if (saved != null) {
            save(null);
            if (home.equals(placed) && !home.equals(previous)) {
                setHome(roles, previous, user, home);
            }
        }
    }

    private static void save(String value) {
        Settings.Global.putString(context.getContentResolver(), SAVED, value);
    }

    @SuppressWarnings("unchecked")
    private static String holder(Object roles, UserHandle user) {
        List<String> holders = (List<String>) XposedHelpers.callMethod(roles,
                "getRoleHoldersAsUser", ROLE_HOME, user);
        return holders == null || holders.isEmpty() ? null : holders.get(0);
    }

    private static void setHome(Object roles, String pkg, UserHandle user, String was) {
        Consumer<Boolean> done = ok -> Hooks.log("controller home: " + was + " -> " + pkg
                + (Boolean.TRUE.equals(ok) ? "" : " refused"));
        XposedHelpers.callMethod(roles, "addRoleHolderAsUser", ROLE_HOME, pkg, 0, user,
                context.getMainExecutor(), done);
    }

    private static boolean controllerConnected() {
        InputManager input = context.getSystemService(InputManager.class);
        for (int id : input.getInputDeviceIds()) {
            InputDevice device = input.getInputDevice(id);
            if (device == null || device.isVirtual() || !device.isExternal()) {
                continue;
            }
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                return true;
            }
        }
        return false;
    }
}
