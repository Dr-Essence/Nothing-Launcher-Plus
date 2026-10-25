package com.dressence.nothinglauncherplus.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.dressence.nothinglauncherplus.Config;

/** Loads and saves the configuration, and tells the launcher to pick changes up. */
final class SettingsStore {

    final SharedPreferences prefs;
    /** False when LSPosed is not managing the prefs, i.e. the launcher cannot read them. */
    final boolean shared;
    Config config;

    @SuppressWarnings("deprecation")
    SettingsStore(Context context) {
        SharedPreferences p;
        boolean s;
        try {
            // LSPosed (xposedsharedprefs) makes this readable by the hooked launcher.
            p = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_WORLD_READABLE);
            s = true;
        } catch (SecurityException e) {
            p = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE);
            s = false;
        }
        prefs = p;
        shared = s;
        config = Config.read(prefs);
    }

    void reload() {
        config = Config.read(prefs);
    }

    void save(Context context) {
        SharedPreferences.Editor editor = prefs.edit();
        config.write(editor);
        editor.commit();
        context.sendBroadcast(new Intent(Config.ACTION_RELOAD).setPackage(Config.LAUNCHER_PKG));
    }
}
