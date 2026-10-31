package com.dressence.nothinglauncherplus.xposed;

import java.lang.reflect.Member;
import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;

/**
 * What every hook shares: logging, installing a feature so its failure is its own, guarding
 * code the hooked app calls back into, and deoptimizing methods whose callees are hooked.
 *
 * <p>Exceptions thrown inside an Xposed hook callback are caught by the framework, but code
 * the module hands to the app (a Runnable it posts, a listener, a receiver, a Drawable it
 * draws) runs as the app's own: an exception there crashes the app. Such code goes through
 * {@link #guard}.
 */
public final class Hooks {

    private static final String TAG = "NothingLauncher+: ";

    public interface Installer {
        void run() throws Throwable;
    }

    private Hooks() {
    }

    public static void log(String message) {
        try {
            XposedBridge.log(TAG + message);
        } catch (Throwable notHooked) {
            // In the module's own app (the overlay) there is no Xposed: Android's log.
            android.util.Log.i("NothingLauncher+", message);
        }
    }

    /** Installs one feature; if it fails it is logged and skipped, the others still work. */
    public static void install(String name, Installer installer) {
        try {
            installer.run();
        } catch (Throwable t) {
            log("failed to install " + name + ": " + t);
        }
    }

    /** {@code task}, made safe to hand to the app: what it throws is logged, not raised. */
    public static Runnable guard(String what, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                log(what + " failed: " + t);
            }
        };
    }

    private static Method deoptimizeMethod;
    private static boolean deoptimizeLookedUp;

    /**
     * Forces {@code member} to run interpreted so callees it inlined (small getters we hook) go
     * through their hooks again. LSPosed exposes this outside the public API 82 surface.
     */
    public static synchronized void deoptimize(Member member) {
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

    public static void deoptimizeAll(Class<?> cls, String methodName) {
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                deoptimize(m);
            }
        }
    }
}
