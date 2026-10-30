package com.dressence.nothinglauncherplus.keyboard;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.inputmethodservice.InputMethodService;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * HeliBoard (helium314.keyboard) with a game controller, the way a PlayStation's on-screen
 * keyboard works: a highlight moves over the keys and the suggestions above them, and the
 * controller's buttons do the keyboard's common jobs.
 *
 * <ul>
 *   <li>D-pad / left stick: move over the keys; up from the top row into the suggestions (and
 *   the toolbar keys beside them), down back to the keys. Rows wrap left and right.</li>
 *   <li>Cross (A): press the highlighted key, or pick the highlighted suggestion; held on a key
 *   that repeats (backspace, arrows), it repeats.</li>
 *   <li>Square (X): backspace (held, it repeats). Triangle (Y): space.</li>
 *   <li>L1 / R1: move the text cursor left / right. L2: shift (twice quickly: caps lock).
 *   R2: done (the keyboard's enter / action key).</li>
 *   <li>Circle (B): close the keyboard.</li>
 * </ul>
 *
 * <p>A key is pressed the way a finger presses it (HeliBoard's PointerTracker): press when the
 * button goes down, the key's input when it comes up (at once, and again on each repeat, for
 * keys that repeat), then release; so shift, caps lock, auto-capitals, suggestions, haptics and
 * sound behave exactly as with touch. A suggestion or toolbar key is clicked as a tap clicks
 * it. Buttons are only taken while the keyboard is up and only from a controller: a hardware
 * keyboard's arrows are left to HeliBoard, and with the keyboard closed every button goes to
 * the app as before.
 */
public final class HeliBoardGamepad {

    public static final String PKG = "helium314.keyboard";
    private static final String CLS_IME = "helium314.keyboard.latin.LatinIME";
    private static final String CLS_KEYBOARD_VIEW = "helium314.keyboard.keyboard.KeyboardView";
    private static final String CLS_STRIP =
            "helium314.keyboard.latin.suggestions.SuggestionStripView";
    private static final String CLS_HAPTIC = "helium314.keyboard.event.HapticEvent";

    /** HeliBoard's key codes (KeyCode.kt, Constants.java). */
    private static final int CODE_ENTER = '\n';
    private static final int CODE_SPACE = ' ';
    private static final int CODE_DELETE = -7;
    private static final int CODE_SHIFT = -11;
    private static final int CODE_ARROW_LEFT = -21;
    private static final int CODE_ARROW_RIGHT = -22;
    private static final int CODE_MULTIPLE_CODE_POINTS = -902;
    private static final int CODE_NOT_SPECIFIED = -10008;
    private static final int NOT_A_COORDINATE = -1;
    /** Key.ACTION_FLAGS_IS_REPEATABLE. */
    private static final int REPEATABLE = 0x01;

    private final Class<?> keyboardViewClass;
    private final Class<?> stripClass;
    private final Object keyPress;
    private final Object keyRepeat;

    private WeakReference<InputMethodService> ime = new WeakReference<>(null);
    /** The highlighted key, or the highlighted view in the suggestion strip; one at a time. */
    private Object focusKey;
    private WeakReference<View> focusStripView = new WeakReference<>(null);
    /** Where the highlight was, on screen: to find it again when the keys change. */
    private float focusX = -1;
    private float focusY = -1;
    /** Buttons whose down was taken: their up is taken too. */
    private final Set<Integer> taken = new HashSet<>();
    /** The key a held Cross pressed, released when Cross comes up. */
    private Object heldKey;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private HeliBoardGamepad(ClassLoader cl) {
        keyboardViewClass = XposedHelpers.findClass(CLS_KEYBOARD_VIEW, cl);
        stripClass = XposedHelpers.findClass(CLS_STRIP, cl);
        Class<?> haptic = XposedHelpers.findClass(CLS_HAPTIC, cl);
        keyPress = XposedHelpers.getStaticObjectField(haptic, "KEY_PRESS");
        keyRepeat = XposedHelpers.getStaticObjectField(haptic, "KEY_REPEAT");
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xFFFFFFFF);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x33FFFFFF);
    }

    public static void install(ClassLoader cl) {
        HeliBoardGamepad pad = new HeliBoardGamepad(cl);
        Class<?> imeClass = XposedHelpers.findClass(CLS_IME, cl);
        XposedHelpers.findAndHookMethod(imeClass, "onKeyDown", int.class, KeyEvent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
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
                        InputMethodService service = (InputMethodService) param.thisObject;
                        if (pad.onKeyUp(service, (KeyEvent) param.args[1])) {
                            param.setResult(true);
                        }
                    }
                });
        // The highlight, drawn over what HeliBoard drew.
        XposedHelpers.findAndHookMethod(pad.keyboardViewClass, "onDraw", Canvas.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        pad.drawKeyFocus((View) param.thisObject, (Canvas) param.args[0]);
                    }
                });
        XposedHelpers.findAndHookMethod(ViewGroup.class, "dispatchDraw", Canvas.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (pad.stripClass.isInstance(param.thisObject)) {
                            pad.drawStripFocus((ViewGroup) param.thisObject,
                                    (Canvas) param.args[0]);
                        }
                    }
                });
        // A new field: the highlight starts again from the middle of the keys.
        XposedBridge.hookAllMethods(imeClass, "onStartInputView", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                pad.clearFocus();
            }
        });
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
        if (!fromController(event) || !service.isInputViewShown()
                || mainKeyboard(service) == null) {
            return false;
        }
        ime = new WeakReference<>(service);
        int code = event.getKeyCode();
        int repeat = event.getRepeatCount();
        boolean handled = true;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                move(service, 0, -1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                move(service, 0, 1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                move(service, -1, 0);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                move(service, 1, 0);
                break;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                pressFocused(service, repeat);
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
                    clearFocus();
                    service.requestHideSelf(0);
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
        if ((code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_DPAD_CENTER)
                && heldKey != null) {
            releaseKey(service, heldKey);
            heldKey = null;
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------
    // Pressing
    // ---------------------------------------------------------------------------------------

    private Object listener(InputMethodService service) {
        return XposedHelpers.getObjectField(service, "mKeyboardActionListener");
    }

    /** Cross: the highlighted key, or the highlighted suggestion / toolbar key. */
    private void pressFocused(InputMethodService service, int repeat) {
        View stripView = focusStripView.get();
        if (stripView != null) {
            if (repeat == 0 && stripView.isShown()) {
                stripView.performClick();
            }
            return;
        }
        Object key = focusedKey(service);
        if (key == null) {
            return;
        }
        boolean repeats = (XposedHelpers.getIntField(key, "mActionFlags") & REPEATABLE) != 0;
        if (repeat == 0) {
            pressKey(service, key, 0);
            heldKey = key;
            if (repeats) {
                inputKey(service, key, false);
            }
        } else if (repeats && heldKey == key) {
            pressKey(service, key, repeat);
            inputKey(service, key, true);
        }
    }

    private void pressKey(InputMethodService service, Object key, int repeat) {
        if (!XposedHelpers.getBooleanField(key, "mEnabled")) {
            return;
        }
        XposedHelpers.callMethod(listener(service), "onPressKey",
                XposedHelpers.getIntField(key, "mCode"), repeat, 1,
                repeat == 0 ? keyPress : keyRepeat);
    }

    /** The key's input: what a finger lifting off it sends. */
    private void inputKey(InputMethodService service, Object key, boolean isRepeat) {
        if (!XposedHelpers.getBooleanField(key, "mEnabled")) {
            return;
        }
        int code = XposedHelpers.getIntField(key, "mCode");
        if (code == CODE_MULTIPLE_CODE_POINTS) {
            XposedHelpers.callMethod(listener(service), "onTextInput",
                    XposedHelpers.callMethod(key, "getOutputText"));
        } else if (code != CODE_NOT_SPECIFIED) {
            XposedHelpers.callMethod(listener(service), "onCodeInput", code, NOT_A_COORDINATE,
                    NOT_A_COORDINATE, isRepeat);
        }
    }

    private void releaseKey(InputMethodService service, Object key) {
        boolean repeats = (XposedHelpers.getIntField(key, "mActionFlags") & REPEATABLE) != 0;
        if (!repeats) {
            inputKey(service, key, false);
        }
        if (XposedHelpers.getBooleanField(key, "mEnabled")) {
            XposedHelpers.callMethod(listener(service), "onReleaseKey",
                    XposedHelpers.getIntField(key, "mCode"), false);
        }
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
    // Moving
    // ---------------------------------------------------------------------------------------

    /** The letters / symbols keyboard view, if it is the one showing; else null. */
    private View mainKeyboard(InputMethodService service) {
        Object switcher = XposedHelpers.getObjectField(service, "mKeyboardSwitcher");
        View main = (View) XposedHelpers.getObjectField(switcher, "mKeyboardView");
        return main != null && main.isShown() ? main : null;
    }

    /** The keys that can be highlighted on {@code view}'s keyboard. */
    private static List<Object> keys(View view) {
        List<Object> keys = new ArrayList<>();
        Object keyboard = XposedHelpers.getObjectField(view, "mKeyboard");
        if (keyboard == null) {
            return keys;
        }
        for (Object key : (List<?>) XposedHelpers.getObjectField(keyboard, "mSortedKeys")) {
            if (key.getClass().getName().endsWith("$Spacer")
                    || XposedHelpers.getIntField(key, "mWidth") <= 0
                    || XposedHelpers.getIntField(key, "mHeight") <= 0) {
                continue;
            }
            keys.add(key);
        }
        return keys;
    }

    /** The highlighted key if it is still on the keyboard shown; else the key nearest it. */
    private Object focusedKey(InputMethodService service) {
        View view = mainKeyboard(service);
        if (view == null) {
            return null;
        }
        List<Object> keys = keys(view);
        if (keys.isEmpty()) {
            return null;
        }
        if (focusKey != null && keys.contains(focusKey)) {
            return focusKey;
        }
        // The keys changed (shift, symbols, another field): the key nearest where it was, or the
        // middle of the keyboard at first.
        float x = focusX;
        float y = focusY;
        if (x < 0) {
            int[] at = new int[2];
            view.getLocationOnScreen(at);
            x = at[0] + view.getWidth() / 2f;
            y = at[1] + view.getHeight() / 2f;
        }
        Object nearest = null;
        float best = Float.MAX_VALUE;
        for (Object key : keys) {
            float dx = centerX(view, key) - x;
            float dy = centerY(view, key) - y;
            float d = dx * dx + dy * dy;
            if (d < best) {
                best = d;
                nearest = key;
            }
        }
        setFocus(view, nearest);
        return nearest;
    }

    private void move(InputMethodService service, int dx, int dy) {
        View view = mainKeyboard(service);
        View stripView = focusStripView.get();
        if (stripView != null && stripView.isShown()) {
            moveInStrip(service, view, stripView, dx, dy);
            return;
        }
        focusStripView = new WeakReference<>(null);
        boolean fresh = focusKey == null;
        Object key = focusedKey(service);
        if (key == null || fresh) {
            invalidate(service);
            return; // The first press only shows where the highlight is.
        }
        List<Object> keys = keys(view);
        float x = centerX(view, key);
        float y = centerY(view, key);
        Object next = null;
        if (dx != 0) {
            // Same row: the next key that way, wrapping round the row.
            List<Object> row = new ArrayList<>();
            for (Object k : keys) {
                if (sameRow(k, key)) {
                    row.add(k);
                }
            }
            row.sort((a, b) -> Integer.compare(XposedHelpers.getIntField(a, "mX"),
                    XposedHelpers.getIntField(b, "mX")));
            int i = row.indexOf(key);
            next = row.get(Math.floorMod(i + dx, row.size()));
        } else {
            // The nearest row that way, and in it the key nearest across.
            float rowY = Float.NaN;
            for (Object k : keys) {
                float ky = centerY(view, k);
                if ((dy < 0 ? ky < y - 1 : ky > y + 1)
                        && (Float.isNaN(rowY) || Math.abs(ky - y) < Math.abs(rowY - y))) {
                    rowY = ky;
                }
            }
            if (Float.isNaN(rowY)) {
                if (dy < 0) {
                    enterStrip(service, x);
                }
                return;
            }
            float best = Float.MAX_VALUE;
            for (Object k : keys) {
                if (Math.abs(centerY(view, k) - rowY) < 1) {
                    float d = Math.abs(centerX(view, k) - x);
                    if (d < best) {
                        best = d;
                        next = k;
                    }
                }
            }
        }
        if (next != null) {
            setFocus(view, next);
            view.invalidate();
        }
    }

    private static boolean sameRow(Object a, Object b) {
        return XposedHelpers.getIntField(a, "mY") == XposedHelpers.getIntField(b, "mY");
    }

    private void setFocus(View view, Object key) {
        focusKey = key;
        if (key != null) {
            focusX = centerX(view, key);
            focusY = centerY(view, key);
        }
    }

    private static float centerX(View view, Object key) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return at[0] + view.getPaddingLeft() + XposedHelpers.getIntField(key, "mX")
                + XposedHelpers.getIntField(key, "mWidth") / 2f;
    }

    private static float centerY(View view, Object key) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return at[1] + view.getPaddingTop() + XposedHelpers.getIntField(key, "mY")
                + XposedHelpers.getIntField(key, "mHeight") / 2f;
    }

    // ---------------------------------------------------------------------------------------
    // Suggestion strip
    // ---------------------------------------------------------------------------------------

    private ViewGroup strip(InputMethodService service) {
        ViewGroup strip = (ViewGroup) XposedHelpers.getObjectField(service, "mSuggestionStripView");
        return strip != null && strip.isShown() ? strip : null;
    }

    /** What can be clicked in the strip (suggestions, toolbar keys), left to right on screen. */
    private static List<View> stripItems(ViewGroup strip) {
        List<View> items = new ArrayList<>();
        collect(strip, items);
        items.sort((a, b) -> Float.compare(screenCenterX(a), screenCenterX(b)));
        return items;
    }

    private static void collect(ViewGroup group, List<View> out) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (!child.isShown() || child.getWidth() <= 0) {
                continue;
            }
            if (child.isClickable() && child.hasOnClickListeners()) {
                out.add(child);
            } else if (child instanceof ViewGroup) {
                collect((ViewGroup) child, out);
            }
        }
    }

    private static float screenCenterX(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return at[0] + view.getWidth() / 2f;
    }

    private void enterStrip(InputMethodService service, float x) {
        ViewGroup strip = strip(service);
        if (strip == null) {
            return;
        }
        View nearest = null;
        float best = Float.MAX_VALUE;
        for (View item : stripItems(strip)) {
            float d = Math.abs(screenCenterX(item) - x);
            if (d < best) {
                best = d;
                nearest = item;
            }
        }
        if (nearest != null) {
            focusStripView = new WeakReference<>(nearest);
            invalidate(service);
        }
    }

    private void moveInStrip(InputMethodService service, View keyboard, View current, int dx,
            int dy) {
        ViewGroup strip = strip(service);
        if (dy > 0 || strip == null) {
            // Down: back to the keys, the top-row key nearest across.
            float x = screenCenterX(current);
            focusStripView = new WeakReference<>(null);
            if (keyboard != null) {
                Object best = null;
                float topY = Float.MAX_VALUE;
                float bestD = Float.MAX_VALUE;
                List<Object> keys = keys(keyboard);
                for (Object k : keys) {
                    topY = Math.min(topY, centerY(keyboard, k));
                }
                for (Object k : keys) {
                    if (Math.abs(centerY(keyboard, k) - topY) < 1) {
                        float d = Math.abs(centerX(keyboard, k) - x);
                        if (d < bestD) {
                            bestD = d;
                            best = k;
                        }
                    }
                }
                setFocus(keyboard, best);
            }
            invalidate(service);
            return;
        }
        if (dx == 0) {
            return; // Up from the strip: nothing above it.
        }
        List<View> items = stripItems(strip);
        int i = items.indexOf(current);
        if (items.isEmpty()) {
            return;
        }
        focusStripView = new WeakReference<>(
                items.get(i < 0 ? 0 : Math.floorMod(i + dx, items.size())));
        invalidate(service);
    }

    // ---------------------------------------------------------------------------------------
    // Highlight
    // ---------------------------------------------------------------------------------------

    private void clearFocus() {
        focusKey = null;
        focusStripView = new WeakReference<>(null);
        focusX = -1;
        focusY = -1;
        heldKey = null;
        InputMethodService service = ime.get();
        if (service != null) {
            invalidate(service);
        }
    }

    private void invalidate(InputMethodService service) {
        View keyboard = mainKeyboard(service);
        if (keyboard != null) {
            keyboard.invalidate();
        }
        ViewGroup strip = strip(service);
        if (strip != null) {
            strip.invalidate();
        }
    }

    private void drawKeyFocus(View view, Canvas canvas) {
        InputMethodService service = ime.get();
        if (focusKey == null || focusStripView.get() != null || service == null
                || view != mainKeyboard(service)) {
            return;
        }
        // The keys may have changed since (shift, symbols): the key now nearest the highlight.
        Object key = focusedKey(service);
        if (key == null) {
            return;
        }
        float inset = dp(view, 2);
        float left = view.getPaddingLeft() + XposedHelpers.getIntField(key, "mX");
        float top = view.getPaddingTop() + XposedHelpers.getIntField(key, "mY");
        rect.set(left + inset, top + inset,
                left + XposedHelpers.getIntField(key, "mWidth") - inset,
                top + XposedHelpers.getIntField(key, "mHeight") - inset);
        drawRing(view, canvas);
    }

    private void drawStripFocus(ViewGroup strip, Canvas canvas) {
        View item = focusStripView.get();
        if (item == null || !item.isShown()) {
            return;
        }
        android.graphics.Rect bounds = new android.graphics.Rect(0, 0, item.getWidth(),
                item.getHeight());
        try {
            strip.offsetDescendantRectToMyCoords(item, bounds);
        } catch (IllegalArgumentException notInside) {
            return;
        }
        float inset = dp(strip, 2);
        rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset,
                bounds.bottom - inset);
        drawRing(strip, canvas);
    }

    private void drawRing(View view, Canvas canvas) {
        float radius = dp(view, 8);
        ring.setStrokeWidth(dp(view, 2));
        canvas.drawRoundRect(rect, radius, radius, fill);
        canvas.drawRoundRect(rect, radius, radius, ring);
    }

    private static float dp(View view, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                view.getResources().getDisplayMetrics());
    }
}
