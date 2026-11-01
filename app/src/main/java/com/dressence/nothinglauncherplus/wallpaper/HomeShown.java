package com.dressence.nothinglauncherplus.wallpaper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Whether Nothing Launcher is on screen (home, app drawer, recents), as the launcher says
 * (Launcher.onResume / onPause, broadcast by the module in the launcher). The wallpapers'
 * sounds play only then: a wallpaper engine's own visibility is not told reliably in every
 * case (an engine for home and one for the lock screen, unlocking), and sounds went on over
 * apps. Read in the wallpaper apps' processes.
 */
final class HomeShown {

    /** Until the launcher says otherwise: a wallpaper starts on the home screen. */
    private static volatile boolean shown = true;
    private static boolean listening;
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private HomeShown() {
    }

    static boolean get() {
        return shown;
    }

    /** Listens once per process; {@code changed} runs on the main thread at each change. */
    static synchronized void listen(Context context, Runnable changed) {
        LISTENERS.add(changed);
        if (listening) {
            return;
        }
        listening = true;
        context.getApplicationContext().registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                boolean now = intent.getBooleanExtra(Config.EXTRA_SHOWN, true);
                if (now != shown) {
                    shown = now;
                    Hooks.log("wallpaper sounds: launcher " + (now ? "shown" : "left"));
                    for (Runnable listener : LISTENERS) {
                        Hooks.guard("home shown", listener).run();
                    }
                }
            }
        }, new IntentFilter(Config.ACTION_HOME_SHOWN), Context.RECEIVER_EXPORTED);
    }
}
