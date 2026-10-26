package com.dressence.nothinglauncherplus.hook;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Nothing's network speed indicator ({@code NetworkSpeedView}: two tiny stacked lines) kept
 * clean: it reloads its face as the shade opens, and with the System font option that face
 * is NDot, whose dots at that size run together and whose taller lines cut the second line
 * off. Every time it draws, each of its texts (text views, or paints it draws with itself)
 * is put back in a plain face and lit the clock's green.
 */
final class NetworkSpeed {

    private static Typeface plain;
    private static boolean dumped;

    private NetworkSpeed() {
    }

    static void install(ConfigStore store) {
        XposedHelpers.findAndHookMethod(ViewGroup.class, "dispatchDraw", Canvas.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View view = (View) param.thisObject;
                        if (!is(view) || !store.get().ndotStatusBar) {
                            return;
                        }
                        if (!dumped) {
                            dumped = true;
                            Hooks.log("network speed: " + dump(view, ""));
                        }
                        keepClean(view);
                    }
                });
    }

    /** True for the indicator itself (not its parts). */
    static boolean is(View view) {
        return view.getClass().getName().contains("NetworkSpeed");
    }

    /** True for a view inside the indicator. */
    static boolean inside(View view) {
        Object p = view.getParent();
        for (int i = 0; i < 4 && p instanceof View; i++, p = ((View) p).getParent()) {
            if (is((View) p)) {
                return true;
            }
        }
        return false;
    }

    private static Typeface plain() {
        if (plain == null) {
            try {
                plain = Typeface.create(
                        Typeface.createFromFile("/system/fonts/Roboto-Regular.ttf"), 500, false);
            } catch (RuntimeException e) {
                plain = Typeface.MONOSPACE;
            }
        }
        return plain;
    }

    private static void keepClean(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.getTypeface() != plain()) {
                text.setTypeface(plain());
            }
            if (text.getCurrentTextColor() != GreenGlow.GREEN) {
                text.setTextColor(GreenGlow.GREEN);
            }
            if (text.getShadowColor() == 0) {
                text.setShadowLayer(text.getTextSize() * 0.2f, 0, 0, 0x882BE86A);
            }
            return;
        }
        paints(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                keepClean(group.getChildAt(i));
            }
        }
    }

    /** Paints a custom view draws its text with: plain and green too. */
    private static void paints(View view) {
        for (Class<?> c = view.getClass(); c != null && c != View.class && c != ViewGroup.class
                && !c.getName().startsWith("android."); c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())
                        || !Paint.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Paint paint = (Paint) f.get(view);
                    if (paint != null && paint.getTypeface() != plain()) {
                        paint.setTypeface(plain());
                        paint.setColor(GreenGlow.GREEN);
                    }
                } catch (IllegalAccessException e) {
                    // Not readable: leave it.
                }
            }
        }
    }

    /** The indicator's views, their faces and sizes: logged once, to see what it is made of. */
    private static String dump(View view, String indent) {
        StringBuilder out = new StringBuilder("\n").append(indent)
                .append(view.getClass().getName());
        if (view instanceof TextView) {
            TextView t = (TextView) view;
            out.append(" text=\"").append(t.getText()).append("\" size=")
                    .append(t.getTextSize()).append(" lines=").append(t.getLineCount());
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                out.append(dump(g.getChildAt(i), indent + "  "));
            }
        }
        return out.toString();
    }
}
