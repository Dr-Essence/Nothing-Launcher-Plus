package com.dressence.nothinglauncherplus.perf;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The performance overlay's readings (CMF Phone 1), twice a second, sent to System UI, which
 * draws {@link PerfHud} ({@link HudWindows}: above the lock screen too, which an app's overlay
 * cannot be). The clocks, loads and temperatures are root-only on this phone, so one root
 * shell reads them in a loop (the files as found on the phone: cpufreq, /proc/stat,
 * /proc/meminfo, MediaTek's GED HAL and DVFSRC, the thermal zones by name, the battery's
 * current and voltage), with SurfaceFlinger's frame times for the focused window's layer (its
 * SurfaceView if it has one, else its own), so FPS shows in any app. The battery's temperature
 * comes from Android.
 */
public final class OverlayService extends Service {

    /**
     * Started with this by Nothing's Game Bar (the system starts it, which Android allows
     * while the module is in the background): on if it is off, off if it is on.
     */
    static final String ACTION_TOGGLE = "com.dressence.nothinglauncherplus.TOGGLE_OVERLAY";
    private static final String CHANNEL = "overlay";
    private static final int NOTIFICATION = 1;
    /** Readings below this are a sensor's "off" value (a powered-down GPU says -274 °C). */
    private static final float NO_READING_C = -40f;

    /**
     * Prints, four times a second, "P" + the focused layer's last present times (ns); and every
     * half second: "F f0..f7 (each core's cluster clock, kHz, 0 when offline) | G opp kHz | U load | T cpu zones (m°C) | X gpu soc
     * (m°C) | D ddr Hz | B µA µV | C the two clusters' set clocks (kHz)", then "S" + the per-core /proc/stat lines,
     * "M" + MemTotal MemAvailable SwapTotal SwapFree (kB), "Y" + simpleperf's per-core cpu-cycles lines over half a second, and
     * "E" to end the round.
     */
    private static final String SENSORS = ""
            + "Z(){ for z in /sys/class/thermal/thermal_zone*; do"
            + " [ \"$(cat $z/type)\" = \"$1\" ] && echo $z/temp && return; done; }\n"
            + "CPU=\"\"; for n in little-core0 little-core1 little-core2 little-core3"
            + " big-core0-1 big-core0-2 big-core1-1 big-core1-2 big-core2-1 big-core2-2"
            + " big-core3-1 big-core3-2; do CPU=\"$CPU $(Z cpu-$n)\"; done\n"
            + "GPU=$(Z gpu); SOC=$(Z soc_max)\n"
            + "PS=/sys/class/power_supply/battery\n"
            + "SP=$(command -v simpleperf)\n"
            // Frames, in a loop of their own four times a second, so FPS follows the screen
            // without waiting for the slower readings: every 2 s, the focused window (the first
            // display with one: the others list "mCurrentFocus=null", often first; with none,
            // as on the lock screen, System UI's NotificationShade, which draws it), and the
            // layer it draws into: its SurfaceView (a game, a video) if it has one, else its own
            // ("pkg/activity#N", or a system window's title, such as NotificationShade#N). It
            // ends with the main shell ($$).
            + "( L=\"\"; m=0; while kill -0 $$ 2>/dev/null; do\n"
            + "  if [ $((m % 8)) = 0 ]; then W=$(dumpsys window | grep mCurrentFocus"
            + " | grep -v =null | head -1 | sed 's/.* u0 //; s/}.*//');"
            + " [ -z \"$W\" ] && W=NotificationShade; P=${W%%/*};"
            + " LS=$(dumpsys SurfaceFlinger --list);"
            + " L=$(echo \"$LS\" | grep -m1 \"SurfaceView\\\\[$P/.*(BLAST)\");"
            + " [ -z \"$L\" ] && L=$(echo \"$LS\" | awk -v w=\"$W#\""
            + " 'index($0,w)==1{print;exit}'); fi\n"
            + "  [ -n \"$L\" ] && echo \"P $(dumpsys SurfaceFlinger --latency \"$L\""
            + " | awk 'NR>1 && $2>0 && $2<9000000000000000000 {print $2}' | tail -120"
            + " | tr '\\n' ' ')\"\n"
            + "  m=$((m+1)); sleep 0.25\n"
            + "done ) &\n"
            + "while :; do\n"
            + "  f=\"\"; for c in 0 1 2 3 4 5 6 7; do d=/sys/devices/system/cpu/cpu$c;"
            + " if [ \"$(cat $d/online 2>/dev/null)\" = 0 ]; then f=\"$f 0\";"
            + " else f=\"$f $(cat $d/cpufreq/scaling_cur_freq 2>/dev/null || echo 0)\"; fi;"
            + " done\n"
            + "  t=\"\"; for z in $CPU; do t=\"$t $(cat $z)\"; done\n"
            + "  echo \"F$f|G $(cat /sys/kernel/ged/hal/current_freqency)"
            + "|U $(cat /sys/kernel/ged/hal/gpu_utilization)|T$t|X $(cat $GPU) $(cat $SOC)"
            + "|D $(cat /sys/class/devfreq/mtk-dvfsrc-devfreq/cur_freq)"
            + "|B $(cat $PS/current_now) $(cat $PS/voltage_now)"
            + "|C $(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq)"
            + " $(cat /sys/devices/system/cpu/cpufreq/policy4/scaling_cur_freq)\"\n"
            + "  echo \"S $(grep '^cpu[0-7] ' /proc/stat | tr '\\n' ',')\"\n"
            + "  echo \"M $(grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree):' /proc/meminfo"
            + " | awk '{print $2}' | tr '\\n' ' ')\"\n"
            // Each core's real clock: its CPU cycles over half a second (the hardware counters,
            // as Scene measures them); this also paces the loop. Without simpleperf, a pause.
            + "  if [ -n \"$SP\" ]; then echo \"Y $($SP stat -a --per-core -e cpu-cycles"
            + " --duration 0.5 2>&1 | grep cpu-cycles | sed 's/cpu-cycles.*//' | tr '\\n' ';')\";"
            + " else sleep 0.5; fi\n"
            + "  echo E\n"
            + "done\n";

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
        boolean toggle = intent != null && ACTION_TOGGLE.equals(intent.getAction());
        if (toggle && running) {
            // From the Game Bar: it was showing, so off.
            remember(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            remember(true);
            readSensors();
        }
        return START_NOT_STICKY;
    }

