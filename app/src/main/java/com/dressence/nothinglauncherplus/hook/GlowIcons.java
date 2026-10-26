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
    /** Texts smaller than this (in sp) are too small for NDot's dots. */
    private static final float SMALL_TEXT_SP = 10;
    /** On tiles that are on, the background lit the deep green of a retro display. */
    private static final int RETRO_GREEN = 0xFF1C5230;
    /** Or the warm amber of a retro car dashboard, its icon a bright glowing amber. */
    private static final int DASH_AMBER = 0xFF6B4500;
    private static final int DASH_ICON = 0xFFFFC233;
    /** The glow round tile labels: as round the clock's. */
    private static final int LABEL_GLOW = 0xAA2BE86A;
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
        // The status bar's other texts (the date over quick settings, network speed) in glowing
        // green too: in NDot, but the tiny ones (network speed's two stacked lines) in a plain
        // face, as NDot's dots at that size run together.
        XposedHelpers.findAndHookMethod(TextView.class, "onDraw", Canvas.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        TextView view = (TextView) param.thisObject;
                        if (StatusBar.keepSmall(view)) {
                            return;
                        }
                        if (kind(view) == STATUS && store.get().ndotStatusBar
                                && !StatusBar.styled(view)) {
                            float sp = view.getTextSize()
                                    / view.getResources().getDisplayMetrics().scaledDensity;
                            if (sp < SMALL_TEXT_SP
                                    || speedView(view)) {
                                StatusBar.styleSmallText(view);
                            } else {
                                StatusBar.styleText(view, true);
                            }
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
            if (!m.getName().startsWith("SmallTileContent") || !composable(m)) {
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
        Hooks.install("green quick settings colours", () -> hookTileColours(cl, store));
        Hooks.install("green quick settings accent", () -> hookAccent(cl, store));
        Hooks.install("glowing quick settings labels", () -> hookTileLabels(cl, store, common));
    }

    /** True for a composable function itself, not a lambda named after it: it takes a Composer. */
    private static boolean composable(Method m) {
        if (m.getName().contains("$")) {
            return false;
        }
        for (Class<?> t : m.getParameterTypes()) {
            if (t.getName().equals("androidx.compose.runtime.Composer")) {
                return true;
            }
        }
        return false;
    }

    /** {@code o}'s long field {@code name}; 0 if it has none. */
    private static long getLong(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getLong(o);
            } catch (ReflectiveOperationException e) {
                // Not declared here: look in the superclass.
            }
        }
        return 0;
    }

    /** Sets {@code o}'s long field {@code name}, final or not. */
    private static void setLong(Object o, String name, long value) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.setLong(o, value);
                return;
            } catch (ReflectiveOperationException e) {
                // Not declared here: look in the superclass.
            }
        }
    }

    /** A Compose {@code Color} (its packed value) from an ARGB colour. */
    private static long composeColour(int argb) {
        return (argb & 0xFFFFFFFFL) << 32;
    }

    /**
     * The tiles' colours: labels green, and a tile that is on lit retro-display green (or
     * dashboard amber, its icon amber too) where it was white (the whole tile, or the icon's
     * part of a tile with two targets). Set as each
     * {@code TileColors} is made: the functions choosing them are inlined into their callers.
     */
    private static void hookTileColours(ClassLoader cl, ConfigStore store) {
        Class<?> colours = XposedHelpers.findClass(PKG_TILES + ".TileColors", cl);
        XposedBridge.hookAllConstructors(colours, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!store.get().ndotQuickSettings) {
                    return;
                }
                Object c = param.thisObject;
                boolean amber = store.get().qsAmber;
                boolean on = false;
                for (String field : new String[] {"background", "iconBackground"}) {
                    if (light(getLong(c, field))) {
                        setLong(c, field, composeColour(amber ? DASH_AMBER : RETRO_GREEN));
                        on = true;
                    }
                }
                // The icon's tint: what the icon is lit (see GlowDrawable).
                setLong(c, "icon", composeColour(on && amber ? DASH_ICON : GreenGlow.GREEN));
                setLong(c, "label", composeColour(GreenGlow.GREEN));
                setLong(c, "secondaryLabel", composeColour(0xB34CFF7A));
            }
        });
    }

    /**
     * The accent the shade's Compose theme paints with ({@code ColorScheme.primary}: the white
     * of a tile that is on, the brightness slider, the sound mode selector) becomes retro
     * green, or dashboard amber; what is drawn on it ({@code onPrimary}: a tile's icon) the
     * bright green or amber the icon is then lit. The theme is built when the shade is, so
     * System UI restarts when the option changes.
     */
    private static void hookAccent(ClassLoader cl, ConfigStore store) {
        Class<?> scheme = XposedHelpers.findClass("androidx.compose.material3.ColorScheme", cl);
        XposedBridge.hookAllConstructors(scheme, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                store.reloadIfChanged();
                if (!store.get().ndotQuickSettings || param.args.length < 2
                        || !(param.args[0] instanceof Long) || !(param.args[1] instanceof Long)) {
                    return;
                }
                boolean amber = store.get().qsAmber;
                param.args[0] = composeColour(amber ? DASH_AMBER : RETRO_GREEN);
                param.args[1] = composeColour(amber ? DASH_ICON : GreenGlow.GREEN);
                if (!loggedAccent) {
                    loggedAccent = true;
                    Hooks.log("green quick settings: accent " + (amber ? "amber" : "green"));
                }
            }
        });
    }

    private static boolean loggedAccent;
    private static boolean loggedLabels;

    /** True for a light, mostly opaque Compose colour: the white of a tile that is on. */
    private static boolean light(long colour) {
        if ((colour & 0x3F) != 0) {
            return false; // Not sRGB: not the theme's white.
        }
        int argb = (int) (colour >>> 32);
        int luma = (Color.red(argb) * 299 + Color.green(argb) * 587 + Color.blue(argb) * 114)
                / 1000;
        return Color.alpha(argb) > 128 && luma > 150;
    }

    /** Tile labels glowing as the clock does: their text style given the same soft shadow. */
    private static void hookTileLabels(ClassLoader cl, ConfigStore store, Class<?> common) {
        Class<?> textStyle = XposedHelpers.findClass("androidx.compose.ui.text.TextStyle", cl);
        Map<Object, Object> glowing = Collections.synchronizedMap(new WeakHashMap<>());
        Class<?> producer;
        try {
            producer = Class.forName("androidx.compose.ui.graphics.ColorProducer", false, cl);
        } catch (ClassNotFoundException e) {
            producer = null; // Labels keep their colour; they still glow.
        }
        Class<?> colourProducer = producer;
        int hooked = 0;
        for (Method m : common.getDeclaredMethods()) {
            if (!m.getName().startsWith("TileLabel") || !composable(m)) {
                continue;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!store.get().ndotQuickSettings) {
                        return;
                    }
                    if (!loggedLabels) {
                        loggedLabels = true;
                        Hooks.log("green quick settings: labels drawn green");
                    }
                    for (int i = 0; i < param.args.length; i++) {
                        Object style = param.args[i];
                        if (colourProducer != null && colourProducer.isInstance(style)
                                && !Proxy.isProxyClass(style.getClass())) {
                            param.args[i] = greenColour(cl, colourProducer, style);
                            continue;
                        }
                        if (!textStyle.isInstance(style)) {
                            continue;
                        }
                        Object glow = glowing.get(style);
                        if (glow == null) {
                            glow = withGlow(cl, style);
                            glowing.put(style, glow);
                        }
                        param.args[i] = glow;
                    }
                }
            });
            hooked++;
        }
        Hooks.log("green quick settings: " + hooked + " label hooks");
    }

    /**
     * A label's {@code ColorProducer} giving green: its one method (named
     * {@code invoke-0d7_KjU} or so, returning the colour as a long) answers the green.
     */
    private static Object greenColour(ClassLoader cl, Class<?> producer, Object original) {
        long green = composeColour(GreenGlow.GREEN);
        return Proxy.newProxyInstance(cl, new Class<?>[] {producer}, (proxy, method, args) -> {
            if (method.getReturnType() == long.class) {
                return green;
            }
            switch (method.getName()) {
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "green " + original;
                default:
                    return method.invoke(original, args);
            }
        });
    }

    /**
     * {@code style} with a soft green shadow: a copy of it, and of its span style, whose shadow
     * is set (Compose builds both only through constructors with mangled names, so the copies
     * are made field by field).
     */
    private static Object withGlow(ClassLoader cl, Object style) {
        try {
            Object span = XposedHelpers.getObjectField(style, "spanStyle");
            Class<?> shadowClass = XposedHelpers.findClass("androidx.compose.ui.graphics.Shadow",
                    cl);
            Constructor<?> make = null;
            for (Constructor<?> c : shadowClass.getDeclaredConstructors()) {
                Class<?>[] t = c.getParameterTypes();
                if (t.length >= 3 && t[0] == long.class && t[1] == long.class
                        && t[2] == float.class) {
                    make = c;
                    break;
                }
            }
            if (make == null) {
                return style;
            }
            Object[] args = new Object[make.getParameterTypes().length];
            args[0] = composeColour(LABEL_GLOW);
            args[1] = 0L; // No offset: a glow all round.
            args[2] = 4 * android.content.res.Resources.getSystem().getDisplayMetrics().density;
            make.setAccessible(true);
            Object shadow = make.newInstance(args);
            Object spanCopy = shallowCopy(span);
            XposedHelpers.setObjectField(spanCopy, "shadow", shadow);
            Object styleCopy = shallowCopy(style);
            XposedHelpers.setObjectField(styleCopy, "spanStyle", spanCopy);
            return styleCopy;
        } catch (Throwable t) {
            Hooks.log("green quick settings: label glow: " + t);
            return style;
        }
    }

    /** A new object of {@code o}'s class with all its fields the same. */
    private static Object shallowCopy(Object o) throws ReflectiveOperationException {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field theUnsafe;
        try {
            theUnsafe = unsafeClass.getDeclaredField("THE_ONE");
        } catch (NoSuchFieldException e) {
            theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        }
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Object copy = unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, o.getClass());
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                f.set(copy, f.get(o));
            }
        }
        return copy;
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

    /** The network speed indicator, whatever the release calls it. */
    private static boolean speedView(View view) {
        String name = view.getClass().getName();
        return name.contains("Speed") || name.contains("Traffic");
    }

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
        String own = view.getClass().getSimpleName();
        if (view instanceof TextView && (own.contains("DateView") || speedView(view))) {
            // The date over the pulled-down quick settings, and the network speed, wherever
            // they are: green like the clock.
            KIND.put(view, STATUS);
            return STATUS;
        }
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
        GreenGlow.draw(canvas, icon, radius, view.getImageAlpha() / 255f, kind == TILE);
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
