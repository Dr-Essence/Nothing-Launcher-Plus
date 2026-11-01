package com.dressence.nothinglauncherplus.wallpaper;

import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Palette Cycle's worlds with their sounds, as the Living Worlds web demo they come from plays
 * them (effectgames.com/demos/worlds: scenes.js gives each world and weather its sound and
 * volume, main.js plays audio/{sound}.mp3 looping (here with no gap: SeamlessLoop), fading in over 2 s to that volume, default
 * 0.5, and out over half a second when the world changes). Palette Cycle has no sound at all.
 *
 * <p>The sound follows the world Palette Cycle shows (its changeImage: world, weather and
 * time-of-day script), downloaded on the phone once and kept in Palette Cycle's cache. Heard
 * only while the wallpaper shows on the home screen or in the app drawer: not over apps, on
 * the lock screen until unlocked, with the screen off, in the picker's preview, nor with
 * another wallpaper set (no Palette Cycle engine runs); one engine at a time. Its single
 * pictures (the Canvas Cycle demo's) take that demo's sounds (demos/canvascycle: scenes.js,
 * main.js, the same playing); see the table for the few in neither demo's list.
 */
final class PaletteCycleSounds {

    private static final String CLS_ENGINE =
            "rak.pixellwp.cycling.wallpaperService.CyclingWallpaperService$CyclingWallpaperEngine";
    private static final String CLS_CHANGING =
            "rak.pixellwp.cycling.wallpaperService.ImageChangingKt";
    private static final String WORLDS_AUDIO = "https://www.effectgames.com/demos/worlds/audio/";
    private static final String CANVAS_AUDIO =
            "https://www.effectgames.com/demos/canvascycle/audio/";
    private static final float DEFAULT_VOLUME = 0.5f;

    /** scenes.js: world (base image) and script → sound and its volume. */
    private static final Map<String, Object[]> SOUNDS = new HashMap<>();

    static {
        put("V26", "janclrscpt", "V13", DEFAULT_VOLUME);
        put("V26SNOW", "jansnowscpt", "V05RAIN", 0.25f);
        put("V19", "febclrscpt", "V19", DEFAULT_VOLUME);
        put("V19", "febcldyscpt", "V19", DEFAULT_VOLUME);
        put("V30", "aprclrscpt", "V30", 0.25f);
        put("V30RAIN", "aprrainscpt", "V30RAIN", DEFAULT_VOLUME);
        put("V08", "MAYCLRSCPT", "V08", 0.25f);
        put("V08", "MAYCLDYSCPT", "V08", 0.25f);
        put("V08RAIN", "MAYRAINSCPT", "V08RAIN", DEFAULT_VOLUME);
        put("V20JOE", "jundayscpt", "V20", DEFAULT_VOLUME);
        put("V25", "julyclearscpt", "V25HEAT", DEFAULT_VOLUME);
        put("V25", "julycloudyscpt", "V25HEAT", DEFAULT_VOLUME);
        put("CORAL", "augclrscpt", "CORAL", 0.25f);
        put("V29", "SEPTCLRCUMSCPT", "V29", DEFAULT_VOLUME);
        put("V29", "SEPTCLDYSCPT", "V29", DEFAULT_VOLUME);
        put("V05AM", "octbegclrscpt", "V13", DEFAULT_VOLUME);
        put("V05AM", "octendclrscpt", "V13", DEFAULT_VOLUME);
        put("V05RAIN", "octrainscpt", "V05RAIN", DEFAULT_VOLUME);
        put("V16", "novclrscpt", "V16", DEFAULT_VOLUME);
        put("V16RAIN", "novrainscpt", "V16RAIN", DEFAULT_VOLUME);
        put("V12BASIC", "DECCLRSCPT", "V13", DEFAULT_VOLUME);
        // March (VW3BASIC, MARCLRSCPT) has no sound in the demo.

        // Canvas Cycle demo (demos/canvascycle/scenes.js): single pictures, each its sound and
        // volume. V01 writes "maxVolumd", which the demo does not read: it plays at 0.5.
        single("V08AM", CANVAS_AUDIO, "V08", 0.25f); // Jungle Waterfall - Morning
        single("V29", CANVAS_AUDIO, "V29", DEFAULT_VOLUME); // Seascape - Day
        single("V19", CANVAS_AUDIO, "V19", DEFAULT_VOLUME); // Mountain Stream - Morning
        single("V26SNOW", CANVAS_AUDIO, "V05RAIN", 0.25f); // Winter Forest - Snow
        single("V08RAIN", CANVAS_AUDIO, "V08RAIN", DEFAULT_VOLUME); // Jungle Waterfall - Rain
        single("V14", CANVAS_AUDIO, "V14", 1.0f); // Mountain Storm - Day
        single("V30", CANVAS_AUDIO, "V30", 0.25f); // Deep Forest - Day
        single("V04", CANVAS_AUDIO, "V04", DEFAULT_VOLUME); // Highland Ruins - Rain
        single("V07", CANVAS_AUDIO, "V07", DEFAULT_VOLUME); // Rough Seas - Day
        single("V20", CANVAS_AUDIO, "V20", DEFAULT_VOLUME); // Crystal Caves - Day
        single("V05RAIN", CANVAS_AUDIO, "V05RAIN", DEFAULT_VOLUME); // Haunted Castle Ruins - Rain
        single("V08PM", CANVAS_AUDIO, "V08", 0.25f); // Jungle Waterfall - Night
        single("V16RAIN", CANVAS_AUDIO, "V16RAIN", DEFAULT_VOLUME); // Mirror Pond - Rain
        single("V19AURA", CANVAS_AUDIO, "V19", DEFAULT_VOLUME); // Mountain Stream - Night
        single("CORAL", CANVAS_AUDIO, "CORAL", 0.25f); // Aquarius - Day
        single("V15", CANVAS_AUDIO, "V15", DEFAULT_VOLUME); // Harbor Town - Night
        single("V30RAIN", CANVAS_AUDIO, "V30RAIN", DEFAULT_VOLUME); // Deep Forest - Rain
        single("V02", CANVAS_AUDIO, "V02", 1.0f); // Mountain Fortress - Dusk
        single("V28", CANVAS_AUDIO, "V28", DEFAULT_VOLUME); // Water City Gates - Fog
        single("V29PM", CANVAS_AUDIO, "V29", DEFAULT_VOLUME); // Seascape - Sunset
        single("V16", CANVAS_AUDIO, "V16", DEFAULT_VOLUME); // Mirror Pond - Morning
        single("V01", CANVAS_AUDIO, "V29", DEFAULT_VOLUME); // Island Fires - Dusk
        single("V09", CANVAS_AUDIO, "V09", 1.0f); // Forest Edge - Day
        single("V16PM", CANVAS_AUDIO, "V16", DEFAULT_VOLUME); // Mirror Pond - Afternoon
        single("V08", CANVAS_AUDIO, "V08", 0.25f); // Jungle Waterfall - Afternoon
        single("V03", CANVAS_AUDIO, "V03", DEFAULT_VOLUME); // Swamp Cathedral - Day
        single("V05HAUNT", CANVAS_AUDIO, "V05HAUNT", DEFAULT_VOLUME); // Haunted Castle Ruins - Night
        single("V10", CANVAS_AUDIO, "V10", DEFAULT_VOLUME); // Deep Swamp - Day
        single("V11AM", CANVAS_AUDIO, "V02", 1.0f); // Approaching Storm - Day
        single("V13", CANVAS_AUDIO, "V13", DEFAULT_VOLUME); // Pond Ripples - Dawn
        single("V17", CANVAS_AUDIO, "V17", 0.25f); // Ice Wind - Day
        single("V19PM", CANVAS_AUDIO, "V19", DEFAULT_VOLUME); // Mountain Stream - Afternoon
        single("V25HEAT", CANVAS_AUDIO, "V25HEAT", DEFAULT_VOLUME); // Desert Heat Wave - Day
        single("V27", CANVAS_AUDIO, "V25HEAT", DEFAULT_VOLUME); // Magic Marsh Cave - Night
        single("V29FOG", CANVAS_AUDIO, "V29", DEFAULT_VOLUME); // Seascape - Fog
        // Pictures not in Canvas Cycle's list: the Living Worlds demo's sound for the same
        // painting (its scenes.js), then other times of day of that painting given its sound
        // (not from either demo). V12, V18, V21-V24 have none in either demo: silent.
        single("V05AM", WORLDS_AUDIO, "V13", DEFAULT_VOLUME);
        single("V25", WORLDS_AUDIO, "V25HEAT", DEFAULT_VOLUME);
        single("V26", WORLDS_AUDIO, "V13", DEFAULT_VOLUME);
        single("V05PM", WORLDS_AUDIO, "V13", DEFAULT_VOLUME);
        single("V25PM", WORLDS_AUDIO, "V25HEAT", DEFAULT_VOLUME);
        single("V26PM", WORLDS_AUDIO, "V13", DEFAULT_VOLUME);
        single("V26NIGHT", WORLDS_AUDIO, "V13", DEFAULT_VOLUME);
    }

    private static void put(String world, String script, String sound, float volume) {
        SOUNDS.put(world + "/" + script, new Object[] {WORLDS_AUDIO + sound, volume});
    }

    private static void single(String picture, String folder, String sound, float volume) {
        SOUNDS.put(picture, new Object[] {folder + sound, volume});
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService DOWNLOADS = Executors.newSingleThreadExecutor();
    private static final String SHOWN = "nlplus.shown";

    private static java.lang.ref.WeakReference<Object> current =
            new java.lang.ref.WeakReference<>(null);
    private static AudioTrack player;
    /** Hidden: faded out and paused, resumed where it was. */
    private static boolean paused;
    /** Counts what was asked for, so only the newest prepared sound starts. */
    private static int generations;
    private static float target;
    /** Decoded loops kept, so a sound comes back at once (the last few). */
    private static final Map<String, SeamlessLoop.Pcm> DECODED =
            new java.util.LinkedHashMap<String, SeamlessLoop.Pcm>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SeamlessLoop.Pcm> e) {
                    return size() > 3;
                }
            };
    private static final long FADE_IN_MS = 2000;
    private static final long RESUME_MS = 600;
    private static final long FADE_OUT_MS = 500;
    private static String playing;
    private static ValueAnimator fade;
    private static Context appContext;
    private static boolean unlockWatched;
    private static String lastDecision;

    private PaletteCycleSounds() {
    }

    static void install(ClassLoader cl, ConfigStore store) {
        Class<?> engineClass = XposedHelpers.findClass(CLS_ENGINE, cl);
        XposedBridge.hookAllMethods(XposedHelpers.findClass(CLS_CHANGING, cl), "changeImage",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object engine = param.args[0];
                        MAIN.post(Hooks.guard("palette cycle sound",
                                () -> update(engine, store)));
                    }
                });
        XposedHelpers.findAndHookMethod(engineClass, "onVisibilityChanged", boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object engine = param.thisObject;
                        store.reloadIfChanged();
                        XposedHelpers.setAdditionalInstanceField(engine, SHOWN, param.args[0]);
                        if (Boolean.TRUE.equals(param.args[0]) && !isPreview(engine)) {
                            current = new java.lang.ref.WeakReference<>(engine);
                        }
                        Hooks.guard("palette cycle sound", () -> update(engine, store)).run();
                    }
                });
        XposedHelpers.findAndHookMethod(engineClass, "onDestroy", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                XposedHelpers.setAdditionalInstanceField(param.thisObject, SHOWN, false);
                if (current.get() == param.thisObject) {
                    Hooks.guard("palette cycle sound", () -> release()).run();
                }
            }
        });
        Hooks.log("palette cycle sounds: ready");
    }

    private static boolean isPreview(Object engine) {
        try {
            return (Boolean) XposedHelpers.callMethod(engine, "isPreview");
        } catch (Throwable t) {
            return true;
        }
    }

    /** The sound for what the current engine shows, where it is seen now (main thread). */
    private static void update(Object engine, ConfigStore store) {
        if (current.get() != engine) {
            return; // Another engine, or none shown yet: this one is not heard.
        }
        if (appContext == null) {
            // The engine is an inner class of Palette Cycle's wallpaper service.
            appContext = ((Context) XposedHelpers.getSurroundingThis(engine))
                    .getApplicationContext();
        }
        watchUnlock(engine, store);
        KeyguardManager keyguard = appContext.getSystemService(KeyguardManager.class);
        boolean visible = Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(engine,
                SHOWN));
        boolean locked = keyguard != null && keyguard.isKeyguardLocked();
        boolean seen = visible && !isPreview(engine) && !locked && HomeShown.get();
        Object[] sound = seen && store.get().paletteCycleSounds ? soundOf(engine) : null;
        String decision = (sound == null ? "silent" : "plays " + sound[0]) + " (visible "
                + visible + ", locked " + locked + ", launcher " + HomeShown.get() + ")";
        if (!decision.equals(lastDecision)) {
            lastDecision = decision;
            Hooks.log("palette cycle sound: " + decision);
        }
        if (sound == null) {
            if (seen || !store.get().paletteCycleSounds) {
                release(); // No sound for what shows, or turned off.
            } else {
                silence(); // Hidden: paused, resumed where it was.
            }
            return;
        }
        play((String) sound[0], (Float) sound[1]);
    }

    /** Leaving the lock screen does not change the wallpaper's visibility: unlocking does. */
    private static void watchUnlock(Object engine, ConfigStore store) {
        if (unlockWatched) {
            return;
        }
        unlockWatched = true;
        HomeShown.listen(appContext, () -> {
            Object shown = current.get();
            if (shown != null) {
                update(shown, store);
            }
        });
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        appContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                Object shown = current.get();
                if (shown != null) {
                    Hooks.guard("palette cycle unlock", () -> update(shown, store)).run();
                }
            }
        }, filter);
    }

    private static Object[] soundOf(Object engine) {
        Object image = XposedHelpers.getObjectField(engine, "currentImage");
        if (image == null) {
            return null;
        }
        String picture = (String) XposedHelpers.getObjectField(image, "imageId");
        if (XposedHelpers.getBooleanField(image, "isTimeline")) {
            return SOUNDS.get(picture + "/" + XposedHelpers.getObjectField(image, "script"));
        }
        return SOUNDS.get(picture);
    }

    private static void play(String sound, float volume) {
        target = volume;
        if (sound.equals(playing)) {
            if (player != null && paused) {
                player.play();
                paused = false;
                Hooks.log("palette cycle sound: resumed " + sound);
                fadeTo(volume, RESUME_MS);
            } else if (player != null) {
                fadeTo(volume, FADE_IN_MS);
            }
            return; // Playing, or still being prepared.
        }
        release();
        playing = sound;
        int generation = ++generations;
        SeamlessLoop.Pcm cached = DECODED.get(sound);
        if (cached != null) {
            start(sound, cached, generation);
            return;
        }
        // demos/<demo>/audio/<name>: kept per demo, as both have files of the same names.
        String[] parts = sound.split("/");
        File file = new File(new File(appContext.getCacheDir(), "nlplus-sounds"),
                parts[parts.length - 3] + "-" + parts[parts.length - 1] + ".mp3");
        DOWNLOADS.execute(Hooks.guard("palette cycle sound download", () -> {
            if (!file.exists() && !download(sound, file)) {
                return;
            }
            SeamlessLoop.Pcm pcm;
            try {
                pcm = SeamlessLoop.prepare(file);
            } catch (Throwable t) {
                Hooks.log("palette cycle: sound " + sound + " not decoded: " + t);
                file.delete(); // A broken download: fetched again next time.
                return;
            }
            Hooks.log("palette cycle: sound " + sound + ": " + pcm.report);
            MAIN.post(Hooks.guard("palette cycle sound start", () -> {
                DECODED.put(sound, pcm);
                start(sound, pcm, generation);
            }));
        }));
    }

    private static boolean download(String sound, File file) {
        File dir = file.getParentFile();
        if (dir != null) {
            dir.mkdirs();
        }
        File part = new File(file.getPath() + ".part");
        java.net.URLConnection connection;
        try {
            connection = new URL(sound + ".mp3").openConnection();
            // One download at a time: a stalled server must not hold back every later sound.
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
        } catch (Throwable t) {
            Hooks.log("palette cycle: sound " + sound + " not downloaded: " + t);
            return false;
        }
        try (InputStream in = connection.getInputStream();
             FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        } catch (Throwable t) {
            Hooks.log("palette cycle: sound " + sound + " not downloaded: " + t);
            part.delete();
            return false;
        }
        return part.renameTo(file);
    }

    /** Starts {@code pcm} if it is still the newest asked for (only one plays). */
    private static void start(String sound, SeamlessLoop.Pcm pcm, int generation) {
        if (generation != generations || !sound.equals(playing) || player != null) {
            return; // Something newer was asked for meanwhile.
        }
        try {
            AudioTrack track = SeamlessLoop.track(pcm);
            player = track;
            paused = false;
            lastVolume = 0;
            track.play();
            Hooks.log("palette cycle sound: started " + sound);
            fadeTo(target, FADE_IN_MS);
            Object shown = current.get();
            if (shown != null && !HomeShown.get()) {
                silence(); // Left while it was prepared.
            }
        } catch (Throwable t) {
            Hooks.log("palette cycle: sound " + sound + " not played: " + t);
            playing = null;
        }
    }

    /** Fades out (half a second) and pauses the sound, kept to resume where it was. */
    private static void silence() {
        AudioTrack track = player;
        if (track == null || paused) {
            return;
        }
        paused = true;
        Hooks.log("palette cycle sound: paused " + playing);
        fadeOut(track, true, () -> {
            if (paused && player == track) {
                track.pause();
            }
        });
    }

    /** Fades out and frees the sound (another sound, none, or turned off). */
    private static void release() {
        AudioTrack track = player;
        String was = playing;
        player = null;
        playing = null;
        paused = false;
        generations++;
        if (track != null) {
            Hooks.log("palette cycle sound: released " + was);
            fadeOut(track, false, () -> {
                track.stop();
                track.release();
            });
        }
    }

    /**
     * Fades {@code track} out, then runs {@code then}. A pause's fade-out is the current
     * fade, which coming back cancels; a released sound's runs on its own to its end.
     */
    private static void fadeOut(AudioTrack track, boolean cancellable, Runnable then) {
        if (fade != null) {
            fade.cancel();
            fade = null;
        }
        ValueAnimator out = ValueAnimator.ofFloat(lastVolume, 0f).setDuration(FADE_OUT_MS);
        if (!cancellable) {
            lastVolume = 0; // The next sound starts from silence.
        }
        out.addUpdateListener(a -> {
            float v = (Float) a.getAnimatedValue();
            track.setVolume(v);
            if (cancellable) {
                lastVolume = v; // A resume fades up from here.
            }
        });
        out.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                Hooks.guard("palette cycle sound fade", then).run();
            }
        });
        if (cancellable) {
            fade = out;
        }
        out.start();
    }

    /** The playing sound's volume, as last set by a fade (main thread). */
    private static float lastVolume;

    /** Fades the playing sound to {@code volume} over {@code ms}. */
    private static void fadeTo(float volume, long ms) {
        AudioTrack mp = player;
        if (mp == null) {
            return;
        }
        if (fade != null) {
            fade.cancel();
        }
        fade = ValueAnimator.ofFloat(lastVolume, volume).setDuration(ms);
        fade.addUpdateListener(a -> {
            float v = (Float) a.getAnimatedValue();
            lastVolume = v;
            if (player == mp) {
                mp.setVolume(v);
            }
        });
        fade.start();
    }
}
