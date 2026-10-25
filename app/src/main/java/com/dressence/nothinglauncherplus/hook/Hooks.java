package com.dressence.nothinglauncherplus.hook;

import java.lang.reflect.Member;
import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;

/** Logging, fault-isolated installation and deoptimization shared by all hooks. */
final class Hooks {

    private static final String TAG = "NothingLauncher+: ";

    interface Installer {
        void run() throws Throwable;
    }

    private Hooks() {
    }

    static void log(String message) {
        XposedBridge.log(TAG + message);
    }

    /** A failing feature is logged and skipped so the remaining features still work. */
    static void install(String name, Installer installer) {
        try {
            installer.run();
        } catch (Throwable t) {
            log("failed to install hook '" + name + "': " + t);
        }
    }

    private static Method deoptimizeMethod;
    private static boolean deoptimizeLookedUp;

    /**
     * Forces {@code member} to run interpreted so callees it inlined (small getters we hook) go
     * through their hooks again. LSPosed exposes this outside the public API 82 surface.
     */
    static synchronized void deoptimize(Member member) {
        if (!deoptimizeLookedUp) {
            deoptimizeLookedUp = true;
            try {
                deoptimizeMethod = XposedBridge.class.getDeclaredMethod(
                        "deoptimizeMethod", Member.class);
            } catch (NoSuchMethodException e) {
                log("deoptimizeMethod unavailable; hooks may be bypassed if ART inlined them");
            }
        }
        if (deoptimizeMethod == null) {
            return;
        }
        try {
            deoptimizeMethod.invoke(null, member);
        } catch (Throwable t) {
            log("deoptimize " + member + " failed: " + t);
        }
    }

    static void deoptimizeAll(Class<?> cls, String methodName) {
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                deoptimize(m);
            }
        }
    }
}
