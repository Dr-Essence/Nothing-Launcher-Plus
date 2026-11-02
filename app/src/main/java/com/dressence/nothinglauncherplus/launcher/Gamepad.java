package com.dressence.nothinglauncherplus.launcher;

import android.app.Activity;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Intent;
import android.graphics.Canvas;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.anim.SpringInterpolator;
import com.dressence.nothinglauncherplus.sound.Sfx;
import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Game controllers (DualSense, DualShock, Xbox, GameSir, Backbone, ...) on the home screen and in
 * the app drawer.
 *
 * <p>Android already turns the D-pad and left stick into arrow keys, A into select and B into
 * back, and Launcher3 moves its keyboard focus with them. On top of that this adds the shoulder
 * and face buttons, a focus ring in the focused item's own shape ({@link FocusRing}) and a
 * spring "lift" on focused icons and folders. Nothing runs until a controller button is pressed
 * or focus moves without touch.
 *
 * <ul>
 *   <li>A (cross) on a widget: a tap on it (the clock opens Clock); on a music widget, play /
 *   pause, with L1 / R1 previous / next and Start opening the app playing</li>
 *   <li>Y (triangle) and X (square): menu of the focused item (app shortcuts, folder
 *   options)</li>
 *   <li>L1 / R1: previous / next home page; scroll the drawer up / down</li>
 *   <li>L2 / R2: first / last home page; top / bottom of the drawer</li>
 *   <li>Start: open / close the app drawer</li>
 *   <li>L3 / R3 (stick click): home options (wallpaper, widgets, settings)</li>
 *   <li>Select: notifications</li>
 * </ul>
 */
final class Gamepad {

    private static final String CLS_LAUNCHER = "com.android.launcher3.Launcher";
    private static final String CLS_LAUNCHER_STATE = "com.android.launcher3.LauncherState";
    private static final String CLS_FLOATING_VIEW = "com.android.launcher3.AbstractFloatingView";
    private static final String CLS_BUBBLE_TEXT = "com.android.launcher3.BubbleTextView";
    private static final String CLS_FOLDER_ICON = "com.android.launcher3.folder.BaseFolderIcon";
    private static final String CLS_POPUP = "com.android.launcher3.popup.PopupContainerWithArrow";
    private static final String CLS_FOCUS_INDICATOR =
            "com.android.launcher3.keyboard.ItemFocusIndicatorHelper";
    /** What a music widget calls itself (its label, as Launcher3 sets it on the host). */
    private static final java.util.regex.Pattern MUSIC =
            java.util.regex.Pattern.compile("(?i).*(music|media|player|playing|song).*");

    /** How long a widget tap is held, like a finger's. */
    private static final long TAP_MS = 60;
    private static final float LIFT_SCALE = 1.08f;
    private static final int LIFT_DP = 8;
    /** Lift: quick and slightly bouncy; settle: calmer. */
    private static final SpringInterpolator LIFT = new SpringInterpolator(0.42f, 2.6f, 0.62f);
    private static final SpringInterpolator SETTLE = new SpringInterpolator(0.32f, 3.0f, 0.85f);

    private final ConfigStore store;
    private final Object normalState;
    private final Object allAppsState;
    private final Class<?> floatingView;
    private final Class<?> bubbleText;
    private final Class<?> folderIcon;
    private final Class<?> popup;
    private final FocusRing ring;
    private WeakReference<View> lifted = new WeakReference<>(null);

    private Gamepad(ClassLoader cl, ConfigStore store) {
        this.store = store;
        Class<?> states = XposedHelpers.findClass(CLS_LAUNCHER_STATE, cl);
        normalState = XposedHelpers.getStaticObjectField(states, "NORMAL");
        allAppsState = XposedHelpers.getStaticObjectField(states, "ALL_APPS");
        floatingView = XposedHelpers.findClass(CLS_FLOATING_VIEW, cl);
        bubbleText = XposedHelpers.findClass(CLS_BUBBLE_TEXT, cl);
        folderIcon = XposedHelpers.findClass(CLS_FOLDER_ICON, cl);
        popup = XposedHelpers.findClass(CLS_POPUP, cl);
        ring = new FocusRing(bubbleText);
    }

