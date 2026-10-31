package com.dressence.nothinglauncherplus.systemui;

import android.view.SurfaceControl;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Test for the landscape fingerprint (off by default, Diagnostics page). The Goodix driver
 * reads only while the panel's HBM is on, and MediaTek's composer turns HBM on only for a
 * frame that holds Nothing's "NTFingerprintDimLayer". SurfaceFlinger passes that layer to the
 * composer in portrait and never in landscape, although it counts it visible there. The one
 * property that sets it apart from Nothing's illumination layer, which does reach the composer
 * in landscape, is the skip-screenshot flag Nothing gives it (NTUdfpsDimLayerController's
 * showSurface and updateAlpha). With the test on, Nothing's own calls set that flag off for the
 * dim layer only, at the source, so the layer is shown exactly as Nothing shows it otherwise.
 * Side effect while on: screenshots and recordings show the lock screen slightly dimmed.
 */
final class FingerprintTest {

    private static final String DIM = "com.nothing.systemui.biometrics.NTUdfpsDimLayerController";
    /** Inside one of Nothing's dim-layer calls on this thread. */
    private static final ThreadLocal<Boolean> IN_DIM = new ThreadLocal<>();
    private static volatile boolean logged;

    private FingerprintTest() {
    }

    static void install(ClassLoader cl, ConfigStore store) {
        Class<?> dim = XposedHelpers.findClass(DIM, cl);
        XC_MethodHook mark = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                IN_DIM.set(Boolean.TRUE);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                IN_DIM.remove();
            }
        };
        int hooked = XposedBridge.hookAllMethods(dim, "showSurface", mark).size()
                + XposedBridge.hookAllMethods(dim, "updateAlpha", mark).size();
        XposedHelpers.findAndHookMethod(SurfaceControl.Transaction.class, "setSkipScreenshot",
                SurfaceControl.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (IN_DIM.get() == null || !Boolean.TRUE.equals(param.args[1])) {
                            return;
                        }
                        try {
                            if (!store.get().fingerprintTest) {
                                return;
                            }
                            param.args[1] = false;
                            if (!logged) {
                                logged = true;
                                Hooks.log("fingerprint test: dim layer shown without"
                                        + " skip-screenshot");
                            }
                        } catch (Throwable t) {
                            // Nothing's value stands.
                        }
                    }
                });
        Hooks.log("fingerprint test: ready (" + hooked + " dim layer calls)");
    }
}
