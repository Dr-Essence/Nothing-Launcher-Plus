package com.dressence.nothinglauncherplus.widgets;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
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
    /** Set on a card activity: its content view, which holds the card. */
    private static final String CARD_CONTENT = "nlplus.cardContent";

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
        // A card turns without a restart (CardOrientation): it is put on (or taken off) its
        // canvas as the turn is told, before it is laid out for the turned screen, so it is
        // never laid out or drawn stretched across it.
        XposedHelpers.findAndHookMethod(Activity.class, "onConfigurationChanged",
                Configuration.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Activity activity = (Activity) param.thisObject;
                        Object content = XposedHelpers.getAdditionalInstanceField(activity,
                                CARD_CONTENT);
                        if (content != null) {
                            Hooks.guard("portrait card turn",
                                    () -> prepare(activity, (ViewGroup) content)).run();
                        }
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
                XposedHelpers.setAdditionalInstanceField(activity, CARD_CONTENT, content);
                // Before its first layout, so it is never drawn stretched across the screen.
                prepare(activity, content);
                // Cards fill in their content after onCreate (fragments) and change it as they
                // run: fitted after every layout, before it is drawn, cheaply when unchanged.
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

    /** The card's root, or null if the content is not one view in a FrameLayout. */
    private static View root(ViewGroup content) {
        return content.getChildCount() == 1
                && content.getChildAt(0).getLayoutParams() instanceof FrameLayout.LayoutParams
                ? content.getChildAt(0) : null;
    }

    private static boolean isLandscape(Activity activity) {
        return activity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }

    /** The portrait screen: {width, height}. */
    private static int[] portraitScreen(Activity activity) {
        Rect screen = activity.getWindowManager().getMaximumWindowMetrics().getBounds();
        return new int[] {Math.min(screen.width(), screen.height()),
                Math.max(screen.width(), screen.height())};
    }

    /**
     * Puts the card's root on a canvas the size of the portrait screen in landscape, and gives
     * it back its own layout in portrait. On the canvas the card lays itself out exactly as in
     * portrait, so turning the screen does not lay it out again at all: only where it is shown,
     * and how large, changes (fit).
     */
    private static void prepare(Activity activity, ViewGroup content) {
        View root = root(content);
        if (root == null) {
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) root.getLayoutParams();
        int[] own = (int[]) XposedHelpers.getAdditionalInstanceField(root, OWN_LAYOUT);
        if (!isLandscape(activity)) {
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
        int[] portrait = portraitScreen(activity);
        apply(root, lp, portrait[0], portrait[1], Gravity.CENTER);
    }

    /**
     * Shows what the card draws on its canvas (its views that draw) centred in the room the
     * system bars leave, scaled down evenly only if it does not fit. Run after each layout,
     * before the frame is drawn.
     */
    private static void fit(Activity activity, ViewGroup content) {
        View root = root(content);
        if (root == null) {
            return;
        }
        if (!isLandscape(activity)
                || XposedHelpers.getAdditionalInstanceField(root, OWN_LAYOUT) == null) {
            prepare(activity, content);
            return;
        }
        int[] portrait = portraitScreen(activity);
        int width = content.getWidth();
        int height = content.getHeight();
        if (root.getWidth() != portrait[0] || root.getHeight() != portrait[1]
                || root.isLayoutRequested() || width <= 0 || height <= 0) {
            return; // Not laid out on its canvas yet: fitted after that layout.
        }
        Rect shown = new Rect();
        if (root instanceof ViewGroup) {
            addShown((ViewGroup) root, (ViewGroup) root, shown);
        }
        if (shown.isEmpty() || !shown.intersect(0, 0, portrait[0], portrait[1])) {
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
        View window = content.getRootView();
        // The room left by the system bars, in content coordinates.
        int left = Math.max(0, bars.left - at[0]);
        int top = Math.max(0, bars.top - at[1]);
        int right = width - Math.max(0, bars.right - (window.getWidth() - at[0] - width));
        int bottom = height - Math.max(0, bars.bottom - (window.getHeight() - at[1] - height));
        float roomX = Math.max(1, right - left);
        float roomY = Math.max(1, bottom - top);
        float scale = Math.min(1f, Math.min(roomX / shown.width(), roomY / shown.height()));
        // The root is centred in the content and scaled about its centre; move what it shows
        // to the centre of the room.
        float dx = (left + right) / 2f - width / 2f
                - scale * (shown.exactCenterX() - portrait[0] / 2f);
        float dy = (top + bottom) / 2f - height / 2f
                - scale * (shown.exactCenterY() - portrait[1] / 2f);
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
