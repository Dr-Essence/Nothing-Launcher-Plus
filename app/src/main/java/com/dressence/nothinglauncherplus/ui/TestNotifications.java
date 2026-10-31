package com.dressence.nothinglauncherplus.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Person;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

/**
 * Test notifications for checking the lock screen's notification layout (Diagnostics): a mix
 * of short, long, conversation, progress, picture and action notifications, so their column
 * is seen with different heights and counts. Posted by this app, cleared from here or by
 * swiping them away.
 */
final class TestNotifications {

    private static final String CHANNEL = "test";
    private static final int FIRST_ID = 7100;
    /** How many different kinds there are; more are posted as repeats of them. */
    static final int KINDS = 6;
    private static final int MAX = 24;

    private TestNotifications() {
    }

    /** Posts {@code count} test notifications (at most MAX); false when they may not show. */
    static boolean post(Context context, int count) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return false;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Test notifications",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
        for (int i = 0; i < Math.min(count, MAX); i++) {
            manager.notify(FIRST_ID + i, build(context, i).build());
        }
        return manager.areNotificationsEnabled();
    }

    static void clear(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        for (int i = 0; i < MAX; i++) {
            manager.cancel(FIRST_ID + i);
        }
    }

    private static Notification.Builder build(Context context, int index) {
        int round = index / KINDS + 1;
        String tag = round > 1 ? " (" + round + ")" : "";
        Notification.Builder b = new Notification.Builder(context, CHANNEL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setWhen(System.currentTimeMillis() - index * 60_000L)
                .setShowWhen(true);
        switch (index % KINDS) {
            case 0:
                return b.setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("Test notification" + tag)
                        .setContentText("A short one-line notification");
            case 1: {
                Person me = new Person.Builder().setName("You").build();
                Person sam = new Person.Builder().setName("Sam").build();
                long now = System.currentTimeMillis();
                return b.setSmallIcon(android.R.drawable.sym_action_chat)
                        .setStyle(new Notification.MessagingStyle(me)
                                .addMessage("Are you coming tonight?", now - 180_000, sam)
                                .addMessage("Yes, on my way soon", now - 120_000, me)
                                .addMessage("Great, see you there" + tag, now - 60_000, sam));
            }
            case 2: {
                String text = "This is a long test notification. Its text runs over several"
                        + " lines when it is expanded, to check how tall notifications fit on"
                        + " the lock screen, and that none of them reaches the fingerprint"
                        + " sensor's circle, however many are shown.";
                return b.setSmallIcon(android.R.drawable.ic_dialog_email)
                        .setContentTitle("Long test notification" + tag)
                        .setContentText(text)
                        .setStyle(new Notification.BigTextStyle().bigText(text));
            }
            case 3:
                return b.setSmallIcon(android.R.drawable.stat_sys_download)
                        .setContentTitle("Downloading test file" + tag)
                        .setContentText("42 MB of 100 MB")
                        .setProgress(100, 42, false)
                        .setOnlyAlertOnce(true);
            case 4:
                return b.setSmallIcon(android.R.drawable.ic_menu_gallery)
                        .setContentTitle("Picture test notification" + tag)
                        .setContentText("Expands to show a picture")
                        .setStyle(new Notification.BigPictureStyle().bigPicture(picture()));
            default:
                return b.setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                        .setContentTitle("Reminder" + tag)
                        .setContentText("Test event in 15 minutes")
                        .addAction(new Notification.Action.Builder(null, "Snooze", null)
                                .build())
                        .addAction(new Notification.Action.Builder(null, "Dismiss", null)
                                .build());
        }
    }

    private static Bitmap picture() {
        Bitmap bitmap = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint();
        paint.setShader(new LinearGradient(0, 0, 512, 256, 0xFFD71921, 0xFF1B1B1D,
                Shader.TileMode.CLAMP));
        new Canvas(bitmap).drawRect(0, 0, 512, 256, paint);
        return bitmap;
    }
}
