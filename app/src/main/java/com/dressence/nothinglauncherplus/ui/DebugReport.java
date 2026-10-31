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
    /** Left by the script once the report is saved ({@link CaptureIndicator} waits for it). */
    /** The CMF Phone 1 SurfaceFlinger build the report's uprobe offsets were read from. */
    private static final String SF_SHA256 =
            "825285c27d7eae599f9bc955a8fd228a44f4b902325bad1182b22e5a8a06815a";
    static final String DONE_FILE = "/data/local/tmp/nlplus-report.done";

    private static final String SCRIPT = ""
            + "DELAY=%1$d\n"
            + "DUR=%2$d\n"
            + "STOP=" + STOP_FILE + "\n"
            + "rm -f $STOP " + DONE_FILE + "\n"
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
            // The screen, in parts of at most 3 minutes (screenrecord's limit), at 2 Mbit/s
            // (about 15 MB a minute) so a report stays small enough to send.
            + "( n=1; left=$DUR; while [ $left -gt 0 ] && [ ! -e $STOP ]; do t=$left;"
            + " [ $t -gt 180 ] && t=180;"
            + " screenrecord --bit-rate 2000000 --time-limit $t $D/video$n.mp4"
            + " >/dev/null 2>&1; left=$((left-t)); n=$((n+1)); done ) &\n"
            + "REC=$!\n"
            // SurfaceFlinger's layer trace for the first 8 s of the capture: each layer's
            // visible region, composition and HWC type per display, frame by frame (to see
            // where the fingerprint dim layer leaves the composer's list in landscape).
            + "printf '%%s\\n' 'buffers { size_kb: 65536 fill_policy: RING_BUFFER }'"
            + " 'data_sources { config { name: \"android.surfaceflinger.layers\"'"
            + " 'surfaceflinger_layers_config { mode: MODE_ACTIVE trace_flags: TRACE_FLAG_INPUT'"
            + " 'trace_flags: TRACE_FLAG_COMPOSITION trace_flags: TRACE_FLAG_HWC'"
            + " 'trace_flags: TRACE_FLAG_BUFFERS trace_flags: TRACE_FLAG_VIRTUAL_DISPLAYS } } }'"
            + " 'duration_ms: 8000' > /data/misc/perfetto-configs/nlplus.pbtxt\n"
            + "( perfetto --txt -c /data/misc/perfetto-configs/nlplus.pbtxt"
            + " -o /data/misc/perfetto-traces/nlplus.pftrace > $D/perfetto.txt 2>&1;"
            + " cp /data/misc/perfetto-traces/nlplus.pftrace $D/layers.pftrace 2>/dev/null ) &\n"
            + "TRACE=$!\n"
            // Fingerprint (landscape): kernel uprobes, read-only breakpoints that let
            // SurfaceFlinger run unchanged, at each check of CompositionEngine's
            // ensureOutputLayerIfVisible in this phone's build (its SHA-256 must match, as the
            // offsets are this build's): which check drops Nothing's dim layer. Only layers drawn
            // with alpha under 1 (the dim layer) are recorded, by their alpha.
            + "UP=$D/uprobes.txt; SFB=/system/bin/surfaceflinger\n"
            + "TR=/sys/kernel/tracing; [ -e $TR/uprobe_events ] || TR=/sys/kernel/debug/tracing\n"
            + "UI=$TR/instances/nlplus; UE=\"inc pass vis rect open draw\"\n"
            + "up_off() { { echo 0 > $UI/tracing_on; for e in $UE; do"
            + " echo 0 > $UI/events/nlplus/$e/enable; done; rmdir $UI; for e in $UE; do"
            + " echo \"-:nlplus/$e\" >> $TR/uprobe_events; done; } 2>/dev/null; }\n"
            + "up_off\n"
            + "if [ \"$(sha256sum $SFB 2>/dev/null | cut -d' ' -f1)\" = \"" + SF_SHA256 + "\" ]"
            + " && [ -w $TR/uprobe_events ]; then\n"
            + "  S=\"a=+664(%%x22):x16\"\n"
            + "  for d in \"inc $SFB:0x4fd844 out=%%x19 a=+664(%%x0):x16 ls=+12(%%x0):u32"
            + " ti=+16(%%x0):u8 ra=+8(%%sp):x64\""
            + " \"pass $SFB:0x4032b4 out=%%x21 a=+664(%%x0):x16 vis=+20(%%x0):u8 opq=+21(%%x0):u8\""
            + " \"vis $SFB:0x4032c4 out=%%x21 $S l=+220(%%x22):x32 t=+224(%%x22):x32"
            + " r=+228(%%x22):x32 b=+232(%%x22):x32 m0=+32(%%x22):x32 m1=+36(%%x22):x32"
            + " m3=+44(%%x22):x32 m4=+48(%%x22):x32 m6=+56(%%x22):x32 m7=+60(%%x22):x32"
            + " sh=+316(%%x22):x32\""
            + " \"rect $SFB:0x4033f0 out=%%x21 $S\" \"open $SFB:0x4034e8 out=%%x21 $S\""
            + " \"draw $SFB:0x4038d0 out=%%x21 $S\"; do echo \"p:nlplus/$d\" >>"
            + " $TR/uprobe_events 2>>$UP || echo \"define failed: $d\" >> $UP; done\n"
            + "  mkdir $UI 2>>$UP; echo 4096 > $UI/buffer_size_kb\n"
            + "  for e in $UE; do echo 'a != 0x3c00' > $UI/events/nlplus/$e/filter 2>>$UP;"
            + " echo 1 > $UI/events/nlplus/$e/enable 2>>$UP; done\n"
            + "  echo 1 > $UI/tracing_on; echo \"probes on\" >> $UP\n"
            + "  grep surfaceflinger /proc/$(pidof surfaceflinger)/maps >> $UP\n"
            + "else echo \"probes off: uprobes unavailable or another surfaceflinger build\" >> $UP;"
            + " fi\n"
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
            // Every view on screen with its bounds, the lock screen and always-on display too
            // (cmd window dump-visible-window-views failed here: "Failed transaction").
            + "  timeout 20 uiautomator dump $D/ui$i.xml >/dev/null 2>&1\n"
            // What the overlay times for FPS: the focused window, the layer picked for it (as
            // OverlayService picks it) and that layer's frame times, raw.
            + "  { W=$(dumpsys window | grep mCurrentFocus | grep -v =null | head -1"
            + " | sed 's/.* u0 //; s/}.*//'); [ -z \"$W\" ] && W=NotificationShade;"
            + " P=${W%%%%/*}; LS=$(dumpsys SurfaceFlinger --list);"
            + " L=$(echo \"$LS\" | grep -m1 \"SurfaceView\\[$P/.*(BLAST)\");"
            + " [ -z \"$L\" ] && L=$(echo \"$LS\" | awk -v w=\"$W#\""
            + " 'index($0,w)==1{print;exit}');"
            + " echo \"focus: $W\"; echo \"layer: $L\"; echo \"$LS\" | grep -F \"$P\";"
            + " dumpsys SurfaceFlinger --latency \"$L\"; } > $D/fps$i.txt 2>&1\n"
            + "  i=$((i+1))\n"
            + "  [ $(date +%%s) -ge $END ] || [ -e $STOP ] && break\n"
            + "  s=0; while [ $s -lt $STEP ] && [ ! -e $STOP ]; do sleep 1; s=$((s+1)); done\n"
            + "done\n"
            // Ends a recording still running (stopped early) so its file is finished.
            + "pkill -INT screenrecord 2>/dev/null\n"
            + "[ -d $UI ] && { echo 0 > $UI/tracing_on; cat $UI/trace >> $UP; }\n"
            + "up_off\n"
            + "wait $TRACE 2>/dev/null\n"
            + "wait $REC 2>/dev/null\n"
            + "dumpsys activity activities > $D/activities.txt 2>&1\n"
            + "dumpsys activity service com.android.systemui > $D/systemui.txt 2>&1\n"
            + "dumpsys input > $D/input.txt 2>&1\n"
            + "dumpsys display > $D/display.txt 2>&1\n"
            // `settings list` failed here ("Failed transaction"): the settings files are read
            // as well, through Android's own binary-XML converter.
            + "for t in system secure global; do settings list $t > $D/settings_$t.txt 2>&1;"
            + " f=/data/system/users/0/settings_$t.xml; abx2xml $f - >"
            + " $D/settings_$t.xml 2>&1 || cp $f $D/settings_$t.xml; done\n"
            + "dumpsys game > $D/game.txt 2>&1\n"
            + "dumpsys battery > $D/battery.txt 2>&1\n"
            // Where this phone keeps its clocks and temperatures, and who may read them.
            + "H=$D/hardware.txt\n"
            + "getenforce > $H 2>&1\n"
            + "for p in /sys/devices/system/cpu/cpufreq/policy*; do echo \"== $p\"; ls -lZ $p;"
            + " for f in related_cpus scaling_cur_freq scaling_min_freq scaling_max_freq"
            + " scaling_available_frequencies scaling_governor; do echo \"$f: $(cat $p/$f)\";"
            + " done; done >> $H 2>&1\n"
            + "for d in /sys/class/devfreq/*; do echo \"== $d\"; ls -lZ $d/; for f in name"
            + " cur_freq available_frequencies governor; do echo \"$f: $(cat $d/$f)\"; done;"
            + " done >> $H 2>&1\n"
            + "for g in /proc/gpufreqv2 /proc/gpufreq /sys/kernel/ged/hal /proc/mali; do"
            + " echo \"== $g\"; ls -lZ $g; for f in $g/*; do [ -f $f ] && echo \"-- $f\" &&"
            + " head -c 2000 $f; done; done >> $H 2>&1\n"
            + "for z in /sys/class/thermal/thermal_zone*; do echo \"$z $(cat $z/type)"
            + " $(cat $z/temp) $(ls -Z $z/temp)\"; done >> $H 2>&1\n"
            + "ls -lZ /sys/class/power_supply/battery/ >> $H 2>&1\n"
            // Each core's clock from every source this phone has, three times (Scene shows a
            // different clock per core of a cluster; scaling_cur_freq is the cluster's).
            + "for k in 1 2 3; do for c in 0 1 2 3 4 5 6 7; do d=/sys/devices/system/cpu/cpu$c;"
            + " echo \"cpu$c cpuinfo_cur=$(cat $d/cpufreq/cpuinfo_cur_freq 2>&1)"
            + " scaling_cur=$(cat $d/cpufreq/scaling_cur_freq 2>&1)\"; done; sleep 0.3;"
            + " done >> $H 2>&1\n"
            + "ls -laZ /proc/cpufreq /sys/kernel/cpufreq* /proc/cpu_loading /sys/devices/system/"
            + "cpu/cpufreq /sys/kernel/fpsgo/fbt /proc/mtk_scheduler >> $H 2>&1\n"
            + "head -c 3000 /proc/cpu_loading >> $H 2>&1\n"
            // Each core's CPU cycles as the overlay reads them (its real clock), raw.
            + "{ command -v simpleperf; timeout 5 simpleperf stat -a --per-core -e cpu-cycles"
            + " --duration 0.5; } >> $H 2>&1\n"
            // Scene (com.omarea.vtools), to see where its overlay reads each core's clock.
            + "for a in $(pm path com.omarea.vtools | sed 's/^package://'); do"
            + " cp $a $D/scene-$(basename $a); done 2>/dev/null\n"
            // SurfaceFlinger and the libraries it loads (from its memory map), to find why
            // it leaves Nothing's fingerprint dim layer out of the composer's list in
            // landscape.
            + "mkdir -p $D/surfaceflinger; cp /system/bin/surfaceflinger $D/surfaceflinger/;"
            + " for l in $(grep -o '/[^ ]*\\.so' /proc/$(pidof surfaceflinger)/maps | sort -u);"
            + " do case $l in *surfaceflinger*|*renderengine*|*compositionengine*|*gui*|*ui.so*"
            + "|*hwc*|*composer*|*mtk*|*libnt*|*nothing*) cp $l $D/surfaceflinger/ 2>/dev/null;;"
            + " esac; done\n"
            + "grep -o '/[^ ]*\\.so' /proc/$(pidof surfaceflinger)/maps | sort -u >"
            + " $D/surfaceflinger/maps.txt 2>&1\n"
            // Nothing's Game Bar, to fit the module's game options into it.
            + "for a in $(pm path com.nothing.ntgamedashboard | sed 's/^package://'); do"
            + " cp $a $D/ntgamedashboard-$(basename $a); done 2>/dev/null\n"
            // PerfMTK (a Magisk module) and its settings, to set CPU speeds through it: its
            // scripts and configs (files under 1 MB), its files' list, and its data folder.
            + "for m in /data/adb/modules/*; do grep -qi perfmtk $m/module.prop 2>/dev/null"
            + " || continue; mkdir -p $D/perfmtk; ls -laR $m > $D/perfmtk/files.txt 2>&1;"
            + " (cd $m && find . -type f -size -1024k | while read f; do"
            + " mkdir -p \"$D/perfmtk/$(dirname \"$f\")\"; cp \"$f\" \"$D/perfmtk/$f\"; done);"
            + " done 2>/dev/null\n"
            + "{ timeout 5 perfmtk --help; timeout 5 perfmtk --caps; timeout 5 perfmtk -j; } >"
            + " $D/perfmtk_commands.txt 2>&1"
            + " </dev/null\n"
            + "ls -laR /data/adb/perfmtk* /sdcard/PerfMTK* /data/local/tmp/*perf* >"
            + " $D/perfmtk_data.txt 2>&1\n"
            + "getprop ro.build.fingerprint > $D/build.txt 2>&1\n"
            + "kill $LOG 2>/dev/null\n"
            + "cp -r /data/adb/lspd/log $D/lsposed 2>/dev/null\n"
            + "rm -f $STOP\n"
            + "cd /sdcard/Download && tar -czf $N.tar.gz $N && rm -rf $N && N=$N.tar.gz\n"
            + "touch " + DONE_FILE + "\n"
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
