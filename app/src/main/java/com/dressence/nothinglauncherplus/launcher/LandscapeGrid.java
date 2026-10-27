package com.dressence.nothinglauncherplus.launcher;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The landscape home screen grid.
 *
 * <p>A portrait page of C columns and R rows does not fit a landscape screen: kept as is, every
 * cell becomes a wide, flat strip. Instead, in phone landscape each page is shown as a 2C x L
 * grid with L = ceil(R / 2): the top half of the portrait page on the left, the bottom half on
 * the right. Cells keep almost exactly their portrait shape, so icons, folders and widgets look
 * the way they do in portrait, just side by side.
 *
 * <p>Only the presentation changes. The launcher database keeps the portrait layout: positions
 * go through AOSP's own {@code CellPosMapper} (the hook tablets use for two-panel pages), so
 * everything the user moves, adds or resizes in landscape is stored back in portrait
 * coordinates. Items that sit across the middle row of the portrait page are shown on whichever
 * half holds most of them, in a free spot. Because the launcher deletes items that overlap or
 * leave the grid when it next loads, every landscape edit is checked against the portrait page
 * and anything that would not fit there (a widget dropped across the fold, for instance) is
 * moved to the nearest free portrait spot.
 */
final class LandscapeGrid {

    private static final String CLS_MAPPER = "com.android.launcher3.celllayout.CellPosMapper";
    private static final String CLS_CELL_POS = CLS_MAPPER + "$CellPos";
    private static final String CLS_ITEM_INFO = "com.android.launcher3.model.data.ItemInfo";
    private static final String CLS_MODEL_WRITER = "com.android.launcher3.model.ModelWriter";
    private static final String CLS_WORKSPACE = "com.android.launcher3.Workspace";
    private static final String CLS_CELL_LAYOUT = "com.android.launcher3.CellLayout";
    private static final String CLS_LAYOUT_PARAMS =
            "com.android.launcher3.celllayout.CellLayoutLayoutParams";
    private static final String CLS_DEVICE_PROFILE = "com.android.launcher3.DeviceProfile";
    private static final String CLS_LAUNCHER = "com.android.launcher3.Launcher";

    private static final int CONTAINER_DESKTOP = -100;
    private static final int CELL_LAYOUT_WORKSPACE = 0;
    /** Lets a drop or reorder animation finish before the corrected layout is rebound. */
    private static final long RELOAD_DELAY_MS = 450;

    /** Portrait page size and the landscape projection of it. */
    private static final class Grid {
        final int columns;
        final int rows;
        /** Rows of the landscape page; portrait rows from here on go to the right half. */
        final int half;

        Grid(int columns, int rows) {
            this.columns = columns;
            this.rows = rows;
            this.half = (rows + 1) / 2;
        }

        boolean sameAs(Grid other) {
            return other != null && other.columns == columns && other.rows == rows;
        }
    }

    /** Where a straddling item was shown, valid while the item stays where it was. */
    private static final class Placement {
        final int cellX, cellY, spanX, spanY, screenId, shownX, shownY;

        Placement(int[] item, int screenId, int shownX, int shownY) {
            this.cellX = item[0];
            this.cellY = item[1];
            this.spanX = item[2];
            this.spanY = item[3];
            this.screenId = screenId;
            this.shownX = shownX;
            this.shownY = shownY;
        }

        boolean matches(int[] item, int screenId) {
            return cellX == item[0] && cellY == item[1] && spanX == item[2] && spanY == item[3]
                    && this.screenId == screenId;
        }
    }

    private final ClassLoader cl;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Class<?> launcherClass;
    private Constructor<?> cellPos;
    private volatile Object bgDataModel;

    /** Straddling items by id; main thread only. */
    private final Map<Integer, Placement> straddlers = new HashMap<>();
    private Grid straddlerGrid;

    /** Desktop items written in landscape since the last check, by portrait page; main thread. */
    private final Map<Integer, Set<Object>> written = new LinkedHashMap<>();
    private Object writer;
    private boolean checkPosted;

    LandscapeGrid(ClassLoader cl) {
        this.cl = cl;
    }

