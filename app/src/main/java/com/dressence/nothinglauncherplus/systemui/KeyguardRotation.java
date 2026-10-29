package com.dressence.nothinglauncherplus.systemui;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The lock screen turns with the device while "Home screen rotation" is on.
 *
 * <p>SystemUI's notification shade window (which holds the lock screen) asks for a fixed
 * orientation while the lock screen shows, unless its keyguard screen rotation switch is on
 * ({@code mKeyguardScreenRotation}, from config_enableLockScreenRotation or the
 * lockscreen.rot_override property; off on phones). Then it asks to follow the device and
 * the user's auto-rotate setting, which is the lock screen's own landscape layout. That one
 * switch is turned on while home rotation is on, as each orientation request is made, and left
 * as SystemUI had it otherwise.
 */
final class KeyguardRotation {

    private static final String[] CLS_SHADE_WINDOW = {
            "com.android.systemui.shade.NotificationShadeWindowControllerImpl",
            "com.android.systemui.statusbar.phone.NotificationShadeWindowControllerImpl",
    };
    private static final String FIELD = "mKeyguardScreenRotation";
    /** SystemUI's own value of the switch, kept on the controller. */
    private static final String OWN = "nlplus.ownKeyguardRotation";

    private KeyguardRotation() {
    }

    static void install(ClassLoader cl, ConfigStore store) {
        Class<?> controller = null;
        for (String name : CLS_SHADE_WINDOW) {
            controller = XposedHelpers.findClassIfExists(name, cl);
            if (controller != null) {
                break;
            }
        }
        if (controller == null) {
            Hooks.log("lock screen rotation: no notification shade window controller");
            return;
        }
        try {
            controller.getDeclaredField(FIELD);
        } catch (NoSuchFieldException e) {
            Hooks.log("lock screen rotation: no " + FIELD + " on " + controller.getName());
            return;
        }
        int hooked = XposedBridge.hookAllMethods(controller, "adjustScreenOrientation",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object c = param.thisObject;
                        Object own = XposedHelpers.getAdditionalInstanceField(c, OWN);
                        if (own == null) {
                            own = XposedHelpers.getBooleanField(c, FIELD);
                            XposedHelpers.setAdditionalInstanceField(c, OWN, own);
                        }
                        store.reloadIfChanged();
                        XposedHelpers.setBooleanField(c, FIELD,
                                (Boolean) own || store.get().allowRotation);
                    }
                }).size();
        Hooks.log("lock screen rotation: " + hooked + " orientation hooks on "
                + controller.getSimpleName());
    }
}
