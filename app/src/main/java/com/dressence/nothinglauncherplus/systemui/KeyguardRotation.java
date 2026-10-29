package com.dressence.nothinglauncherplus.systemui;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The lock screen turns with the device while "Home screen rotation" is on.
 *
 * <p>SystemUI's notification shade window (which holds the lock screen) asks, while the lock
 * screen, bouncer or always-on display shows, to follow the device and the user's auto-rotate
 * setting if {@code KeyguardStateController.isKeyguardScreenRotationAllowed()}, and for a fixed
 * portrait otherwise. That answer comes from the lockscreen.rot_override property,
 * config_enableLockScreenRotation and a landscape lock screen flag, all off on phones. While
 * home rotation is on it answers yes, which is the lock screen's own landscape layout; SystemUI
 * asks again on each configuration change, so the setting is followed from then on.
 */
final class KeyguardRotation {

    private static final String CLS_KEYGUARD_STATE =
            "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl";

    private KeyguardRotation() {
    }

    static void install(ClassLoader cl, ConfigStore store) {
        Class<?> state = XposedHelpers.findClassIfExists(CLS_KEYGUARD_STATE, cl);
        if (state == null) {
            Hooks.log("lock screen rotation: no " + CLS_KEYGUARD_STATE);
            return;
        }
        int hooked = XposedBridge.hookAllMethods(state, "isKeyguardScreenRotationAllowed",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(param.getResult())) {
                            return;
                        }
                        store.reloadIfChanged();
                        if (store.get().allowRotation) {
                            param.setResult(true);
                        }
                    }
                }).size();
        Hooks.log("lock screen rotation: " + hooked + " hooks");
    }
}
