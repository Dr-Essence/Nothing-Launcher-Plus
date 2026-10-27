package com.dressence.nothinglauncherplus.systemui;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * The look of a lit LED display, shared by everything the module recolours in System UI: the
 * green of the drawer's folder name panels with a soft glow round it, for text (a text view
 * set in it) and for icons (an icon's image drawn in it).
 */
final class Led {

    /** The green of the folder name panels. */
    static final int GREEN = 0xFF4CFF7A;
    /** The glow round lit green: the same green, deeper and soft. */
    private static final int GREEN_GLOW = 0xAA2BE86A;
    /** A text's glow reach, in its text size. */
    private static final float TEXT_GLOW = 0.28f;

    /** Text views set in the look, and the colour each is kept in. */
    private static final Map<TextView, Integer> TEXTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** The clock's glow reach in pixels, once it is known: the icons beside it glow as far. */
    static volatile float clockGlow;

    private static final Paint ICON = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static final Paint HALO = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static final Paint BLUR = new Paint();
    private static final int[] OFFSET = new int[2];
    private static float blurRadius;
    private static int iconColour;

    private Led() {
    }

    /** Keeps lit texts lit: any colour System UI later gives them (light or dark) is replaced. */
    static void install() {
        XposedHelpers.findAndHookMethod(TextView.class, "setTextColor", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Integer colour = TEXTS.get(param.thisObject);
                        if (colour != null) {
                            param.args[0] = colour;
                        }
                    }
                });
    }

    /** True once {@code view} is set in the look. */
    static boolean isLit(TextView view) {
        return TEXTS.containsKey(view);
    }

    /**
     * {@code view} lit {@code colour} with the glow, in {@code face} (null: its own face).
     * Each property is set only if it differs, so this is cheap to repeat.
     */
    static void lightText(TextView view, int colour, Typeface face) {
        if (face != null && view.getTypeface() != face) {
            view.setTypeface(face);
        }
        TEXTS.put(view, colour);
        if (view.getCurrentTextColor() != colour) {
            view.setTextColor(colour);
        }
        float glow = view.getTextSize() * TEXT_GLOW;
        int glowColour = colour == GREEN ? GREEN_GLOW : 0xAAFFFFFF;
        if (view.getShadowRadius() != glow || view.getShadowColor() != glowColour) {
            view.setShadowLayer(glow, 0, 0, glowColour);
            // The glow reaches past the text: let it show rather than be cut at the edges.
            if (view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).setClipChildren(false);
            }
        }
    }

    /**
     * Draws {@code icon} at the canvas origin lit {@code colour}, glowing {@code radius} round
     * it; a {@code strong} glow (on the dark quick settings tiles) twice as bright. Main thread.
     */
    static void drawIcon(Canvas canvas, Bitmap icon, float radius, float alpha, boolean strong,
            int colour) {
        if (colour != iconColour) {
            tint(colour);
        }
        int a = Math.round(255 * alpha);
        if (radius >= 1) {
            if (blurRadius != radius) {
                blurRadius = radius;
                BLUR.setMaskFilter(new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL));
            }
            Bitmap glow = icon.extractAlpha(BLUR, OFFSET);
            HALO.setAlpha(a);
            canvas.drawBitmap(glow, OFFSET[0], OFFSET[1], HALO);
            if (strong) {
                canvas.drawBitmap(glow, OFFSET[0], OFFSET[1], HALO);
            }
            glow.recycle();
        }
        ICON.setAlpha(a);
        canvas.drawBitmap(icon, 0, 0, ICON);
    }

    /** The icon paints set to light {@code c}, its glow a little deeper and softer. */
    private static void tint(int c) {
        iconColour = c;
        ICON.setColorFilter(new PorterDuffColorFilter(c, PorterDuff.Mode.SRC_IN));
        int glow = c == GREEN ? GREEN_GLOW : Color.argb(0xAA, Color.red(c) * 85 / 100,
                Color.green(c) * 85 / 100, Color.blue(c) * 85 / 100);
        HALO.setColorFilter(new PorterDuffColorFilter(glow, PorterDuff.Mode.SRC_IN));
    }
}
