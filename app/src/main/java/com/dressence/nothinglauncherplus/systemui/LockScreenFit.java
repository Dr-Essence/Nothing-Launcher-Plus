package com.dressence.nothinglauncherplus.systemui;

import android.view.View;
import android.view.ViewGroup;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Nothing's widgets on the turned lock screen and always-on display. Nothing stacks its date,
 * clock and widgets down from the top (the widget container wraps its widgets, its top on the
 * clock's bottom: NTWidgetContainerSection), sized for the portrait screen; the clock draws at
 * its size setting (NTClockView: normal, or small at 0.7) and the widgets at theirs. Turned,
 * the screen is 1080 high: with the normal clock the column ran to 1114 and the widgets went
 * off the bottom (DebuG report: clock 241..622, widgets 622..1114 of 1080). Whatever the
 * clock's size and the widgets, the widget container is drawn as large as fits between where
 * Nothing put it and the bottom (a small gap kept), centered on its top, and full size when it
 * fits. Its place and Nothing's layout stay as they are: the scale is a drawing transform,
 * set after each layout of the lock screen, and needs no layout pass of its own.
 */
final class LockScreenFit {

    private static final String ROOT = "com.android.systemui.keyguard.ui.view.KeyguardRootView";
    private static final String WIDGETS = "nt_widget_host_view_container";
    private static final String FITTED = "nlplus.fitted";

    private LockScreenFit() {
    }

    static void install(ClassLoader cl, ConfigStore store) {
        XposedBridge.hookAllConstructors(XposedHelpers.findClass(ROOT, cl), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                View root = (View) param.thisObject;
                root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                        Hooks.guard("lock screen widgets fit", () -> fit(root,
                                store.get().lockRotation)).run());
            }
        });
        Hooks.log("lock screen widgets fit: ready");
    }

    /** The widget container's scale for the lock screen as now laid out. */
    static void fit(View root, boolean lockRotation) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        int id = root.getResources().getIdentifier(WIDGETS, "id",
                root.getContext().getPackageName());
        View widgets = id != 0 ? root.findViewById(id) : null;
        if (widgets == null) {
            return;
        }
        float scale = 1f;
        boolean turned = root.getWidth() > root.getHeight();
        if (lockRotation && turned && widgets.getVisibility() == View.VISIBLE
                && widgets.getHeight() > 0) {
            float gap = 8 * root.getResources().getDisplayMetrics().density;
            float room = root.getHeight() - gap - widgets.getTop();
            scale = Math.max(0.5f, Math.min(1f, room / widgets.getHeight()));
        }
        boolean fitted = XposedHelpers.getAdditionalInstanceField(widgets, FITTED) != null;
        if (scale >= 1f && !fitted) {
            return; // Fits, and never scaled here: Nothing's as it is.
        }
        widgets.setPivotX(widgets.getWidth() / 2f);
        widgets.setPivotY(0f);
        widgets.setScaleX(scale);
        widgets.setScaleY(scale);
        if (scale >= 1f) {
            XposedHelpers.removeAdditionalInstanceField(widgets, FITTED);
        } else {
            XposedHelpers.setAdditionalInstanceField(widgets, FITTED, Boolean.TRUE);
        }
    }
}
