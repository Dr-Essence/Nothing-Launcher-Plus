package com.dressence.nothinglauncherplus.systemui;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The fingerprint on the turned lock screen. Nothing's optical sensor (Goodix) reads only while
 * the panel's HBM is on, and MediaTek's composer turns HBM on for a frame that holds a buffer
 * named "NTFingerprintDimLayer" (Nothing's dim layer, from its BLASTBufferQueue). Nothing's
 * SurfaceFlinger leaves the layer named "NTFingerprintDimLayer" out of composition whenever the
 * display's transform hint is ROT_90 or ROT_270 (the per-layer step that builds the composition
 * list checks the tag its Layer constructor sets from the name), so in landscape HBM never
 * comes on and every touch times out (GF_ERROR_UI_READY_TIMEOUT). Nothing's two sensor layers,
 * the dim layer and the illumination dot, are also drawn in the panel's portrait coordinates.
 *
 * With the lock screen turning (the module's setting), this keeps Nothing's mechanism as it is
 * and makes it work turned: the dim layer's layer name (not its buffer's, which the composer
 * reads) leaves out the tag SurfaceFlinger matches, and both layers are placed in the panel's
 * portrait coordinates in every rotation (AOSP's rotateBounds), so the dim covers the screen and
 * the dot lights the sensor. Portrait is unchanged (identity). With colour inversion on, the dim
 * keeps its name: SurfaceFlinger exempts the tagged layer from colour transforms.
 */
final class LandscapeFingerprint {

    private static final String DIM = "com.nothing.systemui.biometrics.NTUdfpsDimLayerController";
    private static final String DOT =
            "com.nothing.systemui.biometrics.NTUdfpsIlluminationController";
    static final String NOTHING_NAME = "NTFingerprintDimLayer";
    static final String TURNED_NAME = "NTFingerprintDim (NLPlus)";

    /** The controller whose call is running on this thread. */
    private static final ThreadLocal<Object> CURRENT = new ThreadLocal<>();
    private static final List<WeakReference<Object>> CONTROLLERS = new CopyOnWriteArrayList<>();
    private static volatile boolean listening;

    private LandscapeFingerprint() {
    }

    static void install(ClassLoader cl, ConfigStore store) {
        Class<?> dim = XposedHelpers.findClass(DIM, cl);
        Class<?> dot = XposedHelpers.findClass(DOT, cl);
        XC_MethodHook mark = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                CURRENT.set(param.thisObject);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                CURRENT.remove();
            }
        };
        XC_MethodHook created = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Hooks.guard("fingerprint layers", () -> remember(param.thisObject)).run();
            }
        };
        for (Class<?> c : new Class<?>[] {dim, dot}) {
            XposedBridge.hookAllConstructors(c, created);
            XposedBridge.hookAllMethods(c, "createSurface", mark);
            XposedBridge.hookAllMethods(c, "showSurface", mark);
        }
        XposedBridge.hookAllMethods(dim, "updateAlpha", mark);

        // The dim layer's name, as Nothing's createSurface gives it.
        XposedHelpers.findAndHookMethod(SurfaceControl.Builder.class, "setName", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object owner = CURRENT.get();
                        if (owner == null || !dim.isInstance(owner)
                                || !NOTHING_NAME.equals(param.args[0])) {
                            return;
                        }
                        try {
                            if (store.get().lockRotation && !inverted(owner)) {
                                param.args[0] = TURNED_NAME;
                                Hooks.log("landscape fingerprint: dim layer named \""
                                        + TURNED_NAME + "\" (buffer keeps " + NOTHING_NAME + ")");
                            }
                        } catch (Throwable t) {
                            // Nothing's name stands.
                        }
                    }
                });
        // Each of Nothing's show transactions sets the layer's z; the same transaction places it.
        XposedHelpers.findAndHookMethod(SurfaceControl.Transaction.class, "setLayer",
                SurfaceControl.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object owner = CURRENT.get();
                        if (owner == null) {
                            return;
                        }
                        try {
                            if (store.get().lockRotation
                                    && param.args[0] == surface(owner)) {
                                place((SurfaceControl.Transaction) param.thisObject, owner);
                            }
                        } catch (Throwable t) {
                            // Left where Nothing puts it.
                        }
                    }
                });
        Hooks.log("landscape fingerprint: ready");
    }

    private static void remember(Object controller) {
        CONTROLLERS.add(new WeakReference<>(controller));
        if (listening) {
            return;
        }
        Context context = (Context) XposedHelpers.getObjectField(controller, "mContext");
        DisplayManager displays =
                context != null ? context.getSystemService(DisplayManager.class) : null;
        if (displays == null) {
            return;
        }
        listening = true;
        displays.registerDisplayListener(new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
            }

            @Override
            public void onDisplayRemoved(int displayId) {
            }

            @Override
            public void onDisplayChanged(int displayId) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    Hooks.guard("fingerprint layers turn", LandscapeFingerprint::placeAll).run();
                }
            }
        }, new Handler(Looper.getMainLooper()));
    }

    /** After a turn: the layers that exist now, placed for the new rotation. */
    private static void placeAll() {
        for (WeakReference<Object> ref : CONTROLLERS) {
            Object controller = ref.get();
            if (controller == null) {
                CONTROLLERS.remove(ref);
                continue;
            }
            SurfaceControl sc = surface(controller);
            if (sc == null || !sc.isValid()) {
                continue;
            }
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            place(t, controller);
            t.apply();
        }
    }

    private static SurfaceControl surface(Object controller) {
        return (SurfaceControl) XposedHelpers.getObjectField(controller, "mSurfaceControl");
    }

    private static void place(SurfaceControl.Transaction t, Object controller) {
        Display display = (Display) XposedHelpers.getObjectField(controller, "mDisplay");
        float[] m = panel(display.getRotation(),
                XposedHelpers.getIntField(controller, "mDisplayWidth"),
                XposedHelpers.getIntField(controller, "mDisplayHeight"));
        // As Transaction.setMatrix(sc, Matrix, float[]) passes android.graphics.Matrix values
        // (both setMatrix forms are hidden from the public SDK: called by reflection, which
        // System UI, a platform app, may use).
        SurfaceControl sc = surface(controller);
        XposedHelpers.callMethod(t, "setMatrix", sc, m[0], m[3], m[1], m[4]);
        XposedHelpers.callMethod(t, "setPosition", sc, m[2], m[5]);
    }

    /**
     * The panel's portrait coordinates in the turned display's, as android.graphics.Matrix
     * values (x' = m0·x + m1·y + m2, y' = m3·x + m4·y + m5), for a panel {@code w} wide and
     * {@code h} high (AOSP's rotateBounds for the fingerprint sensor).
     */
    static float[] panel(int rotation, int w, int h) {
        switch (rotation) {
            case Surface.ROTATION_90:
                return new float[] {0, 1, 0, -1, 0, w, 0, 0, 1};
            case Surface.ROTATION_180:
                return new float[] {-1, 0, w, 0, -1, h, 0, 0, 1};
            case Surface.ROTATION_270:
                return new float[] {0, -1, h, 1, 0, 0, 0, 0, 1};
            default:
                return new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
        }
    }

    private static boolean inverted(Object controller) {
        Context context = (Context) XposedHelpers.getObjectField(controller, "mContext");
        return context != null && Settings.Secure.getInt(context.getContentResolver(),
                "accessibility_display_inversion_enabled", 0) == 1;
    }
}
