package com.dressence.nothinglauncherplus.hook;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;

import com.dressence.nothinglauncherplus.Config;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Landscape home screen and app drawer.
 *
 * <p>Nothing's DeviceProfile subclass replaces AOSP's sizing with portrait-only math (cell width
 * from the screen height, a hotseat always at the bottom, a 1080x2392 portrait reference scale),
 * and the base class no longer carries AOSP's versions of those methods. Rotated, that squashes
 * everything. In phone landscape this class answers those methods with AOSP's own vertical-bar
 * layout (hotseat at the side, cells filling the workspace, labels hidden when rows get tight);
 * portrait is never touched. It also lets the launcher follow auto-rotate and gives the Smart
 * drawer more columns in landscape.
 */
final class Landscape {

    private static final String CLS_SMART_CONTAINER = SmartDrawer.SMART_PKG + "SmartFolderContainer";
    private static final String CLS_UTILITIES = "com.android.launcher3.Utilities";
    private static final String CLS_ROTATION_HELPER = "com.android.launcher3.states.RotationHelper";
    private static final String CLS_LAUNCHER = "com.android.launcher3.Launcher";

    /** Nothing's portrait reference size, transposed for landscape. */
    private static final float REFERENCE_LONG = 2392f;
    private static final float REFERENCE_SHORT = 1080f;
    /** Landscape Smart drawer: tile width relative to the drawer height. */
    private static final float SMART_TILE_TO_HEIGHT = 0.55f;

    private final ClassLoader cl;
    private final ConfigStore store;
    private Method calculateTextHeight;
    private Method iconSizeWithOverlap;

    Landscape(ClassLoader cl, ConfigStore store) {
        this.cl = cl;
        this.store = store;
    }

    void install() {
        Hooks.install("home rotation", this::hookRotation);
        Hooks.install("landscape layout", this::hookDeviceProfile);
        Hooks.install("landscape smart drawer", this::hookSmartProfile);
    }

    // ---------------------------------------------------------------------------------------
    // Rotation
    // ---------------------------------------------------------------------------------------

