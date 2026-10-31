package com.dressence.nothinglauncherplus.perf;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.xposed.Hooks;
import com.dressence.nothinglauncherplus.xposed.NdotFont;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * The module's panels, drawn by System UI (CMF Phone 1): the performance overlay and the debug
 * report's REC pill. An app's overlay is hidden on the lock screen (Android's keyguard policy
 * hides windows layered below the lock screen; an app overlay's layer, 111000, is below it,
 * 171000), while a window above it shows (WindowState.canBeHiddenByKeyguard), as Nothing's
 * fingerprint overlay does with the same window type (TYPE_NAVIGATION_BAR_PANEL), which only
 * System UI may use. So System UI shows the panels, on the lock screen and over apps and games
 * alike, and the module app, which reads the sensors and runs the report as root, only sends it
 * what to show. Only the module app is listened to (it shares its identity with each send).
 */
public final class HudWindows {

    public static final String ACTION_HUD = "com.dressence.nothinglauncherplus.HUD";
    public static final String ACTION_HUD_OFF = "com.dressence.nothinglauncherplus.HUD_OFF";
    public static final String ACTION_PILL = "com.dressence.nothinglauncherplus.PILL";
    public static final String ACTION_PILL_OFF = "com.dressence.nothinglauncherplus.PILL_OFF";
    static final String EXTRA_SAMPLE = "sample";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_COLOR = "color";
    private static final String PLACE = "nlplus_hud";
    /**
     * WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL: hidden from the public SDK the
     * module builds against, its value fixed in Android (2024, as Android 16 has it).
     */
    private static final int TYPE_NAVIGATION_BAR_PANEL = 2024;

    private static PerfHud hud;
    private static WindowManager.LayoutParams hudParams;
    private static TextView pill;

    private HudWindows() {
    }

    /** In System UI: listens once its application has started. */
    public static void install(ClassLoader cl) {
        XposedHelpers.findAndHookMethod("com.android.systemui.SystemUIApplication", cl,
                "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        listen((Application) param.thisObject);
                    }
                });
    }

    private static void listen(Context context) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_HUD);
        filter.addAction(ACTION_HUD_OFF);
        filter.addAction(ACTION_PILL);
        filter.addAction(ACTION_PILL_OFF);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (!Config.MODULE_PKG.equals(getSentFromPackage())) {
                    return; // Only the module app.
                }
                Hooks.guard("module panel", () -> act(context, intent)).run();
            }
        }, filter, Context.RECEIVER_EXPORTED);
        Hooks.log("module panels: listening");
    }

    private static void act(Context context, Intent intent) {
        WindowManager windows = context.getSystemService(WindowManager.class);
        switch (String.valueOf(intent.getAction())) {
            case ACTION_HUD:
                android.os.Bundle data = intent.getBundleExtra(EXTRA_SAMPLE);
                if (data == null) {
                    return;
                }
                if (hud == null) {
                    showHud(context, windows);
                }
                hud.show(PerfHud.Sample.fromBundle(data));
                break;
            case ACTION_HUD_OFF:
                remove(windows, hud);
                hud = null;
                break;
            case ACTION_PILL:
                if (pill == null) {
                    showPill(context, windows);
                }
                pill.setText(intent.getStringExtra(EXTRA_TEXT));
                pill.setTextColor(intent.getIntExtra(EXTRA_COLOR, 0xFFFFFFFF));
                break;
            default:
                remove(windows, pill);
                pill = null;
                break;
        }
    }

    private static WindowManager.LayoutParams params(int flags) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | flags,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("NLPlusPanel");
        return lp;
    }

    /** The performance panel: dragged where wanted, and kept there. */
    private static void showHud(Context context, WindowManager windows) {
        Typeface ndot = NdotFont.get(context);
        hud = new PerfHud(context, ndot != null ? ndot : Typeface.MONOSPACE);
        SharedPreferences place = context.getSharedPreferences(PLACE, Context.MODE_PRIVATE);
        hudParams = params(0);
        hudParams.gravity = Gravity.TOP | Gravity.START;
        hudParams.x = place.getInt("x", context.getResources().getDisplayMetrics().widthPixels / 2);
        hudParams.y = place.getInt("y", dp(context, 32));
        hudParams.alpha = 0.9f;
        windows.addView(hud, hudParams);
        float[] grab = new float[4];
        hud.setOnTouchListener((View view, MotionEvent event) -> {
            try {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        grab[0] = event.getRawX();
                        grab[1] = event.getRawY();
                        grab[2] = hudParams.x;
                        grab[3] = hudParams.y;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        hudParams.x = Math.round(grab[2] + event.getRawX() - grab[0]);
                        hudParams.y = Math.round(grab[3] + event.getRawY() - grab[1]);
                        windows.updateViewLayout(hud, hudParams);
                        break;
                    case MotionEvent.ACTION_UP:
                        place.edit().putInt("x", hudParams.x).putInt("y", hudParams.y).apply();
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                // The panel went away mid-drag.
            }
            return true;
        });
    }

    /** The debug report's pill: takes no touches. */
    private static void showPill(Context context, WindowManager windows) {
        pill = new TextView(context);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        int pad = dp(context, 6);
        pill.setPadding(pad * 2, pad / 2, pad * 2, pad / 2);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xCC000000);
        background.setCornerRadius(pad * 3);
        pill.setBackground(background);
        WindowManager.LayoutParams lp = params(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = pad * 8;
        windows.addView(pill, lp);
    }

    private static void remove(WindowManager windows, View view) {
        if (view != null) {
            try {
                windows.removeView(view);
            } catch (Exception e) {
                // Already gone.
            }
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    /** From the module app: a broadcast to System UI carrying who sends it. */
    public static void send(Context context, Intent intent) {
        context.sendBroadcast(intent.setPackage(Config.SYSTEMUI_PKG), null,
                android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true)
                        .toBundle());
    }
}
