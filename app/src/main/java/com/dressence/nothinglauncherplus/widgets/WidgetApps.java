package com.dressence.nothinglauncherplus.widgets;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
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
 * button off screen). In landscape each card is given what it was made for, a canvas the size
 * of the portrait screen, and what it shows there is centred in the room the system bars
 * leave, scaled down evenly only if the landscape screen is lower. Its layout, its sizes and
 * its own code see a portrait screen, so it looks and works as in portrait, whatever the screen
 * size, aspect ratio or density, and so does any card Nothing adds later.
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
     * Puts the card's root on a canvas the size of the portrait screen while the screen is wider
     * than a portrait one, and gives it back its own layout otherwise. The card lays itself out
     * there exactly as in portrait; what it shows (its views that draw) is then centred in the
     * room the system bars leave, scaled down evenly only if it does not fit.
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
        int portraitHeight = Math.max(screen.width(), screen.height());
        int width = content.getWidth();
        int height = content.getHeight();
        if (width <= portraitWidth || height <= 0) {
            if (own != null) {
                XposedHelpers.removeAdditionalInstanceField(root, OWN_LAYOUT);
                apply(root, lp, own[0], own[1], own[2]);
                place(root, 1f, 0f, 0f);
            }
            return;
        }
        if (own == null) {
            own = new int[] {lp.width, lp.height, lp.gravity};
            XposedHelpers.setAdditionalInstanceField(root, OWN_LAYOUT, own);
        }
        if (apply(root, lp, portraitWidth, portraitHeight, Gravity.CENTER)
                || root.getWidth() != portraitWidth || root.getHeight() != portraitHeight) {
            return; // Fitted once it is laid out on the canvas.
        }
        Rect shown = new Rect();
        if (root instanceof ViewGroup) {
            addShown((ViewGroup) root, (ViewGroup) root, shown);
        }
        if (shown.isEmpty()) {
            return; // Nothing drawn yet (its content comes in a moment).
        }
        Insets bars = Insets.NONE;
        WindowInsets insets = content.getRootWindowInsets();
        if (insets != null) {
            bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
        }
        int[] at = new int[2];
        content.getLocationInWindow(at);
        // The room left by the system bars, in content coordinates.
        int left = Math.max(0, bars.left - at[0]);
        int top = Math.max(0, bars.top - at[1]);
        int right = width - Math.max(0, bars.right - (content.getRootView().getWidth() - at[0]
                - width));
        int bottom = height - Math.max(0, bars.bottom - (content.getRootView().getHeight()
                - at[1] - height));
        float roomX = Math.max(1, right - left);
        float roomY = Math.max(1, bottom - top);
        float scale = Math.min(1f, Math.min(roomX / shown.width(), roomY / shown.height()));
        // The root is centred in the content and scaled about its centre; move what it shows
        // to the centre of the room.
        float dx = (left + right) / 2f - width / 2f
                - scale * (shown.exactCenterX() - portraitWidth / 2f);
        float dy = (top + bottom) / 2f - height / 2f
                - scale * (shown.exactCenterY() - portraitHeight / 2f);
        place(root, scale, dx, dy);
    }

    /** Adds to {@code shown} the bounds, in {@code root}'s coordinates, of what draws in it. */
    private static void addShown(ViewGroup root, ViewGroup group, Rect shown) {
        Rect r = new Rect();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getWidth() <= 0
                    || child.getHeight() <= 0) {
                continue;
            }
            boolean draws = !(child instanceof ViewGroup) || paints(child.getBackground());
            if (draws) {
                r.set(0, 0, child.getWidth(), child.getHeight());
                root.offsetDescendantRectToMyCoords(child, r);
                shown.union(r);
            }
            if (child instanceof ViewGroup) {
                addShown(root, (ViewGroup) child, shown);
            }
        }
    }

    private static boolean paints(Drawable background) {
        return background != null && !(background instanceof ColorDrawable
                && ((ColorDrawable) background).getAlpha() == 0);
    }

    /** Sets the root's layout; true if it changed (laid out again). */
    private static boolean apply(View root, FrameLayout.LayoutParams lp, int width, int height,
            int gravity) {
        if (lp.width == width && lp.height == height && lp.gravity == gravity) {
            return false;
        }
        lp.width = width;
        lp.height = height;
        lp.gravity = gravity;
        root.setLayoutParams(lp);
        return true;
    }

    private static void place(View root, float scale, float dx, float dy) {
        if (root.getScaleX() != scale || root.getScaleY() != scale) {
            root.setScaleX(scale);
            root.setScaleY(scale);
        }
        if (root.getTranslationX() != dx || root.getTranslationY() != dy) {
            root.setTranslationX(dx);
            root.setTranslationY(dy);
        }
    }
}
