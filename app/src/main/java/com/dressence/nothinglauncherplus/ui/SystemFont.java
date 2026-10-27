package com.dressence.nothinglauncherplus.ui;

import android.content.Context;
import android.content.res.Resources;

import java.io.File;
import java.io.OutputStream;
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

    /** The font module earlier versions wrote. */
    private static final String OLD_MODULE = "/data/adb/modules/nlplus_systemfont";
    private static final String WIDGETS_PKG = "com.nothing.hearthstone";
    private static final String[] FONT_DIRS = {"/system/fonts", "/product/fonts",
            "/system_ext/fonts"};
    private static final String[] NAMES = {"ndot", "ntype", "lettera", "nothing"};

    /** A font that can be chosen: its title, and a file path or a widgets-app resource. */
    static final class Choice {
        final String title;
        final String path;
        final int resource;

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
                    String title = name.replaceAll("\\.(ttf|otf|TTF|OTF)$", "");
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
