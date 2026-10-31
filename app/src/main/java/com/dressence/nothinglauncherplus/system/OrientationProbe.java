package com.dressence.nothinglauncherplus.system;

import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Diagnostics: writes to the LSPosed log which window decides the screen's orientation, each
 * time that changes on the phone's own display. Android picks it in
 * {@code DisplayContent.updateOrientation} and keeps the deciding window in
 * {@code mLastOrientationSource}; this only reads that afterwards, so a screen that does not
 * turn can be traced to whatever holds the orientation (an app's lock, the lock screen, a
 * system window) without a terminal. Changes nothing.
 */
public final class OrientationProbe {

    private static final String CLS_DISPLAY_CONTENT = "com.android.server.wm.DisplayContent";
    /** Last line written, so a pass that changes nothing writes nothing. */
    private static String last;

    private OrientationProbe() {
    }

    public static void install(ClassLoader cl) {
        int hooked = XposedBridge.hookAllMethods(XposedHelpers.findClass(CLS_DISPLAY_CONTENT, cl),
                "updateOrientation", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            report(param.thisObject);
                        } catch (Throwable t) {
                            Hooks.log("orientation probe failed: " + t);
                        }
                    }
                }).size();
        Hooks.log("orientation probe: " + hooked + " hooks");
    }

    /**
     * The window that decided: each container keeps the child it took the orientation from
     * ({@code mLastOrientationSource}), so the chain is followed down from the display (whose
     * own source is only its top display area) to the activity or window at its end.
     */
    private static Object source(Object display) {
        Object source = XposedHelpers.getObjectField(display, "mLastOrientationSource");
        for (int depth = 0; source != null && depth < 16; depth++) {
            Object next = XposedHelpers.getObjectField(source, "mLastOrientationSource");
            if (next == null || next == source) {
                break;
            }
            source = next;
        }
        return source;
    }

    private static void report(Object display) {
        if (!XposedHelpers.getBooleanField(display, "isDefaultDisplay")) {
            return;
        }
        Object rotation = XposedHelpers.getObjectField(display, "mDisplayRotation");
        String line = "orientation: requested "
                + XposedHelpers.callMethod(display, "getLastOrientation")
                + " by " + source(display)
                + " · rotation " + XposedHelpers.callMethod(rotation, "getRotation")
                + " · user rotation mode " + XposedHelpers.callMethod(rotation,
                        "getUserRotationMode")
                + " rotation " + XposedHelpers.callMethod(rotation, "getUserRotation");
        synchronized (OrientationProbe.class) {
            if (line.equals(last)) {
                return;
            }
            last = line;
        }
        Hooks.log(line);
    }
}
