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
 * The landscape home screen grid, arranged the way One UI does it.
 *
 * <p>A portrait page of C columns and R rows is shown in landscape as R columns by C rows: the
 * grid turns with the screen, so cells keep their shape and icons, folders and widgets look
 * as they do in portrait. The items are realigned onto it, the largest first, filling from the
 * left corner column by column; items of the same size keep their portrait reading order. A
 * widget taller than the landscape page is shown as tall as the page; its size is kept.
 *
 * <p>Only the presentation changes: the launcher database keeps the portrait layout, which
 * stays the one you arrange. Moving or resizing an item within its page in landscape does not
 * change where it sits in portrait (the landscape page arranges itself). An item added in
 * landscape, or moved onto another page there, is put in a free spot of the portrait page,
 * since the launcher deletes items that overlap or leave the grid when it next loads.
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

    /** A portrait page's size; its landscape page is {@code rows} x {@code columns}. */
    private static final class Grid {
        final int columns;
        final int rows;

        Grid(int columns, int rows) {
            this.columns = columns;
            this.rows = rows;
        }

        int landscapeColumns() {
            return rows;
        }

        int landscapeRows() {
            return columns;
        }

        boolean sameAs(Grid other) {
            return other != null && other.columns == columns && other.rows == rows;
        }
    }

    /** One page's landscape arrangement, valid while its items stay as they were. */
    private static final class Arrangement {
        final String signature;
        final Map<Object, int[]> cells = new IdentityHashMap<>();
        final boolean[][] taken;

        Arrangement(String signature, Grid grid) {
            this.signature = signature;
            taken = new boolean[grid.landscapeColumns()][grid.landscapeRows()];
        }
    }

    private final ClassLoader cl;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Class<?> launcherClass;
    private Constructor<?> cellPos;
    private volatile Object bgDataModel;

    /** Each page's arrangement, by page; rebuilt when its items change. */
    private final Map<Integer, Arrangement> arrangements = new HashMap<>();
    private Grid arrangedGrid;

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

    private Class<?> itemInfo() {
        return XposedHelpers.findClass(CLS_ITEM_INFO, cl);
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
                            int screenId = XposedHelpers.getIntField(item, "screenId");
                            int[] at = present(item, screenId, grid);
                            param.setResult(cellPos.newInstance(at[0], at[1], screenId));
                        }
                    }
                });
        // A landscape cell means nothing in portrait: the portrait position is decided when
        // the item is written (see hookModelWriter), so the cell is passed on as it is.
    }

    /** Where {@code item} is shown on its landscape page: cell x, y and spans x, y. */
    private synchronized int[] present(Object item, int screenId, Grid grid) {
        Arrangement page = arrangement(screenId, grid);
        int[] c = cells(item);
        if (page == null) {
            return new int[] {Math.min(c[1], grid.landscapeColumns() - 1),
                    Math.min(c[0], grid.landscapeRows() - 1),
                    Math.min(c[2], grid.landscapeColumns()), Math.min(c[3], grid.landscapeRows())};
        }
        int[] at = page.cells.get(item);
        if (at == null) {
            // Not in the model yet (being added): the first free spot that holds it.
            at = place(page.taken, c, grid);
            page.cells.put(item, at);
        }
        return at.clone();
    }

    /**
     * The page's landscape arrangement: its items realigned onto the turned grid, largest
     * first, from the left corner, column by column; made again when the page changes.
     */
    private Arrangement arrangement(int screenId, Grid grid) {
        List<Object> items = desktopItems(screenId);
        if (items == null) {
            return null;
        }
        if (!grid.sameAs(arrangedGrid)) {
            arrangements.clear();
            arrangedGrid = grid;
        }
        StringBuilder signature = new StringBuilder();
        for (Object item : items) {
            int[] c = cells(item);
            signature.append(XposedHelpers.getIntField(item, "id")).append(':').append(c[0])
                    .append(',').append(c[1]).append(',').append(c[2]).append(',')
                    .append(c[3]).append(';');
        }
        String key = signature.toString();
        Arrangement known = arrangements.get(screenId);
        if (known != null && known.signature.equals(key)) {
            return known;
        }
        Arrangement page = new Arrangement(key, grid);
        List<Object> order = new ArrayList<>(items);
        order.sort((a, b) -> {
            int[] x = cells(a);
            int[] y = cells(b);
            int area = Integer.compare(shownArea(y, grid), shownArea(x, grid));
            if (area != 0) {
                return area;
            }
            return x[1] != y[1] ? Integer.compare(x[1], y[1]) : Integer.compare(x[0], y[0]);
        });
        for (Object item : order) {
            page.cells.put(item, place(page.taken, cells(item), grid));
        }
        arrangements.put(screenId, page);
        return page;
    }

    private static int shownArea(int[] c, Grid grid) {
        return Math.min(c[2], grid.landscapeColumns()) * Math.min(c[3], grid.landscapeRows());
    }

    /**
     * The first free spot for an item of spans {@code c[2]} x {@code c[3]}, from the left
     * corner, column by column; marked as taken. If none holds it whole, it is shown smaller.
     */
    private static int[] place(boolean[][] taken, int[] c, Grid grid) {
        int columns = grid.landscapeColumns();
        int rows = grid.landscapeRows();
        for (int spanY = Math.min(c[3], rows); spanY >= 1; spanY--) {
            for (int spanX = Math.min(c[2], columns); spanX >= 1; spanX--) {
                for (int x = 0; x + spanX <= columns; x++) {
                    for (int y = 0; y + spanY <= rows; y++) {
                        if (isVacant(taken, x, y, spanX, spanY)) {
                            mark(taken, x, y, spanX, spanY);
                            return new int[] {x, y, spanX, spanY};
                        }
                    }
                }
            }
        }
        return new int[] {0, 0, 1, 1}; // A full page: shown over another item, not lost.
    }

    // ---------------------------------------------------------------------------------------
    // Landscape pages and cells
    // ---------------------------------------------------------------------------------------

    /** Cells are measured for the turned R x C page instead of the portrait C x R one. */
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
                        "calculateCellWidth", width, border.x, grid.landscapeColumns());
                point.y = (Integer) XposedHelpers.callStaticMethod(dp,
                        "calculateCellHeight", height, border.y, grid.landscapeRows());
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
                                    grid.landscapeColumns(), grid.landscapeRows());
                        }
                    }
                });
    }

    /**
     * Each item is drawn at the spans it is shown at on its landscape page (a widget taller
     * than the page, as tall as the page). Its stored size is untouched (see hookModelWriter).
     */
    private void hookSpans() {
        XposedHelpers.findAndHookMethod(CLS_CELL_LAYOUT, cl, "addViewToCellLayout", View.class,
                int.class, int.class, XposedHelpers.findClass(CLS_LAYOUT_PARAMS, cl),
                boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object layout = param.thisObject;
                        Grid grid = current();
                        if (XposedHelpers.getIntField(layout, "mContainerType")
                                != CELL_LAYOUT_WORKSPACE || grid == null) {
                            return;
                        }
                        Object lp = param.args[3];
                        Object info = ((View) param.args[0]).getTag();
                        if (itemInfo().isInstance(info) && onDesktop(info)) {
                            int[] at = present(info, XposedHelpers.getIntField(info, "screenId"),
                                    grid);
                            XposedHelpers.setIntField(lp, "cellHSpan", at[2]);
                            XposedHelpers.setIntField(lp, "cellVSpan", at[3]);
                        }
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

        // Every add, move and resize sets the position here, after the mapper ran. An item
        // moved within its own page in landscape keeps its portrait place: the landscape page
        // arranges itself.
        XposedHelpers.findAndHookMethod(writerClass, "updateItemInfoProps", itemInfo(),
                int.class, int.class, int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object item = param.args[0];
                        if (staysOnPage(item, (Integer) param.args[1], (Integer) param.args[2])) {
                            param.args[3] = XposedHelpers.getIntField(item, "cellX");
                            param.args[4] = XposedHelpers.getIntField(item, "cellY");
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        afterWrite(param.thisObject, param.args[0]);
                    }
                });

        XposedHelpers.findAndHookMethod(writerClass, "modifyItemInDatabase", itemInfo(),
                int.class, int.class, int.class, int.class, int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object item = param.args[0];
                        if (staysOnPage(item, (Integer) param.args[1], (Integer) param.args[2])) {
                            // Its portrait place and size, whatever it was dragged or resized
                            // to on the landscape page.
                            int[] c = cells(item);
                            param.args[3] = c[0];
                            param.args[4] = c[1];
                            param.args[5] = c[2];
                            param.args[6] = c[3];
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
     * True for a desktop item written back onto the page it is already on, in landscape: an
     * edit of the landscape arrangement, which leaves its portrait place as it is.
     */
    private boolean staysOnPage(Object item, int container, int screenId) {
        return container == CONTAINER_DESKTOP && current() != null && onDesktop(item)
                && XposedHelpers.getIntField(item, "screenId") == screenId
                && XposedHelpers.getIntField(item, "id") >= 0;
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
