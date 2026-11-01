package com.dressence.nothinglauncherplus.settings;

import android.app.ActivityManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Debug;
import android.view.View;

import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Diagnostic, before the task manager on Developer options' Running services page is built:
 * which sources Settings' own process can read (it runs as the system user, in SELinux's
 * system_app domain, whose limits are not known here). When the page opens, each source is
 * tried once off the main thread and the result logged: totals (/proc/stat, meminfo,
 * cpufreq, MediaTek's GPU load, thermal zones, /proc/net/dev), other apps' and system
 * processes' /proc/<pid> files, ActivityManager's memory per process, per-app network use
 * (NetworkStatsManager, as Settings' data usage reads it) and the permissions Settings holds.
 * Nothing on the page changes. To go once the task manager is designed from it.
 */
public final class TaskManagerProbe {

    private static final String CLS_VIEW =
            "com.android.settings.applications.RunningProcessesView";
    private static boolean probed;

    private TaskManagerProbe() {
    }

    public static void install(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(CLS_VIEW, cl, "a", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (probed) {
                    return;
                }
                probed = true;
                Context context = ((View) param.thisObject).getContext().getApplicationContext();
                new Thread(Hooks.guard("task manager probe", () -> probe(context)),
                        "nlplus-taskmgr-probe").start();
            }
        });
        Hooks.log("task manager probe: ready");
    }

    private static void probe(Context context) {
        StringBuilder out = new StringBuilder("task manager probe:");
        String[] files = {
                "/proc/stat", "/proc/loadavg", "/proc/meminfo", "/proc/net/dev",
                "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq",
                "/sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq",
                "/sys/kernel/ged/hal/gpu_utilization", "/sys/kernel/ged/hal/current_freqency",
                "/sys/class/thermal/thermal_zone0/type", "/sys/class/thermal/thermal_zone0/temp",
                "/sys/class/power_supply/battery/temp", "/sys/block/zram0/disksize",
        };
        for (String path : files) {
            out.append("\n  ").append(path).append(": ").append(first(path));
        }
        File[] zones = new File("/sys/class/thermal").listFiles();
        out.append("\n  thermal zones listed: ").append(zones == null ? "denied" : zones.length);
        File[] procs = new File("/proc").listFiles((dir, name) -> name.matches("[0-9]+"));
        out.append("\n  /proc pids listed: ").append(procs == null ? "denied" : procs.length);
        ActivityManager am = context.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningAppProcessInfo> running = am.getRunningAppProcesses();
        out.append("\n  running app processes: ").append(running == null ? "none" : running.size());
        int tried = 0;
        if (running != null) {
            for (ActivityManager.RunningAppProcessInfo info : running) {
                if (tried++ >= 4) {
                    break;
                }
                out.append("\n  pid ").append(info.pid).append(' ').append(info.processName)
                        .append(" uid ").append(info.uid).append(" stat: ")
                        .append(first("/proc/" + info.pid + "/stat"));
            }
        }
        out.append("\n  pid 1 (init) stat: ").append(first("/proc/1/stat"));
        if (procs != null) {
            int shown = 0;
            for (File proc : procs) {
                // System daemons and services: low pids that are not apps.
                if (shown >= 3 || Integer.parseInt(proc.getName()) < 100) {
                    continue;
                }
                shown++;
                out.append("\n  pid ").append(proc.getName()).append(" cmdline: ")
                        .append(first(proc.getPath() + "/cmdline").replace('\0', ' '))
                        .append(" stat: ").append(first(proc.getPath() + "/stat"));
            }
        }
        if (running != null && !running.isEmpty()) {
            try {
                Debug.MemoryInfo[] mem = am.getProcessMemoryInfo(new int[] {running.get(0).pid});
                out.append("\n  memory of ").append(running.get(0).processName).append(": ")
                        .append(mem[0].getTotalPss()).append(" kB PSS");
            } catch (Throwable t) {
                out.append("\n  process memory: ").append(t);
            }
        }
        try {
            NetworkStatsManager net = context.getSystemService(NetworkStatsManager.class);
            long end = System.currentTimeMillis();
            NetworkStats stats = net.querySummary(ConnectivityManager.TYPE_WIFI, null,
                    end - 24 * 3600_000L, end);
            int buckets = 0;
            NetworkStats.Bucket bucket = new NetworkStats.Bucket();
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket);
                buckets++;
            }
            stats.close();
            out.append("\n  network per app (wifi, 24 h): ").append(buckets).append(" buckets");
        } catch (Throwable t) {
            out.append("\n  network per app: ").append(t);
        }
        PackageManager pm = context.getPackageManager();
        for (String permission : new String[] {"android.permission.FORCE_STOP_PACKAGES",
                "android.permission.KILL_BACKGROUND_PROCESSES",
                "android.permission.PACKAGE_USAGE_STATS",
                "android.permission.READ_NETWORK_USAGE_HISTORY",
                "android.permission.DUMP"}) {
            out.append("\n  ").append(permission.substring(permission.lastIndexOf('.') + 1))
                    .append(": ").append(context.checkSelfPermission(permission)
                            == PackageManager.PERMISSION_GRANTED ? "granted" : "no");
        }
        out.append("\n  uid ").append(android.os.Process.myUid());
        Hooks.log(out.toString());
    }

    /** The file's first line, or why it could not be read. */
    private static String first(String path) {
        try (BufferedReader in = new BufferedReader(new FileReader(path))) {
            String line = in.readLine();
            return line == null ? "(empty)" : line.length() > 80 ? line.substring(0, 80) : line;
        } catch (Throwable t) {
            return "denied (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
        }
    }
}
