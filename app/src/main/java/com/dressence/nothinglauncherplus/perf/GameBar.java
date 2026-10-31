package com.dressence.nothinglauncherplus.perf;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.lang.reflect.Array;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The module's game options in Nothing's Game Bar (com.nothing.ntgamedashboard, CMF Phone 1).
 * Its settings grid is a fixed list of (key, BUTTON / ENTRY) pairs that GamePanelPagerAdapter
 * builds; each row is data-bound by its key, and for a key it does not know the Game Bar draws
 * an empty row and does nothing on a tap (getEnableState answers null, bindGameSetting falls to
 * its default). Three buttons are added after Nothing's own: the performance overlay, the
 * PerfMTK profile (each tap the next one) and PerfMTK's thermal limits; each row gets its label,
 * icon and tap here, and a tap asks the module's {@link GameBarReceiver} to act.
 */
public final class GameBar {

    public static final String PKG = "com.nothing.ntgamedashboard";
    private static final String CLS_PAGER =
            PKG + ".view.adapter.GamePanelPagerAdapter";
    private static final String CLS_HOLDER = PKG + ".view.viewholder.GameSettingViewHolder";
    private static final String CLS_TYPE = PKG + ".struct.SettingViewType";

    static final String KEY_OVERLAY = "nlplus_overlay";
    static final String KEY_PROFILE = "nlplus_profile";
    static final String KEY_THERMAL = "nlplus_thermal";
    private static final String[] KEYS = {KEY_OVERLAY, KEY_PROFILE, KEY_THERMAL};

    private GameBar() {
    }

    public static void install(ClassLoader cl, ConfigStore store) {
        Class<?> type = XposedHelpers.findClass(CLS_TYPE, cl);
        Object button = XposedHelpers.getStaticObjectField(type, "BUTTON");
        // The grid's pairs are the Game Bar's own Kotlin pairs.
        Class<?> pair = XposedHelpers.findClass("kotlin.Pair", cl);
        XposedBridge.hookAllConstructors(XposedHelpers.findClass(CLS_PAGER, cl),
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object[] own = (Object[]) XposedHelpers.getObjectField(param.thisObject,
                                "gridSettings");
                        Object[] grid = (Object[]) Array.newInstance(
                                own.getClass().getComponentType(), own.length + KEYS.length);
                        System.arraycopy(own, 0, grid, 0, own.length);
                        for (int i = 0; i < KEYS.length; i++) {
                            grid[own.length + i] = XposedHelpers.newInstance(pair, KEYS[i],
                                    button);
                        }
                        XposedHelpers.setObjectField(param.thisObject, "gridSettings", grid);
                    }
                });
        XposedHelpers.findAndHookMethod(CLS_HOLDER, cl, "setData", String.class, type,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String key = (String) param.args[0];
                        if (key == null || !key.startsWith("nlplus_")) {
                            return;
                        }
                        Object binding = XposedHelpers.getObjectField(param.thisObject,
                                "binding");
                        TextView label = (TextView) XposedHelpers.getObjectField(binding,
                                "labelView");
                        ImageView icon = (ImageView) XposedHelpers.getObjectField(binding,
                                "iconView");
                        View row = (View) XposedHelpers.getObjectField(binding,
                                "containerLayout");
                        store.reloadIfChanged();
                        show(key, store.get(), label, icon);
                        Runnable relabel = Hooks.guard("game bar label", () -> {
                            store.reloadIfChanged();
                            show(key, store.get(), label, icon);
                        });
                        Runnable tap = Hooks.guard("game bar", () -> {
                            if (KEY_OVERLAY.equals(key)) {
                                toggleOverlay(row.getContext());
                                return;
                            }
                            ask(row.getContext(), key);
                            // The module saves the new state; shown once it has.
                            row.postDelayed(relabel, 1500);
                        });
                        row.setOnClickListener(v -> tap.run());
                    }
                });
        Hooks.log("game bar: " + KEYS.length + " options added");
    }

    private static void show(String key, Config config, TextView label, ImageView icon) {
        switch (key) {
            case KEY_OVERLAY:
                // Whether it shows is only known to the module's service: a plain switch.
                label.setText("Perf overlay");
                icon.setImageResource(android.R.drawable.ic_menu_view);
                break;
            case KEY_PROFILE:
                label.setText("PerfMTK: " + (config.perfProfile.isEmpty() ? "set"
                        : config.perfProfile));
                icon.setImageResource(android.R.drawable.ic_menu_manage);
                break;
            default:
                label.setText(config.thermalOff ? "Thermal off" : "Thermal on");
                icon.setImageResource(android.R.drawable.ic_menu_compass);
                break;
        }
    }

    /**
     * The overlay is started here, by the Game Bar: Android lets the system start a foreground
     * service for an app in the background, but not that app itself (the module's receiver
     * failed with ForegroundServiceStartNotAllowedException).
     */
    private static void toggleOverlay(Context context) {
        context.startForegroundService(new Intent(OverlayService.ACTION_TOGGLE)
                .setClassName(Config.MODULE_PKG, Config.MODULE_PKG + ".perf.OverlayService"));
    }

    /**
     * The module acts (it has root); the Game Bar only asks, sharing who it is
     * (Android 14+), as the module listens to the system alone.
     */
    private static void ask(Context context, String key) {
        context.sendBroadcast(new Intent(GameBarReceiver.ACTION)
                        .setClassName(Config.MODULE_PKG,
                                Config.MODULE_PKG + ".perf.GameBarReceiver")
                        .putExtra(GameBarReceiver.EXTRA_KEY, key), null,
                android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true)
                        .toBundle());
    }
}
