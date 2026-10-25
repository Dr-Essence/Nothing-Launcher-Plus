package com.dressence.nothinglauncherplus.hook;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;

import java.io.File;
import java.util.Locale;

/**
 * Nothing's NDot 57 typeface, taken from where it already lives on the phone (it is Nothing's
 * font and isn't shipped with the module): the system fonts, or else Nothing's widgets app,
 * which bundles it. Looked up once; null when the phone has neither.
 */
final class NdotFont {

    private static final String WIDGETS_PKG = "com.nothing.hearthstone";
    private static final String[] RESOURCES = {"ndot57", "ndot_57_aligned", "ndot"};
    private static final String[] FONT_DIRS = {"/system/fonts", "/product/fonts"};

    private static boolean loaded;
    private static Typeface typeface;

    private NdotFont() {
    }

    static synchronized Typeface get(Context context) {
        if (!loaded) {
            loaded = true;
            typeface = fromSystem();
            if (typeface == null) {
                typeface = fromWidgets(context);
            }
            Hooks.log("NDot font " + (typeface != null ? "found" : "not found, using dots"));
        }
        return typeface;
    }

    private static Typeface fromSystem() {
        for (String dir : FONT_DIRS) {
            File[] files = new File(dir).listFiles();
            if (files == null) {
                continue;
            }
            for (String wanted : new String[] {"ndot57", "ndot-57", "ndot_57", "ndot"}) {
                for (File f : files) {
                    String name = f.getName().toLowerCase(Locale.ROOT);
                    if (name.startsWith(wanted) && (name.endsWith(".otf") || name.endsWith(".ttf"))) {
                        try {
                            return Typeface.createFromFile(f);
                        } catch (RuntimeException e) {
                            // Unreadable; try the next one.
                        }
                    }
                }
            }
        }
        return null;
    }

    private static Typeface fromWidgets(Context context) {
        try {
            Resources res = context.createPackageContext(WIDGETS_PKG, 0).getResources();
            for (String name : RESOURCES) {
                int id = res.getIdentifier(name, "font", WIDGETS_PKG);
                if (id != 0) {
                    return res.getFont(id);
                }
            }
        } catch (Exception e) {
            Hooks.log("NDot font from widgets unavailable: " + e);
        }
        return null;
    }
}
