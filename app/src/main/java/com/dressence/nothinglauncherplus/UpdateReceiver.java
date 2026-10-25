package com.dressence.nothinglauncherplus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Runs after this module is updated. LSPosed loads a module's new code whenever a hooked app
 * starts, so restarting Nothing Launcher (root) applies the update straight away, no reboot.
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
            } finally {
                result.finish();
            }
        }).start();
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
