package com.dressence.nothinglauncherplus.systemui;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableContainer;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.widget.ImageView;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Quick settings tiles as a retro display: a tile that is on lit deep retro green (or the
 * amber of a retro car dashboard) where it was white, its icon and label green (or amber),
 * and the icons' own animations (airplane mode, rotation, the torch) still playing.
 */
final class QuickSettings {

    private static final String CLS_TILE_VIEW = "com.android.systemui.qs.tileimpl.QSTileViewImpl";
    private static final int STATE_ACTIVE = 2; // Tile.STATE_ACTIVE

    /** The background of a tile that is on: the deep green of a retro display. */
    static final int RETRO_GREEN = 0xFF1C5230;
    /** Or the warm amber of a retro car dashboard, with its icon a bright amber. */
    static final int DASH_AMBER = 0xFF6B4500;
    private static final int DASH_ICON = 0xFFFFC233;

    /**
     * True once a tile has taken its colour from the hooked source: backgrounds are then left
     * as the tiles draw them. Until then (and where the source is not there) the backgrounds
     * are recoloured as they draw (TileBackground).
     */
    static volatile boolean litAtSource;

    private static Method forceAnimationOnUi;

    private QuickSettings() {
    }

    static void install(ClassLoader cl, ConfigStore store) {
        Hooks.install("tile colours", () -> hookTileColours(cl, store));
        Hooks.install("tile animations", () -> hookAnimations(store));
    }

    /** The colour a tile view gives its background for each state: the source of the white. */
    private static void hookTileColours(ClassLoader cl, ConfigStore store) {
        int hooked = XposedBridge.hookAllMethods(XposedHelpers.findClass(CLS_TILE_VIEW, cl),
                "getBackgroundColorForState", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!store.get().ndotQuickSettings || param.args.length == 0
                                || !Integer.valueOf(STATE_ACTIVE).equals(param.args[0])) {
                            return;
                        }
                        param.setResult(store.get().qsAmber ? DASH_AMBER : RETRO_GREEN);
                        if (!litAtSource) {
                            litAtSource = true;
                            Hooks.log("quick settings: tiles lit at the source");
                        }
                    }
                }).size();
        Hooks.log("quick settings: " + hooked + " tile colour hooks");
    }

    /**
     * Animated vector icons run on the render thread when the view draws them itself; drawn
     * into an image to be lit they would never move. They are set to animate on the main
     * thread, frame by frame through the lit drawing.
     */
    private static void hookAnimations(ConfigStore store) throws NoSuchMethodException {
        forceAnimationOnUi = AnimatedVectorDrawable.class.getMethod("forceAnimationOnUI");
        XposedBridge.hookAllMethods(ImageView.class, "setImageDrawable", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length == 1 && param.args[0] instanceof Drawable
                        && (store.get().ndotQuickSettings || store.get().ndotStatusBar)) {
                    animateOnUi((Drawable) param.args[0], 0);
                }
            }
        });
    }

    private static void animateOnUi(Drawable d, int depth) {
        if (d == null || depth > 4) {
            return;
        }
        if (d instanceof AnimatedVectorDrawable) {
            try {
                forceAnimationOnUi.invoke(d);
            } catch (ReflectiveOperationException e) {
                // Left on the render thread: the icon shows its end state.
            }
        } else if (d instanceof DrawableWrapper) {
            animateOnUi(((DrawableWrapper) d).getDrawable(), depth + 1);
        } else if (d instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) d;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                animateOnUi(layers.getDrawable(i), depth + 1);
            }
        } else if (d instanceof DrawableContainer) {
            animateOnUi(d.getCurrent(), depth + 1);
        }
    }

    /** Called as a tile's icon is drawn lit: recolours the tile's backgrounds if needed. */
    static void onTileIconDrawn(ImageView icon, ConfigStore store) {
        if (litAtSource) {
            return;
        }
        // From the icon up to the tile view itself, whichever draws the tile's circle or pill.
        View v = icon;
        for (int i = 0; i < 6 && v != null; i++) {
            Drawable bg = v.getBackground();
            if (bg != null && !(bg instanceof TileBackground)) {
                v.setBackground(new TileBackground(bg, store));
                // This frame drew the old background already: draw again with the new one.
                v.post(v::invalidate);
            }
            if (v.getClass().getSimpleName().contains("TileView")) {
                return;
            }
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
    }

    /**
     * What a tile's icon is lit: amber on a tile that is on, in the amber style (the tile
     * tints such an icon dark, for its white), green otherwise.
     */
    static int iconColour(ImageView icon, ConfigStore store) {
        if (!store.get().qsAmber) {
            return Led.GREEN;
        }
        ColorStateList tint = icon.getImageTintList();
        if (tint == null) {
            return Led.GREEN;
        }
        int c = tint.getDefaultColor();
        int luma = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
        return luma < 110 ? DASH_ICON : Led.GREEN;
    }
}
