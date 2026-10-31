package com.dressence.nothinglauncherplus.perf;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;

import com.dressence.nothinglauncherplus.xposed.NdotFont;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The performance overlay (CMF Phone 1): {@link PerfHud} over everything, once a second. The
 * clocks, loads and temperatures are root-only on this phone, so one root shell reads them in
 * a loop (the files as found on the phone: cpufreq, /proc/stat, /proc/meminfo, MediaTek's GED
 * HAL and DVFSRC, the thermal zones by name, the battery's current and voltage) and SurfaceFlinger's
 * frame times for the game's layer (the first SurfaceView layer drawn through BLAST). The
 * battery's temperature comes from Android. The panel takes no touches and is drawn at 80%
 * opacity, the most Android lets touches pass through an app's overlay, so games underneath
 * play as usual.
 */
public final class OverlayService extends Service {

    private static final String CHANNEL = "overlay";
    private static final int NOTIFICATION = 1;
    /** Readings below this are a sensor's "off" value (a powered-down GPU says -274 °C). */
    private static final float NO_READING_C = -40f;

    /**
     * Each second prints: "F f0..f7 (kHz, 0 when offline) | G opp kHz | U load | T cpu zones
     * (m°C) | X gpu soc (m°C) | D ddr Hz | B µA µV", then "S" + the per-core /proc/stat lines,
     * "M" + MemTotal MemAvailable SwapTotal SwapFree (kB), "P" + the game layer's last present
     * times (ns), and "E" to end the second.
     */
    private static final String SENSORS = ""
            + "Z(){ for z in /sys/class/thermal/thermal_zone*; do"
            + " [ \"$(cat $z/type)\" = \"$1\" ] && echo $z/temp && return; done; }\n"
            + "CPU=\"\"; for n in little-core0 little-core1 little-core2 little-core3"
            + " big-core0-1 big-core0-2 big-core1-1 big-core1-2 big-core2-1 big-core2-2"
            + " big-core3-1 big-core3-2; do CPU=\"$CPU $(Z cpu-$n)\"; done\n"
            + "GPU=$(Z gpu); SOC=$(Z soc_max)\n"
            + "PS=/sys/class/power_supply/battery\n"
            + "L=\"\"; n=0\n"
            + "while :; do\n"
            + "  f=\"\"; for c in 0 1 2 3 4 5 6 7; do d=/sys/devices/system/cpu/cpu$c;"
            + " if [ \"$(cat $d/online 2>/dev/null)\" = 0 ]; then f=\"$f 0\";"
            + " else f=\"$f $(cat $d/cpufreq/scaling_cur_freq 2>/dev/null || echo 0)\"; fi;"
            + " done\n"
            + "  t=\"\"; for z in $CPU; do t=\"$t $(cat $z)\"; done\n"
            + "  echo \"F$f|G $(cat /sys/kernel/ged/hal/current_freqency)"
            + "|U $(cat /sys/kernel/ged/hal/gpu_utilization)|T$t|X $(cat $GPU) $(cat $SOC)"
            + "|D $(cat /sys/class/devfreq/mtk-dvfsrc-devfreq/cur_freq)"
            + "|B $(cat $PS/current_now) $(cat $PS/voltage_now)\"\n"
            + "  echo \"S $(grep '^cpu[0-7] ' /proc/stat | tr '\\n' ',')\"\n"
            + "  echo \"M $(grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree):' /proc/meminfo"
            + " | awk '{print $2}' | tr '\\n' ' ')\"\n"
            + "  [ $((n % 3)) = 0 ] && L=$(dumpsys SurfaceFlinger --list"
            + " | grep -m1 'SurfaceView\\[.*(BLAST)')\n"
            + "  [ -n \"$L\" ] && echo \"P $(dumpsys SurfaceFlinger --latency \"$L\""
            + " | awk 'NR>1 && $2>0 && $2<9000000000000000000 {print $2}' | tail -120"
            + " | tr '\\n' ' ')\"\n"
            + "  echo E\n"
            + "  n=$((n+1)); sleep 1\n"
            + "done\n";

    private final Handler main = new Handler(Looper.getMainLooper());
    private PerfHud panel;
    private Process reader;
    private volatile boolean running;

    public static void start(Context context) {
        context.startForegroundService(new Intent(context, OverlayService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, OverlayService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION, notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        if (panel == null) {
            try {
                show();
            } catch (Exception e) {
                // No overlay permission (or no window): nothing to show, stop quietly.
                stopSelf();
                return START_NOT_STICKY;
            }
            readSensors();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (reader != null) {
            reader.destroy();
        }
        if (panel != null) {
            try {
                getSystemService(WindowManager.class).removeView(panel);
            } catch (Exception e) {
                // Already gone with the window.
            }
            panel = null;
        }
        super.onDestroy();
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                "Performance overlay", NotificationManager.IMPORTANCE_MIN));
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("Performance overlay")
                .setContentText("Turn it off in Nothing Launcher+ → Performance")
                .setOngoing(true)
                .build();
    }

    private void show() {
        Typeface ndot = NdotFont.get(this);
        panel = new PerfHud(this, ndot != null ? ndot : Typeface.MONOSPACE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        int margin = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8,
                getResources().getDisplayMetrics()));
        lp.x = margin;
        lp.y = margin * 4;
        // Touches only pass through an app's overlay up to this opacity (Android 12+).
        lp.alpha = 0.8f;
        getSystemService(WindowManager.class).addView(panel, lp);
    }

    /** One root shell prints the readings every second; each second updates the panel. */
    private void readSensors() {
        running = true;
        new Thread(() -> {
            Reader parser = new Reader();
            try {
                reader = new ProcessBuilder("su").redirectErrorStream(true).start();
                reader.getOutputStream().write(SENSORS.getBytes(StandardCharsets.UTF_8));
                reader.getOutputStream().flush();
                BufferedReader in = new BufferedReader(new InputStreamReader(
                        reader.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (running && (line = in.readLine()) != null) {
                    PerfHud.Sample sample = parser.line(line, batteryTemperature());
                    if (sample != null) {
                        main.post(() -> {
                            if (panel != null) {
                                panel.show(sample);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                // Root refused or the shell ended: the panel keeps "Reading…".
            }
        }, "performance overlay").start();
    }

    /** Battery temperature in °C, from Android; NaN if unknown. */
    private float batteryTemperature() {
        Intent battery = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int tenths = battery != null
                ? battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE)
                : Integer.MIN_VALUE;
        return tenths == Integer.MIN_VALUE ? Float.NaN : tenths / 10f;
    }

    /** Turns the shell's lines into one {@link PerfHud.Sample} a second. */
    static final class Reader {
        private final long[] lastBusy = new long[8];
        private final long[] lastTotal = new long[8];
        private long lastFrame;
        private PerfHud.Sample next = new PerfHud.Sample();

        /** The finished second's sample on its "E" line, else null. */
        PerfHud.Sample line(String line, float battery) {
            if (line.startsWith("F")) {
                main(line);
            } else if (line.startsWith("S ")) {
                stat(line.substring(2));
            } else if (line.startsWith("M ")) {
                long[] m = numbers(line.substring(2));
                if (m.length == 4 && m[0] > 0) {
                    next.ram = 1f - (float) m[1] / m[0];
                    next.swap = m[2] > 0 ? 1f - (float) m[3] / m[2] : -1;
                }
            } else if (line.startsWith("P ")) {
                frames(numbers(line.substring(2)));
            } else if (line.equals("E")) {
                PerfHud.Sample done = next;
                done.batteryTemp = battery;
                next = new PerfHud.Sample();
                return done;
            }
            return null;
        }

        private void main(String line) {
            String[] parts = line.split("\\|");
            if (parts.length < 5) {
                return;
            }
            long[] cores = numbers(parts[0].substring(1));
            System.arraycopy(cores, 0, next.coreKhz, 0, Math.min(8, cores.length));
            long[] gpu = numbers(parts[1].substring(1));
            next.gpuKhz = gpu.length > 1 ? gpu[1] : 0;
            long[] load = numbers(parts[2].substring(1));
            next.gpuLoad = load.length > 0 ? load[0] / 100f : -1;
            float hottest = Float.NaN;
            for (long t : numbers(parts[3].substring(1))) {
                float c = celsius(t);
                if (!Float.isNaN(c) && (Float.isNaN(hottest) || c > hottest)) {
                    hottest = c;
                }
            }
            next.cpuTemp = hottest;
            long[] other = numbers(parts[4].substring(1));
            next.gpuTemp = other.length > 0 ? celsius(other[0]) : Float.NaN;
            next.socTemp = other.length > 1 ? celsius(other[1]) : Float.NaN;
            if (parts.length > 5) {
                long[] ddr = numbers(parts[5].substring(1));
                next.ddrMhz = ddr.length > 0 ? ddr[0] / 1_000_000 : 0;
            }
            if (parts.length > 6) {
                long[] power = numbers(parts[6].substring(1));
                // µA × µV; the sign of the current only says charging or not.
                next.watts = power.length == 2
                        ? Math.abs(power[0] * 1e-6f * (power[1] * 1e-6f)) : -1;
            }
        }

        /** "cpuN user nice system idle iowait irq softirq steal …," per online core. */
        private void stat(String text) {
            for (String core : text.split(",")) {
                String[] words = core.trim().split("\\s+");
                if (words.length < 8 || !words[0].startsWith("cpu")) {
                    continue;
                }
                int i;
                try {
                    i = Integer.parseInt(words[0].substring(3));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (i < 0 || i >= 8) {
                    continue;
                }
                long total = 0;
                for (int w = 1; w < words.length; w++) {
                    total += parse(words[w]);
                }
                long idle = parse(words[4]) + parse(words[5]);
                long busy = total - idle;
                long dTotal = total - lastTotal[i];
                if (lastTotal[i] > 0 && dTotal > 0) {
                    next.coreLoad[i] = Math.max(0, Math.min(1,
                            (float) (busy - lastBusy[i]) / dTotal));
                }
                lastBusy[i] = busy;
                lastTotal[i] = total;
            }
        }

        /** FPS and frame times from the game layer's last present times (ns). */
        private void frames(long[] times) {
            if (times.length < 2) {
                return;
            }
            long newest = times[times.length - 1];
            if (newest == lastFrame) {
                next.fps = 0; // Nothing new presented this second.
                return;
            }
            lastFrame = newest;
            int count = 0;
            List<Float> gaps = new ArrayList<>();
            for (int i = times.length - 1; i > 0; i--) {
                if (newest - times[i - 1] > 1_000_000_000L) {
                    break;
                }
                count++;
            }
            for (int i = 1; i < times.length; i++) {
                gaps.add((times[i] - times[i - 1]) / 1_000_000f);
            }
            next.fps = count;
            next.frameMs = count > 0 ? 1000f / count : 0;
            next.frameTimes = new float[gaps.size()];
            for (int i = 0; i < gaps.size(); i++) {
                next.frameTimes[i] = gaps.get(i);
            }
        }

        private static float celsius(long milli) {
            float c = milli / 1000f;
            return c < NO_READING_C ? Float.NaN : c;
        }

        private static long parse(String word) {
            try {
                return Long.parseLong(word);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        static long[] numbers(String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return new long[0];
            }
            String[] words = trimmed.split("\\s+");
            long[] out = new long[words.length];
            int n = 0;
            for (String word : words) {
                try {
                    long value = Long.parseLong(word);
                    out[n++] = value;
                } catch (NumberFormatException e) {
                    // Not a number (an empty reading): left out.
                }
            }
            return java.util.Arrays.copyOf(out, n);
        }
    }
}
