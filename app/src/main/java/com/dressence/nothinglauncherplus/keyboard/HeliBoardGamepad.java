package com.dressence.nothinglauncherplus.keyboard;

import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.HorizontalScrollView;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * HeliBoard (helium314.keyboard) with a game controller, the way a PlayStation's on-screen
 * keyboard works: a highlight moves over everything on the keyboard, and the controller's
 * buttons do the keyboard's common jobs.
 *
 * <ul>
 *   <li>D-pad / left stick: move the highlight. It goes over all that HeliBoard shows: the keys,
 *   the suggestions and the toolbar above them (opened, closed, pinned keys), the emoji pages,
 *   their categories and bottom row, the clipboard and its buttons, the one-handed mode buttons,
 *   and the panels that open over the keys (accents and other long-press keys, more
 *   suggestions, emoji skin tones). Rows wrap left and right; lists scroll as it moves.</li>
 *   <li>Cross (A): touch what is highlighted, for as long as it is held, so a hold does what a
 *   finger's does: repeat (backspace, arrows), or open the long-press panel. While held with a
 *   panel open, the d-pad slides over the panel and letting go picks.</li>
 *   <li>Square (X): backspace (held, it repeats). Triangle (Y): space.</li>
 *   <li>L1 / R1: move the text cursor left / right. L2: shift (twice quickly: caps lock).
 *   R2: done (the keyboard's enter / action key).</li>
 *   <li>Circle (B): back: close an open panel, else from emoji / clipboard back to the letters,
 *   else close the keyboard.</li>
 * </ul>
 *
 * <p>Cross is a finger: a touch is given to HeliBoard's window where the highlight is, so every
 * key, button and panel behaves exactly as with a finger (HeliBoard's own press, repeat,
 * long-press, popup and pick code). The highlight is drawn in the window's overlay, above all of
 * HeliBoard's views, and follows what it is on when the keys change (shift, symbols, emoji).
 * Buttons are only taken while the keyboard is up and only from a controller: a hardware
 * keyboard's arrows are left to HeliBoard, and with the keyboard closed every button goes to the
 * app as before.
 */
public final class HeliBoardGamepad {

    public static final String PKG = "helium314.keyboard";
    private static final String CLS_IME = "helium314.keyboard.latin.LatinIME";
    private static final String CLS_KEYBOARD_VIEW = "helium314.keyboard.keyboard.KeyboardView";
    private static final String CLS_PANEL = "helium314.keyboard.keyboard.PopupKeysPanel";
    private static final String CLS_HAPTIC = "helium314.keyboard.event.HapticEvent";

    /** HeliBoard's key codes (KeyCode.kt, Constants.java). */
    private static final int CODE_ENTER = '\n';
    private static final int CODE_SPACE = ' ';
    private static final int CODE_DELETE = -7;
    private static final int CODE_SHIFT = -11;
    private static final int CODE_ARROW_LEFT = -21;
    private static final int CODE_ARROW_RIGHT = -22;
    private static final int CODE_ALPHA = -201;
    private static final int NOT_A_COORDINATE = -1;

    private final Class<?> keyboardViewClass;
    private final Class<?> panelClass;
    private final Object keyPress;
    private final Object keyRepeat;

    /** Something the highlight can be on: a key of a keyboard view, or a view ({@code key} null). */
    private static final class Target {
        final View view;
        final Object key;
        /** Where it is shown, in the window: clipped to what of it can be seen. */
        final Rect rect;

        Target(View view, Object key, Rect rect) {
            this.view = view;
            this.key = key;
            this.rect = rect;
        }

        boolean same(Target other) {
            return other != null && view == other.view && key == other.key;
        }
    }

    private WeakReference<InputMethodService> ime = new WeakReference<>(null);
    private Target focus;
    /** Where the highlight was, in the window: to find it again when what it was on goes. */
    private float lastX = -1;
    private float lastY = -1;
    /** Buttons whose down was taken: their up is taken too. */
    private final Set<Integer> taken = new HashSet<>();

    /** The touch Cross is giving, while held: when it started and where it is, in the window. */
    private boolean touching;
    private long touchDownTime;
    private float touchX;
    private float touchY;
    private WeakReference<View> touchRoot = new WeakReference<>(null);

    /** The window the highlight is drawn in, and the drawing. */
    private WeakReference<View> decor = new WeakReference<>(null);
    private final Ring ring = new Ring();

    private HeliBoardGamepad(ClassLoader cl) {
        keyboardViewClass = XposedHelpers.findClass(CLS_KEYBOARD_VIEW, cl);
        panelClass = XposedHelpers.findClass(CLS_PANEL, cl);
        Class<?> haptic = XposedHelpers.findClass(CLS_HAPTIC, cl);
        keyPress = XposedHelpers.getStaticObjectField(haptic, "KEY_PRESS");
        keyRepeat = XposedHelpers.getStaticObjectField(haptic, "KEY_REPEAT");
    }

    public static void install(ClassLoader cl, ConfigStore store) {
        HeliBoardGamepad pad = new HeliBoardGamepad(cl);
        Class<?> imeClass = XposedHelpers.findClass(CLS_IME, cl);
        XposedHelpers.findAndHookMethod(imeClass, "onKeyDown", int.class, KeyEvent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!store.get().heliboardController) {
                            return;
                        }
                        InputMethodService service = (InputMethodService) param.thisObject;
                        if (pad.onKeyDown(service, (KeyEvent) param.args[1])) {
                            param.setResult(true);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(imeClass, "onKeyUp", int.class, KeyEvent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!store.get().heliboardController) {
                            return;
                        }
                        InputMethodService service = (InputMethodService) param.thisObject;
                        if (pad.onKeyUp(service, (KeyEvent) param.args[1])) {
                            param.setResult(true);
                        }
                    }
                });
        // A new field, or the keyboard going away: no touch is left down, and the highlight
        // starts again from the middle.
        XC_MethodHook reset = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                // The switch is read again here (a file check), not on every key.
                store.reloadIfChanged();
                pad.reset();
            }
        };
        XposedBridge.hookAllMethods(imeClass, "onStartInputView", reset);
        XposedBridge.hookAllMethods(imeClass, "onFinishInputView", reset);
        XposedBridge.hookAllMethods(imeClass, "onWindowHidden", reset);
        Hooks.log("heliboard gamepad: on");
    }

    // ---------------------------------------------------------------------------------------
    // Buttons
    // ---------------------------------------------------------------------------------------

    private static boolean fromController(KeyEvent event) {
        if (KeyEvent.isGamepadButton(event.getKeyCode())) {
            return true;
        }
        InputDevice device = event.getDevice();
        int sources = device != null ? device.getSources() : event.getSource();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private boolean onKeyDown(InputMethodService service, KeyEvent event) {
        if (!fromController(event) || !service.isInputViewShown()) {
            return false;
        }
        View root = windowRoot(service);
        if (root == null) {
            return false;
        }
        ime = new WeakReference<>(service);
        attach(root);
        int code = event.getKeyCode();
        int repeat = event.getRepeatCount();
        boolean handled = true;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                move(root, 0, -1, true);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                move(root, 0, 1, true);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                move(root, -1, 0, true);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                move(root, 1, 0, true);
                break;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                // Held, HeliBoard repeats or opens the long-press panel itself, as for a finger.
                if (repeat == 0) {
                    touchDown(root);
                }
                break;
            case KeyEvent.KEYCODE_BUTTON_X:
                typeCode(service, CODE_DELETE, repeat);
                break;
            case KeyEvent.KEYCODE_BUTTON_Y:
                if (repeat == 0) {
                    typeCode(service, CODE_SPACE, 0);
                }
                break;
            case KeyEvent.KEYCODE_BUTTON_L1:
                typeCode(service, CODE_ARROW_LEFT, repeat);
                break;
            case KeyEvent.KEYCODE_BUTTON_R1:
                typeCode(service, CODE_ARROW_RIGHT, repeat);
                break;
            case KeyEvent.KEYCODE_BUTTON_L2:
                if (repeat == 0) {
                    typeCode(service, CODE_SHIFT, 0);
                }
                break;
            case KeyEvent.KEYCODE_BUTTON_R2:
                if (repeat == 0) {
                    typeCode(service, CODE_ENTER, 0);
                }
                break;
            case KeyEvent.KEYCODE_BUTTON_B:
                if (repeat == 0) {
                    back(service, root);
                }
                break;
            default:
                handled = false;
        }
        if (handled) {
            taken.add(code);
        }
        return handled;
    }

    private boolean onKeyUp(InputMethodService service, KeyEvent event) {
        int code = event.getKeyCode();
        if (!taken.remove(code)) {
            return false;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_DPAD_CENTER) {
            touchEnd(MotionEvent.ACTION_UP);
        }
        return true;
    }

    /** Circle: close an open panel, else back to the letters, else close the keyboard. */
    private void back(InputMethodService service, View root) {
        if (touching) {
            touchEnd(MotionEvent.ACTION_CANCEL);
        }
        View panel = openPanel(root);
        if (panel != null && dismiss(panel)) {
            return;
        }
        if (!mainKeyboardShown(service)) {
            typeCode(service, CODE_ALPHA, 0);
            return;
        }
        reset();
        service.requestHideSelf(0);
    }

    /** Closes {@code panel} with HeliBoard's own dismissPopupKeysPanel (renamed in releases). */
    private static boolean dismiss(View panel) {
        for (Method m : panel.getClass().getMethods()) {
            if (m.getName().startsWith("dismissPopupKeysPanel")
                    && m.getParameterTypes().length == 0) {
                try {
                    m.invoke(panel);
                    return true;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    Hooks.log("heliboard gamepad: closing the panel failed: " + e);
                    return false;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------
    // Cross: a finger
    // ---------------------------------------------------------------------------------------

    private void touchDown(View root) {
        if (touching) {
            return;
        }
        Target target = current(root);
        if (target == null) {
            return;
        }
        if (focus == null) {
            show(target); // The first press only shows where the highlight is.
            return;
        }
        show(target);
        touching = true;
        touchRoot = new WeakReference<>(root);
        touchDownTime = SystemClock.uptimeMillis();
        touchX = target.rect.exactCenterX();
        touchY = target.rect.exactCenterY();
        dispatch(MotionEvent.ACTION_DOWN);
    }

    /** While Cross is held over an open panel: the finger slides to {@code target}. */
    private void touchMove(Target target) {
        touchX = target.rect.exactCenterX();
        touchY = target.rect.exactCenterY();
        dispatch(MotionEvent.ACTION_MOVE);
    }

    private void touchEnd(int action) {
        if (!touching) {
            return;
        }
        dispatch(action);
        touching = false;
        touchRoot = new WeakReference<>(null);
    }

    private void dispatch(int action) {
        View root = touchRoot.get();
        if (root == null) {
            touching = false;
            return;
        }
        MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties()};
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
        coords[0].x = touchX;
        coords[0].y = touchY;
        coords[0].pressure = 1f;
        coords[0].size = 1f;
        MotionEvent event = MotionEvent.obtain(touchDownTime, SystemClock.uptimeMillis(), action,
                1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            root.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Shortcut buttons
    // ---------------------------------------------------------------------------------------

    private static Object listener(InputMethodService service) {
        return XposedHelpers.getObjectField(service, "mKeyboardActionListener");
    }

    /** A shortcut button: the key with {@code code}, pressed and let go as by a finger. */
    private void typeCode(InputMethodService service, int code, int repeat) {
        Object listener = listener(service);
        XposedHelpers.callMethod(listener, "onPressKey", code, repeat, 1,
                repeat == 0 ? keyPress : keyRepeat);
        XposedHelpers.callMethod(listener, "onCodeInput", code, NOT_A_COORDINATE,
                NOT_A_COORDINATE, repeat > 0);
        XposedHelpers.callMethod(listener, "onReleaseKey", code, false);
    }

    // ---------------------------------------------------------------------------------------
    // What can be highlighted
    // ---------------------------------------------------------------------------------------

    private static View windowRoot(InputMethodService service) {
        Dialog window = service.getWindow();
        return window != null && window.getWindow() != null
                ? window.getWindow().getDecorView() : null;
    }

    private static boolean mainKeyboardShown(InputMethodService service) {
        Object switcher = XposedHelpers.getObjectField(service, "mKeyboardSwitcher");
        View main = (View) XposedHelpers.getObjectField(switcher, "mKeyboardView");
        return main != null && main.isShown();
    }

    /** The long-press / more-suggestions panel open over the keys, if one is. */
    private View openPanel(View root) {
        List<View> found = new ArrayList<>();
        findPanels(root, found);
        return found.isEmpty() ? null : found.get(found.size() - 1);
    }

    private void findPanels(View view, List<View> out) {
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (panelClass.isInstance(view) && keyboardViewClass.isInstance(view) && view.isShown()
                && view.getWidth() > 0) {
            out.add(view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                findPanels(group.getChildAt(i), out);
            }
        }
    }

    /** All that can be highlighted now: only an open panel's keys while one is open. */
    private List<Target> targets(View root) {
        List<Target> out = new ArrayList<>();
        View panel = openPanel(root);
        if (panel != null) {
            addKeys(root, panel, out);
            if (!out.isEmpty()) {
                return out;
            }
        }
        collect(root, root, out);
        return out;
    }

    private void collect(View root, View view, List<Target> out) {
        if (view.getVisibility() != View.VISIBLE || view.getWidth() <= 0
                || view.getHeight() <= 0) {
            return;
        }
        if (keyboardViewClass.isInstance(view)) {
            addKeys(root, view, out);
            return;
        }
        if (view.isClickable() && view.hasOnClickListeners() && view != root) {
            Rect rect = shown(root, view, new Rect(0, 0, view.getWidth(), view.getHeight()));
            if (rect != null) {
                out.add(new Target(view, null, rect));
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collect(root, group.getChildAt(i), out);
            }
        }
    }

    private void addKeys(View root, View view, List<Target> out) {
        if (!view.isShown()) {
            return;
        }
        for (Object key : keys(view)) {
            Rect rect = shown(root, view, keyRect(view, key));
            if (rect != null) {
                out.add(new Target(view, key, rect));
            }
        }
    }

    /** The keys of {@code view}'s keyboard that can be pressed. */
    private static List<?> keys(View view) {
        Object keyboard = XposedHelpers.getObjectField(view, "mKeyboard");
        if (keyboard == null) {
            return new ArrayList<>();
        }
        List<Object> keys = new ArrayList<>();
        for (Object key : (List<?>) XposedHelpers.callMethod(keyboard, "getSortedKeys")) {
            if (key.getClass().getName().endsWith("$Spacer")
                    || XposedHelpers.getIntField(key, "mWidth") <= 0
                    || XposedHelpers.getIntField(key, "mHeight") <= 0) {
                continue;
            }
            keys.add(key);
        }
        return keys;
    }

    /** The key's bounds in its view, where HeliBoard draws it (KeyboardView.onDrawKey). */
    private static Rect keyRect(View view, Object key) {
        int left = view.getPaddingLeft() + (int) XposedHelpers.callMethod(key, "getX");
        int top = view.getPaddingTop() + (int) XposedHelpers.callMethod(key, "getY");
        return new Rect(left, top, left + XposedHelpers.getIntField(key, "mWidth"),
                top + XposedHelpers.getIntField(key, "mHeight"));
    }

    /**
     * {@code local} (in {@code view}) in the window, clipped to the part that can be seen (lists
     * and scrolled strips hide some); null if none can be.
     */
    private static Rect shown(View root, View view, Rect local) {
        Rect visible = new Rect();
        if (!view.getGlobalVisibleRect(visible)) {
            return null;
        }
        int[] at = new int[2];
        int[] rootAt = new int[2];
        view.getLocationInWindow(at);
        root.getLocationInWindow(rootAt);
        Rect rect = new Rect(local);
        rect.offset(at[0] - rootAt[0], at[1] - rootAt[1]);
        return rect.intersect(visible) && rect.width() > 1 && rect.height() > 1 ? rect : null;
    }

    /** The highlighted thing as it is now, or what is nearest where it was if it went. */
    private Target current(View root) {
        if (focus != null) {
            Target now = refresh(root, focus);
            if (now != null) {
                return now;
            }
        }
        List<Target> all = targets(root);
        if (all.isEmpty()) {
            return null;
        }
        float x = lastX;
        float y = lastY;
        if (x < 0) {
            // At first: the middle of what is shown (the keys, the emoji, the clipboard).
            Rect box = new Rect(all.get(0).rect);
            for (Target t : all) {
                box.union(t.rect);
            }
            x = box.exactCenterX();
            y = box.exactCenterY();
        }
        return nearest(all, x, y);
    }

    /** {@code t} where it is now, or null if it is gone or covered by an open panel. */
    private Target refresh(View root, Target t) {
        if (!t.view.isShown() || t.view.getRootView() != root) {
            return null;
        }
        View panel = openPanel(root);
        if (panel != null && t.view != panel) {
            return null;
        }
        Rect local;
        if (t.key != null) {
            Object keyboard = XposedHelpers.getObjectField(t.view, "mKeyboard");
            if (keyboard == null || !((List<?>) XposedHelpers.callMethod(keyboard,
                    "getSortedKeys")).contains(t.key)) {
                return null; // The keys changed (shift, symbols, another page).
            }
            local = keyRect(t.view, t.key);
        } else {
            local = new Rect(0, 0, t.view.getWidth(), t.view.getHeight());
        }
        Rect rect = shown(root, t.view, local);
        return rect != null ? new Target(t.view, t.key, rect) : null;
    }

    private static Target nearest(List<Target> all, float x, float y) {
        Target best = null;
        float bestD = Float.MAX_VALUE;
        for (Target t : all) {
            float dx = t.rect.exactCenterX() - x;
            float dy = t.rect.exactCenterY() - y;
            float d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------------------------------
    // Moving
    // ---------------------------------------------------------------------------------------

    private void move(View root, int dx, int dy, boolean mayScroll) {
        boolean fresh = focus == null;
        Target cur = current(root);
        if (cur == null) {
            return;
        }
        if (fresh) {
            show(cur); // The first press only shows where the highlight is.
            return;
        }
        if (touching && openPanel(root) == null) {
            return; // A held key that opened no panel: the finger stays on it.
        }
        List<Target> all = targets(root);
        Target next = dy != 0 ? vertical(all, cur, dy) : horizontal(all, cur, dx, false);
        // At the edge of a list (clipboard, emoji) or of the scrolled toolbar that goes on: it
        // scrolls on first, and the move is tried again.
        View scroller = mayScroll && !touching ? scroller(root, cur.view, dx, dy) : null;
        if (scroller != null && (next == null || !inside(next.view, scroller))) {
            Rect size = local(cur);
            scroller.scrollBy(dx * size.width(), dy * size.height());
            root.post(Hooks.guard("heliboard gamepad move", () -> move(root, dx, dy, false)));
            return;
        }
        if (next == null && dx != 0) {
            next = horizontal(all, cur, dx, true);
        }
        if (next == null) {
            return;
        }
        reveal(root, next);
        show(next);
        if (touching) {
            touchMove(next);
        }
    }

    /** The nearest row up / down that lies across from {@code cur}; in it, the nearest across. */
    private static Target vertical(List<Target> all, Target cur, int dy) {
        float cx = cur.rect.exactCenterX();
        float cy = cur.rect.exactCenterY();
        List<Target> ahead = new ArrayList<>();
        List<Target> across = new ArrayList<>();
        for (Target t : all) {
            float ty = t.rect.exactCenterY();
            if (t.same(cur) || (dy < 0 ? ty >= cy - 1 : ty <= cy + 1)) {
                continue;
            }
            ahead.add(t);
            if (t.rect.right > cur.rect.left && t.rect.left < cur.rect.right) {
                across.add(t);
            }
        }
        List<Target> pool = across.isEmpty() ? ahead : across;
        if (pool.isEmpty()) {
            return null;
        }
        Target nearestRow = null;
        for (Target t : pool) {
            if (nearestRow == null || Math.abs(t.rect.exactCenterY() - cy)
                    < Math.abs(nearestRow.rect.exactCenterY() - cy)) {
                nearestRow = t;
            }
        }
        int rowY = nearestRow.rect.centerY();
        Target best = null;
        float bestD = Float.MAX_VALUE;
        for (Target t : pool) {
            if (t.rect.top <= rowY && t.rect.bottom >= rowY) {
                float d = Math.abs(t.rect.exactCenterX() - cx);
                if (d < bestD) {
                    bestD = d;
                    best = t;
                }
            }
        }
        return best;
    }

    /**
     * The next thing left / right in {@code cur}'s row; with {@code wrap}, the far end of the row
     * (the row goes round).
     */
    private static Target horizontal(List<Target> all, Target cur, int dx, boolean wrap) {
        float cx = cur.rect.exactCenterX();
        float cy = cur.rect.exactCenterY();
        Target best = null;
        float bestD = Float.MAX_VALUE;
        for (Target t : all) {
            if (t.same(cur) || t.rect.bottom <= cur.rect.top || t.rect.top >= cur.rect.bottom) {
                continue;
            }
            float tx = t.rect.exactCenterX();
            if (wrap) {
                // Round the row only, not to the side of it: the farthest the other way.
                if (t.rect.top <= cy && t.rect.bottom >= cy
                        && (best == null || -dx * tx > -dx * best.rect.exactCenterX())) {
                    best = t;
                }
                continue;
            }
            if (dx > 0 ? tx <= cx + 1 : tx >= cx - 1) {
                continue;
            }
            float d = Math.abs(tx - cx) + 2 * Math.abs(t.rect.exactCenterY() - cy);
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        return best;
    }

    /**
     * The list or scrolled strip around {@code view} that can scroll that way: a vertical list
     * (clipboard, an emoji category) up / down, the toolbar's strip left / right. Never the emoji
     * category pager: its pages are changed from the categories above.
     */
    private static View scroller(View root, View view, int dx, int dy) {
        for (View v = parent(view); v != null && v != root; v = parent(v)) {
            if (dy != 0 ? v.canScrollVertically(dy)
                    : v instanceof HorizontalScrollView && v.canScrollHorizontally(dx)) {
                return v;
            }
        }
        return null;
    }

    private static View parent(View view) {
        return view.getParent() instanceof View ? (View) view.getParent() : null;
    }

    private static boolean inside(View view, View ancestor) {
        for (View v = view; v != null; v = parent(v)) {
            if (v == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** The target's whole bounds in its own view. */
    private static Rect local(Target t) {
        return t.key != null ? keyRect(t.view, t.key)
                : new Rect(0, 0, t.view.getWidth(), t.view.getHeight());
    }

    /**
     * A part-hidden target is scrolled fully into sight by the list or strip it is in (only by
     * that: not by HeliBoard's window, which must not move).
     */
    private static void reveal(View root, Target t) {
        Rect whole = local(t);
        if (t.rect.width() >= whole.width() && t.rect.height() >= whole.height()) {
            return;
        }
        for (View child = t.view, v = parent(child); v instanceof ViewGroup && v != root;
                child = v, v = parent(v)) {
            boolean scrolls = v.canScrollVertically(1) || v.canScrollVertically(-1)
                    || (v instanceof HorizontalScrollView && (v.canScrollHorizontally(1)
                            || v.canScrollHorizontally(-1)));
            if (scrolls) {
                Rect r = new Rect(whole);
                if (child != t.view) {
                    ((ViewGroup) child).offsetDescendantRectToMyCoords(t.view, r);
                }
                ((ViewGroup) v).requestChildRectangleOnScreen(child, r, true);
                return;
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Highlight
    // ---------------------------------------------------------------------------------------

    private void show(Target t) {
        focus = t;
        lastX = t.rect.exactCenterX();
        lastY = t.rect.exactCenterY();
        ring.place(t.rect, t.view);
    }

    private void reset() {
        touchEnd(MotionEvent.ACTION_CANCEL);
        focus = null;
        lastX = -1;
        lastY = -1;
        ring.place(null, null);
    }

    /** Draws the highlight above all of HeliBoard's window, and keeps it on its target. */
    private void attach(View root) {
        if (decor.get() == root) {
            return;
        }
        View old = decor.get();
        if (old != null) {
            old.getOverlay().remove(ring);
        }
        decor = new WeakReference<>(root);
        root.getOverlay().add(ring);
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (decor.get() != root) {
                    root.getViewTreeObserver().removeOnPreDrawListener(this);
                    return true;
                }
                try {
                    follow(root);
                } catch (Throwable t) {
                    Hooks.log("heliboard gamepad: highlight: " + t);
                    focus = null;
                    ring.place(null, null);
                }
                return true;
            }
        });
    }

    /** Before each frame: the highlight on its target where it is now (it moved, or went). */
    private void follow(View root) {
        if (focus == null) {
            return;
        }
        Target now = current(root);
        if (now == null) {
            focus = null;
            ring.place(null, null);
            return;
        }
        if (!now.same(focus) || !now.rect.equals(focus.rect)) {
            show(now);
        }
    }

    /** The highlight: a rounded ring with a light fill, the way a focused key is shown. */
    private static final class Ring extends Drawable {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float radius;

        Ring() {
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(0xFFFFFFFF);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0x33FFFFFF);
        }

        void place(Rect where, View view) {
            if (where == null) {
                if (!getBounds().isEmpty()) {
                    setBounds(0, 0, 0, 0);
                    invalidateSelf();
                }
                return;
            }
            float inset = dp(view, 2);
            radius = dp(view, 8);
            stroke.setStrokeWidth(dp(view, 2));
            Rect b = new Rect(where);
            b.inset((int) inset, (int) inset);
            if (!b.equals(getBounds())) {
                setBounds(b);
                invalidateSelf();
            }
        }

        @Override
        public void draw(Canvas canvas) {
            if (getBounds().isEmpty()) {
                return;
            }
            rect.set(getBounds());
            canvas.drawRoundRect(rect, radius, radius, fill);
            canvas.drawRoundRect(rect, radius, radius, stroke);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static float dp(View view, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                view.getResources().getDisplayMetrics());
    }
}
