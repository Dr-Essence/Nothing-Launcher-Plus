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
    /** The card's portrait layout size, kept on the view. */
    private static final String OWN_SIZE = "nlplus.ownSize";

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
        // Cards turn without a restart (CardOrientation), so the breathing card is refitted.
        XposedHelpers.findAndHookMethod(Activity.class, "onConfigurationChanged",
                Configuration.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Activity activity = (Activity) param.thisObject;
                        if (isBreathCard(activity)) {
                            View card = breathCard(activity);
                            if (card != null) {
                                fit(card, (Configuration) param.args[0]);
                            }
                        }
                    }
                });
    }

    private void onCreated(Activity activity) {
        store.reloadIfChanged(); // This process has no reload receiver; check on each screen.
        if (!store.get().allowRotation || !CardOrientation.isPortraitLock(activity.getRequestedOrientation())) {
            return;
        }
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (isBreathCard(activity)) {
            fitBreathCard(activity);
        }
    }

    private static boolean isBreathCard(Activity activity) {
        return activity.getClass().getName().equals(BREATH_ACTIVITY);
    }

    private static View breathCard(Activity activity) {
        int id = activity.getResources().getIdentifier(BREATH_CARD_ID, "id", WIDGETS_PKG);
        return id != 0 ? activity.getWindow().getDecorView().findViewById(id) : null;
    }

    /** The card is added by a fragment after onCreate: fitted once it is laid out. */
    private static void fitBreathCard(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        decor.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        Hooks.guard("breathing card", () -> {
                            View card = breathCard(activity);
                            if (card == null) {
                                return; // Fragment not attached yet.
                            }
                            decor.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            fit(card, activity.getResources().getConfiguration());
                        }).run();
                    }
                });
    }

    /**
     * Portrait: the card's own size, full width at 183:223. Landscape, where that overflows the
     * screen: ConstraintLayout sizes it from both sides (0dp = match constraint), which with its
     * ratio kept is the largest card that fits.
     */
    private static void fit(View card, Configuration config) {
        ViewGroup.LayoutParams lp = card.getLayoutParams();
        int[] own = (int[]) XposedHelpers.getAdditionalInstanceField(card, OWN_SIZE);
        if (own == null) {
            own = new int[] {lp.width, lp.height};
            XposedHelpers.setAdditionalInstanceField(card, OWN_SIZE, own);
        }
        boolean landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE;
        int width = landscape ? 0 : own[0];
        int height = landscape ? 0 : own[1];
        if (lp.width != width || lp.height != height) {
            lp.width = width;
            lp.height = height;
            card.setLayoutParams(lp);
        }
    }
}
