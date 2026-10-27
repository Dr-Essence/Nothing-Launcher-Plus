package com.dressence.nothinglauncherplus.systemui;

import android.graphics.Canvas;
import android.widget.TextView;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.NdotFont;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * The status bar's other texts (the date over quick settings, carrier and the like) in
 * glowing green NDot like the clock, and quick settings tile labels in glowing green in their
 * own face. Tiny texts keep their face: NDot's dots run together at that size.
 */
final class GreenTexts {

    /** Texts smaller than this (in sp) are too small for NDot's dots. */
    private static final float SMALL_TEXT_SP = 10;

    private GreenTexts() {
    }

    static void install(ConfigStore store) {
        XposedHelpers.findAndHookMethod(TextView.class, "onDraw", Canvas.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        TextView view = (TextView) param.thisObject;
                        if (Led.isLit(view) || NetworkSpeed.contains(view)) {
                            return;
                        }
                        int area = Areas.of(view);
                        if (area == Areas.TILE && store.get().ndotQuickSettings) {
                            Led.lightText(view, Led.GREEN, null);
                        } else if (area == Areas.STATUS_BAR && store.get().ndotStatusBar) {
                            float sp = view.getTextSize()
                                    / view.getResources().getDisplayMetrics().scaledDensity;
                            Led.lightText(view, Led.GREEN, sp < SMALL_TEXT_SP ? null
                                    : NdotFont.get(view.getContext()));
                        }
                    }
                });
    }
}
