package com.dressence.nothinglauncherplus.hook;

import com.dressence.nothinglauncherplus.Config;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XSharedPreferences;

/** The module configuration as seen from the launcher process. */
final class ConfigStore {

    private final XSharedPreferences prefs =
            new XSharedPreferences(Config.MODULE_PKG, Config.PREFS_NAME);
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
    private volatile Config config = Config.read(prefs);

    Config get() {
        return config;
    }

    /** Cheap file-stat check; used on paths that are about to re-read the config anyway. */
    void reloadIfChanged() {
        if (prefs.hasFileChanged()) {
            prefs.reload();
            config = Config.read(prefs);
        }
    }

    /** Reloads and lets every feature refresh its UI. Call on the main thread. */
    void reloadAndNotify() {
        prefs.reload();
        config = Config.read(prefs);
        for (Runnable listener : changeListeners) {
            try {
                listener.run();
            } catch (Throwable t) {
                Hooks.log("refresh failed: " + t);
            }
        }
    }

    void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }
}