    /**
     * RotationHelper follows "allow home rotation", which Android 16 forces off on phones. Feed
     * it our setting instead; the rest of the rotation flow (config change, new DeviceProfile,
     * relayout) is stock.
     */
    private void hookRotation() {
        Class<?> helper = XposedHelpers.findClass(CLS_ROTATION_HELPER, cl);
        XposedHelpers.findAndHookMethod(helper, "notifyChange", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!XposedHelpers.getBooleanField(param.thisObject, "mIgnoreAutoRotateSettings")) {
                    XposedHelpers.setBooleanField(param.thisObject, "mHomeRotationEnabled",
                            store.get().allowRotation);
                }
            }
        });
        Class<?> launcherClass = XposedHelpers.findClass(CLS_LAUNCHER, cl);
        store.addChangeListener(() -> {
            Object launcher = XposedHelpers.callMethod(
                    XposedHelpers.getStaticObjectField(launcherClass, "ACTIVITY_TRACKER"),
                    "getCreatedContext");
            if (launcher != null) {
                XposedHelpers.callMethod(
                        XposedHelpers.callMethod(launcher, "getRotationHelper"), "notifyChange");
            }
        });
    }

    // ---------------------------------------------------------------------------------------
    // Workspace and hotseat geometry
    // ---------------------------------------------------------------------------------------

    /** Nothing's DeviceProfile subclass: the type the Smart drawer profile is computed from. */
    private Class<?> ntDeviceProfileClass() {
        Class<?> smartProfile = XposedHelpers.findClass(CLS_SMART_CONTAINER, cl);
        try {
            smartProfile = smartProfile.getDeclaredField("smartProfile").getType();
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("SmartFolderContainer.smartProfile not found");
        }
        for (Method m : smartProfile.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[0] == Context.class) {
                return p[1];
            }
        }
        throw new IllegalStateException("Nothing DeviceProfile class not found");
    }

    private void hookDeviceProfile() throws Throwable {
        Class<?> dp = ntDeviceProfileClass();
        calculateTextHeight = XposedHelpers.findMethodExact(
                XposedHelpers.findClass(CLS_UTILITIES, cl), "calculateTextHeight", float.class);
        iconSizeWithOverlap = XposedHelpers.findMethodExact(
                dp.getSuperclass(), "getIconSizeWithOverlap", int.class);

        // Scale everything against the transposed reference, not the portrait one.
        after(dp, "earlyInit", p -> {
            XposedHelpers.setFloatField(p.thisObject, "availableRatio",
                    Math.min(intField(p, "widthPx") / REFERENCE_LONG,
                            intField(p, "heightPx") / REFERENCE_SHORT));
            return null;
        }, Resources.class);

        replace(dp, "getDesiredIconDrawablePadding", p -> (int) (
                XposedHelpers.getIntField(p.thisObject, "mIconDrawablePaddingOriginalPx")
                        * XposedHelpers.getFloatField(p.thisObject, "iconScale")));
        replace(dp, "getFixedCellWidth", p -> intField(p, "iconSizePx")
                + intField(p, "iconDrawablePaddingPx"));
        replace(dp, "getFixedCellHeight", p -> intField(p, "iconSizePx")
                + intField(p, "iconDrawablePaddingPx") + textHeight(intField(p, "iconTextSizePx")));
        replace(dp, "getConfiguredGridHorizontalMargin", p -> 0, Resources.class);
        replace(dp, "getQsbOffsetY", p -> 0); // The hotseat hides the search bar at the side.

        // AOSP non-scalable cell content: icon (+ label when there is room; AOSP's own
        // hideWorkspaceLabelsIfNotEnoughSpace runs right after and drops labels if not).
        replace(dp, "adjustWorkspaceLabelPadding", p -> {
            Object o = p.thisObject;
            int icon = intField(p, "iconSizePx");
            int pad = intField(p, "iconDrawablePaddingPx");
            int cellHeight = overlap(o, icon) + pad + textHeight(intField(p, "iconTextSizePx"));
            XposedHelpers.setIntField(o, "cellWidthPx", icon + pad);
            XposedHelpers.setIntField(o, "cellHeightPx", cellHeight);
            Point cell = (Point) XposedHelpers.callMethod(o, "getCellSize");
            XposedHelpers.setIntField(o, "cellYPaddingPx", Math.max(0, (cell.y - cellHeight) / 2));
            return null;
        }, boolean.class);

        replace(dp, "updateCellLayoutPadding", p -> {
            Resources res = (Resources) p.args[0];
            int id = res.getIdentifier("cell_layout_padding", "dimen", Config.LAUNCHER_PKG);
            int pad = id != 0 ? res.getDimensionPixelSize(id) : 0;
            XposedHelpers.setObjectField(p.thisObject, "cellLayoutPaddingPx",
                    new Rect(pad, pad, pad, pad));
            return null;
        }, Resources.class);

        // Hotseat as a vertical bar at the side.
        replace(dp, "updateHotseatSizes", p -> {
            Object o = p.thisObject;
            int icon = (Integer) p.args[0];
            XposedHelpers.setIntField(o, "hotseatCellHeightPx", overlap(o, icon));
            XposedHelpers.setIntField(o, "hotseatBarSizePx", icon
                    + intField(p, "mHotseatBarEdgePaddingPx")
                    + intField(p, "mHotseatBarWorkspaceSpacePx"));
            return null;
        }, int.class);
        replace(dp, "updateHotseatDsbWidth", p -> null, Context.class);

        replace(dp, "updateWorkspacePadding", p -> {
            Object o = p.thisObject;
            Rect padding = (Rect) XposedHelpers.getObjectField(o, "workspacePadding");
            int bar = intField(p, "hotseatBarSizePx");
            int edge = intField(p, "mHotseatBarEdgePaddingPx");
            padding.top = 0;
            padding.bottom = intField(p, "edgeMarginPx");
            if (seascape(o)) {
                padding.left = bar;
                padding.right = edge;
            } else {
                padding.left = edge;
                padding.right = bar;
            }
            return null;
        });

        replace(dp, "getHotseatLayoutPadding", p -> {
            Object o = p.thisObject;
            Rect insets = (Rect) XposedHelpers.getObjectField(o, "mInsets");
            Rect cellPad = (Rect) XposedHelpers.getObjectField(o, "cellLayoutPaddingPx");
            int top = Math.max(insets.top + cellPad.top, 0);
            int bottom = Math.max(insets.bottom + cellPad.bottom, 0);
            int edge = intField(p, "mHotseatBarEdgePaddingPx");
            int space = intField(p, "mHotseatBarWorkspaceSpacePx");
            return seascape(o)
                    ? new Rect(insets.left + edge, top, space, bottom)
                    : new Rect(space, top, insets.right + edge, bottom);
        }, Context.class);

        replace(dp, "getCalculatedWorkspaceSpringLoadScale", p -> {
            Object o = p.thisObject;
            float bottom = (Float) XposedHelpers.callMethod(
                    o, "getCellLayoutSpringLoadShrunkBottom", p.args[0]);
            float top = (Float) XposedHelpers.callMethod(o, "getCellLayoutSpringLoadShrunkTop");
            int height = (Integer) XposedHelpers.callMethod(o, "getCellLayoutHeight");
            return height > 0 ? Math.min(1f, (bottom - top) / height) : 1f;
        }, Context.class);

        // One line per landscape profile, so test logs show the geometry actually used.
        XposedBridge.hookAllConstructors(dp, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (isPhoneLandscape(param.thisObject)) {
                    logGeometry(param.thisObject);
                }
            }
        });
    }

    private interface Body {
        Object run(XC_MethodHook.MethodHookParam param) throws Throwable;
    }

    /** Replaces {@code name} with {@code body} in phone landscape only. */
    private void replace(Class<?> cls, String name, Body body, Object... parameterTypes) {
        Hooks.install("landscape " + name, () -> {
            Object[] args = new Object[parameterTypes.length + 1];
            System.arraycopy(parameterTypes, 0, args, 0, parameterTypes.length);
            args[parameterTypes.length] = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isPhoneLandscape(param.thisObject)) {
                        return;
                    }
                    try {
                        param.setResult(body.run(param));
                    } catch (Throwable t) {
                        // Fall through to Nothing's own implementation rather than crash.
                        Hooks.log("landscape " + name + " failed: " + t);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(cls, name, args);
        });
    }

    private void after(Class<?> cls, String name, Body body, Object... parameterTypes) {
        Hooks.install("landscape " + name, () -> {
            Object[] args = new Object[parameterTypes.length + 1];
            System.arraycopy(parameterTypes, 0, args, 0, parameterTypes.length);
            args[parameterTypes.length] = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isPhoneLandscape(param.thisObject)) {
                        try {
                            body.run(param);
                        } catch (Throwable t) {
                            Hooks.log("landscape " + name + " failed: " + t);
                        }
                    }
                }
            };
            XposedHelpers.findAndHookMethod(cls, name, args);
        });
    }

    /** A phone held sideways, laid out with the hotseat as a vertical bar. */
    static boolean isPhoneLandscape(Object deviceProfile) {
        return XposedHelpers.getBooleanField(deviceProfile, "isLandscape")
                && XposedHelpers.getBooleanField(deviceProfile, "isPhone")
                && (Boolean) XposedHelpers.callMethod(deviceProfile, "isVerticalBarLayout");
    }

    private static boolean seascape(Object deviceProfile) {
        return (Boolean) XposedHelpers.callMethod(deviceProfile, "isSeascape");
    }

    private static int intField(XC_MethodHook.MethodHookParam param, String name) {
        return XposedHelpers.getIntField(param.thisObject, name);
    }

    private int textHeight(int textSizePx) throws Exception {
        return textSizePx <= 0 ? 0 : (Integer) calculateTextHeight.invoke(null, (float) textSizePx);
    }

    private int overlap(Object deviceProfile, int size) throws Exception {
        return (Integer) iconSizeWithOverlap.invoke(deviceProfile, size);
    }

    private static void logGeometry(Object dp) {
        try {
            Rect padding = (Rect) XposedHelpers.getObjectField(dp, "workspacePadding");
            Point cell = (Point) XposedHelpers.callMethod(dp, "getCellSize");
            Hooks.log("landscape profile " + XposedHelpers.getIntField(dp, "widthPx") + "x"
                    + XposedHelpers.getIntField(dp, "heightPx")
                    + " ratio=" + XposedHelpers.getFloatField(dp, "availableRatio")
                    + " icon=" + XposedHelpers.getIntField(dp, "iconSizePx")
                    + " label=" + XposedHelpers.getIntField(dp, "iconTextSizePx")
                    + " cell=" + cell.x + "x" + cell.y
                    + " content=" + XposedHelpers.getIntField(dp, "cellWidthPx") + "x"
                    + XposedHelpers.getIntField(dp, "cellHeightPx")
                    + " hotseatBar=" + XposedHelpers.getIntField(dp, "hotseatBarSizePx")
                    + " workspacePadding=" + padding.toShortString()
                    + " seascape=" + seascape(dp)
                    + " smartColumns=" + smartColumns(dp));
        } catch (Throwable t) {
            Hooks.log("landscape profile log failed: " + t);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Smart drawer
    // ---------------------------------------------------------------------------------------

    /** Folder tiles per row in the Smart drawer: 2 in portrait, enough to fill landscape. */
    static int smartColumns(Object deviceProfile) {
        if (deviceProfile == null || !isPhoneLandscape(deviceProfile)) {
            return 2;
        }
        int width = XposedHelpers.getIntField(deviceProfile, "availableWidthPx");
        int height = XposedHelpers.getIntField(deviceProfile, "availableHeightPx");
        return Math.max(3, Math.min(6, Math.round(width / (height * SMART_TILE_TO_HEIGHT))));
    }

    /**
     * The Smart drawer profile sizes two tiles across availableWidthPx. In landscape, compute it
     * against the width two tiles of the N-column grid would take, then restore the real width.
     */
    private void hookSmartProfile() throws Throwable {
        Class<?> dp = ntDeviceProfileClass();
        Class<?> smartProfile = XposedHelpers.findClass(CLS_SMART_CONTAINER, cl)
                .getDeclaredField("smartProfile").getType();
        XposedHelpers.findAndHookMethod(smartProfile, "n", Context.class, dp, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object profile = param.args[1];
                int columns = smartColumns(profile);
                if (columns == 2) {
                    return;
                }
                Resources res = ((Context) param.args[0]).getResources();
                int padding = dimen(res, "smart_all_app_padding_h");
                int gap = dimen(res, "all_apps_panel_gap");
                int width = XposedHelpers.getIntField(profile, "availableWidthPx");
                int tile = (int) Math.ceil((width - 2 * padding - (columns - 1) * gap)
                        / (float) columns);
                param.setObjectExtra("width", width);
                XposedHelpers.setIntField(profile, "availableWidthPx", 2 * tile + 2 * padding + gap);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object width = param.getObjectExtra("width");
                if (width != null) {
                    XposedHelpers.setIntField(param.args[1], "availableWidthPx", (Integer) width);
                }
            }
        });
    }

    private static int dimen(Resources res, String name) {
        int id = res.getIdentifier(name, "dimen", Config.LAUNCHER_PKG);
        return id != 0 ? res.getDimensionPixelSize(id) : 0;
    }
}
