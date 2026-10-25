package com.dressence.nothinglauncherplus.hook;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Nothing's widget apps in landscape.
 *
 * <p>Nothing Widgets ({@code com.nothing.hearthstone}) and NothingCardService lock every screen
 * to portrait, including the cards a widget opens over the home screen (the Calm / breathing
 * card, battery and photo views). Over a landscape home screen those were drawn on their side.
 * With "Home screen rotation" on, their portrait locks are lifted: translucent cards then follow
 * the home screen behind them, full screens follow the device.
 */
final class WidgetApps {

    static final String WIDGETS_PKG = "com.nothing.hearthstone";
    static final String CARD_SERVICE_PKG = "com.nothing.cardservice";

    /** The breathing card: full width at 183:223, which overflows a landscape screen. */
    private static final String BREATH_ACTIVITY = WIDGETS_PKG + ".breath.ui.BreathExerciseActivity";
    private static final String BREATH_CARD_ID = "breath_layout";

    private final ConfigStore store;

    private WidgetApps(ConfigStore store) {
        this.store = store;
    }

    static void install(ConfigStore store) {
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
        if (!store.get().allowRotation || !isPortraitLock(activity.getRequestedOrientation())) {
            return;
        }
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (activity.getClass().getName().equals(BREATH_ACTIVITY)) {
            fitBreathCard(activity);
        }
    }

    private static boolean isPortraitLock(int orientation) {
        return orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                || orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                || orientation == ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
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
                        View card = decor.findViewById(id);
                        if (card == null) {
                            return; // Fragment not attached yet.
                        }
                        decor.getViewTreeObserver().removeOnGlobalLayoutListener(this);
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
