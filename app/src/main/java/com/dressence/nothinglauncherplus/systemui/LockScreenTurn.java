package com.dressence.nothinglauncherplus.systemui;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Test: the lock screen turns with the device while "Lock screen rotation (test)" is on, and
 * its landscape layout is written to the LSPosed log, for making it fit.
 *
 * <p>SystemUI's notification shade window (which holds the lock screen) follows the device
 * while the lock screen shows only if {@code KeyguardStateController
 * .isKeyguardScreenRotationAllowed()}; that is SystemUI's own switch for it (also set by the
 * lockscreen.rot_override property and config_enableLockScreenRotation), answered yes here.
 *
 * <p>In landscape SystemUI's resources (values-land) turn on split shade, and with it the lock
 * screen would swap Nothing's own layout ("default" blueprint) for AOSP's "split-shade" one.
 * Nothing added its pieces (date, widgets, depth-effect wallpaper) to its default layout only,
 * so the swapped one showed just a clock across the top with the notifications over it (seen
 * in the logged layout). That swap is switched off: Nothing's layout stays. SystemUI still
 * puts the notifications on the right half in landscape, so while it says the clock is not to
 * be centered (notifications showing), Nothing's clock, date and widgets end at the middle in
 * the same layout pass, as AOSP's split layout does with its clock: they center on the left
 * half. Each landscape layout is still logged (views, where they are, which lie off screen).
 */
final class LockScreenTurn {

    private static final String CLS_KEYGUARD_STATE =
            "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl";
    private static final String CLS_SHADE_WINDOW =
            "com.android.systemui.shade.NotificationShadeWindowView";
    private static final String CLS_BLUEPRINTS =
            "com.android.systemui.keyguard.data.repository.KeyguardBlueprintRepository";
    private static final String CLS_CLOCK_SECTION =
            "com.android.systemui.keyguard.ui.view.layout.sections.ClockSection";
    private static final String CLS_WIDGET_SECTION =
            "com.nothing.systemui.keyguard.ui.view.layout.sections.NTWidgetContainerSection";
    /** Nothing's lock screen pieces kept to the left half beside the notifications. */
    private static final String[] LEFT_HALF = {"lockscreen_clock_view", "keyguard_slice_view",
            "nt_widget_host_view_container"};
    /** ConstraintSet sides and guideline orientation. */
    private static final int START = 6;
    private static final int END = 7;
    private static final int VERTICAL = 1;
    /** The lock screen's views, by resource name. */
    private static final String[] ROOTS = {"keyguard_root_view", "shared_notification_container"};
    private static final int MAX_DEPTH = 7;
    private static final int MAX_LINES = 90;
    /** Layouts logged per System UI run at most, so the log stays readable. */
    private static final int MAX_DUMPS = 12;

    private static int dumps;
    private static String lastLayout;
    /** Set by the clock's constraints, used by the widgets' in the same pass (main thread). */
    private static android.content.Context halfContext;

    private LockScreenTurn() {
    }

