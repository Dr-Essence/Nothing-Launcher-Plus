package com.dressence.nothinglauncherplus.ui;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.provider.Settings;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.R;

import java.util.Collections;

/**
 * Opens Developer options' Running services from outside the launcher: the quick settings
 * tile, the home screen shortcut (offered where launchers list shortcuts, as Settings offers
 * its own) and the module icon's long-press shortcut. system_server opens it (SystemShortcuts:
 * in Nothing's Settings it is a fragment in SubSettings, which apps may not start); with no
 * answer from the system, developer options. It shows nothing itself and closes once the
 * screen is asked for.
 */
public final class RunningServices extends Activity {

    static final String SHORTCUT_ID = "running_services";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        open(this, this::finish);
    }

    /** Asks the system for Running services; developer options if it does not answer. */
    static void open(Context context, Runnable done) {
        context.sendOrderedBroadcast(
                new Intent(Config.ACTION_RUNNING_SERVICES).setPackage("android"), null,
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent intent) {
                        if (getResultCode() != Activity.RESULT_OK) {
                            context.startActivity(new Intent(
                                    Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        }
                        done.run();
                    }
                }, null, Activity.RESULT_CANCELED, null, null);
    }

    /** What starts this screen, for the tile. */
    static PendingIntent pendingIntent(Context context) {
        return PendingIntent.getActivity(context, 0, new Intent(context, RunningServices.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
    }

    static ShortcutInfo shortcut(Context context) {
        return new ShortcutInfo.Builder(context, SHORTCUT_ID)
                .setShortLabel("Running services")
                .setLongLabel("Running services")
                .setIcon(Icon.createWithResource(context, R.drawable.ic_running_services))
                .setIntent(new Intent(Intent.ACTION_VIEW)
                        .setClass(context, RunningServices.class))
                .build();
    }

    /** Shortcuts on or off: the module icon's shortcut and the entry in shortcut lists. */
    static void setShortcuts(Context context, boolean on) {
        ShortcutManager shortcuts = context.getSystemService(ShortcutManager.class);
        if (shortcuts != null) {
            if (on) {
                shortcuts.setDynamicShortcuts(Collections.singletonList(shortcut(context)));
                shortcuts.enableShortcuts(Collections.singletonList(SHORTCUT_ID));
            } else {
                shortcuts.removeAllDynamicShortcuts();
                shortcuts.disableShortcuts(Collections.singletonList(SHORTCUT_ID),
                        "Turned off in Nothing Launcher+");
            }
        }
        context.getPackageManager().setComponentEnabledSetting(
                new ComponentName(context, CreateShortcut.class),
                on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    static boolean shortcutsOn(Context context) {
        return context.getPackageManager().getComponentEnabledSetting(
                new ComponentName(context, CreateShortcut.class))
                != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    /**
     * Listed where launchers offer shortcuts (Launcher3: the widget list), as Settings lists
     * "Settings shortcut": placing it puts a Running services icon on the home screen.
     */
    public static final class CreateShortcut extends Activity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            ShortcutManager shortcuts = getSystemService(ShortcutManager.class);
            if (Intent.ACTION_CREATE_SHORTCUT.equals(getIntent().getAction())
                    && shortcuts != null) {
                setResult(RESULT_OK, shortcuts.createShortcutResultIntent(shortcut(this)));
            } else {
                setResult(RESULT_CANCELED);
            }
            finish();
        }
    }
}
