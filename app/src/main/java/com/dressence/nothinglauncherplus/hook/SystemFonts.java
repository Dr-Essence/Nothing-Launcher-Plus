package com.dressence.nothinglauncherplus.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.os.Handler;
import android.os.Looper;

import com.dressence.nothinglauncherplus.Config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The chosen system font, applied the way Android applies its own: in system_server, where
 * the font manager writes the one font map every app receives when it starts. Apps are given
 * a copy of that map with the sans-serif families set in the chosen font; system_server's own
 * map, which it draws with itself, is never touched. When the choice changes the map is
 * written again at once, so every app opened from then on draws in it, without a reboot.
 *
 * <p>The font is read into memory this class owns and checked to be a font before use. And
 * because a font that breaks system_server breaks the whole phone at every start, a guard
 * file is kept while a font is newly in use: if system_server dies before it is taken away,
 * the next start leaves the font off (until a font is chosen again) instead of crashing again.
 */
final class SystemFonts {

    private static final String CLS_SERVICE =
            "com.android.server.graphics.fonts.FontManagerService";
    private static final String WIDGETS_PKG = "com.nothing.hearthstone";
    /** Present from a font coming into use until system_server has run with it a while. */
    private static final File GUARD = new File("/data/system/nlplus_font_guard");
    /** The font system_server died with, written by the guard: left off while it is chosen. */
    private static final File FAILED = new File("/data/system/nlplus_font_failed");
    /** How long system_server must run with a new font before it counts as safe. */
    private static final long SETTLE_MS = 90_000;

    private static Object service;
    private static Context context;

    /** The font in use and everything it is drawn from, kept for as long as it is used. */
    private static String loadedSpec;
    private static Typeface loaded;
    private static ByteBuffer loadedBytes;

    private SystemFonts() {
    }

    static void install(ClassLoader cl, ConfigStore store) {
        if (GUARD.exists()) {
            // The last system_server died while a font was newly in use: that font stays off.
            String spec = readText(GUARD);
            writeText(FAILED, spec);
            GUARD.delete();
            Hooks.log("system font " + spec + " was in use when the system last crashed:"
                    + " left off until a font is chosen again");
        }
        XposedBridge.hookAllConstructors(XposedHelpers.findClass(CLS_SERVICE, cl),
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        service = param.thisObject;
                        for (Object arg : param.args) {
                            if (arg instanceof Context) {
                                context = (Context) arg;
                            }
                        }
                        listen(store);
                    }
                });
        XposedHelpers.findAndHookMethod(Typeface.class, "serializeFontMap", Map.class,
                new XC_MethodHook() {
                    @Override
                    @SuppressWarnings("unchecked")
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Map<String, Typeface> chosen =
                                withFont((Map<String, Typeface>) param.args[0], store);
                        if (chosen != null) {
                            param.args[0] = chosen;
                        }
                    }
                });
    }

    /** A copy of {@code map} with its text families in the chosen font; null to leave it. */
    private static Map<String, Typeface> withFont(Map<String, Typeface> map, ConfigStore store) {
        store.reloadIfChanged();
        String spec = store.get().systemFont;
        if (spec == null || spec.isEmpty() || spec.equals(readText(FAILED))) {
            return null;
        }
        Typeface font = load(spec);
        if (font == null) {
            return null;
        }
        arm(spec);
        Map<String, Typeface> copy = new LinkedHashMap<>(map);
        int swapped = 0;
        for (Map.Entry<String, Typeface> e : copy.entrySet()) {
            if (isText(e.getKey())) {
                Typeface was = e.getValue();
                e.setValue(Typeface.create(font, was != null ? was.getStyle() : Typeface.NORMAL));
                swapped++;
            }
        }
        Hooks.log("system font " + spec + " in " + swapped + " families");
        return copy;
    }

    /** The families of the default text font, under every name apps ask for it by. */
    private static boolean isText(String name) {
        return name.startsWith("sans-serif") || name.startsWith("google-sans")
                || name.startsWith("roboto") || name.contains("ntype")
                || name.equals("system-ui");
    }

    /** Writes the map again when the choice changes (the settings send this broadcast). */
    private static void listen(ConfigStore store) {
        if (context == null) {
            return;
        }
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                try {
                    store.reloadAndNotify();
                    FAILED.delete(); // A font chosen again is given another chance.
                    rewriteMap();
                } catch (Throwable t) {
                    Hooks.log("system font change failed: " + t);
                }
            }
        }, new IntentFilter(Config.ACTION_FONT), Context.RECEIVER_EXPORTED);
    }

    private static void rewriteMap() {
        if (service == null) {
            return;
        }
        for (String method : new String[] {"updateSerializedFontMap", "updateFontMap",
                "updateSerializedFontMapLocked"}) {
            try {
                XposedHelpers.callMethod(service, method);
                return;
            } catch (NoSuchMethodError e) {
                // Not this release's name: try the next.
            }
        }
        Hooks.log("system font map cannot be rewritten on this release");
    }

    /** Marks {@code spec} as newly in use, until system_server has run with it a while. */
    private static void arm(String spec) {
        if (GUARD.exists()) {
            return;
        }
        writeText(GUARD, spec);
        new Handler(Looper.getMainLooper()).postDelayed(GUARD::delete, SETTLE_MS);
    }

    /**
     * The font for {@code spec} ("file:path" or "res:name" of the widgets app), read into a
     * buffer of our own and checked to be a font; null if it is not one.
     */
    private static Typeface load(String spec) {
        if (spec.equals(loadedSpec)) {
            return loaded;
        }
        try {
            byte[] bytes = readFont(spec);
            if (bytes == null || !isFont(bytes)) {
                Hooks.log("system font " + spec + " is not a font file");
                return null;
            }
            ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length)
                    .order(ByteOrder.nativeOrder());
            buffer.put(bytes).flip();
            Font font = new Font.Builder(buffer).build();
            Typeface typeface = new Typeface.CustomFallbackBuilder(
                    new FontFamily.Builder(font).build()).build();
            loadedSpec = spec;
            loadedBytes = buffer;
            loaded = typeface;
            return typeface;
        } catch (Exception e) {
            Hooks.log("system font " + spec + " could not be loaded: " + e);
            return null;
        }
    }

    private static byte[] readFont(String spec) throws Exception {
        if (spec.startsWith("file:")) {
            try (InputStream in = new FileInputStream(spec.substring(5))) {
                return in.readAllBytes();
            }
        }
        if (spec.startsWith("res:") && context != null) {
            Resources res = context.createPackageContext(WIDGETS_PKG, 0).getResources();
            int id = res.getIdentifier(spec.substring(4), "font", WIDGETS_PKG);
            if (id == 0) {
                return null;
            }
            try (InputStream in = res.openRawResource(id)) {
                return in.readAllBytes();
            }
        }
        return null;
    }

    /** True for a TrueType or OpenType font file (not a collection), by its first bytes. */
    private static boolean isFont(byte[] bytes) {
        if (bytes.length < 1024) {
            return false;
        }
        int tag = (bytes[0] & 0xFF) << 24 | (bytes[1] & 0xFF) << 16 | (bytes[2] & 0xFF) << 8
                | (bytes[3] & 0xFF);
        return tag == 0x00010000 || tag == 0x4F54544F /* OTTO */ || tag == 0x74727565 /* true */;
    }

    private static String readText(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeText(File file, String text) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(String.valueOf(text).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Hooks.log("system font guard not writable: " + e);
        }
    }
}