    static void install(ClassLoader cl, ConfigStore store) {
        Gamepad gamepad = new Gamepad(cl, store);
        Class<?> launcher = XposedHelpers.findClass(CLS_LAUNCHER, cl);
        XposedHelpers.findAndHookMethod(launcher, "dispatchKeyEvent", KeyEvent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (gamepad.onKey((Activity) param.thisObject, (KeyEvent) param.args[0])) {
                            param.setResult(true);
                        }
                    }
                });
        // Launcher3's own keyboard-focus outline (a rectangle round the item's whole box, drawn
        // by the drag layer, folders and the drawer) is off while the controller is on: the
        // focus ring shows focus instead, so there is one indicator, in the item's own shape.
        XposedHelpers.findAndHookMethod(CLS_FOCUS_INDICATOR, cl, "draw", Canvas.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (store.get().controllerNav) {
                            param.setResult(null);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(launcher, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                gamepad.watchFocus((Activity) param.thisObject);
            }
        });
    }

    // ---------------------------------------------------------------------------------------
    // Buttons
    // ---------------------------------------------------------------------------------------

    /** Returns true when the event was a mapped controller button and has been handled. */
    private boolean onKey(Activity launcher, KeyEvent event) {
        if (!store.get().controllerNav || !fromController(event)) {
            return false;
        }
        View widget = focusedWidget(launcher);
        if (widget != null && widgetButton(widget, event.getKeyCode())) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                Hooks.guard("controller widget", () -> pressWidget(launcher, widget,
                        event.getKeyCode())).run();
            }
            return true;
        }
        if (!isMappedButton(event.getKeyCode())) {
            return false;
        }
        // Act on release, like system buttons; swallow the press and its repeats.
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            try {
                handle(launcher, event.getKeyCode());
            } catch (Throwable t) {
                Hooks.log("controller button " + KeyEvent.keyCodeToString(event.getKeyCode())
                        + " failed: " + t);
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------
    // Widgets
    // ---------------------------------------------------------------------------------------

    /** The widget with focus (Launcher3's or Nothing's card host), or null. */
    private static View focusedWidget(Activity launcher) {
        View focused = launcher.getWindow().getDecorView().findFocus();
        if (focused == null) {
            return null;
        }
        for (Class<?> c = focused.getClass(); c != null; c = c.getSuperclass()) {
            String name = c.getName();
            if (name.equals("android.appwidget.AppWidgetHostView")
                    || name.equals("com.nothing.cardhost.CardWidgetHostView")) {
                return focused;
            }
        }
        return null;
    }

    /** A music or media player widget, by its label or provider. */
    private static boolean isMusic(View widget) {
        CharSequence label = widget.getContentDescription();
        if (label != null && MUSIC.matcher(label).matches()) {
            return true;
        }
        if (widget instanceof AppWidgetHostView) {
            AppWidgetProviderInfo info = ((AppWidgetHostView) widget).getAppWidgetInfo();
            return info != null && MUSIC.matcher(info.provider.getClassName()).matches();
        }
        return false;
    }

    /**
     * The buttons a focused widget takes: A (cross) as a tap on it (instead of Launcher3's
     * keyboard path, which ends in its long-press menu); on a music widget A plays or pauses,
     * L1 / R1 skip back / forward and Start opens the app playing.
     */
    private static boolean widgetButton(View widget, int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_START:
                return isMusic(widget);
            default:
                return false;
        }
    }

    private void pressWidget(Activity launcher, View widget, int keyCode) {
        boolean music = isMusic(widget);
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                if (music) {
                    mediaKey(launcher, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                } else {
                    tap(widget);
                }
                break;
            case KeyEvent.KEYCODE_BUTTON_L1:
                mediaKey(launcher, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                break;
            case KeyEvent.KEYCODE_BUTTON_R1:
                mediaKey(launcher, KeyEvent.KEYCODE_MEDIA_NEXT);
                break;
            case KeyEvent.KEYCODE_BUTTON_START:
                launcher.sendBroadcast(new Intent(Config.ACTION_OPEN_MEDIA_APP)
                        .setPackage("android"));
                break;
            default:
                break;
        }
    }

    /** To whichever app has the media session, as headphones' buttons are. */
    private static void mediaKey(Activity launcher, int keyCode) {
        AudioManager audio = launcher.getSystemService(AudioManager.class);
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    /** A finger's tap in the middle of what the widget draws: the widget does what it does. */
    private void tap(View widget) {
        FocusRing.Shape shape = ring.shapeOf(widget);
        float x = shape != null ? shape.bounds.centerX() : widget.getWidth() / 2f;
        float y = shape != null ? shape.bounds.centerY() : widget.getHeight() / 2f;
        long down = SystemClock.uptimeMillis();
        touch(widget, down, down, MotionEvent.ACTION_DOWN, x, y);
        widget.postDelayed(Hooks.guard("controller widget", () ->
                touch(widget, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)),
                TAP_MS);
    }

    private static void touch(View view, long down, long at, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, at, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        view.dispatchTouchEvent(event);
        event.recycle();
    }

    /** A and B stay with Android (select / back fallbacks); the D-pad stays focus navigation. */
    private static boolean isMappedButton(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_BUTTON_MODE:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                return true;
            default:
                return false;
        }
    }

    private static boolean fromController(KeyEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || KeyEvent.isGamepadButton(event.getKeyCode());
    }

    private void handle(Activity launcher, int keyCode) throws Throwable {
        boolean inDrawer = XposedHelpers.callMethod(
                XposedHelpers.callMethod(launcher, "getStateManager"), "getState") == allAppsState;
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_BUTTON_MODE: // PS / Xbox / Guide on the home screen
                goTo(launcher, inDrawer ? normalState : allAppsState);
                break;
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1: {
                boolean forward = keyCode == KeyEvent.KEYCODE_BUTTON_R1;
                if (inDrawer) {
                    View list = drawerList(launcher);
                    if (list != null) {
                        int step = (int) (list.getHeight() * 0.8f);
                        XposedHelpers.callMethod(list, "smoothScrollBy", 0, forward ? step : -step);
                    }
                } else {
                    XposedHelpers.callMethod(XposedHelpers.callMethod(launcher, "getWorkspace"),
                            forward ? "scrollRight" : "scrollLeft");
                }
                break;
            }
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_BUTTON_R2: {
                boolean end = keyCode == KeyEvent.KEYCODE_BUTTON_R2;
                if (inDrawer) {
                    View list = drawerList(launcher);
                    Object adapter = list != null ? XposedHelpers.callMethod(list, "getAdapter") : null;
                    if (adapter != null) {
                        int count = (Integer) XposedHelpers.callMethod(adapter, "getItemCount");
                        XposedHelpers.callMethod(list, "smoothScrollToPosition",
                                end ? Math.max(0, count - 1) : 0);
                    }
                } else {
                    Object workspace = XposedHelpers.callMethod(launcher, "getWorkspace");
                    int pages = (Integer) XposedHelpers.callMethod(workspace, "getPageCount");
                    XposedHelpers.callMethod(workspace, "snapToPage", end ? pages - 1 : 0);
                }
                break;
            }
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_X:
                openMenu(launcher);
                break;
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                if (inDrawer) {
                    goTo(launcher, normalState);
                    break;
                }
                View root = launcher.getWindow().getDecorView();
                XposedHelpers.callMethod(launcher, "showDefaultOptions",
                        root.getWidth() / 2f, root.getHeight() / 2f);
                break;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                // Nothing Launcher may not expand the panel itself (EXPAND_STATUS_BAR): the
                // system does it (SystemShortcuts).
                launcher.sendBroadcast(new Intent(Config.ACTION_EXPAND_NOTIFICATIONS)
                        .setPackage("android"));
                break;
            default:
                break;
        }
    }

    private void goTo(Activity launcher, Object state) {
        XposedHelpers.callStaticMethod(floatingView, "closeAllOpenViews", launcher);
        XposedHelpers.callMethod(XposedHelpers.callMethod(launcher, "getStateManager"),
                "goToState", state);
    }

    private static View drawerList(Activity launcher) {
        Object appsView = XposedHelpers.callMethod(launcher, "getAppsView");
        return appsView != null
                ? (View) XposedHelpers.callMethod(appsView, "getActiveRecyclerView") : null;
    }

    /** App icons show their shortcuts popup; anything else gets its long-press action. */
    private void openMenu(Activity launcher) {
        View focused = launcher.getWindow().getDecorView().findFocus();
        if (focused == null) {
            return;
        }
        if (bubbleText.isInstance(focused)) {
            XposedHelpers.callStaticMethod(popup, "showForIcon", focused);
        } else {
            focused.performLongClick();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Focus lift
    // ---------------------------------------------------------------------------------------

    private void watchFocus(Activity launcher) {
        View root = launcher.getWindow().getDecorView();
        float lift = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, LIFT_DP,
                launcher.getResources().getDisplayMetrics());
        ViewTreeObserver observer = root.getViewTreeObserver();
        observer.addOnGlobalFocusChangeListener((oldFocus, newFocus) ->
                Hooks.guard("controller focus", () -> lift(newFocus, lift)).run());
        // Touching the screen ends controller mode: drop the lift and ring straight away.
        observer.addOnTouchModeChangeListener(inTouchMode -> {
            if (inTouchMode) {
                Hooks.guard("controller focus", this::settle).run();
            }
        });
    }

    /**
     * The newly focused item shows it: the focus ring round its own shape, and home screen
     * icons and folders lift towards the eye; the one before settles back, wherever focus came
     * from (a folder or menu closing moves focus without an old item). Drawer folder tiles fill
     * their cells, so they are not scaled up (the lift went past the list's edge and was cut
     * off); the ring shows them.
     */
    private void lift(View newFocus, float lift) {
        settle();
        if (newFocus == null || !store.get().controllerNav || newFocus.isInTouchMode()) {
            return;
        }
        ring.show(newFocus);
        if (liftable(newFocus) && !drawerTile(newFocus)) {
            newFocus.animate().scaleX(LIFT_SCALE).scaleY(LIFT_SCALE).translationZ(lift)
                    .setInterpolator(LIFT).setDuration(420).start();
            // Only what was lifted is settled: a widget's scale is its own (scale-to-fit).
            lifted = new WeakReference<>(newFocus);
        }
        LauncherSounds.effect(Sfx.MOVE);
    }

    private void settle() {
        ring.hide();
        View view = lifted.get();
        if (view != null) {
            view.animate().scaleX(1).scaleY(1).translationZ(0)
                    .setInterpolator(SETTLE).setDuration(320).start();
            lifted = new WeakReference<>(null);
        }
    }

    /** A Smart drawer folder or entrance tile. */
    private static boolean drawerTile(View view) {
        String name = view.getClass().getName();
        return name.equals(SmartDrawer.CLS_FOLDER_ICON) || name.endsWith(".SmartEntranceIcon");
    }

    /** Icons and folder tiles; widgets keep their own scale-to-fit, so they are left alone. */
    private boolean liftable(View view) {
        String name = view.getClass().getName();
        return bubbleText.isInstance(view) || folderIcon.isInstance(view)
                || name.equals(SmartDrawer.CLS_FOLDER_ICON) || name.endsWith(".SmartEntranceIcon");
    }
}