    /** Saves whether the overlay shows, for the Performance page's switch. */
    private void remember(boolean on) {
        try {
            com.dressence.nothinglauncherplus.ui.SettingsStore store =
                    new com.dressence.nothinglauncherplus.ui.SettingsStore(this);
            if (store.config.perfOverlay != on) {
                store.config.perfOverlay = on;
                store.save(this);
            }
        } catch (Exception e) {
            // Unsaved: the switch shows the old state until changed.
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (reader != null) {
            reader.destroy();
        }
        // System UI takes its panel away.
        HudWindows.send(this, new Intent(HudWindows.ACTION_HUD_OFF));
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

    /** One root shell prints the readings twice a second; each round updates the panel. */
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
                    if (sample != null && running) {
                        // System UI draws it (above the lock screen too).
                        HudWindows.send(this, new Intent(HudWindows.ACTION_HUD)
                                .putExtra(HudWindows.EXTRA_SAMPLE, sample.toBundle()));
                    }
                }
            } catch (Exception e) {
                // Root refused or the shell ended: no more readings are sent.
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
        /** FPS counts the frames of this last stretch of time (ns). */
        private static final long WINDOW_NS = 500_000_000L;
        private long lastFrame;
        /** The frame time graph's columns, oldest first (ms; -1 for none). */
        private final float[] graph = filled(PerfHud.GRAPH_COLUMNS);
        /** The GPU's last real temperature: its sensor goes off with it (-274 °C). */
        private float lastGpuTemp = Float.NaN;
        private PerfHud.Sample next = new PerfHud.Sample();
        /** The last finished round's sample, shown again with each new FPS reading. */
        private PerfHud.Sample shown;
        private float fps = -1;
        private float frameMs;
        private float[] frameTimes = new float[0];

        /**
         * The sample to show: the finished round's on its "E" line, and the last one with the
         * new frames on each "P" line (four times a second, so FPS follows the screen); else
         * null.
         */
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
            } else if (line.startsWith("Y ")) {
                cycles(line.substring(2));
            } else if (line.startsWith("P ")) {
                frames(numbers(line.substring(2)));
                return shown != null ? withFrames(shown) : null;
            } else if (line.equals("E")) {
                shown = next;
                shown.batteryTemp = battery;
                next = new PerfHud.Sample();
                return withFrames(shown);
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
            float gpuNow = other.length > 0 ? celsius(other[0]) : Float.NaN;
            if (Float.isNaN(gpuNow)) {
                // Powered down: its last reading, marked idle.
                next.gpuTemp = lastGpuTemp;
                next.gpuIdle = !Float.isNaN(lastGpuTemp);
            } else {
                next.gpuTemp = gpuNow;
                lastGpuTemp = gpuNow;
            }
            next.socTemp = other.length > 1 ? celsius(other[1]) : Float.NaN;
            if (parts.length > 5) {
                long[] ddr = numbers(parts[5].substring(1));
                next.ddrMhz = ddr.length > 0 ? ddr[0] / 1_000_000 : 0;
            }
            if (parts.length > 7) {
                long[] clusters = numbers(parts[7].substring(1));
                System.arraycopy(clusters, 0, next.clusterKhz, 0, Math.min(2, clusters.length));
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

        /**
         * Each core's real clock from simpleperf's per-core cpu-cycles counts over 0.5 s, one
         * "core count" line each (cut at the event name): the core is the small number, the
         * count the large one (thousands separators dropped), whatever the columns look like.
         */
        private void cycles(String text) {
            for (String row : text.split(";")) {
                String digits = row.replaceAll("(?<=\\d),(?=\\d{3})", "");
                long cpu = -1;
                long count = -1;
                for (String word : digits.trim().split("\\s+")) {
                    String w = word.startsWith("cpu") ? word.substring(3) : word;
                    try {
                        long value = Long.parseLong(w);
                        if (value < 64 && cpu < 0 && count < 0) {
                            cpu = value;
                        } else if (value >= 1000) {
                            count = value;
                        }
                    } catch (NumberFormatException e) {
                        // A column that is not a number.
                    }
                }
                if (cpu >= 0 && cpu < 8 && count > 0) {
                    // Cycles in 0.5 s: × 2 per second, in kHz.
                    next.coreRealKhz[(int) cpu] = count * 2 / 1000;
                }
            }
        }

        private PerfHud.Sample withFrames(PerfHud.Sample sample) {
            sample.fps = fps;
            sample.frameMs = frameMs;
            sample.frameTimes = frameTimes;
            return sample;
        }

        /**
         * FPS and frame times from the focused layer's last present times (ns, on
         * SurfaceFlinger's clock: CLOCK_MONOTONIC, as {@link SystemClock#uptimeMillis}). FPS
         * counts the frames presented in the half second up to now (MangoHud's default
         * sampling period), so it follows the screen and falls to 0 when nothing draws; the
         * graph takes each newly presented frame's time, and an empty column for a read with
         * none, so it moves with what is on screen rather than repeating old frames.
         */
        private void frames(long[] times) {
            frames(times, SystemClock.uptimeMillis() * 1_000_000L);
        }

        void frames(long[] times, long now) {
            int count = 0;
            long first = 0;
            long last = 0;
            for (long time : times) {
                if (time > now - WINDOW_NS && time <= now) {
                    if (count == 0) {
                        first = time;
                    }
                    last = time;
                    count++;
                }
            }
            fps = count * (1_000_000_000f / WINDOW_NS);
            frameMs = count > 1 ? (last - first) / 1_000_000f / (count - 1) : 0;
            int added = 0;
            long previous = lastFrame;
            for (long time : times) {
                if (time <= lastFrame) {
                    continue;
                }
                // A gap of 200 ms or more is the app resting (a UI draws only when something
                // changes), not a slow frame: an empty column.
                long gap = previous > 0 ? time - previous : 0;
                graph(gap > 0 && gap < 200_000_000L ? gap / 1_000_000f : -1);
                previous = time;
                added++;
            }
            if (times.length > 0) {
                lastFrame = Math.max(lastFrame, times[times.length - 1]);
            }
            if (added == 0) {
                graph(-1);
            }
            frameTimes = graph.clone();
        }

        /** Scrolls the graph by one frame time (ms; -1 for none). */
        private void graph(float ms) {
            System.arraycopy(graph, 1, graph, 0, graph.length - 1);
            graph[graph.length - 1] = ms;
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

        private static float[] filled(int size) {
            float[] columns = new float[size];
            java.util.Arrays.fill(columns, -1);
            return columns;
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
