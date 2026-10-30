package com.dressence.nothinglauncherplus.ui;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Collects what fixing a screen problem needs, without a terminal: after a countdown (time to
 * lock the phone, turn it, hold a finger on the sensor) a root script takes three rounds of
 * screenshots, screen-composer (SurfaceFlinger) and window dumps a few seconds apart, then the
 * activities, logcat and the LSPosed log, and packs them into one file in Download. The script
 * runs on its own as root, so it carries on while this app is in the background or closed;
 * a notification says when the file is ready.
 */
final class DebugReport {

    static final int COUNTDOWN_SECONDS = 15;

    private static final String SCRIPT = ""
            + "T=$(date +%Y%m%d-%H%M%S)\n"
            + "N=NLPlus-report-$T\n"
            + "D=/sdcard/Download/$N\n"
            + "mkdir -p $D || exit 1\n"
            + "sleep " + COUNTDOWN_SECONDS + "\n"
            + "for i in 1 2 3; do\n"
            + "  dumpsys power | grep -E 'mWakefulness=|mHalAutoSuspend' > $D/power$i.txt 2>&1\n"
            + "  screencap -p $D/screen$i.png\n"
            + "  dumpsys SurfaceFlinger > $D/sf$i.txt 2>&1\n"
            + "  dumpsys SurfaceFlinger --frontend > $D/sf_frontend$i.txt 2>&1\n"
            + "  dumpsys window displays > $D/window_displays$i.txt 2>&1\n"
            + "  dumpsys window windows > $D/window_windows$i.txt 2>&1\n"
            + "  [ $i -lt 3 ] && sleep 4\n"
            + "done\n"
            + "dumpsys activity activities > $D/activities.txt 2>&1\n"
            + "settings list system | grep -i rotation > $D/rotation_settings.txt 2>&1\n"
            + "getprop ro.build.fingerprint > $D/build.txt 2>&1\n"
            + "logcat -b all -d > $D/logcat.txt 2>&1\n"
            + "cp -r /data/adb/lspd/log $D/lsposed 2>/dev/null\n"
            + "cd /sdcard/Download && tar -czf $N.tar.gz $N && rm -rf $N && N=$N.tar.gz\n"
            + "cmd notification post -S bigtext -t 'Nothing Launcher+' nlplus_report"
            + " \"Debug report saved: Download/$N\" >/dev/null 2>&1\n";

    private DebugReport() {
    }

    /**
     * Starts the capture in the background as root; returns null once it has started, else
     * why it could not (no root). Call off the main thread: root may ask for permission first.
     */
    static String start(Context context) {
        try {
            File script = new File(context.getCacheDir(), "debug-report.sh");
            try (FileOutputStream out = new FileOutputStream(script)) {
                out.write(SCRIPT.getBytes(StandardCharsets.UTF_8));
            }
            // A session of its own, so the capture outlives this app and the su call.
            Process p = new ProcessBuilder("su", "-c", "setsid sh " + script.getAbsolutePath()
                    + " </dev/null >/dev/null 2>&1 &").redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            int code = p.waitFor();
            return code == 0 ? null : output.isEmpty() ? "Root access was refused" : output;
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : e.toString();
        }
    }
}
