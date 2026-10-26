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
     * Returns null on success, else what went wrong. Needs root. With {@code now}, the font is
     * mounted in place at once and the phone's interface restarted (a soft reboot of a few
     * seconds: Android reads its fonts once, when the interface starts); otherwise it takes
     * effect at the next reboot.
     */
    static String apply(Context context, Choice choice, boolean now) {
        try {
            if (choice == null) {
                StringBuilder off = new StringBuilder();
                if (now) {
                    // Every font mounted over by an earlier choice, back to the phone's own.
                    for (String target : defaultFontFiles()) {
                        off.append("umount -l '").append(target).append("' 2>/dev/null\n");
                    }
                }
                String error = su("rm -rf " + MODULE + "\n" + inGlobalMounts(off.toString()));
                if (error == null && now) {
                    su(RESTART);
                }
                return error;
            }
            // The font itself, handed to the root shell in the script: the shell need not
            // read this app's own storage (it may not be allowed to).
            byte[] fontBytes;
            try (InputStream in = open(context, choice)) {
                fontBytes = in.readAllBytes();
            }
            if (fontBytes.length < 1024) {
                return "the font could not be read";
            }
            StringBuilder script = new StringBuilder();
            script.append("M=").append(MODULE).append("\n")
                    .append("rm -rf $M && mkdir -p $M/system/fonts $M/system/product/fonts\n")
                    .append("printf 'id=nlplus_systemfont\\nname=Nothing Launcher+ system font"
                            + "\\nversion=1\\nversionCode=1\\nauthor=Nothing Launcher+\\n"
                            + "description=")
                    .append(choice.title.replace("'", ""))
                    .append(" as the system font\\n' > $M/module.prop\n")
                    .append("base64 -d > $M/" + FONT_COPY + " <<'NLPLUS_FONT'\n")
                    .append(android.util.Base64.encodeToString(fontBytes,
                            android.util.Base64.DEFAULT))
                    .append("NLPLUS_FONT\n");
            Set<String> targets = defaultFontFiles();
            // Whatever another font module (one that worked, such as a zFont one) replaces,
            // this one replaces too.
            targets.addAll(otherModuleFonts());
            replaced = targets.size();
            for (String target : targets) {
                String dest = target.startsWith("/product/") ? "$M/system" + target
                        : "$M" + target;
                script.append("cp \"$M/" + FONT_COPY + "\" '").append(dest)
                        .append("' || echo \"could not write ").append(dest).append("\"\n");
            }
            script.append("chmod -R 0755 $M; chmod 0644 $M/system/fonts/* "
                    + "$M/system/product/fonts/* 2>/dev/null; chcon -R u:object_r:system_file:s0"
                    + " $M/system 2>/dev/null; rmdir $M/system/product/fonts "
                    + "$M/system/product 2>/dev/null\n")
                    // Nothing written means nothing to mount: say so rather than succeed.
                    .append("n=$(find $M/system -type f | wc -l); if [ \"$n\" -lt 1 ]; then "
                            + "echo \"no font files were written to the module\"; exit 1; fi\n");
            if (now) {
                // The module's copy mounted over each file now, for every app to see.
                StringBuilder mounts = new StringBuilder();
                for (String target : targets) {
                    String copy = MODULE + (target.startsWith("/product/") ? "/system" + target
                            : target);
                    mounts.append("umount -l '").append(target).append("' 2>/dev/null; ")
                            .append("mount -o bind '").append(copy).append("' '")
                            .append(target).append("'\n");
                }
                script.append(inGlobalMounts(mounts.toString()));
            }
            String error = su(script.toString());
            if (error == null && now) {
                su(RESTART);
            }
            return error;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /** The chosen font, kept in the module beside its system folder (for {@link #verify}). */
    private static final String FONT_COPY = "nlplus_font.ttf";

    /** Restarts the phone's interface (not the phone): every app starts again, new fonts. */
    private static final String RESTART = "setprop ctl.restart zygote";

    /**
     * {@code commands} run in the system's own mount namespace, so a mount is seen by every
     * app, not only by this root shell.
     */
    private static String inGlobalMounts(String commands) {
        if (commands.isEmpty()) {
            return "";
        }
        return "cat > /data/local/tmp/nlplus_font.sh <<'EOF'\n" + commands
                + "EOF\n"
                + "nsenter --mount=/proc/1/ns/mnt sh /data/local/tmp/nlplus_font.sh 2>/dev/null"
                + " || sh /data/local/tmp/nlplus_font.sh\n"
                + "rm -f /data/local/tmp/nlplus_font.sh\n";
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

    /**
     * The system font files other font modules put in place, on or off: their
     * system/fonts, system/product/fonts and system/system_ext/fonts, as the live paths.
     */
    static Set<String> otherModuleFonts() {
        Set<String> out = new LinkedHashSet<>();
        String list = suOutput("for d in /data/adb/modules/*/system/fonts "
                + "/data/adb/modules/*/system/product/fonts "
                + "/data/adb/modules/*/system/system_ext/fonts; do "
                + "case \"$d\" in */nlplus_systemfont/*) continue;; esac; "
                + "[ -d \"$d\" ] && ls \"$d\" | sed \"s|^|$d/|\"; done");
        if (list == null) {
            return out;
        }
        for (String line : list.split("\n")) {
            line = line.trim();
            String lower = line.toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".ttf") || lower.endsWith(".otf"))) {
                continue;
            }
            int at = line.indexOf("/system/");
            if (at < 0) {
                continue;
            }
            String path = line.substring(at); // /system/fonts/x, /system/product/fonts/x
            path = path.replaceFirst("^/system/(product|system_ext)/", "/$1/");
            if (new File(path).exists()) {
                out.add(path);
            }
        }
        return out;
    }

    /**
     * Checks each replaced file as every app now sees it: how many are the chosen font. A
     * line per file, for the report.
     */
    static String verify(Context context) {
        String font = MODULE + "/" + FONT_COPY;
        StringBuilder script = new StringBuilder();
        for (String target : defaultFontFiles()) {
            script.append("if cmp -s '").append(font).append("' '")
                    .append(target).append("'; then echo \"OK ").append(target)
                    .append("\"; else echo \"-- ").append(target).append("\"; fi\n");
        }
        for (String target : otherModuleFonts()) {
            script.append("if cmp -s '").append(font).append("' '")
                    .append(target).append("'; then echo \"OK ").append(target)
                    .append("\"; else echo \"-- ").append(target).append("\"; fi\n");
        }
        script.append("echo; echo \"Root: $(magisk -v 2>/dev/null || ksud -V 2>/dev/null"
                + " || apd -V 2>/dev/null)\"\n");
        script.append("echo \"Module files: $(find /data/adb/modules/nlplus_systemfont/system"
                + " -type f 2>/dev/null | wc -l)\"\n");
        script.append("ls /data/adb/modules/nlplus_systemfont 2>/dev/null | tr '\\n' ' '\n");
        String out = suOutput(script.toString());
        return out == null ? "Root access was refused" : out;
    }

    /** Runs {@code script} as root and returns what it printed, or null without root. */
    private static String suOutput(String script) {
        try {
            Process p = new ProcessBuilder("su").redirectErrorStream(true).start();
            try (OutputStream in = p.getOutputStream()) {
                in.write(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                in.write("\nexit\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            String output = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            p.waitFor();
            return output;
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
