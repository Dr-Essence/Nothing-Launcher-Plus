package com.dressence.nothinglauncherplus.hook;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The status bar's and quick settings' icons lit the green of the NDot clock, with its soft
 * glow: their own shapes, crisp, only the colour changed. The quick settings tiles (Android 16
 * draws them with Compose) have each icon wrapped so it draws green, its animations playing.
 */
final class GlowIcons {

    private static final int NONE = 0;
    private static final int STATUS = 1;
    private static final int TILE = 2;
    /** The glow's reach round a tile icon, in icon heights. */
    private static final float GLOW = 0.12f;

    private static final String PKG_TILES =
            "com.android.systemui.qs.panels.ui.compose.infinitegrid";
    private static final String CLS_ICON = "com.android.systemui.common.shared.model.Icon";

    /** Per view: which kind of icon it is, worked out once it is in its window. */
    private static final Map<View, Integer> KIND = new WeakHashMap<>();
    private static final Map<ImageView, Bitmap> BUFFERS = new WeakHashMap<>();

    /** Tile icons already wrapped in green, by the icon they were: each wrapped only once. */
    private static final Map<Object, Object> TILE_ICONS =
            Collections.synchronizedMap(new LinkedHashMap<Object, Object>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Object, Object> eldest) {
                    return size() > 64;
                }
            });
    private static boolean loggedTiles;

    private GlowIcons() {
    }

    static void install(ClassLoader cl, ConfigStore store) {
        XposedHelpers.findAndHookMethod(ImageView.class, "onDraw", Canvas.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        ImageView view = (ImageView) param.thisObject;
                        int kind = kind(view);
                        if (kind == NONE) {
                            return;
                        }
                        boolean on = kind == STATUS ? store.get().ndotStatusBar
                                : store.get().ndotQuickSettings;
                        if (on && draw(view, (Canvas) param.args[0], kind)) {
                            param.setResult(null);
                        }
                    }
                });
        // The status bar's small texts (network speed and the like) in green NDot too.
        XposedHelpers.findAndHookMethod(TextView.class, "onDraw", Canvas.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        TextView view = (TextView) param.thisObject;
                        if (kind(view) == STATUS && store.get().ndotStatusBar
                                && !StatusBar.styled(view)) {
                            StatusBar.styleText(view, true);
                        }
                    }
                });
        Hooks.install("green quick settings tiles", () -> hookComposeTiles(cl, store));
    }

    // ---- Quick settings tiles drawn with Compose (Android 16) ----

    /**
     * Every tile icon goes through {@code SmallTileContent(iconProvider, ...)}: its provider is
     * wrapped so the icon it gives is the same icon in green. And the icon that tiles get from
     * their state ({@code getTileIcon}), where it is still there to hook.
     */
    private static void hookComposeTiles(ClassLoader cl, ConfigStore store) {
        Class<?> function1 = XposedHelpers.findClass("kotlin.jvm.functions.Function1", cl);
        Class<?> common = XposedHelpers.findClass(PKG_TILES + ".CommonTileKt", cl);
        int hooked = 0;
        for (Method m : common.getDeclaredMethods()) {
            if (!m.getName().startsWith("SmallTileContent")) {
                continue;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    for (int i = 0; i < param.args.length; i++) {
                        Object arg = param.args[i];
                        if (function1.isInstance(arg) && !Proxy.isProxyClass(arg.getClass())) {
                            param.args[i] = greenProvider(cl, function1, arg, store);
                        }
                    }
                }
            });
            hooked++;
        }
        Hooks.log("green quick settings: " + hooked + " tile icon hooks");
        try {
            Class<?> tile = XposedHelpers.findClass(PKG_TILES + ".TileKt", cl);
            XposedBridge.hookAllMethods(tile, "getTileIcon", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (store.get().ndotQuickSettings && param.args[0] instanceof Context) {
                        param.setResult(inGreen((Context) param.args[0], param.getResult(), cl));
                    }
                }
            });
        } catch (Throwable t) {
            // Folded into its callers on this build: the provider hook covers it.
        }
    }

    /** {@code provider} (a {@code Context.() -> Icon}) giving its icon in green. */
    private static Object greenProvider(ClassLoader cl, Class<?> function1, Object provider,
            ConfigStore store) {
        return Proxy.newProxyInstance(cl, new Class<?>[] {function1}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "invoke":
                    Object icon = method.invoke(provider, args);
                    if (store.get().ndotQuickSettings && args != null && args.length == 1
                            && args[0] instanceof Context) {
                        return inGreen((Context) args[0], icon, cl);
                    }
                    return icon;
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "NDot " + provider;
                default:
                    return method.invoke(provider, args);
            }
        });
    }

    /**
     * A tile's {@code Icon} (a resource or a loaded drawable) as a loaded icon whose drawable is
     * that icon in green. The same icon always gives the same drawable, so Compose keeps it and its
     * animation rather than starting it again on every redraw.
     */
    private static Object inGreen(Context context, Object icon, ClassLoader cl) {
        if (icon == null) {
            return null;
        }
        Object done = TILE_ICONS.get(icon);
        if (done != null) {
            return done;
        }
        try {
            String name = icon.getClass().getName();
            Drawable drawable;
            if (name.equals(CLS_ICON + "$Resource")) {
                drawable = context.getDrawable((Integer) XposedHelpers.callMethod(icon, "getRes"));
            } else if (name.equals(CLS_ICON + "$Loaded")) {
                drawable = (Drawable) XposedHelpers.callMethod(icon, "getDrawable");
                if (drawable instanceof GlowDrawable) {
                    return icon;
                }
            } else {
                return icon;
            }
            if (drawable == null) {
                return icon;
            }
            Object description = XposedHelpers.callMethod(icon, "getContentDescription");
            Object green = loaded(cl, GlowDrawable.wrap(drawable), description);
            TILE_ICONS.put(icon, green);
            if (!loggedTiles) {
                loggedTiles = true;
                Hooks.log("green quick settings: tile icons drawn green");
            }
            return green;
        } catch (Throwable t) {
            Hooks.log("green quick settings: " + t);
            return icon;
        }
    }

    /** A new {@code Icon.Loaded(drawable, description)}, whatever else its constructor takes. */
    private static Object loaded(ClassLoader cl, Drawable drawable, Object description)
            throws ReflectiveOperationException {
        Class<?> loaded = XposedHelpers.findClass(CLS_ICON + "$Loaded", cl);
        Constructor<?> best = null;
        for (Constructor<?> c : loaded.getDeclaredConstructors()) {
            Class<?>[] types = c.getParameterTypes();
            if (c.isSynthetic() || types.length < 1 || !types[0].isAssignableFrom(Drawable.class)) {
                continue;
            }
            if (best == null || types.length < best.getParameterTypes().length) {
                best = c;
            }
        }
        if (best == null) {
            throw new NoSuchMethodException("Icon.Loaded(Drawable, ...)");
        }
        Class<?>[] types = best.getParameterTypes();
        Object[] args = new Object[types.length];
        args[0] = drawable;
        for (int i = 1; i < types.length; i++) {
            if (i == 1 && (description == null || types[1].isInstance(description))) {
                args[i] = description;
            } else if (types[i] == int.class) {
                args[i] = 0;
            } else if (types[i] == boolean.class) {
                args[i] = false;
            }
        }
        best.setAccessible(true);
        return best.newInstance(args);
    }

    // ---- Icons drawn with views: the status bar, and tiles on older releases ----

    /** What {@code view} is: a status bar icon, a quick settings tile icon, or neither. */
    private static int kind(View view) {
        Integer known = KIND.get(view);
        if (known != null) {
            return known;
        }
        if (!view.isAttachedToWindow()) {
            return NONE;
        }
        int kind = NONE;
        int depth = 0;
        for (ViewParent p = view.getParent(); p instanceof View && depth < 14;
                p = p.getParent(), depth++) {
            String name = p.getClass().getSimpleName();
            if (name.contains("Battery")) {
                break; // Drawn green by its own filter.
            }
            if (name.contains("QSTile") || name.contains("QSIconView")
                    || name.contains("TileView")) {
                kind = TILE;
                break;
            }
            if (name.contains("StatusBar") || name.contains("StatusIcon")
                    || name.contains("SystemIcons") || name.contains("ShadeHeader")
                    || name.contains("QuickStatusBarHeader") || name.contains("NotificationIcon")) {
                kind = STATUS;
                break;
            }
        }
        KIND.put(view, kind);
        return kind;
    }

    /** Draws {@code view}'s icon green and glowing; false to let it draw itself. */
    private static boolean draw(ImageView view, Canvas canvas, int kind) {
        Drawable d = view.getDrawable();
        int w = view.getWidth();
        int h = view.getHeight();
        if (d == null || w <= 0 || h <= 0 || w > 512 || h > 512) {
            return false;
        }
        Bitmap icon = BUFFERS.get(view);
        if (icon == null || icon.getWidth() != w || icon.getHeight() != h) {
            icon = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            BUFFERS.put(view, icon);
        }
        icon.eraseColor(Color.TRANSPARENT);
        // The icon exactly as the view would draw it: padding, scaling and state included.
        Canvas c = new Canvas(icon);
        c.translate(view.getPaddingLeft(), view.getPaddingTop());
        Matrix m = view.getImageMatrix();
        if (m != null) {
            c.concat(m);
        }
        d.draw(c);
        float radius = kind == STATUS && StatusBar.glowRadius > 0 ? StatusBar.glowRadius
                : h * GLOW;
        GreenGlow.draw(canvas, icon, radius, view.getImageAlpha() / 255f);
        letGlowShow(view);
        return true;
    }

    /** The glow reaches a little past the icon: let it show rather than be cut off. */
    private static void letGlowShow(View view) {
        ViewParent p = view.getParent();
        for (int i = 0; i < 2 && p instanceof ViewGroup; i++, p = p.getParent()) {
            ViewGroup g = (ViewGroup) p;
            if (g.getClipChildren()) {
                g.setClipChildren(false);
            }
        }
    }
}
