package com.dressence.nothinglauncherplus.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Landscape for everything around the home screen: recents, folders and the launcher's own
 * screens. Each piece only acts while "Home screen rotation" is on.
 */
final class LandscapeSurfaces {

    private static final String CLS_ORIENTED_STATE = "com.android.quickstep.util.RecentsOrientedState";
    private static final String CLS_SYSUI_PROXY = "com.android.quickstep.SystemUiProxy";
    private static final String CLS_FOLDER = "com.android.launcher3.folder.Folder";
    private static final String CLS_LAUNCHER = "com.android.launcher3.Launcher";
    /** RecentsOrientedState.FLAG_HOME_ROTATION_ALLOWED_IN_PREFS. */
    private static final int FLAG_HOME_ROTATION_ALLOWED = 4;
    private static final String[] OWN_PACKAGES = {"com.nothing.", "com.android.searchlauncher."};

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
        Hooks.install("landscape launcher screens", this::hookOwnActivities);
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
     * Nothing opens every folder at a fixed top margin chosen for portrait; on a landscape
     * screen the header and rows then run off the bottom. In landscape the margin is set so the
     * folder is centred between the system bars. The open animation reads the same margin.
     */
    private void hookFolders() {
        Class<?> folder = XposedHelpers.findClass(CLS_FOLDER, cl);
        XposedHelpers.findAndHookMethod(folder, "centerAboutIcon", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View view = (View) param.thisObject;
                Object portrait = XposedHelpers.getAdditionalInstanceField(view, "nlplusMargin");
                if (portrait == null) {
                    portrait = XposedHelpers.getIntField(view, "mFolderMarginTop");
                    XposedHelpers.setAdditionalInstanceField(view, "nlplusMargin", portrait);
                }
                Object dp = XposedHelpers.callMethod(XposedHelpers.getObjectField(
                        view, "mActivityContext"), "getDeviceProfile");
                int margin = (Integer) portrait;
                if (Landscape.isPhoneLandscape(dp)) {
                    Rect insets = (Rect) XposedHelpers.getObjectField(dp, "mInsets");
                    int height = (Integer) XposedHelpers.callMethod(view, "getFolderHeight");
                    int room = XposedHelpers.getIntField(dp, "heightPx") - insets.top
                            - insets.bottom;
                    margin = insets.top + Math.max(0, (room - height) / 2);
                }
                XposedHelpers.setIntField(view, "mFolderMarginTop", margin);
            }
        });
    }

    // ---------------------------------------------------------------------------------------
    // The launcher's own screens
    // ---------------------------------------------------------------------------------------

    /**
     * Search, home settings, drawer settings, icon packs, layout and wallpaper settings are
     * locked to portrait in Nothing's manifest, so opening one from landscape flipped the phone
     * back. Let them follow the device like the home screen does.
     */
    private void hookOwnActivities() {
        Class<?> launcher = XposedHelpers.findClass(CLS_LAUNCHER, cl);
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Activity activity = (Activity) param.thisObject;
                        if (!store.get().allowRotation || launcher.isInstance(activity)
                                || !isOwn(activity) || activity.getRequestedOrientation()
                                != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                            return;
                        }
                        activity.setRequestedOrientation(
                                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    }
                });
    }

    private static boolean isOwn(Activity activity) {
        String name = activity.getClass().getName();
        if (name.endsWith(".FallbackHome")) {
            return false; // Shown before unlock, while the real home is not ready.
        }
        if (name.contains(".setting.")) {
            // Nothing's settings pages crash when recreated by a rotation (their fragment
            // host is gone); they keep their portrait lock.
            return false;
        }
        for (String prefix : OWN_PACKAGES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
