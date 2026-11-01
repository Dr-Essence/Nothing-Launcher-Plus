package com.dressence.nothinglauncherplus.settings;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;
import com.dressence.nothinglauncherplus.xposed.NdotFont;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Developer options → Running services as a task manager, on Nothing's own page (Settings
 * 16.0.0: RunningServices fragment, RunningProcessesView with its list and RAM bar header,
 * rows bound by A1). Added to it, never replacing it:
 * <ul>
 *   <li>a header under Nothing's RAM bar (TaskHeader): CPU load and clocks, memory, swap,
 *   network speed, temperatures and the processes using the most CPU, system ones too;</li>
 *   <li>on each row, under Nothing's summary, the process's CPU share and its app's network
 *   use today;</li>
 *   <li>a long-press on a row: its details and Stop / Force stop, with a warning for what is
 *   dangerous to stop (Android's own processes, persistent and system apps, the home screen,
 *   the keyboard).</li>
 * </ul>
 * Settings runs as the system user and holds FORCE_STOP_PACKAGES, KILL_BACKGROUND_PROCESSES,
 * DUMP and READ_NETWORK_USAGE_HISTORY (probe on the phone). Figures refresh every 2 s off the
 * main thread while the page is on screen. Settings' classes are obfuscated: the names here are
 * those of Settings 16.0.0 (RunningProcessesView.a sets the page up, its field G is the list;
 * A1.a binds a row, A1.d its summary; K1.n its process, whose r is the pid and p the uid).
 */
public final class TaskManager {

    private static final String CLS_VIEW =
            "com.android.settings.applications.RunningProcessesView";
    private static final String CLS_ROW = "com.android.settings.applications.A1";
    private static final long TICK_MS = 2000;
    /** Packages whose stopping can take the phone down or lose calls, input or the home. */
    private static final Set<String> CRITICAL = new HashSet<>(Arrays.asList(
            "android", "com.android.systemui", "com.android.phone", "com.android.settings",
            "com.android.server.telecom", "com.android.bluetooth", "com.android.nfc",
            "com.android.providers.settings", "com.android.networkstack",
            "com.google.android.networkstack", "com.android.se", "com.android.shell",
            "com.google.android.gms", "com.google.android.gsf", "com.nothing.launcher"));

    private static final Map<TextView, Row> ROWS = new WeakHashMap<>();
    private static final TaskStats STATS = new TaskStats();
    private static Handler worker;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** A row's process, and the summary Nothing gave it. */
    private static final class Row {
        CharSequence base;
        int pid;
        int uid;
    }

    private TaskManager() {
    }

