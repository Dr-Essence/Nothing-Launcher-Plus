package com.dressence.nothinglauncherplus.launcher;

import android.graphics.Point;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedHelpers;

/**
 * The home screen geometry of the portrait profile, which landscape keeps exactly: cell, the
 * space between cells, and how an icon sits in its cell. Recorded as the launcher makes each
 * portrait profile, by display width, and read again from the launcher's list once the finished
 * profile is there; a landscape profile looks it up by its height. Landscape profiles are also
 * made before the portrait one is in the launcher's list (at startup, and in the copy Nothing
 * sizes its widgets with), so the list alone is not enough.
 */
final class PortraitGeometry {

    private static final Map<Integer, PortraitGeometry> BY_WIDTH = new ConcurrentHashMap<>();

    final Point cell;
    final Point border;
    final int iconSize;
    final int iconTextSize;
    final int iconDrawablePadding;
    final int cellWidth;
    final int cellHeight;
    final int cellYPadding;
    final int folderIconOffsetY;
    /** The profile this was read from, and whether it was still being made then. */
    private final Object source;
    private final boolean early;

    private PortraitGeometry(Object profile, boolean early) {
        source = profile;
        this.early = early;
        cell = (Point) XposedHelpers.callMethod(profile, "getCellSize", new Point());
        border = new Point((Point) XposedHelpers.getObjectField(profile,
                "cellLayoutBorderSpacePx"));
        iconSize = XposedHelpers.getIntField(profile, "iconSizePx");
        iconTextSize = XposedHelpers.getIntField(profile, "iconTextSizePx");
        iconDrawablePadding = XposedHelpers.getIntField(profile, "iconDrawablePaddingPx");
        cellWidth = XposedHelpers.getIntField(profile, "cellWidthPx");
        cellHeight = XposedHelpers.getIntField(profile, "cellHeightPx");
        cellYPadding = XposedHelpers.getIntField(profile, "cellYPaddingPx");
        folderIconOffsetY = XposedHelpers.getIntField(profile, "folderIconOffsetYPx");
    }

    /** The portrait profile this was read from. */
    Object profile() {
        return source;
    }

    /** True for a phone profile held upright. */
    static boolean isPortraitPhone(Object profile) {
        return !XposedHelpers.getBooleanField(profile, "isLandscape")
                && XposedHelpers.getBooleanField(profile, "isPhone");
    }

    /**
     * Remembers portrait profile {@code profile}; {@code early} while the launcher's subclass
     * may still be setting it up (read again from the list once it is there).
     */
    static void record(Object profile, boolean early) {
        PortraitGeometry geometry = new PortraitGeometry(profile, early);
        if (geometry.cell.x > 0 && geometry.cell.y > 0) {
            BY_WIDTH.put(XposedHelpers.getIntField(profile, "widthPx"), geometry);
        }
    }

    /**
     * The portrait geometry for landscape profile {@code landscape}, or null if not known: from
     * the launcher's finished portrait profile when its list has one, else as recorded.
     */
    static PortraitGeometry of(Object landscape) {
        int shortSide = XposedHelpers.getIntField(landscape, "heightPx");
        Object profiles = XposedHelpers.getObjectField(
                XposedHelpers.getObjectField(landscape, "inv"), "supportedProfiles");
        if (profiles != null) {
            for (Object profile : (Iterable<?>) profiles) {
                if (isPortraitPhone(profile)
                        && XposedHelpers.getIntField(profile, "widthPx") == shortSide) {
                    PortraitGeometry known = BY_WIDTH.get(shortSide);
                    if (known == null || known.source != profile || known.early) {
                        record(profile, false);
                    }
                    break;
                }
            }
        }
        return BY_WIDTH.get(shortSide);
    }
}