    static void install(ClassLoader cl, ConfigStore store) {
        Class<?> state = XposedHelpers.findClassIfExists(CLS_KEYGUARD_STATE, cl);
        if (state == null) {
            Hooks.log("lock screen rotation: no " + CLS_KEYGUARD_STATE);
            return;
        }
        int hooked = XposedBridge.hookAllMethods(state, "isKeyguardScreenRotationAllowed",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(param.getResult())) {
                            return;
                        }
                        store.reloadIfChanged();
                        if (store.get().lockRotation) {
                            param.setResult(true);
                        }
                    }
                }).size();
        keepNothingLayout(cl, store);
        Class<?> window = XposedHelpers.findClassIfExists(CLS_SHADE_WINDOW, cl);
        if (window != null) {
            XposedHelpers.findAndHookMethod(window, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    View root = (View) param.thisObject;
                    root.getViewTreeObserver().addOnGlobalLayoutListener(
                            Hooks.guard("lock screen layout", () -> {
                                if (store.get().lockRotation) {
                                    record(root);
                                }
                            })::run);
                }
            });
        }
        Hooks.log("lock screen rotation: " + hooked + " hooks, layout log "
                + (window != null ? "on" : "off"));
    }

    private static void keepNothingLayout(ClassLoader cl, ConfigStore store) {
        Class<?> blueprints = XposedHelpers.findClassIfExists(CLS_BLUEPRINTS, cl);
        Class<?> clock = XposedHelpers.findClassIfExists(CLS_CLOCK_SECTION, cl);
        Class<?> widgets = XposedHelpers.findClassIfExists(CLS_WIDGET_SECTION, cl);
        if (blueprints == null || clock == null || widgets == null) {
            Hooks.log("lock screen layout: classes not found, layout left to SystemUI");
            return;
        }
        XposedBridge.hookAllMethods(blueprints, "applyBlueprint", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if ("split-shade".equals(param.args[0]) && store.get().lockRotation) {
                    param.args[0] = "default";
                }
            }
        });
        XposedBridge.hookAllMethods(clock, "applyConstraints", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                halfContext = null;
                try {
                    android.content.Context context = (android.content.Context)
                            XposedHelpers.getObjectField(param.thisObject, "context");
                    Object model = XposedHelpers.getObjectField(param.thisObject,
                            "keyguardClockViewModel");
                    Object centered = XposedHelpers.callMethod(XposedHelpers.callMethod(model,
                            "getClockShouldBeCentered"), "getValue");
                    Resources res = context.getResources();
                    int split = res.getIdentifier("config_use_split_notification_shade", "bool",
                            context.getPackageName());
                    if (store.get().lockRotation && Boolean.FALSE.equals(centered)
                            && res.getConfiguration().orientation
                                    == Configuration.ORIENTATION_LANDSCAPE
                            && split != 0 && res.getBoolean(split)) {
                        halfContext = context;
                    }
                } catch (Throwable t) {
                    Hooks.log("lock screen layout: " + t);
                }
            }
        });
        XposedBridge.hookAllMethods(widgets, "applyConstraints", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                android.content.Context context = halfContext;
                halfContext = null;
                if (context == null) {
                    return;
                }
                try {
                    leftHalf(context, param.args[0]);
                } catch (Throwable t) {
                    Hooks.log("lock screen layout: " + t);
                }
            }
        });
    }

    /** Ends Nothing's clock, date and widgets at the middle of the lock screen. */
    private static void leftHalf(android.content.Context context, Object set) {
        Resources res = context.getResources();
        String pkg = context.getPackageName();
        int guide = res.getIdentifier("split_shade_guideline", "id", pkg);
        if (guide == 0) {
            return;
        }
        int[] known = (int[]) XposedHelpers.callMethod(set, "getKnownIds");
        XposedHelpers.callMethod(set, "create", guide, VERTICAL);
        XposedHelpers.callMethod(set, "setGuidelinePercent", guide, 0.5f);
        for (String name : LEFT_HALF) {
            int id = res.getIdentifier(name, "id", pkg);
            if (id != 0 && contains(known, id)) {
                XposedHelpers.callMethod(set, "connect", id, END, guide, START);
            }
        }
    }

    private static boolean contains(int[] ids, int id) {
        for (int i : ids) {
            if (i == id) {
                return true;
            }
        }
        return false;
    }

    /** Logs the lock screen's layout if it shows in landscape and differs from the last one. */
    private static void record(View window) {
        if (dumps >= MAX_DUMPS || window.getResources().getConfiguration().orientation
                != Configuration.ORIENTATION_LANDSCAPE) {
            return;
        }
        StringBuilder out = new StringBuilder();
        int[] lines = {0};
        for (String name : ROOTS) {
            int id = window.getResources().getIdentifier(name, "id",
                    window.getContext().getPackageName());
            View v = id != 0 ? window.findViewById(id) : null;
            if (v != null && v.isShown()) {
                dump(window, v, 0, out, lines);
            }
        }
        if (out.length() == 0) {
            return; // No lock screen showing.
        }
        String layout = "window " + window.getWidth() + "x" + window.getHeight() + "\n" + out;
        if (layout.equals(lastLayout)) {
            return;
        }
        lastLayout = layout;
        dumps++;
        for (String line : layout.split("\n")) {
            Hooks.log("lock screen layout: " + line);
        }
    }

    private static void dump(View window, View v, int depth, StringBuilder out, int[] lines) {
        if (v.getVisibility() != View.VISIBLE || v.getWidth() <= 0 || v.getHeight() <= 0
                || v.getAlpha() == 0f || lines[0] >= MAX_LINES) {
            return;
        }
        Rect r = new Rect(0, 0, v.getWidth(), v.getHeight());
        try {
            ((ViewGroup) window).offsetDescendantRectToMyCoords(v, r);
        } catch (IllegalArgumentException notInside) {
            return;
        }
        boolean off = r.left < 0 || r.top < 0 || r.right > window.getWidth()
                || r.bottom > window.getHeight();
        for (int i = 0; i < depth; i++) {
            out.append("  ");
        }
        out.append(name(v)).append(' ').append(r.toShortString())
                .append(off ? " OFF-SCREEN" : "")
                .append(v.getScaleX() != 1f ? " scale " + v.getScaleX() : "")
                .append('\n');
        lines[0]++;
        if (depth < MAX_DEPTH && v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                dump(window, g.getChildAt(i), depth + 1, out, lines);
            }
        }
    }

    private static String name(View v) {
        String cls = v.getClass().getSimpleName();
        if (v.getId() == View.NO_ID) {
            return cls;
        }
        try {
            return v.getResources().getResourceEntryName(v.getId()) + ":" + cls;
        } catch (Resources.NotFoundException e) {
            return cls;
        }
    }
}
