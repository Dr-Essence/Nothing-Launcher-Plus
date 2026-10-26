package com.dressence.nothinglauncherplus.hook;

import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;

import java.lang.reflect.Field;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * The chosen system font (see the module's System font option) in every app the module hooks,
 * without a reboot: once the app has started, the system's default and sans-serif typefaces
 * in that app are swapped for the chosen one, so all text that uses the system font is drawn
 * in it. An app picks it up the next time it starts.
 */
final class FontSwap {

    private static final String WIDGETS_PKG = "com.nothing.hearthstone";

    private FontSwap() {
    }

    static void install(ConfigStore store) {
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String spec = store.get().systemFont;
                        if (spec == null || spec.isEmpty()) {
                            return;
                        }
                        Typeface font = load((Context) param.args[0], spec);
                        if (font == null) {
                            Hooks.log("system font unavailable: " + spec);
                            return;
                        }
                        swap(font);
                    }
                });
    }

    private static Typeface load(Context context, String spec) {
        try {
            if (spec.startsWith("file:")) {
                return Typeface.createFromFile(spec.substring(5));
            }
            if (spec.startsWith("res:")) {
                Resources res = context.createPackageContext(WIDGETS_PKG, 0).getResources();
                int id = res.getIdentifier(spec.substring(4), "font", WIDGETS_PKG);
                return id == 0 ? null : res.getFont(id);
            }
        } catch (Exception e) {
            Hooks.log("system font failed to load: " + e);
        }
        return null;
    }

    /** Every sans-serif family and the default typefaces become {@code font}. */
    @SuppressWarnings("unchecked")
    private static void swap(Typeface font) {
        Typeface bold = Typeface.create(font, Typeface.BOLD);
        try {
            Map<String, Typeface> map = (Map<String, Typeface>) XposedHelpers.getStaticObjectField(
                    Typeface.class, "sSystemFontMap");
            for (Map.Entry<String, Typeface> e : map.entrySet()) {
                String name = e.getKey();
                if (name.startsWith("sans-serif") || name.startsWith("google-sans")
                        || name.startsWith("roboto") || name.contains("ntype")
                        || name.equals("system-ui")) {
                    Typeface was = e.getValue();
                    e.setValue(Typeface.create(font, was != null ? was.getStyle()
                            : Typeface.NORMAL));
                }
            }
        } catch (Throwable t) {
            Hooks.log("system font map: " + t);
        }
        setStatic("DEFAULT", font);
        setStatic("SANS_SERIF", font);
        setStatic("DEFAULT_BOLD", bold);
        try {
            Typeface[] defaults = (Typeface[]) XposedHelpers.getStaticObjectField(Typeface.class,
                    "sDefaults");
            defaults[Typeface.NORMAL] = font;
            defaults[Typeface.BOLD] = bold;
            defaults[Typeface.ITALIC] = Typeface.create(font, Typeface.ITALIC);
            defaults[Typeface.BOLD_ITALIC] = Typeface.create(font, Typeface.BOLD_ITALIC);
        } catch (Throwable t) {
            // Older or newer layout: the default below still applies.
        }
        try {
            // The default every Paint without a typeface draws with.
            XposedHelpers.callStaticMethod(Typeface.class, "setDefault", font);
        } catch (Throwable t) {
            Hooks.log("system font default: " + t);
        }
    }

    private static void setStatic(String name, Typeface value) {
        try {
            Field f = Typeface.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(null, value);
        } catch (Throwable t) {
            // A final field this runtime will not change: the font map still covers it.
        }
    }
}
