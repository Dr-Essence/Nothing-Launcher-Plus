package com.dressence.nothinglauncherplus.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    private static final String CLS_NT_LAUNCHER = "com.nothing.launcher.NTLauncher";
    private static final String CLS_IDP = "com.android.launcher3.InvariantDeviceProfile";

    /** Nothing's portrait reference size, transposed for landscape. */
    private static final float REFERENCE_LONG = 2392f;
    private static final float REFERENCE_SHORT = 1080f;
    /**
     * Landscape Smart drawer: tile width relative to the screen height. Tiles are 1.11x as tall
     * as wide, so this shows about one and a half rows under the search bar.
     */
    private static final float SMART_TILE_TO_HEIGHT = 0.36f;
    /** Largest landscape icon relative to its cell, leaving room for folder rings and dots. */
    private static final float ICON_TO_CELL = 0.9f;

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
        Hooks.install("landscape profiles", this::hookProfileChoice);
        Hooks.install("landscape config change", this::hookConfigChange);
        Hooks.install("landscape layout", this::hookDeviceProfile);
        Hooks.install("landscape smart drawer", this::hookSmartProfile);
        Hooks.install("landscape icon fit", this::hookIconFit);
        Hooks.install("landscape hotseat log", this::hookHotseatLog);
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

    /**
     * Nothing builds a DeviceProfile for every orientation but drops the landscape ones from
     * {@code supportedProfiles}, the list the launcher picks its profile from, so a rotated
     * launcher kept laying out its portrait profile on a landscape window. With rotation on, the
     * list is swapped for the full one Nothing keeps for Recents. The field itself is changed
     * because the lookups that read it are small enough for ART to inline past any hook.
     */
    private void hookProfileChoice() {
        Class<?> idp = XposedHelpers.findClass(CLS_IDP, cl);
        XposedBridge.hookAllMethods(idp, "initGrid", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length == 4) {
                    remakeLandscapeProfiles(param.thisObject, (Context) param.args[0]);
                    applyProfileChoice(param.thisObject);
                }
            }
        });
        Class<?> launcherClass = XposedHelpers.findClass(CLS_LAUNCHER, cl);
        XposedHelpers.findAndHookMethod(launcherClass, "dispatchDeviceProfileChanged",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object dp = XposedHelpers.callMethod(param.thisObject, "getDeviceProfile");
                        Hooks.log("launcher profile " + XposedHelpers.getIntField(dp, "widthPx")
                                + "x" + XposedHelpers.getIntField(dp, "heightPx")
                                + " landscape=" + isPhoneLandscape(dp));
                    }
                });
        store.addChangeListener(() -> {
            Object launcher = XposedHelpers.callMethod(
                    XposedHelpers.getStaticObjectField(launcherClass, "ACTIVITY_TRACKER"),
                    "getCreatedContext");
            if (launcher != null) {
                applyProfileChoice(XposedHelpers.getObjectField(
                        XposedHelpers.callMethod(launcher, "getDeviceProfile"), "inv"));
            }
        });
    }

    /**
     * NTLauncher.handleConfigurationChanged drops every change to landscape on purpose ("Skip to
     * handle the config change for the landscape orientation"), so a rotated launcher never
     * picked a new profile or relaid out. With rotation on, run what the base class
     * (StatefulActivity) does instead: relayout when the config or rotation changed.
     */
    private void hookConfigChange() {
        XposedHelpers.findAndHookMethod(CLS_NT_LAUNCHER, cl, "handleConfigurationChanged",
                Configuration.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Configuration config = (Configuration) param.args[0];
                        if (config.orientation != Configuration.ORIENTATION_LANDSCAPE
                                || !store.get().allowRotation) {
                            return;
                        }
                        Activity launcher = (Activity) param.thisObject;
                        Configuration old = (Configuration) XposedHelpers.getObjectField(
                                launcher, "mOldConfig");
                        int rotation = launcher.getDisplay().getRotation();
                        if ((config.diff(old) & (ActivityInfo.CONFIG_ORIENTATION
                                | ActivityInfo.CONFIG_SCREEN_SIZE)) != 0
                                || rotation != XposedHelpers.getIntField(launcher, "mOldRotation")) {
                            XposedHelpers.callMethod(launcher, "onHandleConfigurationChanged");
                            Object dp = XposedHelpers.callMethod(launcher, "getDeviceProfile");
                            Hooks.log("rotated: launcher profile "
                                    + XposedHelpers.getIntField(dp, "widthPx") + "x"
                                    + XposedHelpers.getIntField(dp, "heightPx")
                                    + " landscape=" + isPhoneLandscape(dp));
                        }
                        old.setTo(config);
                        XposedHelpers.setIntField(launcher, "mOldRotation", rotation);
                        param.setResult(null);
                    }
                });
    }

    /**
     * The launcher makes its profiles one after another, so a landscape one can be made before
     * the portrait one it takes its cell from (LandscapeGrid): those are made again once all
     * are there. The launcher copies its own profile from this list, and Nothing sizes its
     * widgets with the list's profile itself.
     */
    private static void remakeLandscapeProfiles(Object idp, Context context) {
        List<?> all = (List<?>) XposedHelpers.getObjectField(idp,
                "recentActivitySupportedProfiles");
        if (all == null) {
            return;
        }
        List<Object> remade = new ArrayList<>(all.size());
        boolean changed = false;
        for (Object profile : all) {
            if (isPhoneLandscape(profile)) {
                profile = XposedHelpers.callMethod(profile, "copy", context);
                changed = true;
            }
            remade.add(profile);
        }
        if (changed) {
            XposedHelpers.setObjectField(idp, "recentActivitySupportedProfiles",
                    Collections.unmodifiableList(remade));
        }
    }

    private void applyProfileChoice(Object idp) {
        Object portraitOnly = XposedHelpers.getAdditionalInstanceField(idp, "nlplusPortrait");
        Object all = XposedHelpers.getObjectField(idp, "recentActivitySupportedProfiles");
        Object current = XposedHelpers.getObjectField(idp, "supportedProfiles");
        if (current != all) {
            portraitOnly = current;
            XposedHelpers.setAdditionalInstanceField(idp, "nlplusPortrait", current);
        }
        Object wanted = store.get().allowRotation || portraitOnly == null ? all : portraitOnly;
        if (wanted != current) {
            XposedHelpers.setObjectField(idp, "supportedProfiles", wanted);
            Hooks.log("launcher profiles: " + (wanted == all ? "all orientations" : "portrait only"));
        }
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

    /**
     * Nothing's grid keeps its 6-8 rows in landscape, so a row is only ~90-140 px tall while the
     * grid asks for 150+ px icons; they were drawn cut in half. After every icon sizing pass,
     * icons take the portrait profile's geometry, scaled as the landscape cells allow (see
     * LandscapeGrid); before that is known, the icon is set to what a cell holds and the label
     * dropped. Raised as well as lowered: the launcher's profiles made from other
     * contexts came out with smaller icons, and icons set up from those were drawn that size.
     */
    private void hookIconFit() {
        Class<?> base = XposedHelpers.findClass("com.android.launcher3.DeviceProfile", cl);
        XposedHelpers.findAndHookMethod(base, "updateIconSize", float.class, Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object o = param.thisObject;
                        if (!isPhoneLandscape(o)) {
                            return;
                        }
                        try {
                            fitIcons(o);
                        } catch (Throwable t) {
                            Hooks.log("landscape icon fit failed: " + t);
                        }
                    }
                });
    }

    private void fitIcons(Object o) throws Exception {
        PortraitGeometry portrait = PortraitGeometry.of(o);
        float scale = LandscapeGrid.contentScale(o);
        if (portrait != null && scale > 0) {
            // Icons, names and folders as in portrait, scaled as the landscape cells allow and
            // centred in them (see LandscapeGrid).
            int icon = Math.round(portrait.iconSize * scale);
            XposedHelpers.setIntField(o, "iconSizePx", icon);
            XposedHelpers.setIntField(o, "iconTextSizePx",
                    Math.round(portrait.iconTextSize * scale));
            XposedHelpers.setIntField(o, "iconDrawablePaddingPx",
                    Math.round(portrait.iconDrawablePadding * scale));
            XposedHelpers.setIntField(o, "cellWidthPx", Math.round(portrait.cellWidth * scale));
            XposedHelpers.setIntField(o, "cellHeightPx",
                    Math.round(portrait.cellHeight * scale));
            XposedHelpers.setIntField(o, "cellYPaddingPx", Math.max(0,
                    Math.round(portrait.cellYPadding * scale) + (LandscapeGrid.cellHeight(o)
                            - Math.round(portrait.cell.y * scale)) / 2));
            XposedHelpers.callMethod(o, "updateHotseatSizes", icon);
            XposedHelpers.callMethod(o, "updateFolderIconSize");
            XposedHelpers.setIntField(o, "folderIconOffsetYPx",
                    Math.round(portrait.folderIconOffsetY * scale));
            return;
        }
        Point cell = (Point) XposedHelpers.callMethod(o, "getCellSize");
        int fit = (int) (Math.min(cell.x, cell.y) * ICON_TO_CELL);
        int icon = XposedHelpers.getIntField(o, "iconSizePx");
        if (fit <= 0 || icon == fit) {
            return;
        }
        int height = overlap(o, fit);
        XposedHelpers.setIntField(o, "iconSizePx", fit);
        XposedHelpers.setIntField(o, "iconTextSizePx", 0);
        XposedHelpers.setIntField(o, "iconDrawablePaddingPx", 0);
        XposedHelpers.setIntField(o, "cellWidthPx", fit);
        XposedHelpers.setIntField(o, "cellHeightPx", height);
        XposedHelpers.setIntField(o, "cellYPaddingPx", Math.max(0, (cell.y - height) / 2));
        XposedHelpers.callMethod(o, "updateHotseatSizes", fit);
        XposedHelpers.callMethod(o, "updateFolderIconSize");
        XposedHelpers.setIntField(o, "folderIconOffsetYPx",
                (fit - XposedHelpers.getIntField(o, "folderIconSizePx")) / 2);
    }

    /** Logs how the hotseat is laid out, to compare with the profile it was given. */
    private void hookHotseatLog() {
        Class<?> hotseat = XposedHelpers.findClass("com.android.launcher3.Hotseat", cl);
        XposedHelpers.findAndHookMethod(hotseat, "resetLayout", boolean.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object view = param.thisObject;
                Hooks.log("hotseat layout vertical=" + param.args[0]
                        + " grid=" + XposedHelpers.callMethod(view, "getCountX") + "x"
                        + XposedHelpers.callMethod(view, "getCountY"));
            }
        });
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
        return Math.max(3, Math.min(7, Math.round(width / (height * SMART_TILE_TO_HEIGHT))));
    }

    /**
     * The Smart drawer profile sizes two tiles across availableWidthPx, and icons and padding
     * from availableRatio. In landscape, compute it against the width two tiles of the N-column
     * grid take and a ratio scaled to match, then restore both.
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
                int twoTiles = 2 * tile + 2 * padding + gap;
                float ratio = XposedHelpers.getFloatField(profile, "availableRatio");
                param.setObjectExtra("width", width);
                param.setObjectExtra("ratio", ratio);
                XposedHelpers.setIntField(profile, "availableWidthPx", twoTiles);
                // Icon and padding sizes follow availableRatio, not the tile: scale it by how
                // much narrower the tiles are than in portrait (screen's short side).
                XposedHelpers.setFloatField(profile, "availableRatio", ratio * twoTiles
                        / (float) XposedHelpers.getIntField(profile, "heightPx"));
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object width = param.getObjectExtra("width");
                if (width != null) {
                    XposedHelpers.setIntField(param.args[1], "availableWidthPx", (Integer) width);
                    XposedHelpers.setFloatField(param.args[1], "availableRatio",
                            (Float) param.getObjectExtra("ratio"));
                }
            }
        });
    }

    private static int dimen(Resources res, String name) {
        int id = res.getIdentifier(name, "dimen", Config.LAUNCHER_PKG);
        return id != 0 ? res.getDimensionPixelSize(id) : 0;
    }
}
