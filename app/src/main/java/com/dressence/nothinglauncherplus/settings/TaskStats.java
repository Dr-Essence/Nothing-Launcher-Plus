package com.dressence.nothinglauncherplus.settings;

import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.HardwarePropertiesManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.SparseArray;

import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The task manager's figures, read in Settings' process (system user) from what it may read
 * (DebuG probe on the phone): /proc/stat (CPU load), /proc/meminfo (memory, swap), cpufreq
 * (clocks), the traffic counters (network speed), the battery's temperature, NetworkStatsManager
 * (each app's network use today, as Settings' data usage reads it) and Android's CPU tracker
 * (the "cpuinfo" service's dump, which Settings may read with DUMP: each process's CPU share,
 * system processes too). GPU load, the thermal zones and other processes' /proc files are
 * closed to Settings; temperatures besides the battery's come from the thermal HAL
 * (HardwarePropertiesManager) where Android lets Settings ask it. Read off the main thread.
 */
final class TaskStats {

    /** A process's CPU share, from the CPU tracker. */
    static final class Proc {
        final int pid;
        final String name;
        final float percent;

        Proc(int pid, String name, float percent) {
            this.pid = pid;
            this.name = name;
            this.percent = percent;
        }
    }

    float cpuPercent = -1;
    final long[] coreKhz = new long[8];
    long memTotal;
    long memAvailable;
    long swapTotal;
    long swapFree;
    long rxPerSecond = -1;
    long txPerSecond = -1;
    float batteryC = Float.NaN;
    float cpuC = Float.NaN;
    float gpuC = Float.NaN;
    final List<Proc> top = new ArrayList<>();
    final SparseArray<Float> cpuByPid = new SparseArray<>();
    /** uid → {received, sent} bytes today. */
    final SparseArray<long[]> netByUid = new SparseArray<>();

    private static final Pattern CPU_LINE =
            Pattern.compile("^\\s*([0-9.]+)% (\\d+)/([^:]+):");

    private long lastBusy = -1;
    private long lastTotal = -1;
    private long lastRx = -1;
    private long lastTx = -1;
    private long lastTrafficAt;
    private long lastNetAt;
    private boolean thermalLogged;
    private boolean cpuinfoLogged;

    /** Reads every figure again (worker thread). */
    void refresh(Context context) {
        readCpu();
        for (int c = 0; c < coreKhz.length; c++) {
            coreKhz[c] = number(first(
                    "/sys/devices/system/cpu/cpu" + c + "/cpufreq/scaling_cur_freq"));
        }
        readMemory();
        readTraffic();
        readTemperatures(context);
        readCpuTracker();
        if (System.currentTimeMillis() - lastNetAt > 15_000) {
            lastNetAt = System.currentTimeMillis();
            readNetworkToday(context);
        }
    }

    private void readCpu() {
        String line = first("/proc/stat");
        if (line == null || !line.startsWith("cpu ")) {
            return;
        }
        String[] parts = line.trim().split("\\s+");
        long total = 0;
        for (int i = 1; i < parts.length; i++) {
            total += number(parts[i]);
        }
        long idle = number(parts[4]) + (parts.length > 5 ? number(parts[5]) : 0);
        long busy = total - idle;
        if (lastTotal >= 0 && total > lastTotal) {
            cpuPercent = 100f * (busy - lastBusy) / (total - lastTotal);
        }
        lastBusy = busy;
        lastTotal = total;
    }

