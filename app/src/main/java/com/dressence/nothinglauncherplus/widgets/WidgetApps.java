package com.dressence.nothinglauncherplus.widgets;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Nothing's widget apps in landscape.
 *
 * <p>Nothing Widgets ({@code com.nothing.hearthstone}) and NothingCardService lock every screen
 * to portrait, including the cards a widget opens over the home screen (the Calm / breathing
 * card, battery and photo views). Over a landscape home screen those were drawn on their side.
 * With "Home screen rotation" on, their portrait locks are lifted (as they launch, by
 * CardOrientation in the system, and here for any screen they set it on themselves):
 * translucent cards then follow the home screen behind them, full screens follow the device.
 */
public final class WidgetApps {

    private static final String WIDGETS_PKG = "com.nothing.hearthstone";
    private static final String CARD_SERVICE_PKG = "com.nothing.cardservice";

    /** The breathing card: full width at 183:223, which overflows a landscape screen. */
    private static final String BREATH_ACTIVITY = WIDGETS_PKG + ".breath.ui.BreathExerciseActivity";
    private static final String BREATH_CARD_ID = "breath_layout";

    private final ConfigStore store;

    private WidgetApps(ConfigStore store) {
        this.store = store;
    }

    /** True for the apps this part of the module is for. */
    public static boolean handles(String pkg) {
        return WIDGETS_PKG.equals(pkg) || CARD_SERVICE_PKG.equals(pkg);
    }

    public static void install(ConfigStore store) {
        WidgetApps apps = new WidgetApps(store);
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        apps.onCreated((Activity) param.thisObject);
                    }
                });
    }

    private void onCreated(Activity activity) {
        store.reloadIfChanged(); // This process has no reload receiver; check on each screen.
        if (!store.get().allowRotation || !CardOrientation.isPortraitLock(activity.getRequestedOrientation())) {
            return;
        }
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (activity.getClass().getName().equals(BREATH_ACTIVITY)) {
            fitBreathCard(activity);
        }
    }

    /**
     * The card is added by a fragment after onCreate. Once it is laid out in landscape, let
     * ConstraintLayout size it from both sides (0dp = match constraint): with its ratio kept,
     * that is the largest card that fits the screen.
     */
    private static void fitBreathCard(Activity activity) {
        int id = activity.getResources().getIdentifier(BREATH_CARD_ID, "id", WIDGETS_PKG);
        View decor = activity.getWindow().getDecorView();
        if (id == 0) {
            return;
        }
        decor.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        Hooks.guard("breathing card", () -> fit(this)).run();
                    }

                    private void fit(ViewTreeObserver.OnGlobalLayoutListener self) {
                        View card = decor.findViewById(id);
                        if (card == null) {
                            return; // Fragment not attached yet.
                        }
                        decor.getViewTreeObserver().removeOnGlobalLayoutListener(self);
                        if (activity.getResources().getConfiguration().orientation
                                != Configuration.ORIENTATION_LANDSCAPE) {
                            return;
                        }
                        ViewGroup.LayoutParams lp = card.getLayoutParams();
                        lp.width = 0;
                        lp.height = 0;
                        card.setLayoutParams(lp);
                    }
                });
    }
}
