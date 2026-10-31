package com.dressence.nothinglauncherplus.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import com.dressence.nothinglauncherplus.perf.Device;

/**
 * Shows while a debug report runs, so it is clear when it captures: a buzz when capturing
 * starts and two when the report is saved (felt on the lock screen too), an ongoing
 * notification counting down (shown on the lock screen and in the status bar) and, where an
 * overlay can show (not over the lock screen), a small "● REC" pill with the seconds. The
 * report script leaves {@link DebugReport#DONE_FILE} when it has saved the report; that ends it.
 */
public final class CaptureIndicator extends Service {

    private static final String CHANNEL = "capture";
    private static final int NOTIFICATION = 2;
    private static final String EXTRA_DELAY = "delay";
    private static final String EXTRA_LENGTH = "length";

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView pill;
    private long startAt;
    private long endAt;
    private String phase = "";

    static void start(Context context, int delaySeconds, int seconds) {
        context.startForegroundService(new Intent(context, CaptureIndicator.class)
                .putExtra(EXTRA_DELAY, delaySeconds).putExtra(EXTRA_LENGTH, seconds));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        long now = SystemClock.elapsedRealtime();
        startAt = now + 1000L * (intent != null ? intent.getIntExtra(EXTRA_DELAY, 0) : 0);
        endAt = startAt + 1000L * (intent != null ? intent.getIntExtra(EXTRA_LENGTH, 0) : 0);
        phase = "";
        startForeground(NOTIFICATION, notification("Capture starts in…", startAt),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        if (pill == null && Settings.canDrawOverlays(this)) {
            try {
                showPill();
            } catch (Exception e) {
                pill = null; // No window: the notification and buzzes still tell.
            }
        }
        main.removeCallbacksAndMessages(null);
        main.post(this::tick);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (pill != null) {
            try {
                getSystemService(WindowManager.class).removeView(pill);
            } catch (Exception e) {
                // Already gone.
            }
            pill = null;
        }
        super.onDestroy();
    }

    /** Once a second: the phase, the pill, and whether the report is saved yet. */
    private void tick() {
        long now = SystemClock.elapsedRealtime();
        String next = now < startAt ? "waiting" : now < endAt ? "capturing" : "saving";
        if (!next.equals(phase)) {
            phase = next;
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (next.equals("capturing")) {
                buzz(1);
                manager.notify(NOTIFICATION, notification("Capturing…", endAt));
            } else if (next.equals("saving")) {
                manager.notify(NOTIFICATION, notification("Saving the report…", 0));
            }
        }
        if (pill != null) {
            long left = Math.max(0, ((next.equals("waiting") ? startAt : endAt) - now) / 1000);
            pill.setText(next.equals("waiting") ? "● starts in " + left + " s"
                    : next.equals("capturing") ? "● REC " + left + " s" : "● saving…");
            pill.setTextColor(next.equals("capturing") ? 0xFFFF4444 : 0xFFFFFFFF);
        }
        if (next.equals("saving")) {
            checkDone();
        } else {
            main.postDelayed(this::tick, 1000);
        }
    }

    /** The report script leaves the done file once it has saved the report (root-only). */
    private void checkDone() {
        new Thread(() -> {
            boolean done = Device.su("[ -e " + DebugReport.DONE_FILE + " ] && rm -f "
                    + DebugReport.DONE_FILE) == null;
            main.post(() -> {
                if (done) {
                    buzz(2);
                    stopSelf();
                } else {
                    main.postDelayed(this::tick, 2000);
                }
            });
        }, "capture indicator").start();
    }

    private void buzz(int times) {
        try {
            Vibrator vibrator = getSystemService(Vibrator.class);
            if (vibrator == null) {
                return;
            }
            long[] timings = times == 1 ? new long[] {0, 250} : new long[] {0, 150, 150, 150};
            vibrator.vibrate(VibrationEffect.createWaveform(timings, -1));
        } catch (Exception e) {
            // No vibrator: the notification still tells.
        }
    }

    private Notification notification(String text, long countdownTo) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Debug report",
                NotificationManager.IMPORTANCE_LOW));
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("Nothing Launcher+ debug report")
                .setContentText(text)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true);
        if (countdownTo > 0) {
            // A live countdown in the notification, to the next phase.
            builder.setUsesChronometer(true).setChronometerCountDown(true)
                    .setWhen(System.currentTimeMillis()
                            + (countdownTo - SystemClock.elapsedRealtime()));
        }
        return builder.build();
    }

    private void showPill() {
        pill = new TextView(this);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        int pad = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6,
                getResources().getDisplayMetrics()));
        pill.setPadding(pad * 2, pad / 2, pad * 2, pad / 2);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xCC000000);
        background.setCornerRadius(pad * 3);
        pill.setBackground(background);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = pad * 8;
        // Touches only pass through an app's overlay up to this opacity (Android 12+).
        lp.alpha = 0.8f;
        getSystemService(WindowManager.class).addView(pill, lp);
    }
}