    public static void install(ClassLoader cl, ConfigStore store) {
        XposedHelpers.findAndHookMethod(CLS_VIEW, cl, "a", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                store.reloadIfChanged();
                if (store.get().taskManager) {
                    Hooks.guard("task manager page", () -> setUp((ViewGroup) param.thisObject))
                            .run();
                }
            }
        });
        XposedBridge.hookAllMethods(XposedHelpers.findClass(CLS_ROW, cl), "a",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (store.get().taskManager && param.args.length == 3) {
                            Hooks.guard("task manager row",
                                    () -> bindRow(param.thisObject, param.args[1])).run();
                        }
                    }
                });
        Hooks.log("task manager: ready");
    }

    private static synchronized Handler worker() {
        if (worker == null) {
            HandlerThread thread = new HandlerThread("nlplus-taskmgr");
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        return worker;
    }

    private static void setUp(ViewGroup page) {
        ListView list = (ListView) XposedHelpers.getObjectField(page, "G");
        TaskHeader header = new TaskHeader(page.getContext(), NdotFont.get(page.getContext()));
        list.addHeaderView(header, null, false);
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            Object item = parent.getItemAtPosition(position);
            if (item == null) {
                return false;
            }
            Hooks.guard("task manager details", () -> details(view.getContext(), item)).run();
            return true;
        });
        Context app = page.getContext().getApplicationContext();
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            try {
                STATS.refresh(app);
            } catch (Throwable t) {
                Hooks.log("task manager read failed: " + t);
            }
            MAIN.post(Hooks.guard("task manager show", () -> {
                header.show(STATS);
                refreshRows();
            }));
            if (page.isAttachedToWindow()) {
                worker().postDelayed(tick[0], TICK_MS);
            }
        };
        page.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                worker().removeCallbacks(tick[0]);
                worker().post(tick[0]);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                worker().removeCallbacks(tick[0]);
            }
        });
        if (page.isAttachedToWindow()) {
            worker().post(tick[0]);
        }
    }

    private static void bindRow(Object holder, Object item) {
        TextView summary = (TextView) XposedHelpers.getObjectField(holder, "d");
        Object process = process(item);
        if (summary == null || process == null) {
            return;
        }
        Row row = new Row();
        row.base = summary.getText();
        row.pid = XposedHelpers.getIntField(process, "r");
        row.uid = XposedHelpers.getIntField(process, "p");
        ROWS.put(summary, row);
        show(summary, row);
    }

    private static void refreshRows() {
        for (Map.Entry<TextView, Row> entry : ROWS.entrySet()) {
            if (entry.getKey().isAttachedToWindow()) {
                show(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void show(TextView summary, Row row) {
        StringBuilder extra = new StringBuilder();
        Float cpu = row.pid > 0 ? STATS.cpuOf(row.pid) : null;
        extra.append("CPU ").append(cpu == null ? "0%"
                : String.format(Locale.ROOT, "%.1f%%", cpu));
        long[] net = STATS.netOf(row.uid);
        if (net != null && net[0] + net[1] > 0) {
            Context c = summary.getContext();
            extra.append("  ·  today ↓").append(Formatter.formatShortFileSize(c, net[0]))
                    .append(" ↑").append(Formatter.formatShortFileSize(c, net[1]));
        }
        String text = row.base + "\n" + extra;
        if (!text.contentEquals(summary.getText())) {
            summary.setText(text);
        }
    }

    /** The row's process record (K1.n); null for a row that groups several. */
    private static Object process(Object item) {
        return XposedHelpers.getObjectField(item, "n");
    }

    // -------------------------------------------------------------------------------------
    // Details and stopping
    // -------------------------------------------------------------------------------------

    private enum Danger { APP, SYSTEM, CRITICAL }

    private static void details(Context context, Object item) {
        Object process = process(item);
        if (process == null) {
            return;
        }
        int pid = XposedHelpers.getIntField(process, "r");
        int uid = XposedHelpers.getIntField(process, "p");
        String processName = (String) XposedHelpers.getObjectField(process, "q");
        PackageItemInfo info = (PackageItemInfo) XposedHelpers.getObjectField(process, "c");
        String pkg = info != null ? info.packageName : processName;
        CharSequence label = (CharSequence) XposedHelpers.getObjectField(item, "d");
        PackageManager pm = context.getPackageManager();
        ApplicationInfo app = null;
        try {
            app = pm.getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            // A process with no package of its own name: judged by its uid.
        }
        Danger danger = danger(context, pkg, uid, app);
        ActivityManager am = context.getSystemService(ActivityManager.class);
        StringBuilder message = new StringBuilder();
        message.append("Package  ").append(pkg)
                .append("\nProcess  ").append(processName)
                .append("\nPID ").append(pid).append("  ·  UID ").append(uid);
        if (pid > 0) {
            try {
                Debug.MemoryInfo[] mem = am.getProcessMemoryInfo(new int[] {pid});
                message.append("\nRAM  ").append(Formatter.formatShortFileSize(context,
                        mem[0].getTotalPss() * 1024L));
            } catch (Throwable t) {
                // Unknown.
            }
        }
        Float cpu = pid > 0 ? STATS.cpuOf(pid) : null;
        message.append("\nCPU  ").append(cpu == null ? "0%"
                : String.format(Locale.ROOT, "%.1f%%", cpu));
        long[] net = STATS.netOf(uid);
        if (net != null) {
            message.append("\nNetwork today  ↓ ")
                    .append(Formatter.formatShortFileSize(context, net[0])).append("  ↑ ")
                    .append(Formatter.formatShortFileSize(context, net[1]));
        }
        message.append("\n\n").append(warning(danger));
        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(label != null ? label : pkg)
                .setMessage(message)
                .setNegativeButton("Close", null)
                .setNeutralButton("Stop background", (d, w) -> Hooks.guard("task manager stop",
                        () -> {
                            am.killBackgroundProcesses(pkg);
                            Toast.makeText(context, "Background work of " + pkg + " stopped",
                                    Toast.LENGTH_SHORT).show();
                        }).run());
        // Never Android itself, nor Settings, which shows this page.
        boolean canForceStop = app != null && !"android".equals(pkg)
                && !context.getPackageName().equals(pkg);
        if (canForceStop) {
            builder.setPositiveButton("Force stop", (d, w) -> {
                if (danger == Danger.APP) {
                    forceStop(context, am, pkg);
                } else {
                    new AlertDialog.Builder(context)
                            .setTitle("Force stop " + (label != null ? label : pkg) + "?")
                            .setMessage(warning(danger) + "\n\nForce stop anyway?")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Force stop", (d2, w2) ->
                                    forceStop(context, am, pkg))
                            .show();
                }
            });
        }
        builder.show();
    }

    private static void forceStop(Context context, ActivityManager am, String pkg) {
        Hooks.guard("task manager force stop", () -> {
            XposedHelpers.callMethod(am, "forceStopPackage", pkg);
            Toast.makeText(context, pkg + " force stopped", Toast.LENGTH_SHORT).show();
        }).run();
    }

    private static Danger danger(Context context, String pkg, int uid, ApplicationInfo app) {
        String home = null;
        String keyboard = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
        android.content.pm.ResolveInfo resolved = context.getPackageManager().resolveActivity(
                new android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_HOME), 0);
        if (resolved != null && resolved.activityInfo != null) {
            home = resolved.activityInfo.packageName;
        }
        if (CRITICAL.contains(pkg) || uid < 10000 || pkg.equals(home)
                || (keyboard != null && keyboard.startsWith(pkg + "/"))
                || (app != null && (app.flags & ApplicationInfo.FLAG_PERSISTENT) != 0)) {
            return Danger.CRITICAL;
        }
        if (app != null && (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            return Danger.SYSTEM;
        }
        return Danger.APP;
    }

    private static String warning(Danger danger) {
        switch (danger) {
            case CRITICAL:
                return "⚠ DANGEROUS TO STOP: part of Android itself, the home screen, the"
                        + " keyboard, calls or connections. Stopping it can freeze or restart"
                        + " the phone, drop calls or lose connections.";
            case SYSTEM:
                return "⚠ System app: stopping it can switch off a phone feature until it"
                        + " starts again.";
            default:
                return "Safe to stop: it starts again when it is needed.";
        }
    }
}
