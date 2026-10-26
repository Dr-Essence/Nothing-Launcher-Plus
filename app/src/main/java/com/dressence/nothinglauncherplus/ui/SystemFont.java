package com.dressence.nothinglauncherplus.ui;

import android.content.Context;
import android.content.res.Resources;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sets one of Nothing's own fonts (NDot, NType, ...) as the phone's system font, the way font
 * modules do: a small Magisk / KernelSU module that puts the chosen font in place of every
 * file of the system's default sans-serif family. The fonts are the ones already on the
 * phone, in its font folders or inside Nothing's widgets app; none ship with this app.
 */
final class SystemFont {

    /** Where the generated module lives (Magisk and KernelSU both read it). */
    private static final String MODULE = "/data/adb/modules/nlplus_systemfont";
    private static final String WIDGETS_PKG = "com.nothing.hearthstone";
    private static final String[] FONT_DIRS = {"/system/fonts", "/product/fonts",
            "/system_ext/fonts"};
    private static final String[] FONT_CONFIGS = {"/system/etc/fonts.xml",
            "/product/etc/fonts_customization.xml", "/system/etc/font_fallback.xml"};
    private static final String[] NAMES = {"ndot", "ntype", "lettera", "nothing"};
    /** The Latin text fonts a phone's interface may be set in. */
    private static final String[] TEXT_FONTS = {"roboto", "ntype", "googlesans", "google-sans",
            "nothing"};

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

    /** How many font files the last {@link #apply} put the chosen font in place of. */
    static int replaced;

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
     * Installs {@code choice} as the system font (null: removes it, back to the phone's own).
     * Returns null on success, else what went wrong. Needs root; takes effect after a reboot.
     */
    static String apply(Context context, Choice choice) {
        try {
            if (choice == null) {
                return su("rm -rf " + MODULE);
            }
            // The font, copied where the root shell can read it.
            File font = new File(context.getCacheDir(), "nlplus_systemfont.ttf");
            try (InputStream in = open(context, choice);
                    OutputStream out = new FileOutputStream(font)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            StringBuilder script = new StringBuilder();
            script.append("M=").append(MODULE).append("\n")
                    .append("rm -rf $M && mkdir -p $M/system/fonts $M/system/product/fonts\n")
                    .append("printf 'id=nlplus_systemfont\\nname=Nothing Launcher+ system font"
                            + "\\nversion=1\\nversionCode=1\\nauthor=Nothing Launcher+\\n"
                            + "description=")
                    .append(choice.title.replace("'", ""))
                    .append(" as the system font\\n' > $M/module.prop\n");
            Set<String> targets = defaultFontFiles();
            replaced = targets.size();
            for (String target : targets) {
                String dest = target.startsWith("/product/") ? "$M/system" + target
                        : "$M" + target;
                script.append("cp '").append(font.getAbsolutePath()).append("' '")
                        .append(dest).append("'\n");
            }
            script.append("chmod -R 0755 $M; chmod 0644 $M/system/fonts/* "
                    + "$M/system/product/fonts/* 2>/dev/null; chcon -R u:object_r:system_file:s0"
                    + " $M/system 2>/dev/null; rmdir $M/system/product/fonts "
                    + "$M/system/product 2>/dev/null; true\n");
            return su(script.toString());
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private static InputStream open(Context context, Choice choice) throws Exception {
        if (choice.path != null) {
            return new FileInputStream(choice.path);
        }
        return context.createPackageContext(WIDGETS_PKG, 0).getResources()
                .openRawResource(choice.resource);
    }

    /**
     * The files of the system's text fonts: every font of the sans-serif families in the
     * system's font lists, and every Roboto, NType and Google Sans file there is.
     */
    static Set<String> defaultFontFiles() {
        Set<String> files = new LinkedHashSet<>();
        Pattern family = Pattern.compile(
                "<family[^>]*name=\"(sans-serif[^\"]*|google-sans[^\"]*)\"[^>]*>(.*?)</family>",
                Pattern.DOTALL);
        Pattern font = Pattern.compile("<font[^>]*>\\s*([^<\\s]+\\.(?:ttf|otf|ttc))",
                Pattern.CASE_INSENSITIVE);
        for (String config : FONT_CONFIGS) {
            String xml = read(config);
            if (xml == null) {
                continue;
            }
            Matcher fam = family.matcher(xml);
            while (fam.find()) {
                Matcher f = font.matcher(fam.group(2));
                while (f.find()) {
                    String name = f.group(1);
                    if (name.toLowerCase(Locale.ROOT).endsWith(".ttc")) {
                        continue; // A collection (CJK): left alone.
                    }
                    String where = locate(name);
                    if (where != null) {
                        files.add(where);
                    }
                }
            }
        }
        // Nothing OS has its own text font setting (NType or the stock one): every Latin text
        // font the phone can use is replaced, so the chosen font shows whichever is picked.
        for (String dir : FONT_DIRS) {
            File[] all = new File(dir).listFiles();
            if (all == null) {
                continue;
            }
            for (File f : all) {
                String lower = f.getName().toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".ttf") || lower.endsWith(".otf"))) {
                    continue;
                }
                for (String prefix : TEXT_FONTS) {
                    if (lower.startsWith(prefix)) {
                        files.add(f.getAbsolutePath());
                        break;
                    }
                }
            }
        }
        // Never Nothing's dot or display fonts themselves.
        files.removeIf(path -> {
            String lower = new File(path).getName().toLowerCase(Locale.ROOT);
            return lower.contains("ndot") || lower.contains("emoji");
        });
        return files;
    }

    private static String locate(String name) {
        for (String dir : FONT_DIRS) {
            if (new File(dir, name).exists()) {
                return dir + "/" + name;
            }
        }
        return null;
    }

    private static String read(String path) {
        try (InputStream in = new FileInputStream(path)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
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
