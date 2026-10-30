package com.dressence.nothinglauncherplus.systemui;

import android.content.res.Resources;
import android.view.View;
import android.view.ViewParent;

import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.util.Arrays;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Records, in the LSPosed log, what moves the status bar clock: every change to the padding or
 * horizontal offset of the status bar and the containers the clock sits in, with the code that
 * made it, and every time the clock lands somewhere else on the screen with the paddings and
 * offsets of everything around it. Only changes are logged. For finding the status bar's clock
 * pressed against the screen's edge, which comes and goes.
 */
final class StatusBarProbe {

    private static final String CLS_CLOCK = "com.android.systemui.statusbar.policy.Clock";
    private static final String CLS_STATUS_BAR =
            "com.android.systemui.statusbar.phone.PhoneStatusBarView";
    /** The status bar views between the bar and its clock, by resource name. */
    private static final String[] WATCHED = {"status_bar_contents", "status_bar_start_side",
            "status_bar_start_side_container", "status_bar_start_side_content",
            "status_bar_start_side_except_heads_up", "status_bar_left_side", "clock"};
    private static final java.util.Set<String> WATCHED_SET =
            new java.util.HashSet<>(Arrays.asList(WATCHED));
    private static final String LAST = "nlplus.probeLast";

    private StatusBarProbe() {
    }

    static void install(ClassLoader cl) {
        Class<?> statusBar = XposedHelpers.findClassIfExists(CLS_STATUS_BAR, cl);
        Class<?> clock = XposedHelpers.findClassIfExists(CLS_CLOCK, cl);
        if (statusBar == null || clock == null) {
            Hooks.log("status bar probe: classes not found");
            return;
        }
        XC_MethodHook changed = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                View view = (View) param.thisObject;
                String name = watchedName(view, statusBar);
                if (name == null) {
                    return;
                }
                String now = "padding " + view.getPaddingLeft() + "," + view.getPaddingTop()
                        + "," + view.getPaddingRight() + " x+" + view.getTranslationX();
                if (now.equals(XposedHelpers.getAdditionalInstanceField(view, LAST))) {
                    return;
                }
                XposedHelpers.setAdditionalInstanceField(view, LAST, now);
                Hooks.log("status bar probe: " + name + " " + now + " by " + callers());
            }
        };
        XposedHelpers.findAndHookMethod(View.class, "setPadding", int.class, int.class,
                int.class, int.class, changed);
        XposedHelpers.findAndHookMethod(View.class, "setPaddingRelative", int.class, int.class,
                int.class, int.class, changed);
        XposedHelpers.findAndHookMethod(View.class, "setTranslationX", float.class, changed);
        XposedBridge.hookAllMethods(clock, "onLayout", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                View view = (View) param.thisObject;
                if (!inside(view, statusBar)) {
                    return;
                }
                int[] at = new int[2];
                view.getLocationOnScreen(at);
                String now = "at x " + at[0] + " width " + view.getWidth() + " rotation "
                        + (view.getDisplay() != null ? view.getDisplay().getRotation() : -1);
                Object last = XposedHelpers.getAdditionalInstanceField(view, LAST + "Clock");
                if (now.equals(last)) {
                    return;
                }
                XposedHelpers.setAdditionalInstanceField(view, LAST + "Clock", now);
                StringBuilder chain = new StringBuilder();
                for (ViewParent p = view.getParent(); p instanceof View; p = p.getParent()) {
                    View v = (View) p;
                    chain.append(" | ").append(name(v)).append(" pad ")
                            .append(v.getPaddingLeft()).append(" left ").append(v.getLeft())
                            .append(" x+").append(v.getTranslationX());
                    if (statusBar.isInstance(v)) {
                        break;
                    }
                }
                Hooks.log("status bar probe: clock " + now + chain);
            }
        });
        Hooks.log("status bar probe: on");
    }

    /** The name the probe gives {@code view} if it is one it watches, else null. */
    private static String watchedName(View view, Class<?> statusBar) {
        if (statusBar.isInstance(view)) {
            return "status bar";
        }
        String name = idName(view);
        return name != null && WATCHED_SET.contains(name) && inside(view, statusBar)
                ? name : null;
    }

    private static boolean inside(View view, Class<?> statusBar) {
        for (ViewParent p = view.getParent(); p instanceof View; p = p.getParent()) {
            if (statusBar.isInstance(p)) {
                return true;
            }
        }
        return false;
    }

    private static String name(View view) {
        String id = idName(view);
        return id != null ? id : view.getClass().getSimpleName();
    }

    /** Resource names by id, looked up once each: these hooks run on every animation frame. */
    private static final java.util.Map<Integer, String> NAMES = new java.util.HashMap<>();

    private static String idName(View view) {
        int id = view.getId();
        if (id == View.NO_ID) {
            return null;
        }
        synchronized (NAMES) {
            if (NAMES.containsKey(id)) {
                return NAMES.get(id);
            }
        }
        String name;
        try {
            name = view.getResources().getResourceEntryName(id);
        } catch (Resources.NotFoundException e) {
            name = null;
        }
        synchronized (NAMES) {
            NAMES.put(id, name);
        }
        return name;
    }

    /** The first few callers outside the view framework and the hooking machinery. */
    private static String callers() {
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            if (c.startsWith("java.") || c.startsWith("android.view.View")
                    || c.startsWith("de.robv.") || c.startsWith("LSPHooker")
                    || c.startsWith("org.lsposed.") || c.startsWith("com.dressence.")
                    || c.startsWith("J.") || c.contains("LspHooker")) {
                continue;
            }
            out.append(n == 0 ? "" : " < ").append(c.substring(c.lastIndexOf('.') + 1))
                    .append('.').append(e.getMethodName());
            if (++n == 5) {
                break;
            }
        }
        return out.toString();
    }
}
