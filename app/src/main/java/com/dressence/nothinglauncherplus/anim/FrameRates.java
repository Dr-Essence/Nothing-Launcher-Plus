package com.dressence.nothinglauncherplus.anim;

import android.view.Display;

/** What the panel can do, so animations run at its real refresh rate. */
public final class FrameRates {

    /** Animations never ask for more than this. */
    public static final float CAP = 120f;

    private FrameRates() {
    }

    /**
     * The highest refresh rate the panel offers at its current resolution, capped at
     * {@link #CAP}; 60 when unknown.
     */
    public static float target(Display display) {
        if (display == null) {
            return 60f;
        }
        Display.Mode current = display.getMode();
        float best = current.getRefreshRate();
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                    && mode.getPhysicalHeight() == current.getPhysicalHeight()) {
                best = Math.max(best, mode.getRefreshRate());
            }
        }
        return Math.min(CAP, best);
    }

    /** The rate the panel is running at right now (it may idle lower on adaptive panels). */
    public static float current(Display display) {
        return display != null ? display.getRefreshRate() : 60f;
    }
}
