package com.dressence.nothinglauncherplus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Runs after this module is updated. LSPosed loads a module's new code whenever a hooked app
 * starts, so restarting Nothing Launcher (root) applies the update straight away, no reboot.
 *
 * <p>An update can also land on a memory card used as internal storage (adopted storage),
 * whatever the APK asks for (the "force allow apps on external" developer option, or an
 * installer that picks the volume): that card is mounted late at boot, after LSPosed loads its
 * modules and after Android checks its apps, so the module was not loaded and Android deleted
 * its data (settings, folders) as belonging to a missing app. The module moves itself back to
 * the phone's own storage then (root).
 */
public final class UpdateReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            return;
        }
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                restartLauncher();
                if (onMemoryCard(context)) {
                    android.util.Log.i("NothingLauncher+", "updated onto the memory card ("
                            + context.getApplicationInfo().sourceDir + "): moving to internal");
                    // Last: the move stops this app.
                    boolean asked = moveToInternalStorage(context.getPackageName());
                    android.util.Log.i("NothingLauncher+", "move to internal: "
                            + (asked ? "done" : "failed (no root?)"));
                } else {
                    android.util.Log.i("NothingLauncher+", "updated on internal storage");
                }
            } finally {
                result.finish();
            }
        }).start();
    }

    /** True if this app is installed on adopted storage (a memory card), not the phone's. */
    public static boolean onMemoryCard(Context context) {
        String apk = context.getApplicationInfo().sourceDir;
        return apk != null && apk.startsWith("/mnt/expand/");
    }

    /**
     * Moves this app to the phone's own storage, waiting for the move. The move force-stops
     * this app, which may end this process before the command returns; the command itself
     * runs as root and finishes regardless. Needs root; false if it could not be asked.
     */
    public static boolean moveToInternalStorage(String pkg) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "pm move-package " + pkg + " internal"});
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** The "Force allow apps on external" developer option: installs ignore internalOnly. */
    public static boolean externalForced(Context context) {
        return android.provider.Settings.Global.getInt(context.getContentResolver(),
                "force_allow_on_external", 0) != 0;
    }

    /** Force-stops Nothing Launcher; Android brings home straight back. Needs root. */
    public static boolean restartLauncher() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "am force-stop " + Config.LAUNCHER_PKG});
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