    private void readMemory() {
        try (BufferedReader in = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = in.readLine()) != null) {
                long kb = number(line.replaceAll("[^0-9]", ""));
                if (line.startsWith("MemTotal:")) {
                    memTotal = kb * 1024;
                } else if (line.startsWith("MemAvailable:")) {
                    memAvailable = kb * 1024;
                } else if (line.startsWith("SwapTotal:")) {
                    swapTotal = kb * 1024;
                } else if (line.startsWith("SwapFree:")) {
                    swapFree = kb * 1024;
                }
            }
        } catch (Exception e) {
            // Left as last read.
        }
    }

    private void readTraffic() {
        long rx = TrafficStats.getTotalRxBytes();
        long tx = TrafficStats.getTotalTxBytes();
        long now = System.currentTimeMillis();
        if (lastRx >= 0 && now > lastTrafficAt && rx >= lastRx && tx >= lastTx) {
            rxPerSecond = (rx - lastRx) * 1000 / (now - lastTrafficAt);
            txPerSecond = (tx - lastTx) * 1000 / (now - lastTrafficAt);
        }
        lastRx = rx;
        lastTx = tx;
        lastTrafficAt = now;
    }

    private void readTemperatures(Context context) {
        Intent battery = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            batteryC = tenths == Integer.MIN_VALUE ? Float.NaN : tenths / 10f;
        }
        try {
            HardwarePropertiesManager hpm =
                    context.getSystemService(HardwarePropertiesManager.class);
            cpuC = max(hpm.getDeviceTemperatures(HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU,
                    HardwarePropertiesManager.TEMPERATURE_CURRENT));
            gpuC = max(hpm.getDeviceTemperatures(HardwarePropertiesManager.DEVICE_TEMPERATURE_GPU,
                    HardwarePropertiesManager.TEMPERATURE_CURRENT));
        } catch (Throwable t) {
            if (!thermalLogged) {
                thermalLogged = true;
                Hooks.log("task manager: thermal HAL not available to Settings: " + t);
            }
        }
    }

    /** Android's CPU tracker ("dumpsys cpuinfo"): each process's recent CPU share. */
    private void readCpuTracker() {
        List<Proc> procs = new ArrayList<>();
        try {
            IBinder service = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "cpuinfo");
            if (service == null) {
                throw new IllegalStateException("no cpuinfo service");
            }
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            service.dumpAsync(pipe[1].getFileDescriptor(), new String[0]);
            pipe[1].close();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])))) {
                String line;
                while ((line = in.readLine()) != null) {
                    Matcher m = CPU_LINE.matcher(line);
                    if (m.find()) {
                        procs.add(new Proc(Integer.parseInt(m.group(2)), m.group(3).trim(),
                                Float.parseFloat(m.group(1))));
                    }
                }
            }
        } catch (Throwable t) {
            if (!cpuinfoLogged) {
                cpuinfoLogged = true;
                Hooks.log("task manager: CPU tracker not readable: " + t);
            }
            return;
        }
        if (procs.isEmpty() && !cpuinfoLogged) {
            cpuinfoLogged = true;
            Hooks.log("task manager: CPU tracker gave no process lines");
        }
        synchronized (this) {
            cpuByPid.clear();
            top.clear();
            for (Proc p : procs) {
                cpuByPid.put(p.pid, p.percent);
            }
            procs.sort((a, b) -> Float.compare(b.percent, a.percent));
            top.addAll(procs.subList(0, Math.min(5, procs.size())));
        }
    }

    /** Each app's mobile and Wi-Fi data since midnight. */
    private void readNetworkToday(Context context) {
        Calendar midnight = Calendar.getInstance();
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);
        long start = midnight.getTimeInMillis();
        long end = System.currentTimeMillis();
        SparseArray<long[]> byUid = new SparseArray<>();
        NetworkStatsManager net = context.getSystemService(NetworkStatsManager.class);
        for (int type : new int[] {ConnectivityManager.TYPE_WIFI,
                ConnectivityManager.TYPE_MOBILE}) {
            try (NetworkStats stats = net.querySummary(type, null, start, end)) {
                NetworkStats.Bucket bucket = new NetworkStats.Bucket();
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket);
                    long[] sum = byUid.get(bucket.getUid());
                    if (sum == null) {
                        sum = new long[2];
                        byUid.put(bucket.getUid(), sum);
                    }
                    sum[0] += bucket.getRxBytes();
                    sum[1] += bucket.getTxBytes();
                }
            } catch (Throwable t) {
                // That network has no record (no SIM, Wi-Fi never used today).
            }
        }
        synchronized (this) {
            netByUid.clear();
            for (int i = 0; i < byUid.size(); i++) {
                netByUid.put(byUid.keyAt(i), byUid.valueAt(i));
            }
        }
    }

    synchronized Float cpuOf(int pid) {
        return cpuByPid.get(pid);
    }

    synchronized long[] netOf(int uid) {
        return netByUid.get(uid);
    }

    synchronized List<Proc> topCopy() {
        return new ArrayList<>(top);
    }

    private static float max(float[] values) {
        float best = Float.NaN;
        if (values != null) {
            for (float v : values) {
                if (v > -100 && v < 200 && (Float.isNaN(best) || v > best)) {
                    best = v;
                }
            }
        }
        return best;
    }

    private static String first(String path) {
        try (BufferedReader in = new BufferedReader(new FileReader(path))) {
            return in.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private static long number(String text) {
        try {
            return text == null || text.isEmpty() ? 0 : Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
