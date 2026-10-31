package com.dressence.nothinglauncherplus.launcher;

import android.view.MotionEvent;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Diagnostic, while the drawer's search gets no results turned: Nothing's drawer search
 * (DrawerSearchController, y8.h) starts a search session only from its touch handler (y8.i),
 * on a tap it decides by its own flags; turned, no session started after a turn (DebuG8: no
 * "onTransitionStart: enter = true" in landscape). With diagnostic logging on, each tap it
 * judges and each session it starts is logged with those flags. To go once the cause is fixed.
 */
final class DrawerSearchTrace {

    private DrawerSearchTrace() {
    }

    static void install(ClassLoader cl, ConfigStore store) {
        Class<?> touch = XposedHelpers.findClassIfExists("y8.i", cl);
        Class<?> controller = XposedHelpers.findClassIfExists("y8.h", cl);
        Class<?> flags = XposedHelpers.findClassIfExists("j9.f", cl);
        if (touch == null || controller == null) {
            Hooks.log("drawer search trace: classes not found");
            return;
        }
        XposedBridge.hookAllMethods(touch, "a", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!store.get().diagnosticLogging || param.args.length != 2
                            || !(param.args[0] instanceof MotionEvent)) {
                        return;
                    }
                    MotionEvent event = (MotionEvent) param.args[0];
                    if (event.getActionMasked() != MotionEvent.ACTION_UP) {
                        return;
                    }
                    Object enabled = flags == null ? "?" : XposedHelpers.callMethod(
                            XposedHelpers.getStaticObjectField(flags, "a"), "a");
                    Object busy = XposedHelpers.callMethod(
                            XposedHelpers.getObjectField(param.thisObject, "b"), "S");
                    Object transition = XposedHelpers.callMethod(
                            XposedHelpers.getObjectField(param.thisObject, "c"), "i");
                    Hooks.log("drawer search tap at " + (int) event.getRawX() + ","
                            + (int) event.getRawY() + ": handled " + param.getResult()
                            + ", search on " + enabled + ", busy " + busy + ", in transition "
                            + transition + ", second arg " + param.args[1]);
                } catch (Throwable t) {
                    Hooks.log("drawer search trace: " + t);
                }
            }
        });
        XposedBridge.hookAllMethods(controller, "F", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (store.get().diagnosticLogging) {
                    Hooks.log("drawer search: start " + (param.args.length > 0 ? param.args[0]
                            : "") + " -> " + param.getResult());
                }
            }
        });
        Hooks.log("drawer search trace: ready");
    }
}