    void install() {
        Hooks.install("landscape grid", () -> {
            launcherClass = XposedHelpers.findClass(CLS_LAUNCHER, cl);
            cellPos = XposedHelpers.findConstructorExact(CLS_CELL_POS, cl,
                    int.class, int.class, int.class);
            hookMapper();
            hookCellSize();
            hookPages();
        });
        Hooks.install("landscape grid spans", this::hookSpans);
        Hooks.install("landscape grid safety", this::hookModelWriter);
    }

    // ---------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------

    /** The projection of {@code deviceProfile}, or null when it is not phone landscape. */
    private static Grid gridFor(Object deviceProfile) {
        if (deviceProfile == null || !Landscape.isPhoneLandscape(deviceProfile)) {
            return null;
        }
        Object inv = XposedHelpers.getObjectField(deviceProfile, "inv");
        int columns = XposedHelpers.getIntField(inv, "numColumns");
        int rows = XposedHelpers.getIntField(inv, "numRows");
        return columns > 0 && rows >= 2 ? new Grid(columns, rows) : null;
    }

    /** The projection the launcher is showing right now. */
    private Grid current() {
        Object launcher = XposedHelpers.callMethod(
                XposedHelpers.getStaticObjectField(launcherClass, "ACTIVITY_TRACKER"),
                "getCreatedContext");
        return launcher != null
                ? gridFor(XposedHelpers.callMethod(launcher, "getDeviceProfile")) : null;
    }

    private static int[] cells(Object item) {
        return new int[] {
                XposedHelpers.getIntField(item, "cellX"), XposedHelpers.getIntField(item, "cellY"),
                XposedHelpers.getIntField(item, "spanX"), XposedHelpers.getIntField(item, "spanY")};
    }

    private static boolean onDesktop(Object item) {
        return XposedHelpers.getIntField(item, "container") == CONTAINER_DESKTOP;
    }

    // ---------------------------------------------------------------------------------------
    // Portrait <-> landscape positions
    // ---------------------------------------------------------------------------------------

    private void hookMapper() {
        Class<?> mapper = XposedHelpers.findClass(CLS_MAPPER, cl);
        XposedHelpers.findAndHookMethod(mapper, "mapModelToPresenter",
                XposedHelpers.findClass(CLS_ITEM_INFO, cl), new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Object item = param.args[0];
                        if (!onDesktop(item)) {
                            return;
                        }
                        Grid grid = current();
                        if (grid != null) {
                            int[] shown = present(item, grid);
                            param.setResult(cellPos.newInstance(shown[0], shown[1],
                                    XposedHelpers.getIntField(item, "screenId")));
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(mapper, "mapPresenterToModel",
                int.class, int.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if ((Integer) param.args[3] != CONTAINER_DESKTOP) {
                            return;
                        }
                        int x = (Integer) param.args[0];
                        Grid grid = x > 0 ? current() : null;
                        // The left half is the portrait page as is; only the right half moves.
                        if (grid != null && x >= grid.columns) {
                            param.setResult(cellPos.newInstance(x - grid.columns,
                                    (Integer) param.args[1] + grid.half, param.args[2]));
                        }
                    }
                });
    }

    /** Landscape cell of a portrait desktop item. */
    private int[] present(Object item, Grid grid) {
        int[] c = cells(item);
        int x = c[0], y = c[1];
        if (y + c[3] <= grid.half) {
            return new int[] {x, y};
        }
        if (y >= grid.half) {
            return new int[] {x + grid.columns, y - grid.half};
        }
        return placeStraddler(item, c, grid);
    }

    /**
     * An item across the fold goes to the half holding most of it, or the other half, or the
     * nearest free cell, so it does not cover anything. Placements are remembered so the item
     * stays put while it is dragged or resized.
     */
    private int[] placeStraddler(Object item, int[] c, Grid grid) {
        int screenId = XposedHelpers.getIntField(item, "screenId");
        int id = XposedHelpers.getIntField(item, "id");
        if (!grid.sameAs(straddlerGrid)) {
            straddlers.clear();
            straddlerGrid = grid;
        }
        Placement known = straddlers.get(id);
        if (id >= 0 && known != null && known.matches(c, screenId)) {
            return new int[] {known.shownX, known.shownY};
        }
        int spanX = Math.min(c[2], grid.columns);
        int spanY = Math.min(c[3], grid.half);
        int top = grid.half - c[1];
        int bottom = c[1] + c[3] - grid.half;
        int[] upper = {c[0], Math.max(0, grid.half - spanY)};
        int[] lower = {c[0] + grid.columns, 0};
        int[][] candidates = top >= bottom
                ? new int[][] {upper, lower} : new int[][] {lower, upper};

        int[] shown = candidates[0];
        boolean[][] taken = landscapeOccupancy(item, screenId, grid);
        if (taken != null) {
            int[] free = vacant(taken, candidates, spanX, spanY);
            if (free == null) {
                free = nearestVacant(taken, candidates[0][0], candidates[0][1], spanX, spanY);
            }
            if (free != null) {
                shown = free;
            }
        }
        if (id >= 0) {
            straddlers.put(id, new Placement(c, screenId, shown[0], shown[1]));
        }
        return shown;
    }

