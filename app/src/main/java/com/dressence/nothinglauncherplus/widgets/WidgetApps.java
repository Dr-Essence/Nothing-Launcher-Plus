package com.dressence.nothinglauncherplus.widgets;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Nothing's widget apps in landscape.
 *
 * <p>Nothing Widgets ({@code com.nothing.hearthstone}) and NothingCardService lock every screen
 * to portrait, including the cards a widget opens over the home screen (the Calm and Relax
 * breathing card, battery and photo views). Over a landscape home screen those were drawn on
 * their side. With "Home screen rotation" on, their portrait locks are lifted (as they launch,
 * by CardOrientation in the system, and here for any screen they set it on themselves).
 *
 * <p>Full screens (settings) then follow the device like any app. A card is laid out only for
 * a portrait screen (Nothing ships no landscape layout for any of them): stretched across a
 * landscape one it overflowed it (the breathing card took the whole height and pushed its close
 * button off screen). In landscape each card is given what it was made for, a canvas of the
 * portrait screen's width and of the height its content takes there, shown whole: centred,
 * and scaled down evenly only if the landscape screen is lower than that. Its layout, its sizes
 * and its own code see a portrait screen, so it looks and works as in portrait, whatever the
 * screen size, aspect ratio or density, and so does any card Nothing adds later.
 */
public final class WidgetApps {

    private static final String WIDGETS_PKG = "com.nothing.hearthstone";
    private static final String CARD_SERVICE_PKG = "com.nothing.cardservice";
    /** The card root's own layout, kept on it while it is on a landscape canvas. */
    private static final String OWN_LAYOUT = "nlplus.ownLayout";

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
        if (!store.get().allowRotation) {
            return;
        }
        if (CardOrientation.isPortraitLock(activity.getRequestedOrientation())) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
        if (isPortraitCard(activity)) {
            ViewGroup content = activity.findViewById(android.R.id.content);
            if (content != null) {
                // Cards turn without a restart (CardOrientation) and fill in their content
                // after onCreate (fragments): fitted on every layout, cheaply when unchanged.
                content.getViewTreeObserver().addOnGlobalLayoutListener(
                        Hooks.guard("portrait card", () -> fit(activity, content))::run);
            }
        }
    }

    /** A card: a translucent screen over the home screen, declared portrait-only. */
    private static boolean isPortraitCard(Activity activity) {
        try {
            ActivityInfo info = activity.getPackageManager().getActivityInfo(
                    activity.getComponentName(), 0);
            if (!CardOrientation.isPortraitLock(info.screenOrientation)) {
                return false;
            }
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        TypedArray a = activity.getTheme().obtainStyledAttributes(
                new int[] {android.R.attr.windowIsTranslucent});
        try {
            return a.getBoolean(0, false);
        } finally {
            a.recycle();
        }
    }

    /**
     * Puts the card's root on a portrait canvas while the screen is wider than a portrait one,
     * and gives it back its own layout otherwise.
     */
    private static void fit(Activity activity, ViewGroup content) {
        if (content.getChildCount() != 1
                || !(content.getChildAt(0).getLayoutParams() instanceof FrameLayout.LayoutParams)) {
            return;
        }
        View root = content.getChildAt(0);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) root.getLayoutParams();
        int[] own = (int[]) XposedHelpers.getAdditionalInstanceField(root, OWN_LAYOUT);
        Rect screen = activity.getWindowManager().getMaximumWindowMetrics().getBounds();
        int portraitWidth = Math.min(screen.width(), screen.height());
        int width = content.getWidth();
        int height = content.getHeight();
        if (width <= portraitWidth || height <= 0) {
            if (own != null) {
                XposedHelpers.removeAdditionalInstanceField(root, OWN_LAYOUT);
                apply(root, lp, own[0], own[1], own[2], 1f);
            }
            return;
        }
        if (own == null) {
            own = new int[] {lp.width, lp.height, lp.gravity};
            XposedHelpers.setAdditionalInstanceField(root, OWN_LAYOUT, own);
        }
        // The height the card takes on a portrait screen; a card that only fills what it is
        // given (no height of its own) gets the landscape height.
        root.measure(View.MeasureSpec.makeMeasureSpec(portraitWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int natural = root.getMeasuredHeight();
        if (root.getWidth() > 0 && root.getHeight() > 0) {
            // Back to the size it is laid out at, for anything that reads it before the next
            // layout pass.
            root.measure(
                    View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(root.getHeight(), View.MeasureSpec.EXACTLY));
        }
        int canvasHeight = natural > 0 ? natural : height;
        float scale = Math.min(1f, Math.min(height / (float) canvasHeight,
                width / (float) portraitWidth));
        apply(root, lp, portraitWidth, canvasHeight, Gravity.CENTER, scale);
    }

    private static void apply(View root, FrameLayout.LayoutParams lp, int width, int height,
            int gravity, float scale) {
        if (lp.width != width || lp.height != height || lp.gravity != gravity) {
            lp.width = width;
            lp.height = height;
            lp.gravity = gravity;
            root.setLayoutParams(lp);
        }
        if (root.getScaleX() != scale || root.getScaleY() != scale) {
            root.setScaleX(scale);
            root.setScaleY(scale);
        }
    }
}
