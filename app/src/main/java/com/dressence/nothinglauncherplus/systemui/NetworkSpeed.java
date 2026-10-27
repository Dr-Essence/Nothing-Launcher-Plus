package com.dressence.nothinglauncherplus.systemui;

import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.View;
import android.widget.ImageView;

/**
 * Nothing's network speed indicator ({@code NetworkSpeedView}) shows its two tiny lines as
 * images of text, drawn by Nothing at exactly their pixel size. Redrawn through a glow they
 * break up, so they are only lit green: a colour filter on the image, every pixel left where
 * Nothing put it.
 */
final class NetworkSpeed {

    private static final PorterDuffColorFilter GREEN =
            new PorterDuffColorFilter(Led.GREEN, PorterDuff.Mode.SRC_IN);

    private NetworkSpeed() {
    }

    /** True for a view inside the indicator. */
    static boolean contains(View view) {
        Object p = view.getParent();
        for (int i = 0; i < 4 && p instanceof View; i++, p = ((View) p).getParent()) {
            if (p.getClass().getName().contains("NetworkSpeed")) {
                return true;
            }
        }
        return false;
    }

    /** {@code image} (part of the indicator) green when {@code on}, as Nothing drew it if not. */
    static void light(ImageView image, boolean on) {
        if (on && image.getColorFilter() != GREEN) {
            image.setColorFilter(GREEN);
        } else if (!on && image.getColorFilter() == GREEN) {
            image.clearColorFilter();
        }
    }
}