    /** Landscape cells taken by the page's other items, or null without the model. */
    private boolean[][] landscapeOccupancy(Object self, int screenId, Grid grid) {
        List<Object> items = desktopItems(screenId);
        if (items == null) {
            return null;
        }
        boolean[][] taken = new boolean[grid.columns * 2][grid.half];
        for (Object other : items) {
            if (other == self) {
                continue;
            }
            int[] c = cells(other);
            int spanX = Math.min(c[2], grid.columns);
            int spanY = Math.min(c[3], grid.half);
            int[] at = null;
            if (c[1] + c[3] <= grid.half) {
                at = new int[] {c[0], c[1]};
            } else if (c[1] >= grid.half) {
                at = new int[] {c[0] + grid.columns, c[1] - grid.half};
            } else {
                Placement p = straddlers.get(XposedHelpers.getIntField(other, "id"));
                if (p != null && p.matches(c, screenId)) {
                    at = new int[] {p.shownX, p.shownY};
                }
            }
            if (at != null) {
                mark(taken, at[0], at[1], spanX, spanY);
            }
        }
        return taken;
    }

    // ---------------------------------------------------------------------------------------
    // Landscape pages and cells
    // ---------------------------------------------------------------------------------------

    /** Cells are measured for the 2C x L page instead of the portrait C x R one. */
    private void hookCellSize() {
        Class<?> dp = XposedHelpers.findClass(CLS_DEVICE_PROFILE, cl);
        XposedHelpers.findAndHookMethod(dp, "getCellSize", Point.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object o = param.thisObject;
                Grid grid = gridFor(o);
                if (grid == null) {
                    return;
                }
                Point point = param.args[0] != null ? (Point) param.args[0] : new Point();
                Rect pad = (Rect) XposedHelpers.getObjectField(o, "cellLayoutPaddingPx");
                Point border = (Point) XposedHelpers.getObjectField(o, "cellLayoutBorderSpacePx");
                int width = (Integer) XposedHelpers.callMethod(o, "getCellLayoutWidth")
                        - pad.left - pad.right;
                int height = (Integer) XposedHelpers.callMethod(o, "getCellLayoutHeight")
                        - pad.top - pad.bottom;
                point.x = (Integer) XposedHelpers.callStaticMethod(dp,
                        "calculateCellWidth", width, border.x, grid.columns * 2);
                point.y = (Integer) XposedHelpers.callStaticMethod(dp,
                        "calculateCellHeight", height, border.y, grid.half);
                param.setResult(point);
            }
        });
        // getCellSize() is a one-line forwarder ART may inline into its callers.
        Hooks.deoptimizeAll(dp, "getCellSize");
    }

    /** Home pages are created for the profile in use, so a rotation rebuilds them. */
    private void hookPages() {
        XposedHelpers.findAndHookMethod(CLS_WORKSPACE, cl, "insertNewWorkspaceScreen",
                int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Grid grid = current();
                        if (grid != null && param.getResult() != null) {
                            XposedHelpers.callMethod(param.getResult(), "setGridSize",
                                    grid.columns * 2, grid.half);
                        }
                    }
                });
    }

    /**
     * A widget taller than half the portrait page is shown L rows tall rather than running off
     * the page. Its stored size is untouched (see {@link #hookModelWriter}).
     */
    private void hookSpans() {
        XposedHelpers.findAndHookMethod(CLS_CELL_LAYOUT, cl, "addViewToCellLayout", View.class,
                int.class, int.class, XposedHelpers.findClass(CLS_LAYOUT_PARAMS, cl),
                boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object layout = param.thisObject;
                        if (XposedHelpers.getIntField(layout, "mContainerType")
                                != CELL_LAYOUT_WORKSPACE || current() == null) {
                            return;
                        }
                        Object lp = param.args[3];
                        int countX = XposedHelpers.getIntField(layout, "mCountX");
                        int countY = XposedHelpers.getIntField(layout, "mCountY");
                        int x = (Integer) XposedHelpers.callMethod(lp, "getCellX");
                        int y = (Integer) XposedHelpers.callMethod(lp, "getCellY");
                        clampSpan(lp, "cellHSpan", countX - x);
                        clampSpan(lp, "cellVSpan", countY - y);
                    }
                });
    }

    private static void clampSpan(Object lp, String field, int room) {
        int span = XposedHelpers.getIntField(lp, field);
        if (room >= 1 && span > room) {
            XposedHelpers.setIntField(lp, field, room);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Keeping the portrait layout loadable
    // ---------------------------------------------------------------------------------------

    private void hookModelWriter() {
        Class<?> writerClass = XposedHelpers.findClass(CLS_MODEL_WRITER, cl);
        XposedBridge.hookAllConstructors(writerClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                bgDataModel = XposedHelpers.getObjectField(param.thisObject, "mBgDataModel");
            }
        });
        Class<?> itemInfo = XposedHelpers.findClass(CLS_ITEM_INFO, cl);

        // Every add, move and resize sets the position here, after the mapper ran.
        XposedHelpers.findAndHookMethod(writerClass, "updateItemInfoProps", itemInfo,
                int.class, int.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        afterWrite(param.thisObject, param.args[0]);
                    }
                });

        XposedHelpers.findAndHookMethod(writerClass, "modifyItemInDatabase", itemInfo,
                int.class, int.class, int.class, int.class, int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Grid grid = (Integer) param.args[1] == CONTAINER_DESKTOP ? current() : null;
                        if (grid == null) {
                            return;
                        }
                        // Shown cut to L rows, so an unchanged height comes back as L.
                        int stored = XposedHelpers.getIntField(param.args[0], "spanY");
                        if (stored > grid.half && (Integer) param.args[6] == grid.half) {
                            param.args[6] = stored;
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // The spans are set after updateItemInfoProps: check with the new ones.
                        afterWrite(param.thisObject, param.args[0]);
                    }
                });
    }

    /**
     * Keeps a written desktop item inside the portrait page right away (out-of-bounds items are
     * deleted on load) and queues an overlap check of its page once the current gesture's
     * writes are done; reorders write several items one after another and only the end state
     * has to be consistent.
     */
    private void afterWrite(Object modelWriter, Object item) {
        if (!onDesktop(item)) {
            return;
        }
        Grid grid = current();
        if (grid == null) {
            return;
        }
        int[] c = cells(item);
        int x = Math.max(0, Math.min(c[0], grid.columns - Math.min(c[2], grid.columns)));
        int y = Math.max(0, Math.min(c[1], grid.rows - Math.min(c[3], grid.rows)));
        if (x != c[0] || y != c[1]) {
            XposedHelpers.setIntField(item, "cellX", x);
            XposedHelpers.setIntField(item, "cellY", y);
        }
        int screenId = XposedHelpers.getIntField(item, "screenId");
        Set<Object> items = written.get(screenId);
        if (items == null) {
            items = Collections.newSetFromMap(new IdentityHashMap<>());
            written.put(screenId, items);
        }
        items.add(item);
        writer = modelWriter;
        if (!checkPosted) {
            checkPosted = true;
            main.post(Hooks.guard("landscape grid check", this::checkWrittenPages));
        }
    }

    private void checkWrittenPages() {
        checkPosted = false;
        Grid grid = current();
        Object modelWriter = writer;
        Map<Integer, Set<Object>> pages = new LinkedHashMap<>(written);
        written.clear();
        writer = null;
        if (grid == null || modelWriter == null) {
            return;
        }
        boolean moved = false;
        for (Map.Entry<Integer, Set<Object>> page : pages.entrySet()) {
            try {
                moved |= checkPage(modelWriter, page.getKey(), page.getValue(), grid);
            } catch (Throwable t) {
                Hooks.log("landscape grid check of page " + page.getKey() + " failed: " + t);
            }
        }
        if (moved) {
            // Show the corrected positions: the views still sit where they were dropped.
            main.postDelayed(Hooks.guard("landscape grid reload", this::reload),
                    RELOAD_DELAY_MS);
        }
    }

    /** Moves written items that overlap others on the portrait page; true if any moved. */
    private boolean checkPage(Object modelWriter, int screenId, Set<Object> recent, Grid grid) {
        List<Object> items = desktopItems(screenId);
        if (items == null) {
            return false;
        }
        boolean[][] taken = new boolean[grid.columns][grid.rows];
        for (Object item : items) {
            if (!recent.contains(item)) {
                int[] c = cells(item);
                mark(taken, c[0], c[1], c[2], c[3]);
            }
        }
        boolean moved = false;
        for (Object item : recent) {
            if (!onDesktop(item) || XposedHelpers.getIntField(item, "screenId") != screenId) {
                continue; // Moved again since, to another page or into a folder.
            }
            int[] c = cells(item);
            int spanX = Math.min(c[2], grid.columns);
            int spanY = Math.min(c[3], grid.rows);
            if (isVacant(taken, c[0], c[1], spanX, spanY)) {
                mark(taken, c[0], c[1], spanX, spanY);
                continue;
            }
            int[] free = nearestVacant(taken, c[0], c[1], spanX, spanY);
            if (free == null) {
                Hooks.log("landscape grid: no portrait room on page " + screenId + " for " + item);
                continue;
            }
            mark(taken, free[0], free[1], spanX, spanY);
            // Left-half coordinates are portrait coordinates, so the mapper passes them through.
            XposedHelpers.callMethod(modelWriter, "moveItemInDatabase", item,
                    CONTAINER_DESKTOP, screenId, free[0], free[1]);
            Hooks.log("landscape grid: moved " + item + " to " + free[0] + "," + free[1]
                    + " so it fits the portrait page");
            moved = true;
        }
        return moved;
    }

    private void reload() {
        Object launcher = XposedHelpers.callMethod(
                XposedHelpers.getStaticObjectField(launcherClass, "ACTIVITY_TRACKER"),
                "getCreatedContext");
        if (launcher != null) {
            XposedHelpers.callMethod(XposedHelpers.callMethod(launcher, "getModel"), "forceReload");
        }
    }

    /** The model's items on desktop page {@code screenId}, or null before the model is known. */
    private List<Object> desktopItems(int screenId) {
        Object model = bgDataModel;
        if (model == null) {
            return null;
        }
        List<Object> items = new ArrayList<>();
        synchronized (model) {
            for (Object item : (Iterable<?>) XposedHelpers.getObjectField(model, "itemsIdMap")) {
                if (item != null && onDesktop(item)
                        && XposedHelpers.getIntField(item, "screenId") == screenId) {
                    items.add(item);
                }
            }
        }
        return items;
    }

    // ---------------------------------------------------------------------------------------
    // Occupancy
    // ---------------------------------------------------------------------------------------

    private static void mark(boolean[][] taken, int x, int y, int spanX, int spanY) {
        for (int i = Math.max(0, x); i < Math.min(taken.length, x + spanX); i++) {
            for (int j = Math.max(0, y); j < Math.min(taken[i].length, y + spanY); j++) {
                taken[i][j] = true;
            }
        }
    }

    private static boolean isVacant(boolean[][] taken, int x, int y, int spanX, int spanY) {
        if (x < 0 || y < 0 || x + spanX > taken.length || y + spanY > taken[0].length) {
            return false;
        }
        for (int i = x; i < x + spanX; i++) {
            for (int j = y; j < y + spanY; j++) {
                if (taken[i][j]) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int[] vacant(boolean[][] taken, int[][] candidates, int spanX, int spanY) {
        for (int[] c : candidates) {
            if (isVacant(taken, c[0], c[1], spanX, spanY)) {
                return c;
            }
        }
        return null;
    }

    private static int[] nearestVacant(boolean[][] taken, int x, int y, int spanX, int spanY) {
        int[] best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i + spanX <= taken.length; i++) {
            for (int j = 0; j + spanY <= taken[0].length; j++) {
                int distance = (i - x) * (i - x) + (j - y) * (j - y);
                if (distance < bestDistance && isVacant(taken, i, j, spanX, spanY)) {
                    best = new int[] {i, j};
                    bestDistance = distance;
                }
            }
        }
        return best;
    }
}
