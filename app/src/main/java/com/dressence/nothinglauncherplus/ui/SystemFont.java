package com.dressence.nothinglauncherplus.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Nothing's own fonts on this phone (NDot, NType, ...), in its font folders or inside
 * Nothing's widgets app, to choose one as the system font; none ship with this app. The
 * choice is applied by the module's hook in the system (hook.SystemFonts).
 */
final class SystemFont {

    /** The font module earlier versions wrote (it replaced the system's font files). */
    private static final String OLD_MODULE = "/data/adb/modules/nlplus_systemfont";
    /** The module that adds the widgets app's fonts to the system's fonts, as new files. */
    private static final String FONTS_MODULE = "/data/adb/modules/nlplus_fonts";
    private static final String ADDED_PREFIX = "NLPlus-";
    private static final String WIDGETS_PKG = "com.nothing.hearthstone";
    private static final String[] FONT_DIRS = {"/system/fonts", "/product/fonts",
            "/system_ext/fonts"};
    private static final String[] NAMES = {"ndot", "ntype", "lettera", "nothing"};

    /**
     * A font that can be chosen: its title, and a file path, or a widgets-app resource that is
     * first added to the system's fonts as a file ({@link #addToSystem}).
     */
    static final class Choice {
        final String title;
        final String path;
        final int resource;

        /** True for a font the system does not have as a file yet. */
        boolean needsAdding() {
            return path == null;
        }

        Choice(String title, String path, int resource) {
            this.title = title;
            this.path = path;
            this.resource = resource;
        }
    }

    private SystemFont() {
    }

    /** Nothing's fonts on this phone. */
    static List<Choice> find(Context context) {
        List<Choice> out = new ArrayList<>();
        Set<String> titles = new LinkedHashSet<>();
        for (String dir : FONT_DIRS) {
            File[] files = new File(dir).listFiles();
            if (files == null) {
                continue;
            }
            for (File f : files) {
                String name = f.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (nothing(lower) && (lower.endsWith(".ttf") || lower.endsWith(".otf"))) {
                    // A font added from the widgets app is listed under its own name.
                    String title = name.replaceAll("\\.(ttf|otf|TTF|OTF)$", "")
                            .replaceFirst("^" + ADDED_PREFIX, "");
                    if (titles.add(title.toLowerCase(Locale.ROOT))) {
                        out.add(new Choice(title, f.getAbsolutePath(), 0));
                    }
                }
            }
        }
        try {
            Resources res = context.createPackageContext(WIDGETS_PKG, 0).getResources();
            for (java.lang.reflect.Field field : Class.forName(WIDGETS_PKG + ".R$font")
                    .getFields()) {
                addResource(res, field.getName(), out, titles);
            }
        } catch (Throwable reflective) {
            // No R class to read: try the names Nothing has used.
            try {
                Resources res = context.createPackageContext(WIDGETS_PKG, 0).getResources();
                for (String name : new String[] {"ndot57", "ndot_57_aligned", "ndot55",
                        "ndot", "ntype82", "ntype82_regular", "ntype_headline", "lettera"}) {
                    addResource(res, name, out, titles);
                }
            } catch (Exception e) {
                // Widgets app not there.
            }
        }
        return out;
    }

    private static void addResource(Resources res, String name, List<Choice> out,
            Set<String> titles) {
        if (!nothing(name.toLowerCase(Locale.ROOT))) {
            return;
        }
        int id = res.getIdentifier(name, "font", WIDGETS_PKG);
        if (id != 0 && titles.add(name.toLowerCase(Locale.ROOT))) {
            out.add(new Choice(name, null, id));
        }
    }

    private static boolean nothing(String lower) {
        for (String n : NAMES) {
            if (lower.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code choice} as a typeface to preview it in this app (null: the phone's own font);
     * null if it cannot be read. A widgets-app font is read into a buffer of this app's own.
     */
    static Typeface typeface(Context context, Choice choice) {
        try {
            if (choice == null) {
                File roboto = new File("/system/fonts/Roboto-Regular.ttf");
                return roboto.canRead() ? Typeface.createFromFile(roboto) : Typeface.DEFAULT;
            }
            if (choice.path != null) {
                return Typeface.createFromFile(choice.path);
            }
            byte[] bytes;
            try (InputStream in = context.createPackageContext(WIDGETS_PKG, 0).getResources()
                    .openRawResource(choice.resource)) {
                bytes = in.readAllBytes();
            }
            ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length)
                    .order(ByteOrder.nativeOrder());
            buffer.put(bytes).flip();
            return new Typeface.CustomFallbackBuilder(new FontFamily.Builder(
                    new Font.Builder(buffer).build()).build()).build();
        } catch (Exception e) {
            return null;
        }
    }

    /** Where {@code choice}, a widgets-app font, is once added to the system's fonts. */
    static String addedPath(Choice choice) {
        return "/system/fonts/" + ADDED_PREFIX + choice.title + ".otf";
    }

    /**
     * Adds {@code choice} (a font of the widgets app) to the system's fonts as a new file, by a
     * small Magisk / KernelSU module: it is there after the next reboot, at
     * {@link #addedPath}. Nothing of the system's own is replaced. Null on success, else what
     * went wrong. Needs root.
     */
    static String addToSystem(Context context, Choice choice) {
        try {
            byte[] bytes;
            try (InputStream in = context.createPackageContext(WIDGETS_PKG, 0).getResources()
                    .openRawResource(choice.resource)) {
                bytes = in.readAllBytes();
            }
            if (bytes.length < 1024) {
                return "the font could not be read";
            }
            String file = FONTS_MODULE + addedPath(choice);
            String script = "M=" + FONTS_MODULE + "\n"
                    + "mkdir -p $M/system/fonts\n"
                    + "printf 'id=nlplus_fonts\\nname=Nothing Launcher+ fonts\\nversion=1\\n"
                    + "versionCode=1\\nauthor=Nothing Launcher+\\ndescription=Nothing fonts"
                    + " from the widgets app, added to the system fonts\\n' > $M/module.prop\n"
                    + "base64 -d > '" + file + "' <<'NLPLUS_FONT'\n"
                    + android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                    + "NLPLUS_FONT\n"
                    + "chmod 0755 $M $M/system $M/system/fonts; chmod 0644 '" + file + "'\n"
                    + "chcon -R u:object_r:system_file:s0 $M/system 2>/dev/null\n"
                    + "[ -s '" + file + "' ] || { echo 'the font was not written'; exit 1; }\n";
            return su(script);
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : e.toString();
        }
    }

    /**
     * Removes the font module earlier versions of this app wrote (it put the chosen font in
     * place of the system's font files; the font is now applied by the module's hook in the
     * system instead). Gone after the next reboot. Needs root; false without it.
     */
    static boolean removeOldModule() {
        try {
            return su("rm -rf " + OLD_MODULE) == null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Restarts System UI and the launcher only (they start again by themselves in a moment),
     * so they draw in the newly chosen font; nothing else is touched. False without root.
     */
    static boolean restartSystemUi() {
        try {
            return su("pkill -f com.android.systemui; am force-stop "
                    + com.dressence.nothinglauncherplus.Config.LAUNCHER_PKG) == null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Runs {@code script} as root; null on success, else the error. */
    private static String su(String script) throws Exception {
        Process p = new ProcessBuilder("su").redirectErrorStream(true).start();
        try (OutputStream in = p.getOutputStream()) {
            in.write(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            in.write("\nexit\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        String output = new String(p.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).trim();
        int code = p.waitFor();
        return code == 0 ? null : output.isEmpty() ? "Root access was refused" : output;
    }
}
