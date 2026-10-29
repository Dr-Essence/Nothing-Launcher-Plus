package com.dressence.nothinglauncherplus.widgets;

import android.app.Activity;
import android.content.pm.ActivityInfo;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Nothing's Customisation page (Nothing Wallpaper's CustomizationPickerActivity, opened from
 * the home screen) follows the home screen into landscape.
 *
 * <p>It is declared portrait-only (lifted in system_server as it opens, CardOrientation), and
 * as it starts it asks again for portrait unless it is on a large unfolded foldable, where it
 * asks to follow the device. While home rotation is on, that portrait request is made a
 * follow-the-device one, as on the foldable. Its other screens keep their portrait locks.
 */
public final class CustomizationPage {

    public static final String PKG = "com.nothing.wallpaper";
    /** The activity itself; "com.android.customization.picker..." is only an alias of it. */
    public static final String ACTIVITY =
            "com.android.wallpaper.picker.CustomizationPickerActivity";

    private CustomizationPage() {
    }

    public static void install(ConfigStore store) {
        XposedHelpers.findAndHookMethod(Activity.class, "setRequestedOrientation", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if ((Integer) param.args[0] != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                || !ACTIVITY.equals(param.thisObject.getClass().getName())) {
                            return;
                        }
                        store.reloadIfChanged();
                        if (store.get().allowRotation) {
                            param.args[0] = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
                        }
                    }
                });
    }
}
