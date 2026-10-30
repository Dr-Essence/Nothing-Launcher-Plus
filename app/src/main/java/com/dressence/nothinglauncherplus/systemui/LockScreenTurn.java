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
 *
 * <p>The fingerprint sensor (under the screen) is where it is whichever way the screen turns.
 * SystemUI's sensor overlay (UdfpsControllerOverlay, which lights the sensor and reads the
 * finger) turns the sensor's spot with the screen ({@code RotationUtils.rotateBounds}) except
 * on the lock screen ({@code shouldRotate()}), which AOSP assumes never turns; so in landscape
 * it sat at the portrait spot, off the screen. That one exception is lifted while the lock
 * screen turns (not while going to sleep, as before). The lock icon over the sensor
 * (DefaultDeviceEntrySection) is placed from the sensor's unturned spot; it is given the spot
 * turned the same way, in the lock screen's own coordinates.
 *
 * <p>The overlay turns its {@code sensorBounds} in place on every layout (twice when it shows),
 * and after {@code updateOverlayParams} that Rect is the overlay parameters' own, so the
 * sensor's spot was turned again and again (the scanning animation landed near the top left).
 * While the lock screen turns, each layout starts {@code sensorBounds} from a fresh copy of the
 * unturned spot, so it is turned exactly once and the parameters stay unturned.
 *
 * <p>The PIN / pattern / password screen (bouncer): Nothing's layouts for it are portrait
 * only; in landscape the PIN pad was squeezed under the lock icon. SystemUI carries AOSP's own
 * landscape bouncer (the keyguard_*_motion_layout layouts, which jump to "split_constraints" in
 * landscape: entry beside the keys), switched on by the unreleased flag
 * lockscreen.enable_landscape, and update_bouncer_constraints is on for phones. That flag is
 * answered yes only inside the bouncer's own code (choosing and setting up its layout, and its
 * turn listener), nowhere else (not the shade or quick settings). Should that code ever fail,
 * the error is kept from System UI and the bouncer goes back to Nothing's layouts. The
 * bouncer's fingerprint icon is placed over the sensor as on the lock screen.
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
    private static final String CLS_UDFPS_OVERLAY =
            "com.android.systemui.biometrics.UdfpsControllerOverlay";
    private static final String CLS_ENTRY_SECTION =
            "com.android.systemui.keyguard.ui.view.layout.sections.DefaultDeviceEntrySection";
    private static final String CLS_FLAGS = "com.android.systemui.flags.Flags";
    private static final String[] CLS_FEATURE_FLAGS = {
            "com.android.systemui.flags.FeatureFlagsClassicRelease",
            "com.android.systemui.flags.FeatureFlagsClassicDebug"};
    private static final String CLS_FLIPPER =
            "com.android.keyguard.KeyguardSecurityViewFlipperController";
    private static final String CLS_SECURITY_CONTAINER =
            "com.android.keyguard.KeyguardSecurityContainerController";
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
    private static final String[] ROOTS = {"keyguard_root_view", "shared_notification_container",
            "keyguard_message_area_container", "keyguard_bouncer_container", "nt_bouncer_icon_area"};
    private static final int MAX_DEPTH = 9;
    private static final int MAX_LINES = 160;
    /** Layouts logged per System UI run at most, so the log stays readable. */
    private static final int MAX_DUMPS = 30;

    /** Inside the bouncer's own code, where the landscape flag is answered yes. */
    private static final ThreadLocal<int[]> BOUNCER = ThreadLocal.withInitial(() -> new int[1]);
    /** Set if the bouncer's landscape code failed once: Nothing's layouts from then on. */
    private static volatile boolean bouncerFailed;
    /** The fingerprint spot on the screen as last turned, and the rotation it is for. */
    private static volatile int[] turnedSpot;

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
        turnFingerprint(cl, store);
        landscapeBouncer(cl, store);
        portraitAod(cl, store);
        logFingerprint(cl, store);
        lightSensor(cl, store);
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

    private static void turnFingerprint(ClassLoader cl, ConfigStore store) {
        Class<?> overlay = XposedHelpers.findClassIfExists(CLS_UDFPS_OVERLAY, cl);
        if (overlay != null) {
            XposedBridge.hookAllMethods(overlay, "shouldRotate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(param.getResult()) || !store.get().lockRotation) {
                        return;
                    }
                    try {
                        Object monitor = XposedHelpers.getObjectField(param.thisObject,
                                "keyguardUpdateMonitor");
                        if (!(Boolean) XposedHelpers.callMethod(monitor, "isGoingToSleep")) {
                            param.setResult(true);
                        }
                    } catch (Throwable t) {
                        Hooks.log("lock screen fingerprint: " + t);
                    }
                }
            });
        }
        if (overlay != null) {
            XposedBridge.hookAllMethods(overlay, "updateDimensions", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!store.get().lockRotation) {
                        return;
                    }
                    try {
                        Object params = XposedHelpers.getObjectField(param.thisObject,
                                "overlayParams");
                        Rect natural = (Rect) XposedHelpers.callMethod(params,
                                "getSensorBounds");
                        XposedHelpers.setObjectField(param.thisObject, "sensorBounds",
                                new Rect(natural));
                    } catch (Throwable t) {
                        Hooks.log("lock screen fingerprint: " + t);
                    }
                }
            });
        }
        Class<?> entry = XposedHelpers.findClassIfExists(CLS_ENTRY_SECTION, cl);
        if (entry == null) {
            Hooks.log("lock screen fingerprint: no " + CLS_ENTRY_SECTION);
            return;
        }
        for (java.lang.reflect.Method m : entry.getDeclaredMethods()) {
            if (!m.getName().startsWith("centerIcon")) {
                continue;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!store.get().lockRotation) {
                        return;
                    }
                    try {
                        turnIcon(param);
                    } catch (Throwable t) {
                        Hooks.log("lock screen fingerprint: " + t);
                    }
                }
            });
        }
        Hooks.log("lock screen fingerprint: " + (overlay != null ? "overlay turns" : "no overlay"));
    }

    /**
     * The lock icon over the fingerprint sensor: its spot (natural, unturned display
     * coordinates) turned as the sensor overlay turns it, then in the lock screen's coordinates.
     */
    private static void turnIcon(XC_MethodHook.MethodHookParam param) {
        Object section = param.thisObject;
        Object model = XposedHelpers.callMethod(
                XposedHelpers.getObjectField(section, "deviceEntryIconViewModel"), "get");
        Object udfps = XposedHelpers.callMethod(XposedHelpers.callMethod(model,
                "isUdfpsSupported"), "getValue");
        if (!Boolean.TRUE.equals(udfps)) {
            return; // No sensor: the icon is placed from the screen's own size already.
        }
        android.view.WindowManager wm = (android.view.WindowManager)
                XposedHelpers.getObjectField(section, "windowManager");
        android.view.Display display = wm.getDefaultDisplay();
        int rotation = display.getRotation();
        if (rotation != android.view.Surface.ROTATION_90
                && rotation != android.view.Surface.ROTATION_270) {
            return;
        }
        android.graphics.Point size = new android.graphics.Point();
        display.getRealSize(size);
        int naturalW = size.y; // Turned a quarter: the natural width is the height now.
        int naturalH = size.x;
        android.graphics.Point center = (android.graphics.Point) param.args[0];
        int x = center.x;
        int y = center.y;
        // RotationUtils.rotateBounds, as the sensor overlay turns the sensor's spot.
        int tx = rotation == android.view.Surface.ROTATION_90 ? y : naturalH - y;
        int ty = rotation == android.view.Surface.ROTATION_90 ? naturalW - x : x;
        int[] origin = rootOrigin(section, wm);
        param.args[0] = new android.graphics.Point(tx - origin[0], ty - origin[1]);
        turnedSpot = new int[] {tx, ty, rotation};
        Hooks.log("lock screen fingerprint: icon at " + tx + "," + ty + " on screen (sensor "
                + x + "," + y + " unturned, rotation " + rotation + ", lock screen at "
                + origin[0] + "," + origin[1] + ")");
    }

    /**
     * The bouncer's fingerprint icon (Nothing centers it across and sets only its top, for
     * portrait): over the sensor's turned spot, as the lock screen's icon.
     */
    private static void turnBouncerIcon(Object section) {
        int[] spot = turnedSpot;
        android.view.WindowManager wm = (android.view.WindowManager)
                XposedHelpers.getObjectField(section, "windowManager");
        View icon = (View) XposedHelpers.getObjectField(section, "bouncerUdfpsIconContainer");
        if (icon == null || !(icon.getLayoutParams()
                instanceof android.widget.FrameLayout.LayoutParams)) {
            return;
        }
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) icon.getLayoutParams();
        if (spot == null || wm.getDefaultDisplay().getRotation() != spot[2]) {
            // Nothing's own placement (centered across, its top set): Nothing never sets the
            // left margin, so the one set here for landscape goes (it had pushed the icon
            // off the screen, logged at x 3017).
            if (lp.leftMargin != 0) {
                lp.leftMargin = 0;
                icon.setLayoutParams(lp);
            }
            return;
        }
        int[] origin = {0, 0};
        if (icon.getParent() instanceof View && ((View) icon.getParent()).getWidth() > 0) {
            ((View) icon.getParent()).getLocationOnScreen(origin);
        }
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        lp.leftMargin = spot[0] - lp.width / 2 - origin[0];
        lp.topMargin = spot[1] - lp.height / 2 - origin[1];
        icon.setLayoutParams(lp);
    }

    /**
     * Nothing's bouncer lock icon (a lock glyph above the PIN, 348 px from the top for
     * portrait) sat between the landscape bouncer's two halves, over its message: left out in
     * landscape, back as Nothing had it when the screen turns back.
     */
    private static void landscapeBouncerLock(Object section) {
        View lock = (View) XposedHelpers.getObjectField(section, "bouncerlockIconContainer");
        android.view.WindowManager wm = (android.view.WindowManager)
                XposedHelpers.getObjectField(section, "windowManager");
        if (lock == null) {
            return;
        }
        int rotation = wm.getDefaultDisplay().getRotation();
        boolean turned = rotation == android.view.Surface.ROTATION_90
                || rotation == android.view.Surface.ROTATION_270;
        String key = "nlplus.hiddenLock";
        if (turned && lock.getVisibility() == View.VISIBLE) {
            lock.setVisibility(View.GONE);
            XposedHelpers.setAdditionalInstanceField(lock, key, Boolean.TRUE);
        } else if (!turned && XposedHelpers.removeAdditionalInstanceField(lock, key) != null) {
            lock.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Nothing places the bouncer's message 448 px down (nt_message_area_margin_top), under its
     * portrait lock icon; in the landscape bouncer that pushed the PIN entry below the
     * bouncer's bottom (logged 902..1039 against 976). In landscape the message starts at the
     * top of its half; Nothing's margin comes back in portrait.
     */
    private static void messageMargin(View bouncer, boolean landscape) {
        int id = bouncer.getResources().getIdentifier("bouncer_message_area", "id",
                bouncer.getContext().getPackageName());
        View message = id != 0 ? bouncer.findViewById(id) : null;
        if (message == null
                || !(message.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) message.getLayoutParams();
        String key = "nlplus.messageTop";
        Object nothing = XposedHelpers.getAdditionalInstanceField(message, key);
        if (landscape) {
            if (nothing == null) {
                XposedHelpers.setAdditionalInstanceField(message, key, lp.topMargin);
            }
            lp.topMargin = 0;
        } else if (nothing != null) {
            lp.topMargin = (Integer) nothing;
            XposedHelpers.removeAdditionalInstanceField(message, key);
        } else {
            return;
        }
        message.setLayoutParams(lp);
    }

    private static void landscapeBouncer(ClassLoader cl, ConfigStore store) {
        Class<?> flags = XposedHelpers.findClassIfExists(CLS_FLAGS, cl);
        Class<?> flipper = XposedHelpers.findClassIfExists(CLS_FLIPPER, cl);
        if (flags == null || flipper == null) {
            Hooks.log("lock screen bouncer: classes not found, Nothing's layouts kept");
            return;
        }
        Object flag = XposedHelpers.getStaticObjectField(flags, "LOCKSCREEN_ENABLE_LANDSCAPE");
        int answered = 0;
        for (String name : CLS_FEATURE_FLAGS) {
            Class<?> impl = XposedHelpers.findClassIfExists(name, cl);
            if (impl == null) {
                continue;
            }
            answered += XposedBridge.hookAllMethods(impl, "isEnabled", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 1 && param.args[0] == flag
                            && BOUNCER.get()[0] > 0 && !bouncerFailed) {
                        param.setResult(true);
                    }
                }
            }).size();
        }
        XC_MethodHook scope = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (store.get().lockRotation) {
                    BOUNCER.get()[0]++;
                    param.setObjectExtra("nlplus.scoped", Boolean.TRUE);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.getObjectExtra("nlplus.scoped") == null) {
                    return;
                }
                BOUNCER.get()[0]--;
                Throwable failed = param.getThrowable();
                if (failed != null) {
                    // Kept from System UI: the bouncer goes back to Nothing's layouts.
                    bouncerFailed = true;
                    param.setResult(null);
                    Hooks.log("lock screen bouncer: landscape layout failed, Nothing's kept: "
                            + android.util.Log.getStackTraceString(failed));
                }
            }
        };
        int scoped = 0;
        for (java.lang.reflect.Method m : flipper.getDeclaredMethods()) {
            if (m.getName().equals("asynchronouslyInflateView")
                    || m.getName().startsWith("lambda$asynchronouslyInflateView")) {
                XposedBridge.hookMethod(m, scope);
                scoped++;
            }
        }
        // The bouncer's turn listener (an inner class of the security container controller).
        for (int i = 1; i <= 30; i++) {
            Class<?> inner = XposedHelpers.findClassIfExists(CLS_SECURITY_CONTAINER + "$" + i, cl);
            if (inner == null) {
                continue;
            }
            for (java.lang.reflect.Method m : inner.getDeclaredMethods()) {
                if (m.getName().equals("onOrientationChanged")) {
                    XposedBridge.hookMethod(m, scope);
                    scoped++;
                }
            }
        }
        Class<?> entry = XposedHelpers.findClassIfExists(CLS_ENTRY_SECTION, cl);
        if (entry != null) {
            XposedBridge.hookAllMethods(entry, "updateBouncerLockIconContainer",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!store.get().lockRotation) {
                                return;
                            }
                            try {
                                turnBouncerIcon(param.thisObject);
                                landscapeBouncerLock(param.thisObject);
                            } catch (Throwable t) {
                                Hooks.log("lock screen bouncer: " + t);
                            }
                        }
                    });
        }
        for (String view : new String[] {"com.android.keyguard.KeyguardPINView",
                "com.android.keyguard.KeyguardPatternView",
                "com.android.keyguard.KeyguardPasswordView"}) {
            Class<?> c = XposedHelpers.findClassIfExists(view, cl);
            if (c != null) {
                XposedBridge.hookAllMethods(c, "updateConstraints", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!store.get().lockRotation || param.args.length != 1) {
                            return;
                        }
                        try {
                            messageMargin((View) param.thisObject,
                                    Boolean.TRUE.equals(param.args[0]));
                        } catch (Throwable t) {
                            Hooks.log("lock screen bouncer: " + t);
                        }
                    }
                });
            }
        }
        Hooks.log("lock screen bouncer: flag answered in " + answered + ", bouncer code "
                + scoped);
    }

    /**
     * Where the lock screen (the icon's ConstraintLayout) starts on the screen: as laid out if
     * it already is for this orientation, else the window's system bar and cutout insets.
     */
    private static int[] rootOrigin(Object section, android.view.WindowManager wm) {
        View panel = (View) XposedHelpers.getObjectField(section, "notificationPanelView");
        Rect bounds = wm.getCurrentWindowMetrics().getBounds();
        if (panel != null) {
            int id = panel.getResources().getIdentifier("keyguard_root_view", "id",
                    panel.getContext().getPackageName());
            View root = id != 0 ? panel.getRootView().findViewById(id) : null;
            if (root != null && root.getWidth() > 0
                    && (root.getWidth() > root.getHeight())
                            == (bounds.width() > bounds.height())) {
                int[] at = new int[2];
                root.getLocationOnScreen(at);
                return at;
            }
        }
        android.graphics.Insets insets = wm.getCurrentWindowMetrics().getWindowInsets()
                .getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
        return new int[] {insets.left, 0};
    }

    /**
     * The always-on display stays portrait, as Nothing made it (in landscape its portrait AOD
     * was drawn sideways in a strip). SystemUI turns the shade window with the device while it
     * shows the lock screen, the bouncer or the AOD if rotation is allowed; while dozing it
     * gets Nothing's own answer when rotation is not allowed (5, nosensor), as before.
     */
    private static void portraitAod(ClassLoader cl, ConfigStore store) {
        Class<?> window = XposedHelpers.findClassIfExists(
                "com.android.systemui.shade.NotificationShadeWindowControllerImpl", cl);
        if (window == null) {
            Hooks.log("lock screen aod: no shade window controller");
            return;
        }
        XposedBridge.hookAllMethods(window, "adjustScreenOrientation", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!store.get().lockRotation || param.args.length != 1) {
                    return;
                }
                try {
                    Object state = param.args[0];
                    if (XposedHelpers.getBooleanField(state, "dozing")
                            && !XposedHelpers.getBooleanField(state, "keyguardGoingAway")) {
                        Object lp = XposedHelpers.getObjectField(param.thisObject, "mLpChanged");
                        XposedHelpers.setIntField(lp, "screenOrientation",
                                android.content.pm.ActivityInfo
                                        .SCREEN_ORIENTATION_NOSENSOR);
                    }
                } catch (Throwable t) {
                    Hooks.log("lock screen aod: " + t);
                }
            }
        });
    }

    /**
     * What the fingerprint does in landscape, in the LSPosed log: finger down / up on the
     * sensor overlay, where the overlay window is, and what the fingerprint service answers
     * (acquired, help, failed, error, authenticated). For finding why a read fails.
     */
    private static void logFingerprint(ClassLoader cl, ConfigStore store) {
        XC_MethodHook log = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!store.get().lockRotation) {
                    return;
                }
                StringBuilder args = new StringBuilder();
                for (Object a : param.args) {
                    if (!(a instanceof View)) {
                        args.append(args.length() == 0 ? "" : ", ").append(a);
                    }
                }
                Hooks.log("fingerprint: " + param.method.getName() + "(" + args + ")");
            }
        };
        Class<?> controller = XposedHelpers.findClassIfExists(
                "com.android.systemui.biometrics.UdfpsController", cl);
        if (controller != null) {
            for (String m : new String[] {"onFingerDown", "onFingerUp", "onFingerDownFromTP",
                    "onFingerUpFromTP"}) {
                XposedBridge.hookAllMethods(controller, m, log);
            }
        }
        Class<?> monitor = XposedHelpers.findClassIfExists(
                "com.android.keyguard.KeyguardUpdateMonitor", cl);
        if (monitor != null) {
            for (String m : new String[] {"handleFingerprintAcquired", "handleFingerprintHelp",
                    "handleFingerprintAuthFailed", "handleFingerprintError",
                    "handleFingerprintAuthenticated"}) {
                XposedBridge.hookAllMethods(monitor, m, log);
            }
        }
        Class<?> overlay = XposedHelpers.findClassIfExists(CLS_UDFPS_OVERLAY, cl);
        if (overlay != null) {
            XposedBridge.hookAllMethods(overlay, "updateDimensions", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object lp = param.getResult();
                    if (!store.get().lockRotation || !(lp instanceof
                            android.view.WindowManager.LayoutParams)) {
                        return;
                    }
                    android.view.WindowManager.LayoutParams w =
                            (android.view.WindowManager.LayoutParams) lp;
                    Hooks.log("fingerprint: overlay window at " + w.x + "," + w.y + " size "
                            + w.width + "x" + w.height + ", sensor " + XposedHelpers
                            .getObjectField(param.thisObject, "sensorBounds"));
                }
            });
        }
    }

    /**
     * The light under the finger for the optical fingerprint sensor, in landscape. On MediaTek
     * phones Nothing lights it with a white dot on a layer of its own
     * (NTUdfpsIlluminationController), drawn at the sensor's portrait spot; every landscape
     * read ended in "Hold longer" (acquired 5) with the finger still down, and every read that
     * passed was in portrait. Moving that layer (0.30.7) did not help, so it is left as Nothing
     * draws it. While the screen is turned the sensor is also lit the way Nothing lights it on
     * its other phones: the white dot view (hbm_view, in Nothing's layout but left unused on
     * MediaTek) inside the sensor overlay window, which sits on the sensor (its scanning ring
     * shows there), shown at finger down and hidden at finger up.
     */
    private static void lightSensor(ClassLoader cl, ConfigStore store) {
        Class<?> view = XposedHelpers.findClassIfExists(
                "com.nothing.systemui.biometrics.NTUdfpsView", cl);
        if (view == null) {
            Hooks.log("fingerprint light: no NTUdfpsView");
            return;
        }
        XposedBridge.hookAllMethods(view, "onFingerDown", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!store.get().lockRotation) {
                    return;
                }
                try {
                    windowLight((ViewGroup) param.thisObject, true);
                } catch (Throwable t) {
                    Hooks.log("fingerprint light: " + t);
                }
            }
        });
        XposedBridge.hookAllMethods(view, "onFingerUp", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    windowLight((ViewGroup) param.thisObject, false);
                } catch (Throwable t) {
                    Hooks.log("fingerprint light: " + t);
                }
            }
        });
    }

    private static final String LIGHT_SHOWN = "nlplus.lightShown";

    private static void windowLight(ViewGroup udfps, boolean down) {
        int id = udfps.getResources().getIdentifier("hbm_view", "id",
                udfps.getContext().getPackageName());
        View light = id != 0 ? udfps.findViewById(id) : null;
        if (light == null) {
            return;
        }
        if (!down) {
            if (XposedHelpers.removeAdditionalInstanceField(light, LIGHT_SHOWN) != null) {
                light.setVisibility(View.INVISIBLE);
            }
            return;
        }
        if (!XposedHelpers.getBooleanField(udfps, "mIsMTK")
                || XposedHelpers.getObjectField(udfps, "mGhbmView") != null
                || udfps.getDisplay() == null) {
            return; // Nothing lights it in this window itself already, or not on MediaTek.
        }
        int rotation = udfps.getDisplay().getRotation();
        if (rotation != android.view.Surface.ROTATION_90
                && rotation != android.view.Surface.ROTATION_270) {
            return;
        }
        Rect sensor = (Rect) XposedHelpers.callMethod(
                XposedHelpers.getObjectField(udfps, "overlayParams"), "getSensorBounds");
        int size = XposedHelpers.getIntField(udfps, "mScanningViewSize");
        // As Nothing's updateGhbmLayoutParams: sensor-sized, in the middle of the window.
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) light.getLayoutParams();
        lp.width = sensor.width();
        lp.height = sensor.height();
        lp.topMargin = size / 2 - sensor.width() / 2;
        lp.setMarginStart(size / 2 - sensor.width() / 2);
        light.setLayoutParams(lp);
        light.setVisibility(View.VISIBLE);
        XposedHelpers.callMethod(light, "drawIlluminationDot", sensor);
        if (XposedHelpers.setAdditionalInstanceField(light, LIGHT_SHOWN, Boolean.TRUE) == null) {
            Hooks.log("fingerprint light: lit in the sensor window (rotation " + rotation + ")");
        }
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
                if (name.equals("lockscreen_clock_view")) {
                    // Nothing measures its clock as wide as the screen; kept that wide it
                    // stuck out past the left edge (logged at -1008..1180): the half's width.
                    XposedHelpers.callMethod(set, "constrainWidth", id, 0);
                }
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
