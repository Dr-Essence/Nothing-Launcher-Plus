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
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import com.dressence.nothinglauncherplus.xposed.NdotFont;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The Ndot performance overlay (CMF Phone 1): a small panel over everything with each CPU
 * core's clock, the GPU's clock and load, and the CPU, GPU, SoC and battery temperatures,
 * once a second. The clocks and temperatures are root-only on this phone, so one root shell
 * reads them in a loop (the files as found on the phone: cpufreq, MediaTek's GED HAL, the
 * thermal zones by name); the battery's comes from Android. The panel takes no touches and is
 * drawn at 80% opacity, the most Android lets touches pass through an app's overlay, so games
 * underneath play as usual.
 */
public final class OverlayService extends Service {

    private static final String CHANNEL = "overlay";
    private static final int NOTIFICATION = 1;

    /**
     * Prints one line a second: "F f0..f7 (kHz, 0 when offline) | G opp kHz | U load ... |
     * T little0..3 big0-1..big3-2 (m°C) | X gpu soc (m°C)". Thermal zones are found by type.
     */
    private static final String SENSORS = ""
            + "Z(){ for z in /sys/class/thermal/thermal_zone*; do"
            + " [ \"$(cat $z/type)\" = \"$1\" ] && echo $z/temp && return; done; }\n"
            + "CPU=\"\"; for n in little-core0 little-core1 little-core2 little-core3"
            + " big-core0-1 big-core0-2 big-core1-1 big-core1-2 big-core2-1 big-core2-2"
            + " big-core3-1 big-core3-2; do CPU=\"$CPU $(Z cpu-$n)\"; done\n"
            + "GPU=$(Z gpu); SOC=$(Z soc_max)\n"
            + "while :; do\n"
            + "  f=\"\"; for c in 0 1 2 3 4 5 6 7; do d=/sys/devices/system/cpu/cpu$c;"
            + " if [ \"$(cat $d/online 2>/dev/null)\" = 0 ]; then f=\"$f 0\";"
            + " else f=\"$f $(cat $d/cpufreq/scaling_cur_freq 2>/dev/null || echo 0)\"; fi;"
            + " done\n"
            + "  t=\"\"; for z in $CPU; do t=\"$t $(cat $z)\"; done\n"
            + "  echo \"F$f|G $(cat /sys/kernel/ged/hal/current_freqency)"
            + "|U $(cat /sys/kernel/ged/hal/gpu_utilization)|T$t|X $(cat $GPU) $(cat $SOC)\"\n"
            + "  sleep 1\n"
            + "done\n";

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView panel;
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
        panel = new TextView(this);
        Typeface ndot = NdotFont.get(this);
        panel.setTypeface(ndot != null ? ndot : Typeface.MONOSPACE);
        panel.setTextColor(0xFFFFFFFF);
        panel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        int pad = dp(6);
        panel.setPadding(pad, pad / 2, pad, pad / 2);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xCC000000);
        background.setCornerRadius(dp(8));
        panel.setBackground(background);
        panel.setText("Reading…");
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(8);
        lp.y = dp(36);
        // Touches only pass through an app's overlay up to this opacity (Android 12+).
        lp.alpha = 0.8f;
        getSystemService(WindowManager.class).addView(panel, lp);
    }

    /** One root shell prints the sensors every second; each line updates the panel. */
    private void readSensors() {
        running = true;
        new Thread(() -> {
            try {
                reader = new ProcessBuilder("su").redirectErrorStream(true).start();
                reader.getOutputStream().write(SENSORS.getBytes(StandardCharsets.UTF_8));
                reader.getOutputStream().flush();
                BufferedReader in = new BufferedReader(new InputStreamReader(
                        reader.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (running && (line = in.readLine()) != null) {
                    String text = format(line, batteryTemperature());
                    if (text != null) {
                        main.post(() -> {
                            if (panel != null) {
                                panel.setText(text);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                // Root refused or the shell ended: the panel says so.
            }
            if (running) {
                main.post(() -> {
                    if (panel != null) {
                        panel.setText("No root: overlay stopped");
                    }
                });
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

    /** The panel's three lines from one sensor line; null if the line is not one. */
    static String format(String line, float battery) {
        String[] parts = line.split("\\|");
        if (parts.length != 5 || !parts[0].startsWith("F")) {
            return null;
        }
        long[] cores = numbers(parts[0].substring(1));
        long[] gpu = numbers(parts[1].substring(1));
        long[] load = numbers(parts[2].substring(1));
        long[] cpuTemps = numbers(parts[3].substring(1));
        long[] other = numbers(parts[4].substring(1));
        StringBuilder out = new StringBuilder("CPU");
        for (int i = 0; i < cores.length; i++) {
            out.append(i == 4 ? "  |" : " ").append(cores[i] == 0 ? "off"
                    : String.format(Locale.ROOT, "%.1f", cores[i] / 1_000_000f));
        }
        out.append(" GHz\nGPU ")
                .append(gpu.length > 1 ? gpu[1] / 1000 + " MHz" : "?")
                .append("  ").append(load.length > 0 ? load[0] + "%" : "?");
        long cpuMax = Long.MIN_VALUE;
        for (long t : cpuTemps) {
            cpuMax = Math.max(cpuMax, t);
        }
        out.append("\nCPU ").append(celsius(cpuMax))
                .append("  GPU ").append(other.length > 0 ? celsius(other[0]) : "?")
                .append("  SoC ").append(other.length > 1 ? celsius(other[1]) : "?")
                .append("  BAT ").append(Float.isNaN(battery) ? "?"
                        : String.format(Locale.ROOT, "%.0f°", battery));
        return out.toString();
    }

    private static String celsius(long milli) {
        return milli == Long.MIN_VALUE ? "?" : Math.round(milli / 1000f) + "°";
    }

    private static long[] numbers(String text) {
        String[] words = text.trim().split("\\s+");
        long[] out = new long[words.length];
        int n = 0;
        for (String word : words) {
            try {
                long value = Long.parseLong(word);
                out[n++] = value;
            } catch (NumberFormatException e) {
                // Not a number (empty reading): left out.
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
