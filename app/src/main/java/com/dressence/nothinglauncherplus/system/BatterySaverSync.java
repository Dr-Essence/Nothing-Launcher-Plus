package com.dressence.nothinglauncherplus.system;

import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Battery saver that another app switched through its setting can be switched again.
 *
 * <p>Android's BatterySaverStateMachine keeps battery saver's state (off, on by the user, on
 * automatically) beside the saver itself, and decides a tap of the tile or Settings switch from
 * that state. An app that switches battery saver by writing Settings.Global low_power (as
 * EnforceDoze does from a root shell: DebuG18, "putForUser ... low_power, value=0/1") reaches
 * the saver through setSettingsLocked -> enableBatterySaverLocked, which turns it on or off but
 * leaves the state as it was. Turned off that way while the state said "on by the user", every
 * later tap was refused as already on ("Tried to enable BS when it's already MANUAL_ON") until a
 * reboot. When the setting changes the saver here, the state is made to agree: on from off is
 * "on by the user", off from on (by the user or automatically) is "off". Android's own switching
 * is untouched (its writes of the setting change nothing here, as its record already agrees).
 */
public final class BatterySaverSync {

    private static final String CLS_MACHINE =
            "com.android.server.power.batterysaver.BatterySaverStateMachine";
    private static final int STATE_OFF = 1;
    private static final int STATE_MANUAL_ON = 2;
    private static final int STATE_AUTOMATIC_ON = 3;
    private static final String BEFORE = "nlplus.saverBefore";

    private BatterySaverSync() {
    }

    public static void install(ClassLoader cl) {
        XposedBridge.hookAllMethods(XposedHelpers.findClass(CLS_MACHINE, cl), "setSettingsLocked",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            XposedHelpers.setAdditionalInstanceField(param.thisObject, BEFORE,
                                    XposedHelpers.getBooleanField(param.thisObject,
                                            "mSettingBatterySaverEnabled"));
                        } catch (Throwable t) {
                            // Android's handling stands.
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object machine = param.thisObject;
                            Object before = XposedHelpers.getAdditionalInstanceField(machine,
                                    BEFORE);
                            boolean setting = (Boolean) param.args[0];
                            if (!(before instanceof Boolean) || (Boolean) before == setting) {
                                return; // The setting did not switch the saver.
                            }
                            boolean on = (Boolean) XposedHelpers.callMethod(
                                    XposedHelpers.getObjectField(machine,
                                            "mBatterySaverController"), "isFullEnabled");
                            int state = XposedHelpers.getIntField(machine, "mState");
                            int agreed = state;
                            if (on && state == STATE_OFF) {
                                agreed = STATE_MANUAL_ON;
                            } else if (!on && (state == STATE_MANUAL_ON
                                    || state == STATE_AUTOMATIC_ON)) {
                                agreed = STATE_OFF;
                            }
                            if (agreed != state) {
                                XposedHelpers.setIntField(machine, "mState", agreed);
                                Hooks.log("battery saver: switched through its setting ("
                                        + (on ? "on" : "off") + "); state " + state + " -> "
                                        + agreed);
                            }
                        } catch (Throwable t) {
                            Hooks.log("battery saver sync: " + t);
                        }
                    }
                });
        Hooks.log("battery saver sync: ready");
    }
}
