package com.dressence.nothinglauncherplus.system;

import android.hardware.Sensor;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The window manager's rotation sensor (WindowOrientationListener), here MediaTek's device
 * orientation sensor ({@code dev_orient}, type 27), which reports only changes.
 *
 * <p>Waking: the window manager starts the sensor again with {@code enable()}, which clears the
 * last rotation it knew ({@code resetLocked(true)}: proposed and desired -1) and then waits for
 * the sensor; held as it was, the phone gives no change to report, and the screen kept its old
 * rotation until it was turned (DebuG7: 3 s after a wake, sensor on, proposed rotation -1). For
 * a sensor that reports only changes, it starts again keeping what it knew, as Android's own
 * {@code enable(false)} does; a turn while it was off still reports as a change.
 *
 * <p>Always-on display: the window manager keeps the sensor on only while the phone is awake,
 * or while dreaming if {@code shouldStayEnabledWhileDreaming()} says so, which Android answers
 * yes for a type 27 sensor that can wake the phone; MediaTek's cannot, so it was off on the
 * always-on display (DebuG7: awake false, screen on, keyguard drawn, sensor off). With the
 * setting on, it answers yes, and the sensor is on while the always-on display shows; the
 * window manager still turns it off with the screen.
 */
public final class RotationSensor {

    private static final String LISTENER = "com.android.server.wm.WindowOrientationListener";
    private static final int TYPE_DEVICE_ORIENTATION = 27;

    private RotationSensor() {
    }

    public static void install(ClassLoader cl, ConfigStore store) {
        Class<?> listener = XposedHelpers.findClass(LISTENER, cl);
        XposedHelpers.findAndHookMethod(listener, "enable", boolean.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    store.reloadIfChanged();
                    if (!(store.get().lockRotation || store.get().allowRotation)) {
                        return;
                    }
                    Sensor sensor = (Sensor) XposedHelpers.getObjectField(param.thisObject,
                            "mSensor");
                    if (sensor != null && sensor.getType() == TYPE_DEVICE_ORIENTATION) {
                        param.args[0] = false;
                    }
                } catch (Throwable t) {
                    // Android's own start stands.
                }
            }
        });
        XposedBridge.hookAllMethods(listener, "shouldStayEnabledWhileDreaming",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (store.get().lockRotation && store.get().aodRotation) {
                                param.setResult(true);
                            }
                        } catch (Throwable t) {
                            // Android's answer stands.
                        }
                    }
                });
        Hooks.log("rotation sensor: ready");
    }
}
