package com.dressence.nothinglauncherplus.xposed;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * Nothing's NDot 57 for the module's own drawing in the launcher and System UI, taken from
 * where it already is on the phone (it is Nothing's font and not shipped with the module).
 *
 * <p>The font is read into a buffer of its own and checked to be a font before use.
 * {@code Resources.getFont} of another app's resources is never used: such a font reads from
 * that app's resource memory, which can be released while the font is still drawn with, and
 * then the process dies in native code with nothing to catch.
 */
public final class NdotFont {

    private static final String WIDGETS_PKG = "com.nothing.hearthstone";
    private static final String[] NDOT_FILES = {"ndot57", "ndot-57", "ndot_57", "ndot"};
    private static final String[] NDOT_RESOURCES = {"ndot57", "ndot_57_aligned", "ndot"};
    private static final String[] FONT_DIRS = {"/system/fonts", "/product/fonts"};

    private static boolean ndotLooked;
    private static Typeface ndot;

    private NdotFont() {
    }

    /** NDot 57, from the system fonts or else Nothing's widgets app; null if neither has it. */
    public static synchronized Typeface get(Context context) {
        if (!ndotLooked) {
            ndotLooked = true;
            ndot = ndotFromSystem();
            if (ndot == null) {
                ndot = ndotFromWidgets(context);
            }
            Hooks.log("NDot font " + (ndot != null ? "found" : "not found"));
        }
        return ndot;
    }

    /** A typeface drawn from {@code bytes} (kept by it); null unless they are a font. */
    private static Typeface fromBytes(byte[] bytes) {
        if (bytes == null || !isFont(bytes)) {
            return null;
        }
        try {
            ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length)
                    .order(ByteOrder.nativeOrder());
            buffer.put(bytes).flip();
            Font font = new Font.Builder(buffer).build();
            return new Typeface.CustomFallbackBuilder(new FontFamily.Builder(font).build())
                    .build();
        } catch (Exception e) {
            Hooks.log("font could not be built: " + e);
            return null;
        }
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

    private static Typeface ndotFromSystem() {
        for (String dir : FONT_DIRS) {
            File[] files = new File(dir).listFiles();
            if (files == null) {
                continue;
            }
            for (String wanted : NDOT_FILES) {
                for (File f : files) {
                    String name = f.getName().toLowerCase(Locale.ROOT);
                    if (name.startsWith(wanted)
                            && (name.endsWith(".otf") || name.endsWith(".ttf"))) {
                        try {
                            Typeface font = fromBytes(readFile(f));
                            if (font != null) {
                                return font;
                            }
                        } catch (Exception e) {
                            // Unreadable; try the next one.
                        }
                    }
                }
            }
        }
        return null;
    }

    private static Typeface ndotFromWidgets(Context context) {
        for (String name : NDOT_RESOURCES) {
            try {
                Typeface font = fromBytes(readWidgetsFont(context, name));
                if (font != null) {
                    return font;
                }
            } catch (Exception e) {
                // Not this name, or no widgets app: try the next.
            }
        }
        return null;
    }

    private static byte[] readFile(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            return in.readAllBytes();
        }
    }

    private static byte[] readWidgetsFont(Context context, String name) throws Exception {
        Resources res = context.createPackageContext(WIDGETS_PKG, 0).getResources();
        int id = res.getIdentifier(name, "font", WIDGETS_PKG);
        if (id == 0) {
            return null;
        }
        try (InputStream in = res.openRawResource(id)) {
            return in.readAllBytes();
        }
    }
}
