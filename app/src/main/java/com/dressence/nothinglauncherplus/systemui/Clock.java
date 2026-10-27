package com.dressence.nothinglauncherplus.systemui;

import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewParent;
import android.widget.TextView;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.NdotFont;

import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** The status bar clock in green NDot, and as an option the date before the time. */
final class Clock {

    private static final String CLS_CLOCK = "com.android.systemui.statusbar.policy.Clock";

    /** The date pattern for the locale it was made for ("27 Oct", "Oct 27"). */
    private static Locale patternLocale;
    private static String pattern;

    private Clock() {
    }

    static void install(ClassLoader cl, ConfigStore store) {
        Class<?> clock = XposedHelpers.findClass(CLS_CLOCK, cl);
        XC_MethodHook light = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                store.reloadIfChanged();
                if (store.get().ndotStatusBar) {
                    TextView view = (TextView) param.thisObject;
                    Led.lightText(view, Led.GREEN, NdotFont.get(view.getContext()));
                    Led.clockGlow = view.getTextSize() * 0.28f;
                }
            }
        };
        XposedBridge.hookAllMethods(clock, "onAttachedToWindow", light);
        XposedBridge.hookAllMethods(clock, "onDarkChanged", light);
        XposedBridge.hookAllMethods(clock, "updateClock", light);
        XposedBridge.hookAllMethods(clock, "getSmallTime", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (store.get().statusBarDate && param.getResult() instanceof CharSequence
                        && !inHeader((View) param.thisObject)) {
                    param.setResult(DateFormat.format(datePattern(), new Date()) + "  "
                            + param.getResult());
                }
            }
        });
    }

    private static String datePattern() {
        Locale locale = Locale.getDefault();
        if (!locale.equals(patternLocale)) {
            patternLocale = locale;
            pattern = DateFormat.getBestDateTimePattern(locale, "dMMM");
        }
        return pattern;
    }

    /**
     * True for the clock over the pulled-down quick settings, which shows its own date: some
     * parent of it there is a header, quick settings or shade view.
     */
    private static boolean inHeader(View view) {
        for (ViewParent p = view.getParent(); p instanceof View; p = p.getParent()) {
            View v = (View) p;
            String name = v.getClass().getName().toLowerCase(Locale.ROOT);
            String id = "";
            if (v.getId() != View.NO_ID) {
                try {
                    id = v.getResources().getResourceEntryName(v.getId())
                            .toLowerCase(Locale.ROOT);
                } catch (RuntimeException e) {
                    // No name for this id.
                }
            }
            if (name.contains("header") || name.contains(".qs.") || name.contains("shade")
                    || id.contains("header") || id.startsWith("qs") || id.contains("shade")) {
                return true;
            }
        }
        return false;
    }
}
