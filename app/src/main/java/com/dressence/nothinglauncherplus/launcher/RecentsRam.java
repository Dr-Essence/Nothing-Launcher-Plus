package com.dressence.nothinglauncherplus.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Memory in recents, as custom ROMs show it (crDroid's MemInfoView, Infinity X): how much
 * memory is free of the phone's total, and swap below it when the phone has swap (zram); a
 * tap opens Running services (Developer options' page, opened by the system). It takes
 * Nothing's Screenshot button's look (background, colours, padding, focus), so it matches.
 *
 * <p>It is one more button in recents' row of action buttons (Screenshot, Split:
 * OverviewActionsView's {@code action_buttons}), made with Nothing's own button style
 * ({@code OverviewActionButton}), so it looks like them and fades, hides and moves with the
 * row by Nothing's own animation (the row's MultiValueAlpha). The figures are read on a
 * background thread every 3 s only while the button is on screen.
 */
final class RecentsRam {

    private static final String CLS_ACTIONS = "com.android.quickstep.views.OverviewActionsView";
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
            TextView shot = (TextView) screenshot;
            ram.screenshot = new java.lang.ref.WeakReference<>(shot);
            ram.setTypeface(shot.getTypeface());
            ram.setTextColor(shot.getTextColors());
            ram.lineSize = shot.getTextSize();
            android.graphics.drawable.Drawable background = shot.getBackground();
            ram.setBackground(background == null || background.getConstantState() == null
                    ? null : background.getConstantState().newDrawable(res).mutate());
            ram.setFocusable(shot.isFocusable());
            ram.setDefaultFocusHighlightEnabled(shot.getDefaultFocusHighlightEnabled());
            ram.setPadding(shot.getPaddingLeft(), shot.getPaddingTop(),
                    shot.getPaddingRight(), shot.getPaddingBottom());
        }
        ram.setTextSize(TypedValue.COMPLEX_UNIT_PX, ram.lineSize);
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

    /**
     * Running services, opened by system_server (SystemShortcuts: Nothing's Settings has it
     * only as a fragment Developer options opens in SubSettings, which the launcher may not
     * start). With no answer from the system (its part loads at a reboot after updating, or it
     * found no such screen), developer options, which lists Running services.
     */
    private static void openRunningServices(Context context) {
        context.sendOrderedBroadcast(
                new Intent(Config.ACTION_RUNNING_SERVICES).setPackage("android"), null,
                new android.content.BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent intent) {
                        if (getResultCode() == android.app.Activity.RESULT_OK) {
                            return;
                        }
                        Hooks.log("recents memory: no answer from the system; developer"
                                + " options instead");
                        Hooks.guard("recents developer options", () -> context.startActivity(
                                new Intent(android.provider.Settings
                                        .ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))).run();
                    }
                }, null, android.app.Activity.RESULT_CANCELED, null, null);
    }

    private static synchronized Handler worker() {
        if (worker == null) {
            HandlerThread thread = new HandlerThread("nlplus-recents-memory");
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        return worker;
    }

    /** Diagnostic: a view's background, its tint, state and the colour it draws mid-view. */
    private static String describe(View view) {
        android.graphics.drawable.Drawable bg = view.getBackground();
        StringBuilder out = new StringBuilder();
        out.append(view.getWidth()).append('x').append(view.getHeight())
                .append(" bgTint=").append(view.getBackgroundTintList())
                .append(" state=").append(java.util.Arrays.toString(view.getDrawableState()))
                .append(" bg=").append(bg);
        if (bg instanceof android.graphics.drawable.LayerDrawable) {
            android.graphics.drawable.LayerDrawable layers =
                    (android.graphics.drawable.LayerDrawable) bg;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                out.append(" layer").append(i).append('#').append(layers.getId(i)).append('=')
                        .append(layers.getDrawable(i));
            }
        }
        if (bg != null && view.getWidth() > 0 && view.getHeight() > 0) {
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                    view.getWidth(), view.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            bg.draw(new android.graphics.Canvas(bitmap));
            out.append(" drawsMid=#").append(Integer.toHexString(
                    bitmap.getPixel(view.getWidth() / 2, view.getHeight() / 2)));
            bitmap.recycle();
        }
        return out.toString();
    }

    /** Swap total and free, in bytes, from /proc/meminfo (zeros when there is none). */
    private static long[] swap() {
        long total = 0;
        long free = 0;
        try (java.io.BufferedReader in = new java.io.BufferedReader(
                new java.io.FileReader("/proc/meminfo"))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("SwapTotal:")) {
                    total = kilobytes(line) * 1024;
                } else if (line.startsWith("SwapFree:")) {
                    free = kilobytes(line) * 1024;
                }
            }
        } catch (Exception e) {
            return new long[] {0, 0};
        }
        return new long[] {total, free};
    }

    private static long kilobytes(String line) {
        return Long.parseLong(line.replaceAll("[^0-9]", ""));
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
        private boolean stateLogged;
        /** Nothing's Screenshot button, to compare backgrounds in the diagnostic. */
        java.lang.ref.WeakReference<TextView> screenshot;
        /** Nothing's Screenshot text size; two lines (memory, swap) are drawn smaller. */
        float lineSize = 36f;

        RamButton(Context context, int style, ConfigStore store) {
            super(context, null, 0, style);
            this.store = store;
            this.activityManager = context.getSystemService(ActivityManager.class);
            setMaxLines(2);
            setGravity(android.view.Gravity.CENTER);
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
            if (isVisible && !stateLogged) {
                stateLogged = true;
                // Diagnostic: the button draws a teal fill Screenshot does not, while neither
                // focused nor pressed (DebuG11); both backgrounds, as drawn, are compared.
                TextView shot = screenshot != null ? screenshot.get() : null;
                post(() -> Hooks.log("recents memory: ram " + describe(this) + " | screenshot "
                        + (shot != null ? describe(shot) : "none")));
            }
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
                String text = "RAM " + Formatter.formatShortFileSize(getContext(), info.availMem)
                        + " free · " + totalLabel(info.totalMem);
                long[] swap = swap();
                if (swap[0] > 0) {
                    text += "\nSwap " + Formatter.formatShortFileSize(getContext(), swap[1])
                            + " free · " + Formatter.formatShortFileSize(getContext(), swap[0]);
                }
                boolean twoLines = swap[0] > 0;
                String shownText = text;
                main.post(() -> {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, twoLines ? lineSize * 0.82f
                            : lineSize);
                    setText(shownText);
                });
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
