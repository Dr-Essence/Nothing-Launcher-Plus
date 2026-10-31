package com.dressence.nothinglauncherplus.ui;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Collects what fixing a screen problem needs, without a terminal. After a chosen wait (time
 * to set the problem up: lock the phone, turn it, open the screen that misbehaves) a root
 * script records the screen and, every few seconds for the chosen time, takes a screenshot,
 * the screen composer's (SurfaceFlinger) layers, the window manager's windows and the view
 * layout of every visible window (System UI's included); logcat runs throughout. At the end it
 * adds the activities, System UI's own state, the input and display state and the LSPosed log,
 * and packs it all into one file in Download. The script runs on its own as root, so it
 * carries on while this app is in the background or closed; it can be stopped early, and a
 * notification says when the file is ready.
 */
final class DebugReport {

    /** Created to end a running capture early; the script removes it. */
    private static final String STOP_FILE = "/data/local/tmp/nlplus-report.stop";

    private static final String SCRIPT = ""
            + "DELAY=%1$d\n"
            + "DUR=%2$d\n"
            + "STOP=" + STOP_FILE + "\n"
            + "rm -f $STOP\n"
            + "T=$(date +%%Y%%m%%d-%%H%%M%%S)\n"
            + "N=NLPlus-report-$T\n"
            + "D=/sdcard/Download/$N\n"
            + "mkdir -p $D || exit 1\n"
            // logcat from what is in its buffer now to the end of the capture.
            + "logcat -b all -v threadtime > $D/logcat.txt 2>&1 &\n"
            + "LOG=$!\n"
            + "waited=0\n"
            + "while [ $waited -lt $DELAY ] && [ ! -e $STOP ]; do sleep 1;"
            + " waited=$((waited+1)); done\n"
            // The screen, in parts of at most 3 minutes (screenrecord's limit).
            + "( n=1; left=$DUR; while [ $left -gt 0 ] && [ ! -e $STOP ]; do t=$left;"
            + " [ $t -gt 180 ] && t=180; screenrecord --time-limit $t $D/video$n.mp4"
            + " >/dev/null 2>&1; left=$((left-t)); n=$((n+1)); done ) &\n"
            + "REC=$!\n"
            + "STEP=5; [ $DUR -gt 60 ] && STEP=10; [ $DUR -gt 300 ] && STEP=30\n"
            + "END=$(( $(date +%%s) + DUR ))\n"
            + "i=1\n"
            + "while :; do\n"
            + "  date > $D/time$i.txt\n"
            + "  dumpsys power | grep -E 'mWakefulness=' >> $D/time$i.txt 2>&1\n"
            + "  screencap -p $D/screen$i.png\n"
            + "  dumpsys SurfaceFlinger > $D/sf$i.txt 2>&1\n"
            + "  dumpsys SurfaceFlinger --frontend > $D/sf_frontend$i.txt 2>&1\n"
            + "  dumpsys window displays > $D/window_displays$i.txt 2>&1\n"
            + "  dumpsys window windows > $D/window_windows$i.txt 2>&1\n"
            + "  timeout 20 cmd window dump-visible-window-views > $D/views$i.zip 2>/dev/null\n"
            + "  i=$((i+1))\n"
            + "  [ $(date +%%s) -ge $END ] || [ -e $STOP ] && break\n"
            + "  s=0; while [ $s -lt $STEP ] && [ ! -e $STOP ]; do sleep 1; s=$((s+1)); done\n"
            + "done\n"
            // Ends a recording still running (stopped early) so its file is finished.
            + "pkill -INT screenrecord 2>/dev/null\n"
            + "wait $REC 2>/dev/null\n"
            + "dumpsys activity activities > $D/activities.txt 2>&1\n"
            + "dumpsys activity service com.android.systemui > $D/systemui.txt 2>&1\n"
            + "dumpsys input > $D/input.txt 2>&1\n"
            + "dumpsys display > $D/display.txt 2>&1\n"
            + "settings list system | grep -i rotation > $D/rotation_settings.txt 2>&1\n"
            + "getprop ro.build.fingerprint > $D/build.txt 2>&1\n"
            + "kill $LOG 2>/dev/null\n"
            + "cp -r /data/adb/lspd/log $D/lsposed 2>/dev/null\n"
            + "rm -f $STOP\n"
            + "cd /sdcard/Download && tar -czf $N.tar.gz $N && rm -rf $N && N=$N.tar.gz\n"
            + "cmd notification post -S bigtext -t 'Nothing Launcher+' nlplus_report"
            + " \"Debug report saved: Download/$N\" >/dev/null 2>&1\n";

    private DebugReport() {
    }

    /**
     * Starts a capture in the background as root: it waits {@code delaySeconds}, then
     * captures for {@code seconds}. Returns null once it has started, else why it could not
     * (no root). Call off the main thread: root may ask for permission first.
     */
    static String start(Context context, int delaySeconds, int seconds) {
        try {
            File script = new File(context.getCacheDir(), "debug-report.sh");
            try (FileOutputStream out = new FileOutputStream(script)) {
                out.write(String.format(java.util.Locale.ROOT, SCRIPT, delaySeconds, seconds)
                        .getBytes(StandardCharsets.UTF_8));
            }
            // A session of its own, so the capture outlives this app and the su call.
            return su("setsid sh " + script.getAbsolutePath() + " </dev/null >/dev/null 2>&1 &");
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : e.toString();
        }
    }

    /** Ends a running capture now; it still saves what it has. Null once asked, else why not. */
    static String stop() {
        try {
            return su("touch " + STOP_FILE);
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : e.toString();
        }
    }

    private static String su(String command) throws Exception {
        Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        int code = p.waitFor();
        return code == 0 ? null : output.isEmpty() ? "Root access was refused" : output;
    }
}
