package com.dressence.nothinglauncherplus.launcher;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.view.View;
import android.widget.TextView;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Landscape for everything around the home screen: recents and folders. Each piece only acts
 * while "Home screen rotation" is on. The launcher's own settings screens keep Nothing's
 * portrait lock: its home screen layout preview is only drawn right in portrait.
 */
final class LandscapeSurfaces {

    private static final String CLS_ORIENTED_STATE = "com.android.quickstep.util.RecentsOrientedState";
    private static final String CLS_SYSUI_PROXY = "com.android.quickstep.SystemUiProxy";
    private static final String CLS_FOLDER = "com.android.launcher3.folder.Folder";
    private static final String CLS_DEVICE_PROFILE = "com.android.launcher3.DeviceProfile";
    /** The folder name's minimum height in Nothing's layout, and the gap under it. */
    private static final int FOLDER_NAME_DP = 72;
    private static final int FOLDER_NAME_GAP_DP = 8;
    /** RecentsOrientedState.FLAG_HOME_ROTATION_ALLOWED_IN_PREFS. */
    private static final int FLAG_HOME_ROTATION_ALLOWED = 4;

    private final ClassLoader cl;
    private final ConfigStore store;
    /** Live recents states, so a settings change reaches them. */
    private final Set<Object> orientedStates = Collections.newSetFromMap(new WeakHashMap<>());

    LandscapeSurfaces(ClassLoader cl, ConfigStore store) {
        this.cl = cl;
        this.store = store;
    }

    void install() {
        Hooks.install("landscape recents", this::hookRecents);
        Hooks.install("landscape folders", this::hookFolders);
    }

    // ---------------------------------------------------------------------------------------
    // Recents
    // ---------------------------------------------------------------------------------------

    /**
     * Nothing emptied RecentsOrientedState.updateHomeRotationSetting, so recents never learns
     * that home may rotate and draws its portrait layout turned sideways ("fake landscape":
     * cards as thin strips, menus on their side). Restore AOSP's body: set the flag and tell
     * SystemUI.
     */
    private void hookRecents() {
        Class<?> state = XposedHelpers.findClass(CLS_ORIENTED_STATE, cl);
        // The emptied method is a likely inlining target, so act after the constructor that
        // calls it instead.
        XposedBridge.hookAllConstructors(state, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                synchronized (orientedStates) {
                    orientedStates.add(param.thisObject);
                }
                applyHomeRotation(param.thisObject);
            }
        });
        store.addChangeListener(() -> {
            Object[] states;
            synchronized (orientedStates) {
                states = orientedStates.toArray();
            }
            for (Object s : states) {
                applyHomeRotation(s);
            }
        });
    }

    private void applyHomeRotation(Object orientedState) {
        boolean allowed = store.get().allowRotation;
        try {
            XposedHelpers.callMethod(orientedState, "setFlag", FLAG_HOME_ROTATION_ALLOWED, allowed);
            Context context = (Context) XposedHelpers.getObjectField(orientedState, "mContext");
            Object proxy = XposedHelpers.callMethod(XposedHelpers.getStaticObjectField(
                    XposedHelpers.findClass(CLS_SYSUI_PROXY, cl), "INSTANCE"), "get", context);
            XposedHelpers.callMethod(proxy, "setHomeRotationEnabled", allowed);
        } catch (Throwable t) {
            Hooks.log("recents rotation setting failed: " + t);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Folders
    // ---------------------------------------------------------------------------------------

    /**
     * Nothing opens every folder at a fixed 346 px top margin under a 399 px header with the
     * name at its top, sizes chosen for portrait; on a 1080 px landscape screen that left room
     * for about one row of apps and cut the rest off at the bottom. In landscape the header is
     * just the name's line, the folder has as many rows as fit under it (more apps go to further
     * pages, as in portrait) and it is centred between the system bars. The open animation
     * reads the same margin and header.
     */
    private void hookFolders() {
        XposedBridge.hookAllConstructors(XposedHelpers.findClass(CLS_DEVICE_PROFILE, cl),
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (Landscape.isPhoneLandscape(param.thisObject)) {
                            fitFolderRows(param.thisObject);
                        }
                    }
                });

        Class<?> folder = XposedHelpers.findClass(CLS_FOLDER, cl);
        XposedHelpers.findAndHookMethod(folder, "centerAboutIcon", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View view = (View) param.thisObject;
                int[] portrait = (int[]) XposedHelpers.getAdditionalInstanceField(view,
                        "nlplusFolderChrome");
                if (portrait == null) {
                    portrait = new int[] {XposedHelpers.getIntField(view, "mFolderMarginTop"),
                            XposedHelpers.getIntField(view, "mHeaderHeight")};
                    XposedHelpers.setAdditionalInstanceField(view, "nlplusFolderChrome",
                            portrait);
                }
                Object dp = XposedHelpers.callMethod(XposedHelpers.getObjectField(
                        view, "mActivityContext"), "getDeviceProfile");
                int margin = portrait[0];
                int header = portrait[1];
                if (Landscape.isPhoneLandscape(dp)) {
                    TextView name = (TextView) XposedHelpers.getObjectField(view, "mFolderName");
                    header = Math.min(header, folderNameRoom(name));
                    XposedHelpers.setIntField(view, "mHeaderHeight", header); // For the height.
                    Rect insets = (Rect) XposedHelpers.getObjectField(dp, "mInsets");
                    int height = (Integer) XposedHelpers.callMethod(view, "getFolderHeight");
                    int room = XposedHelpers.getIntField(dp, "heightPx") - insets.top
                            - insets.bottom;
                    margin = insets.top + Math.max(0, (room - height) / 2);
                }
                XposedHelpers.setIntField(view, "mHeaderHeight", header);
                XposedHelpers.setIntField(view, "mFolderMarginTop", margin);
            }
        });
        // A private method ART may inline into the folder's opening.
        Hooks.deoptimizeAll(folder, "animateOpen");
    }

    /** The folder name's line and a small gap under it. */
    private static int folderNameRoom(TextView name) {
        float density = name.getResources().getDisplayMetrics().density;
        return Math.max(name.getMinHeight(), Math.round(name.getTextSize() * 1.3f))
                + Math.round(FOLDER_NAME_GAP_DP * density);
    }

    /** Landscape folders get as many rows as fit under the name; never more than portrait. */
    private static void fitFolderRows(Object dp) {
        int cell = XposedHelpers.getIntField(dp, "folderCellHeightPx");
        int rows = XposedHelpers.getIntField(dp, "numFolderRows");
        if (cell <= 0 || rows <= 1) {
            return;
        }
        Rect insets = (Rect) XposedHelpers.getObjectField(dp, "mInsets");
        float density = Resources.getSystem().getDisplayMetrics().density;
        int room = XposedHelpers.getIntField(dp, "heightPx") - insets.top - insets.bottom
                - Math.round((FOLDER_NAME_DP + FOLDER_NAME_GAP_DP) * density)
                - XposedHelpers.getIntField(dp, "folderContentPaddingTop")
                - XposedHelpers.getIntField(dp, "folderFooterHeightPx");
        int fit = Math.max(1, Math.min(rows, room / cell));
        if (fit != rows) {
            XposedHelpers.setIntField(dp, "numFolderRows", fit);
        }
    }
}
