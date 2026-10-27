package com.dressence.nothinglauncherplus.xposed;

import com.dressence.nothinglauncherplus.Config;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XSharedPreferences;

/** The module's settings as a hooked app sees them (read through XSharedPreferences). */
public final class ConfigStore {

    private final XSharedPreferences prefs =
            new XSharedPreferences(Config.MODULE_PKG, Config.PREFS_NAME);
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
    private volatile Config config = Config.read(prefs);

    public Config get() {
        return config;
    }

    /** Re-reads the settings if their file changed (a file stat): not for every frame. */
    public void reloadIfChanged() {
        if (prefs.hasFileChanged()) {
            prefs.reload();
            config = Config.read(prefs);
        }
    }

    /** Re-reads the settings and lets every feature refresh. Call on the main thread. */
    public void reloadAndNotify() {
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

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }
}
