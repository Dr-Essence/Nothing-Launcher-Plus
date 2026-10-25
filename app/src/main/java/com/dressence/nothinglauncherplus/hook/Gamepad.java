package com.dressence.nothinglauncherplus.hook;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;

import com.dressence.nothinglauncherplus.anim.SpringInterpolator;
import com.dressence.nothinglauncherplus.sound.Sfx;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Game controllers (DualSense, DualShock, Xbox, GameSir, Backbone, ...) on the home screen and in
 * the app drawer.
 *
 * <p>Android already turns the D-pad and left stick into arrow keys, A into select and B into
 * back, and Launcher3 moves its keyboard focus with them. On top of that this adds the shoulder
 * and face buttons, and a spring "lift" on the focused item so it is obvious where focus is.
 * Nothing runs until a controller button is pressed or focus moves without touch.
 *
 * <ul>
 *   <li>Y: open / close the app drawer</li>
 *   <li>L1 / R1: previous / next home page; scroll the drawer up / down</li>
 *   <li>L2 / R2: first / last home page; top / bottom of the drawer</li>
 *   <li>X: menu of the focused item (app shortcuts, folder options)</li>
 *   <li>Start: home options (wallpaper, widgets, settings); closes the drawer</li>
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
        if (!store.get().controllerNav || !isMappedButton(event.getKeyCode())
                || !fromController(event)) {
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
            case KeyEvent.KEYCODE_BUTTON_SELECT:
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
            case KeyEvent.KEYCODE_BUTTON_Y:
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
            case KeyEvent.KEYCODE_BUTTON_X:
                openMenu(launcher);
                break;
            case KeyEvent.KEYCODE_BUTTON_START:
                if (inDrawer) {
                    goTo(launcher, normalState);
                } else {
                    View root = launcher.getWindow().getDecorView();
                    XposedHelpers.callMethod(launcher, "showDefaultOptions",
                            root.getWidth() / 2f, root.getHeight() / 2f);
                }
                break;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                Object statusBar = launcher.getSystemService("statusbar");
                if (statusBar != null) {
                    XposedHelpers.callMethod(statusBar, "expandNotificationsPanel");
                }
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
        observer.addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            settle(oldFocus);
            if (newFocus != null && store.get().controllerNav && !newFocus.isInTouchMode()
                    && liftable(newFocus)) {
                newFocus.animate().scaleX(LIFT_SCALE).scaleY(LIFT_SCALE).translationZ(lift)
                        .setInterpolator(LIFT).setDuration(420).start();
                lifted = new WeakReference<>(newFocus);
                LauncherSounds.effect(Sfx.MOVE);
            }
        });
        // Touching the screen ends controller mode: drop the lift straight away.
        observer.addOnTouchModeChangeListener(inTouchMode -> {
            if (inTouchMode) {
                settle(lifted.get());
            }
        });
    }

    private void settle(View view) {
        if (view != null && view == lifted.get()) {
            view.animate().scaleX(1).scaleY(1).translationZ(0)
                    .setInterpolator(SETTLE).setDuration(320).start();
            lifted = new WeakReference<>(null);
        }
    }

    /** Icons and folder tiles; widgets keep their own scale-to-fit, so they are left alone. */
    private boolean liftable(View view) {
        String name = view.getClass().getName();
        return bubbleText.isInstance(view) || folderIcon.isInstance(view)
                || name.equals(SmartDrawer.CLS_FOLDER_ICON) || name.endsWith(".SmartEntranceIcon");
    }
}
