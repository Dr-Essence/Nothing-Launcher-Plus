package com.dressence.nothinglauncherplus.perf;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Process;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.ui.SettingsStore;

/**
 * Acts on the module's options in Nothing's Game Bar ({@link GameBar}): the performance
 * overlay on or off, the next PerfMTK profile, PerfMTK's thermal limits on or off, saving the
 * new state so the Game Bar's labels show it. Only the system (the Game Bar runs as it) is
 * listened to, so no other app can switch these.
 */
public final class GameBarReceiver extends BroadcastReceiver {

    static final String ACTION = "com.dressence.nothinglauncherplus.GAME_BAR";
    static final String EXTRA_KEY = "key";
    private static final String[] PROFILES = {"performance", "balanced", "powersave"};

    @Override
    public void onReceive(Context context, Intent intent) {
        // Who sent it is known from Android 14 on; before that nothing is acted on.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                || getSentFromUid() != Process.SYSTEM_UID || !Device.isCmfPhone1()) {
            return;
        }
        String key = intent.getStringExtra(EXTRA_KEY);
        SettingsStore store = new SettingsStore(context);
        Config config = store.config;
        if (GameBar.KEY_OVERLAY.equals(key)) {
            config.perfOverlay = !config.perfOverlay;
            store.save(context);
            if (config.perfOverlay) {
                OverlayService.start(context);
            } else {
                OverlayService.stop(context);
            }
            return;
        }
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                if (GameBar.KEY_PROFILE.equals(key)) {
                    String next = PROFILES[0];
                    for (int i = 0; i < PROFILES.length; i++) {
                        if (PROFILES[i].equals(config.perfProfile)) {
                            next = PROFILES[(i + 1) % PROFILES.length];
                        }
                    }
                    if (Device.su("perfmtk " + next) == null) {
                        config.perfProfile = next;
                        store.save(context);
                    }
                } else if (GameBar.KEY_THERMAL.equals(key)) {
                    boolean off = !config.thermalOff;
                    if (Device.su("perfmtk --thermal " + (off ? "disable" : "enable")) == null) {
                        config.thermalOff = off;
                        store.save(context);
                    }
                }
            } finally {
                result.finish();
            }
        }, "game bar").start();
    }
}
