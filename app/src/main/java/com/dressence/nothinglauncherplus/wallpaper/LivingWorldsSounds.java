package com.dressence.nothinglauncherplus.wallpaper;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.WebView;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Array;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Living Worlds' ambient sounds on the home screen and in the app drawer (Living Worlds
 * 2.12.74). Each of its 12 worlds has its own sounds, played by its JavaScript (sounds.js:
 * Sounds.cycle sends "playSound" with the tracks, fades and volumes of the moment) and by its
 * Android side (SoundManager, Sound: MediaPlayer on assets/sound/*.m4a). The app plays them;
 * the live wallpaper does not, twice over: its JavaScript runs with fixed settings that have no
 * "Ambient Sounds" (Storage.wallpaperItems), so it asks for none, and its message handler
 * (MyLWPService$JSInterface.postMessage) only handles "setSceneKey".
 *
 * <p>So, inside Living Worlds and with its own parts: the wallpaper's JavaScript gets Ambient
 * Sounds on (App.store.set({playSounds: true}), as the app's switch does; set again every few
 * seconds, since a world change reloads the page), and its "playSound" messages go to a
 * SoundManager of the app's own, one per wallpaper engine. The sounds play only while the
 * wallpaper is seen on the home screen or in the app drawer: they stop when an app covers it
 * (the engine turns invisible), on the lock screen (until the phone is unlocked) and with the
 * screen off; the wallpaper picker's preview stays silent. They come from Living Worlds'
 * own sound code running in the wallpaper for the world and time it draws, so they follow the
 * picture exactly as in the app. Nothing here runs unless a Living Worlds wallpaper engine
 * does: with another wallpaper set there is none, and an engine that is replaced stops its
 * sounds. Should two run at once (while switching, or on home and lock), only the last to be
 * shown is heard.
 */
public final class LivingWorldsSounds {

    public static final String PKG = "com.pixfabrik.livingworlds";
    private static final String CLS_SERVICE = PKG + ".MyLWPService";
    private static final String CLS_ENGINE = CLS_SERVICE + "$MyEngine";
    private static final String CLS_JS = CLS_SERVICE + "$JSInterface";
    private static final String CLS_SOUNDS = PKG + ".SoundManager";
    private static final String CLS_SPEC = PKG + ".SoundSpec";
    private static final String MANAGER = "nlplus.sounds";
    private static final String SHOWN = "nlplus.shown";
    private static final String ENABLED_AT = "nlplus.enabledAt";
    private static final long ENABLE_EVERY_MS = 3000;
    private static final String ENABLE_JS = "window.App&&App.store&&App.allowSounds"
            + "&&!App.store.get().playSounds&&App.store.set({playSounds:true})";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** The one engine that may sound: the last to become visible (main thread). */
    private static java.lang.ref.WeakReference<Object> current =
            new java.lang.ref.WeakReference<>(null);

    private LivingWorldsSounds() {
    }

    public static void install(ClassLoader cl, ConfigStore store) {
        Class<?> engineClass = XposedHelpers.findClass(CLS_ENGINE, cl);
        Class<?> managerClass = XposedHelpers.findClass(CLS_SOUNDS, cl);
        Class<?> specClass = XposedHelpers.findClass(CLS_SPEC, cl);

        // Each frame the wallpaper draws: Ambient Sounds on in its JavaScript.
        XposedHelpers.findAndHookMethod(CLS_JS, cl, "drawFrame", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object engine = XposedHelpers.getObjectField(param.thisObject, "myActivity");
                if (store.get().livingWorldsSounds && !isPreview(engine)) {
                    enableSounds(engine);
                }
            }
        });

        // Its "playSound" messages: to the engine's own SoundManager.
        XposedHelpers.findAndHookMethod(CLS_JS, cl, "postMessage", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object engine = XposedHelpers.getObjectField(param.thisObject,
                                "myActivity");
                        String message = (String) param.args[0];
                        if (message == null || !message.contains("\"playSound\"")
                                || !store.get().livingWorldsSounds || isPreview(engine)) {
                            return;
                        }
                        Object specs;
                        try {
                            JSONArray sounds = new JSONObject(message).getJSONArray("sounds");
                            specs = Array.newInstance(specClass, sounds.length());
                            for (int i = 0; i < sounds.length(); i++) {
                                JSONObject s = sounds.getJSONObject(i);
                                Object spec = XposedHelpers.newInstance(specClass);
                                XposedHelpers.setObjectField(spec, "name", s.getString("name"));
                                XposedHelpers.setObjectField(spec, "volume",
                                        (float) s.getDouble("volume"));
                                XposedHelpers.setBooleanField(spec, "loop", s.getBoolean("loop"));
                                Array.set(specs, i, spec);
                            }
                        } catch (Throwable t) {
                            Hooks.log("living worlds sounds: bad message: " + t);
                            return;
                        }
                        Object chosen = specs;
                        MAIN.post(Hooks.guard("living worlds play", () ->
                                XposedHelpers.callMethod(manager(engine, managerClass), "play",
                                        chosen)));
                    }
                });

        // Heard only while the wallpaper shows on the home screen or in the app drawer.
        XposedHelpers.findAndHookMethod(engineClass, "onVisibilityChanged", boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object engine = param.thisObject;
                        store.reloadIfChanged(); // The switch, as last set.
                        XposedHelpers.setAdditionalInstanceField(engine, SHOWN, param.args[0]);
                        Object was = current.get();
                        if (Boolean.TRUE.equals(param.args[0]) && !isPreview(engine)
                                && was != engine) {
                            // Two engines at once (switching, home and lock): one is heard.
                            current = new java.lang.ref.WeakReference<>(engine);
                            if (was != null) {
                                Hooks.guard("living worlds other",
                                        () -> audible(was, managerClass, store)).run();
                            }
                        }
                        Hooks.guard("living worlds visibility",
                                () -> audible(engine, managerClass, store)).run();
                    }
                });
        XposedHelpers.findAndHookMethod(engineClass, "onDestroy", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                // The wallpaper replaced or gone: it never sounds again.
                XposedHelpers.setAdditionalInstanceField(param.thisObject, SHOWN, false);
                Object manager = XposedHelpers.getAdditionalInstanceField(param.thisObject,
                        MANAGER);
                if (manager != null) {
                    Hooks.guard("living worlds stop",
                            () -> XposedHelpers.callMethod(manager, "stop")).run();
                }
            }
        });
        Hooks.log("living worlds sounds: ready");
    }

    private static boolean isPreview(Object engine) {
        try {
            return (Boolean) XposedHelpers.callMethod(engine, "isPreview");
        } catch (Throwable t) {
            return true;
        }
    }

    private static void enableSounds(Object engine) {
        Long at = (Long) XposedHelpers.getAdditionalInstanceField(engine, ENABLED_AT);
        long now = SystemClock.elapsedRealtime();
        if (at != null && now - at < ENABLE_EVERY_MS) {
            return;
        }
        XposedHelpers.setAdditionalInstanceField(engine, ENABLED_AT, now);
        WebView web = (WebView) XposedHelpers.getObjectField(engine, "myWebView");
        if (web != null) {
            web.post(Hooks.guard("living worlds enable",
                    () -> web.evaluateJavascript(ENABLE_JS, null)));
        }
    }

    /** The engine's SoundManager (the app's), made on the main thread on first use. */
    private static Object manager(Object engine, Class<?> managerClass) {
        Object manager = XposedHelpers.getAdditionalInstanceField(engine, MANAGER);
        if (manager == null) {
            Context context = (Context) XposedHelpers.getObjectField(engine, "myContext");
            manager = XposedHelpers.newInstance(managerClass, context);
            XposedHelpers.setAdditionalInstanceField(engine, MANAGER, manager);
            listenForUnlock(engine, managerClass, context);
            boolean audible = shouldPlay(engine, context);
            if (!audible) {
                XposedHelpers.callMethod(manager, "stop");
            }
        }
        return manager;
    }

    /** Leaving the lock screen does not change the wallpaper's visibility: unlocking does. */
    private static void listenForUnlock(Object engine, Class<?> managerClass, Context context) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                Hooks.guard("living worlds unlock", () -> audible(engine, managerClass, null))
                        .run();
            }
        }, filter);
    }

    private static boolean shouldPlay(Object engine, Context context) {
        Object shown = XposedHelpers.getAdditionalInstanceField(engine, SHOWN);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return Boolean.TRUE.equals(shown) && !isPreview(engine) && current.get() == engine
                && (keyguard == null || !keyguard.isKeyguardLocked());
    }

    /** Starts or stops the engine's sounds for where the wallpaper is seen now. */
    private static void audible(Object engine, Class<?> managerClass, ConfigStore store) {
        Object manager = XposedHelpers.getAdditionalInstanceField(engine, MANAGER);
        if (manager == null) {
            return; // No sound asked for yet.
        }
        Context context = (Context) XposedHelpers.getObjectField(engine, "myContext");
        boolean on = (store == null || store.get().livingWorldsSounds)
                && shouldPlay(engine, context);
        XposedHelpers.callMethod(manager, on ? "start" : "stop");
    }
}
