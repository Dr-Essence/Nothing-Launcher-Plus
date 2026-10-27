package com.dressence.nothinglauncherplus.systemui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Status bar icons (4G, Wi-Fi, airplane mode, headphones...) and quick settings tile icons lit
 * green with the clock's glow: each drawn as it would be (state, level, animation and all),
 * then shown in the green, its own shape untouched.
 */
final class GreenIcons {

    /** A tile icon's glow reach, in its height. */
    private static final float TILE_GLOW = 0.12f;
    /** Larger icons are not icons (a media artwork, a wallpaper preview): left alone. */
    private static final int MAX_SIZE = 512;

    /** Per icon view: the image it is drawn into before being lit, and its canvas. */
    private static final Map<ImageView, Frame> FRAMES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final class Frame {
        final Bitmap bitmap;
        final Canvas canvas;

        Frame(int w, int h) {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            canvas = new Canvas(bitmap);
        }
    }

    private GreenIcons() {
    }

    static void install(ConfigStore store) {
        XposedHelpers.findAndHookMethod(ImageView.class, "onDraw", Canvas.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        ImageView view = (ImageView) param.thisObject;
                        if (NetworkSpeed.contains(view)) {
                            NetworkSpeed.light(view, store.get().ndotStatusBar);
                            return;
                        }
                        int area = Areas.of(view);
                        boolean on = area == Areas.STATUS_BAR ? store.get().ndotStatusBar
                                : area == Areas.TILE && store.get().ndotQuickSettings;
                        if (on && draw(view, (Canvas) param.args[0], area, store)) {
                            param.setResult(null);
                        }
                    }
                });
    }

    /** Draws {@code view}'s icon lit; false to let it draw itself. */
    private static boolean draw(ImageView view, Canvas canvas, int area, ConfigStore store) {
        Drawable d = view.getDrawable();
        int w = view.getWidth();
        int h = view.getHeight();
        if (d == null || w <= 0 || h <= 0 || w > MAX_SIZE || h > MAX_SIZE) {
            return false;
        }
        Frame frame = FRAMES.get(view);
        if (frame == null || frame.bitmap.getWidth() != w || frame.bitmap.getHeight() != h) {
            frame = new Frame(w, h);
            FRAMES.put(view, frame);
        }
        // The icon exactly as the view would draw it: padding, scaling and state included.
        frame.bitmap.eraseColor(Color.TRANSPARENT);
        int save = frame.canvas.save();
        frame.canvas.translate(view.getPaddingLeft(), view.getPaddingTop());
        Matrix m = view.getImageMatrix();
        if (m != null) {
            frame.canvas.concat(m);
        }
        d.draw(frame.canvas);
        frame.canvas.restoreToCount(save);

        float alpha = view.getImageAlpha() / 255f;
        if (area == Areas.TILE) {
            QuickSettings.onTileIconDrawn(view, store);
            int colour = QuickSettings.iconColour(view, store);
            Led.drawIcon(canvas, frame.bitmap, h * TILE_GLOW, alpha, true, colour);
        } else {
            float radius = Led.clockGlow > 0 ? Led.clockGlow : h * TILE_GLOW;
            Led.drawIcon(canvas, frame.bitmap, radius, alpha, false, Led.GREEN);
        }
        letGlowShow(view);
        return true;
    }

    /** The glow reaches a little past the icon: let it show rather than be cut off. */
    private static void letGlowShow(ImageView view) {
        ViewParent p = view.getParent();
        for (int i = 0; i < 2 && p instanceof ViewGroup; i++, p = p.getParent()) {
            ViewGroup g = (ViewGroup) p;
            if (g.getClipChildren()) {
                g.setClipChildren(false);
            }
        }
    }
}
