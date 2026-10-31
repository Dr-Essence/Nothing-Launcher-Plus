package com.dressence.nothinglauncherplus.launcher;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Memory in recents, as custom ROMs show it (crDroid's MemInfoView, Infinity X): how much
 * memory is free of the phone's total, and a tap opens Android's running services (Developer
 * options → Running services, Settings$DevRunningServicesActivity).
 *
 * <p>It is one more button in recents' row of action buttons (Screenshot, Split:
 * OverviewActionsView's {@code action_buttons}), made with Nothing's own button style
 * ({@code OverviewActionButton}), so it looks like them and fades, hides and moves with the
 * row by Nothing's own animation (the row's MultiValueAlpha). The figures are read on a
 * background thread every 3 s only while the button is on screen.
 */
final class RecentsRam {

    private static final String CLS_ACTIONS = "com.android.quickstep.views.OverviewActionsView";
    private static final ComponentName RUNNING_SERVICES = new ComponentName(
            "com.android.settings", "com.android.settings.Settings$DevRunningServicesActivity");
    private static final long REFRESH_MS = 3000;
    private static final int[] KNOWN_GB = {1, 2, 3, 4, 6, 8, 10, 12, 16, 24, 32, 48, 64};

    private static Handler worker;

    private RecentsRam() {
    }

    static void install(ClassLoader cl, ConfigStore store) {
        XposedHelpers.findAndHookMethod(CLS_ACTIONS, cl, "onFinishInflate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Hooks.guard("recents memory", () -> add((View) param.thisObject, store)).run();
            }
        });
    }

    private static void add(View actions, ConfigStore store) {
        Context context = actions.getContext();
        Resources res = context.getResources();
        String pkg = context.getPackageName();
        int rowId = res.getIdentifier("action_buttons", "id", pkg);
        View row = rowId != 0 ? actions.findViewById(rowId) : null;
        int style = res.getIdentifier("OverviewActionButton", "style", pkg);
        int theme = res.getIdentifier("OverviewActionButtonHighlightColor", "style", pkg);
        if (!(row instanceof LinearLayout) || style == 0) {
            Hooks.log("recents memory: actions row not found");
            return;
        }
        LinearLayout buttons = (LinearLayout) row;
        Context themed = theme != 0 ? new ContextThemeWrapper(context, theme) : context;
        RamButton ram = new RamButton(themed, style, store);
        // As Nothing's Screenshot button: same text size and font.
        int screenshotId = res.getIdentifier("action_screenshot", "id", pkg);
        View screenshot = screenshotId != 0 ? buttons.findViewById(screenshotId) : null;
        if (screenshot instanceof TextView) {
            ram.setTextSize(TypedValue.COMPLEX_UNIT_PX, ((TextView) screenshot).getTextSize());
            ram.setTypeface(((TextView) screenshot).getTypeface());
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int spacing = res.getIdentifier("overview_actions_button_spacing", "dimen", pkg);
        lp.setMarginStart(spacing != 0 ? res.getDimensionPixelSize(spacing) : 0);
        lp.gravity = android.view.Gravity.CENTER_VERTICAL;
        buttons.addView(ram, lp);
        ram.setOnClickListener(v -> Hooks.guard("recents running services",
                () -> openRunningServices(v.getContext())).run());
        java.lang.ref.WeakReference<RamButton> weak = new java.lang.ref.WeakReference<>(ram);
        store.addChangeListener(() -> {
            RamButton shown = weak.get();
            if (shown != null) {
                shown.applySetting();
            }
        });
    }

    private static void openRunningServices(Context context) {
        Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(RUNNING_SERVICES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (intent.resolveActivity(context.getPackageManager()) == null) {
            // Not in this Settings: developer options, where Running services is listed.
            intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    private static synchronized Handler worker() {
        if (worker == null) {
            HandlerThread thread = new HandlerThread("nlplus-recents-memory");
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        return worker;
    }

    /** The phone's memory as sold (8 GB, not 7.4): the next known size up. */
    private static String totalLabel(long bytes) {
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        for (int size : KNOWN_GB) {
            if (gb <= size) {
                return size + " GB";
            }
        }
        return Math.round(gb) + " GB";
    }

    /** The memory button: refreshes while it is on screen and the setting is on. */
    private static final class RamButton extends Button {

        private final ConfigStore store;
        private final ActivityManager activityManager;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Runnable refresh = this::refresh;
        private boolean shown;

        RamButton(Context context, int style, ConfigStore store) {
            super(context, null, 0, style);
            this.store = store;
            this.activityManager = context.getSystemService(ActivityManager.class);
            setSingleLine(true);
            setContentDescription("Memory, open running services");
            applySetting();
        }

        void applySetting() {
            setVisibility(store.get().recentsMemory ? VISIBLE : GONE);
        }

        @Override
        public void onVisibilityAggregated(boolean isVisible) {
            super.onVisibilityAggregated(isVisible);
            shown = isVisible;
            Handler handler = worker();
            handler.removeCallbacks(refresh);
            if (isVisible) {
                handler.post(refresh);
            }
        }

        /** On the worker thread. */
        private void refresh() {
            try {
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                activityManager.getMemoryInfo(info);
                String text = Formatter.formatShortFileSize(getContext(), info.availMem)
                        + " free · " + totalLabel(info.totalMem);
                main.post(() -> setText(text));
            } catch (Throwable t) {
                Hooks.log("recents memory read failed: " + t);
                return;
            }
            if (shown) {
                worker().postDelayed(refresh, REFRESH_MS);
            }
        }
    }
}
