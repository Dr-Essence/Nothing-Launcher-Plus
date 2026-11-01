package com.dressence.nothinglauncherplus.wallpaper;

import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;

/**
 * Palette Cycle (rak.pixellwp, F-Droid 2.3.1): its pictures download again. It asked
 * effectgames.com's old addresses (http://…/scene.php?file=…, http://…/image.php?file=…), which
 * now answer with a 167-character notice instead of the picture (an http→https move, which
 * Java's URL connection does not follow), and then failed reading it (DebuG: all 70
 * downloads, StringIndexOutOfBoundsException in cleanTimelineImageJson). Its author changed
 * the addresses and the reading on 2026-10-31 (commit b88f93a, "update image urls"), not yet
 * released; this applies that same change to the installed app until it is:
 * https://www.effectgames.com/demos/worlds/cache/{id}-{month}-{script}.js and
 * https://www.effectgames.com/demos/canvascycle/images/{id}.LBM.json, read by trimming them
 * (and, for the time-of-day worlds, taking off "CanvasCycle.initScene(" … ");").
 */
public final class PaletteCycle {

    public static final String PKG = "rak.pixellwp";
    private static final String CLS_DOWNLOADER = "rak.pixellwp.cycling.jsonLoading.JsonDownloader";

    private PaletteCycle() {
    }

    public static void install(ClassLoader cl) {
        Class<?> downloader = XposedHelpers.findClass(CLS_DOWNLOADER, cl);
        XposedHelpers.findAndHookMethod(downloader, "getFullUrl",
                XposedHelpers.findClass("rak.pixellwp.cycling.jsonModels.ImageInfo", cl),
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        Object image = param.args[0];
                        if ((Boolean) XposedHelpers.callMethod(image, "isTimeline")) {
                            return "https://www.effectgames.com/demos/worlds/cache/"
                                    + XposedHelpers.callMethod(image, "getJustId") + "-"
                                    + XposedHelpers.callMethod(image, "getMonth") + "-"
                                    + XposedHelpers.callMethod(image, "getScript") + ".js";
                        }
                        return "https://www.effectgames.com/demos/canvascycle/images/"
                                + XposedHelpers.callMethod(image, "getId") + ".LBM.json";
                    }
                });
        XposedHelpers.findAndHookMethod(downloader, "cleanTimelineImageJson", String.class,
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        String json = ((String) param.args[0]).trim();
                        if (json.startsWith("CanvasCycle.initScene(")) {
                            json = json.substring("CanvasCycle.initScene(".length());
                        }
                        if (json.endsWith(");")) {
                            json = json.substring(0, json.length() - 2);
                        }
                        return json;
                    }
                });
        XposedHelpers.findAndHookMethod(downloader, "cleanImageJson", String.class,
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        return ((String) param.args[0]).trim();
                    }
                });
        // The download itself, as the app does it (gzip asked for), but unzipped only when the
        // server says the answer is gzip, so a plain answer is read too.
        XposedHelpers.findAndHookMethod(downloader, "downloadImage", new XC_MethodReplacement() {
            @Override
            @SuppressWarnings("unchecked")
            protected Object replaceHookedMethod(MethodHookParam param) {
                Object image = XposedHelpers.getObjectField(param.thisObject, "image");
                String url = (String) XposedHelpers.callMethod(param.thisObject, "getFullUrl",
                        image);
                try {
                    java.net.URLConnection connection = new java.net.URL(url).openConnection();
                    connection.setRequestProperty("Accept-Encoding", "gzip");
                    java.io.InputStream in = connection.getInputStream();
                    if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) {
                        in = new java.util.zip.GZIPInputStream(in);
                    }
                    try (java.io.InputStream body = in) {
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        byte[] buffer = new byte[64 * 1024];
                        int n;
                        while ((n = body.read(buffer)) > 0) {
                            out.write(buffer, 0, n);
                        }
                        return out.toString("UTF-8");
                    }
                } catch (Throwable t) {
                    Hooks.log("palette cycle: download of " + url + " failed: " + t);
                    ((java.util.Set<Object>) XposedHelpers.getObjectField(param.thisObject,
                            "downloading")).remove(image);
                    return null;
                }
            }
        });
        Hooks.log("palette cycle: downloads from effectgames.com's current addresses");
    }
}
