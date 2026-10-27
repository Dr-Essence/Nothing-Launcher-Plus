package com.dressence.nothinglauncherplus.launcher;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.app.Activity;
import android.appwidget.AppWidgetHostView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The landscape home screen grid.
 *
 * <p>A portrait page of C columns and R rows is shown in landscape as R columns by C rows, so
 * cells keep about their shape and icons, folders and widgets look as they do in portrait. Each
 * page's items are laid out on it in their portrait reading order, left to right and top to
 * bottom: widgets first, then apps and folders in the room left. Cells have the portrait
 * cell's shape (scaled down only if the page needs it) and the grid is centred, so widgets keep
 * their portrait size and look; one taller than the page is shown as tall as the page. Sizes in
 * the launcher's database are kept.
 *
 * <p>Only the presentation changes: the launcher database keeps the portrait layout, which
 * stays the one you arrange. Moving or resizing an item within its page in landscape does not
 * change where it sits in portrait (the landscape page arranges itself). An item added in
 * landscape, or moved onto another page there, is put in a free spot of the portrait page,
 * since the launcher deletes items that overlap or leave the grid when it next loads.
 *
 * <p>The launcher also deletes an item it binds onto a cell that is already taken ("Collision
 * while binding"), which in landscape meant losing widgets for good. While a landscape page is
 * bound no cell counts as taken, and such a delete is refused; the page is arranged right
 * after.
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
    private static final String CLS_WIDGET_SIZES = "com.android.launcher3.widget.util.WidgetSizes";
    /**
     * The portrait cell of each display width, as the launcher measured it: landscape profiles
     * are also made before the portrait one is in the launcher's list (at startup, and the copy
     * Nothing sizes its widgets with), and they take their cell from here.
     */
    private static final Map<Integer, Point> PORTRAIT_CELLS = new ConcurrentHashMap<>();
    /** The longest a rotation may hold the launcher's drawing while its pages are rebuilt. */
    private static final long HOLD_DRAW_MS = 1000;
    /** How Launcher.bindInflatedItems words the delete of an item bound onto a taken cell. */
    private static final String COLLISION_DELETE = "Collision while binding";

    /**
     * A portrait page's size, and its landscape page: {@code columns} rows (the portrait page
     * turned) of cells with the portrait cell's shape, as many across as the page holds and at
     * least {@code rows}.
     */
    private static final class Grid {
        final int columns;
        final int rows;
        final int landscapeColumns;
        /** The landscape cell; null if the portrait cell is not known yet. */
        final Point cell;

        Grid(int columns, int rows, int landscapeColumns, Point cell) {
            this.columns = columns;
            this.rows = rows;
            this.landscapeColumns = landscapeColumns;
            this.cell = cell;
        }

        int landscapeColumns() {
            return landscapeColumns;
        }

        int landscapeRows() {
            return columns;
        }

        boolean sameAs(Grid other) {
            return other != null && other.columns == columns && other.rows == rows;
        }
    }

    private final ClassLoader cl;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Class<?> launcherClass;
    private Constructor<?> cellPos;
    private volatile Object bgDataModel;

    private boolean arrangePosted;
    /** The draw hold of a rotation in progress, and where it is; main thread. */
    private View heldRoot;
    private ViewTreeObserver.OnPreDrawListener heldDraw;
    private final Runnable releaseLater = Hooks.guard("rotation release", this::releaseDrawing);
    /** Depth of bindInflatedItems calls running in landscape; main thread. */
    private int binding;

    /** Desktop items written in landscape since the last check, by portrait page; main thread. */
    private final Map<Integer, Set<Object>> written = new LinkedHashMap<>();
    private Object writer;
    private boolean checkPosted;
    /** Set while the safety check writes a portrait place itself. */
    private boolean movingToPortrait;

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
            hookPagePadding();
        });
        Hooks.install("landscape grid spans", this::hookSpans);
        Hooks.install("landscape grid arrangement", this::hookArrange);
        Hooks.install("landscape grid safety", this::hookModelWriter);
        Hooks.install("clean rotation", this::hookRebindHold);
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
        if (columns <= 0 || rows < 2) {
            return null;
        }
        Point portrait = PORTRAIT_CELLS.get(XposedHelpers.getIntField(deviceProfile, "heightPx"));
        if (portrait == null) {
            portrait = portraitCell(deviceProfile);
        }
        if (portrait == null || portrait.x <= 0 || portrait.y <= 0) {
            return new Grid(columns, rows, rows, null);
        }
        Rect pad = (Rect) XposedHelpers.getObjectField(deviceProfile, "cellLayoutPaddingPx");
        Point border = (Point) XposedHelpers.getObjectField(deviceProfile,
                "cellLayoutBorderSpacePx");
        int width = (Integer) XposedHelpers.callMethod(deviceProfile, "getCellLayoutWidth")
                - pad.left - pad.right;
        int height = (Integer) XposedHelpers.callMethod(deviceProfile, "getCellLayoutHeight")
                - pad.top - pad.bottom;
        // The portrait cell, smaller only if the turned page does not fit otherwise.
        float scale = Math.min(1f, Math.min(
                (height - (columns - 1) * border.y) / (float) (columns * portrait.y),
                (width - (rows - 1) * border.x) / (float) (rows * portrait.x)));
        Point cell = new Point(Math.max(1, (int) (portrait.x * scale)),
                Math.max(1, (int) (portrait.y * scale)));
        int across = Math.max(rows, (width + border.x) / (cell.x + border.x));
        return new Grid(columns, rows, across, cell);
    }

    /** The launcher activity that is up, or null. */
    private Object createdLauncher() {
        return XposedHelpers.callMethod(
                XposedHelpers.getStaticObjectField(launcherClass, "ACTIVITY_TRACKER"),
                "getCreatedContext");
    }

    /** The projection the launcher is showing right now. */
    private Grid current() {
        Object launcher = createdLauncher();
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

    /**
     * While a landscape page is built, each item is put in its portrait cell turned with the
     * screen (portrait row as landscape column, portrait column as landscape row): always a
     * cell of the R x C page, so the launcher never drops one for being off the page, and
     * worked out from the item alone, without the launcher's model. The page is arranged as
     * soon as it is built (see hookArrange). An item already on a page is where it was arranged
     * (dragging and resizing start from there).
     */
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
                        if (grid == null) {
                            return;
                        }
                        int screenId = XposedHelpers.getIntField(item, "screenId");
                        View shown = shownView(item, screenId);
                        if (shown != null) {
                            Object lp = shown.getLayoutParams();
                            param.setResult(cellPos.newInstance(
                                    (Integer) XposedHelpers.callMethod(lp, "getCellX"),
                                    (Integer) XposedHelpers.callMethod(lp, "getCellY"),
                                    screenId));
                            return;
                        }
                        int[] c = cells(item);
                        param.setResult(cellPos.newInstance(
                                Math.min(c[1], grid.landscapeColumns() - 1),
                                Math.min(c[0], grid.landscapeRows() - 1), screenId));
                    }
                });
        // A landscape cell means nothing in portrait: the portrait position is decided when
        // the item is written (see hookModelWriter), so the cell is passed on as it is.
    }

    /** The view showing {@code item} on desktop page {@code screenId}, or null; main thread. */
    private View shownView(Object item, int screenId) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return null;
        }
        Object launcher = createdLauncher();
        if (launcher == null) {
            return null;
        }
        Object page = XposedHelpers.callMethod(XposedHelpers.callMethod(launcher, "getWorkspace"),
                "getScreenWithId", screenId);
        if (page == null) {
            return null;
        }
        ViewGroup container = (ViewGroup) XposedHelpers.callMethod(page, "getShortcutsAndWidgets");
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child.getTag() == item) {
                return child;
            }
        }
        return null;
    }

    /**
     * Once the launcher has put a page's items on it (and after items are added), each page is
     * arranged: its items, from their portrait places and sizes, laid out on the turned grid.
     * Only the page's own views are moved; the model is not touched.
     */
    private void hookArrange() {
        XC_MethodHook arrange = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                postArrange();
            }
        };
        // Every way items reach the pages: the loader's pre-inflated pages, later additions,
        // a widget bound on its own, and the end of a bind.
        for (String bind : new String[] {"bindInflatedItems", "bindItems", "bindAppWidget",
                "finishBindingItems"}) {
            XposedBridge.hookAllMethods(launcherClass, bind, arrange);
        }

        // Items are bound onto their turned portrait cells, which may overlap until the page is
        // arranged: none of them is a collision.
        XposedBridge.hookAllMethods(launcherClass, "bindInflatedItems", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                binding++;
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                binding--;
            }
        });
        // isOccupied is small enough for ART to inline into the bind.
        Hooks.deoptimizeAll(launcherClass, "bindInflatedItems");
        XposedHelpers.findAndHookMethod(CLS_CELL_LAYOUT, cl, "isOccupied", int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (binding > 0 && XposedHelpers.getIntField(param.thisObject,
                                "mContainerType") == CELL_LAYOUT_WORKSPACE && current() != null) {
                            param.setResult(false);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(CLS_MODEL_WRITER, cl, "deleteItemFromDatabase",
                itemInfo(), String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String reason = (String) param.args[1];
                        if (reason != null && reason.startsWith(COLLISION_DELETE)
                                && current() != null) {
                            Hooks.log("landscape grid: kept " + param.args[0]
                                    + " (the launcher wanted to delete it: " + reason + ")");
                            param.setResult(null);
                        }
                    }
                });
    }

    private void postArrange() {
        if (!arrangePosted) {
            arrangePosted = true;
            main.post(Hooks.guard("landscape arrangement", () -> {
                arrangePosted = false;
                arrangeAll();
            }));
        }
    }

    private void arrangeAll() {
        Grid grid = current();
        if (grid == null) {
            return;
        }
        Object launcher = createdLauncher();
        if (launcher == null) {
            return;
        }
        Object screens = XposedHelpers.getObjectField(
                XposedHelpers.callMethod(launcher, "getWorkspace"), "mWorkspaceScreens");
        int count = (Integer) XposedHelpers.callMethod(screens, "size");
        for (int i = 0; i < count; i++) {
            arrange(XposedHelpers.callMethod(screens, "valueAt", i), grid);
        }
        if (count > 0) {
            checkIconSize(XposedHelpers.callMethod(screens, "valueAt", 0),
                    XposedHelpers.callMethod(launcher, "getDeviceProfile"));
        }
    }

    /** Logs it if a page's icons are drawn at another size than the launcher's profile says. */
    private static void checkIconSize(Object cellLayout, Object profile) {
        ViewGroup container = (ViewGroup) XposedHelpers.callMethod(cellLayout,
                "getShortcutsAndWidgets");
        int wanted = XposedHelpers.getIntField(profile, "iconSizePx");
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            Object tag = child.getTag();
            if (child instanceof android.widget.TextView && tag != null
                    && XposedHelpers.getIntField(tag, "spanX") == 1) {
                int size = (Integer) XposedHelpers.callMethod(child, "getIconSize");
                if (size != wanted) {
                    Hooks.log("landscape icons drawn at " + size + " px, profile " + wanted);
                }
                return;
            }
        }
    }

    /** True for an item as wide as the portrait page (a calendar, say). */
    private static boolean pageWide(int[] c, Grid grid) {
        return c[2] > 1 && c[2] >= grid.columns;
    }

    // ---------------------------------------------------------------------------------------
    // Rotation
    // ---------------------------------------------------------------------------------------

    /**
     * A rotation relays the launcher out and rebinds its pages, and the pages are filled a
     * moment later: the first frames on the turned screen showed the old pages stretched to it,
     * then empty pages, then the items. While Android turns the screen it keeps showing the
     * last frame until the app draws, so drawing is held from the rebind until the pages are
     * filled and arranged (as the launcher itself does at startup), for a second at most: the
     * turn goes straight to the finished screen.
     */
    private void hookRebindHold() {
        XposedHelpers.findAndHookMethod(launcherClass, "rebindCallbacks", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                holdDrawing((Activity) param.thisObject);
            }
        });
        XposedBridge.hookAllMethods(launcherClass, "finishBindingItems", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                // After the arrangement, which finishBindingItems also posts.
                main.post(Hooks.guard("rotation release", () -> releaseDrawing()));
            }
        });
    }

    private void holdDrawing(Activity launcher) {
        releaseDrawing();
        if (!launcher.hasWindowFocus()) {
            // Not a turn of the home screen in view (an app opening over it turns it): its
            // own animation must keep drawing.
            return;
        }
        View root = launcher.getWindow().getDecorView();
        ViewTreeObserver.OnPreDrawListener hold = () -> false;
        root.getViewTreeObserver().addOnPreDrawListener(hold);
        heldRoot = root;
        heldDraw = hold;
        main.postDelayed(releaseLater, HOLD_DRAW_MS);
    }

    private void releaseDrawing() {
        main.removeCallbacks(releaseLater);
        if (heldDraw != null) {
            heldRoot.getViewTreeObserver().removeOnPreDrawListener(heldDraw);
            heldRoot.invalidate();
            heldDraw = null;
            heldRoot = null;
        }
    }

    /**
     * Lays one page's items out on the turned grid in portrait reading order, left to right and
     * top to bottom: widgets first, then apps and folders in the room left.
     */
    private void arrange(Object cellLayout, Grid grid) {
        ViewGroup container = (ViewGroup) XposedHelpers.callMethod(cellLayout,
                "getShortcutsAndWidgets");
        Class<?> info = itemInfo();
        List<View> views = new ArrayList<>();
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (info.isInstance(child.getTag()) && onDesktop(child.getTag())) {
                views.add(child);
            }
        }
        if (views.isEmpty()) {
            return;
        }
        views.sort((a, b) -> {
            int[] x = cells(a.getTag());
            int[] y = cells(b.getTag());
            boolean xSingle = x[2] * x[3] == 1;
            boolean ySingle = y[2] * y[3] == 1;
            if (xSingle != ySingle) {
                return xSingle ? 1 : -1;
            }
            return x[1] != y[1] ? Integer.compare(x[1], y[1]) : Integer.compare(x[0], y[0]);
        });
        boolean[][] taken = new boolean[grid.landscapeColumns()][grid.landscapeRows()];
        int[][] places = new int[views.size()][];
        boolean changed = false;
        for (int i = 0; i < views.size(); i++) {
            places[i] = place(taken, cells(views.get(i).getTag()), grid);
            Object lp = views.get(i).getLayoutParams();
            changed |= (Integer) XposedHelpers.callMethod(lp, "getCellX") != places[i][0]
                    || (Integer) XposedHelpers.callMethod(lp, "getCellY") != places[i][1]
                    || XposedHelpers.getIntField(lp, "cellHSpan") != places[i][2]
                    || XposedHelpers.getIntField(lp, "cellVSpan") != places[i][3];
        }
        if (!changed) {
            return;
        }
        // Every view leaves its cells before any takes its new ones.
        for (View view : views) {
            XposedHelpers.callMethod(cellLayout, "markCellsAsUnoccupiedForView", view);
        }
        for (int i = 0; i < views.size(); i++) {
            View view = views.get(i);
            Object lp = view.getLayoutParams();
            boolean resized = XposedHelpers.getIntField(lp, "cellHSpan") != places[i][2]
                    || XposedHelpers.getIntField(lp, "cellVSpan") != places[i][3];
            XposedHelpers.callMethod(lp, "setCellX", places[i][0]);
            XposedHelpers.callMethod(lp, "setCellY", places[i][1]);
            XposedHelpers.setIntField(lp, "cellHSpan", places[i][2]);
            XposedHelpers.setIntField(lp, "cellVSpan", places[i][3]);
            XposedHelpers.callMethod(cellLayout, "markCellsAsOccupiedForView", view);
            if (resized) {
                tellWidgetItsSize(view, places[i][2], places[i][3]);
            }
        }
        container.requestLayout();
    }

    /**
     * A widget shown at other spans is told its size, so it draws for it: Nothing's widgets and
     * cards take their corners from it, app widgets their layout.
     */
    private void tellWidgetItsSize(View view, int spanX, int spanY) {
        try {
            XposedHelpers.callMethod(view, "applyDisplaySpan", spanX, spanY);
        } catch (NoSuchMethodError notNothings) {
            // Not one of Nothing's widgets.
        }
        if (view instanceof AppWidgetHostView) {
            try {
                XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_WIDGET_SIZES, cl),
                        "updateWidgetSizeRanges", view, createdLauncher(), spanX, spanY);
            } catch (Throwable t) {
                Hooks.log("landscape grid: widget size not updated: " + t);
            }
        }
    }

    /**
     * The first free spot for an item of portrait spans {@code c[2]} x {@code c[3]}, row by
     * row from the top left, marked as taken: cell x, y and the spans it is shown at, its
     * portrait spans where they fit; a widget as wide as the portrait page is as wide as the
     * landscape page, as it fills the page in portrait. If no spot holds it whole, it is shown
     * smaller.
     */
    private static int[] place(boolean[][] taken, int[] c, Grid grid) {
        int columns = grid.landscapeColumns();
        int rows = grid.landscapeRows();
        for (int spanY = Math.min(c[3], rows); spanY >= 1; spanY--) {
            int wide = pageWide(c, grid) ? columns : Math.min(c[2], columns);
            for (int spanX = wide; spanX >= 1; spanX--) {
                for (int y = 0; y + spanY <= rows; y++) {
                    for (int x = 0; x + spanX <= columns; x++) {
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

    /**
     * Landscape cells have the portrait cell's shape, so every icon, folder and widget looks as
     * it does in portrait (see Grid); the page's cells are centred on it (hookPagePadding).
     */
    private void hookCellSize() {
        Class<?> dp = XposedHelpers.findClass(CLS_DEVICE_PROFILE, cl);
        XposedHelpers.findAndHookMethod(dp, "getCellSize", Point.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object o = param.thisObject;
                Grid grid = gridFor(o);
                if (grid == null || grid.cell == null) {
                    return;
                }
                Point point = param.args[0] != null ? (Point) param.args[0] : new Point();
                point.set(grid.cell.x, grid.cell.y);
                param.setResult(point);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object o = param.thisObject;
                if (param.getResult() instanceof Point
                        && !XposedHelpers.getBooleanField(o, "isLandscape")
                        && XposedHelpers.getBooleanField(o, "isPhone")) {
                    PORTRAIT_CELLS.put(XposedHelpers.getIntField(o, "widthPx"),
                            new Point((Point) param.getResult()));
                }
            }
        });
        // getCellSize() is a one-line forwarder ART may inline into its callers.
        Hooks.deoptimizeAll(dp, "getCellSize");
    }

    /** The cell of the portrait profile for the same display as landscape profile {@code o}. */
    private static Point portraitCell(Object o) {
        Object inv = XposedHelpers.getObjectField(o, "inv");
        int shortSide = XposedHelpers.getIntField(o, "heightPx");
        Object profiles = XposedHelpers.getObjectField(inv, "supportedProfiles");
        if (profiles == null) {
            return null; // Still being made.
        }
        for (Object profile : (Iterable<?>) profiles) {
            if (!XposedHelpers.getBooleanField(profile, "isLandscape")
                    && XposedHelpers.getBooleanField(profile, "isPhone")
                    && XposedHelpers.getIntField(profile, "widthPx") == shortSide) {
                // Not landscape, so this is the launcher's own measure.
                return (Point) XposedHelpers.callMethod(profile, "getCellSize", new Point());
            }
        }
        return null;
    }

    /**
     * A landscape page measures its cells from its own size; it is padded so that they come out
     * as Grid.cell, the grid centred in the room left over.
     */
    private void hookPagePadding() {
        XposedHelpers.findAndHookMethod(CLS_CELL_LAYOUT, cl, "onMeasure", int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        View layout = (View) param.thisObject;
                        if (XposedHelpers.getIntField(layout, "mContainerType")
                                != CELL_LAYOUT_WORKSPACE) {
                            return;
                        }
                        Object launcher = createdLauncher();
                        if (launcher == null) {
                            return;
                        }
                        Object profile = XposedHelpers.callMethod(launcher, "getDeviceProfile");
                        Rect base = (Rect) XposedHelpers.getObjectField(profile,
                                "cellLayoutPaddingPx");
                        Grid grid = gridFor(profile);
                        Point cell = grid != null ? grid.cell : null;
                        int left = base.left;
                        int top = base.top;
                        int right = base.right;
                        int bottom = base.bottom;
                        if (cell != null && XposedHelpers.getIntField(layout, "mCountX")
                                == grid.landscapeColumns()) {
                            Point border = (Point) XposedHelpers.getObjectField(layout,
                                    "mBorderSpace");
                            int columns = grid.landscapeColumns();
                            int rows = grid.landscapeRows();
                            int spareX = View.MeasureSpec.getSize((Integer) param.args[0])
                                    - base.left - base.right
                                    - columns * cell.x - (columns - 1) * border.x;
                            int spareY = View.MeasureSpec.getSize((Integer) param.args[1])
                                    - base.top - base.bottom
                                    - rows * cell.y - (rows - 1) * border.y;
                            if (spareX > 0) {
                                left += spareX / 2;
                                right += spareX - spareX / 2;
                            }
                            if (spareY > 0) {
                                top += spareY / 2;
                                bottom += spareY - spareY / 2;
                            }
                        }
                        if (layout.getPaddingLeft() != left || layout.getPaddingTop() != top
                                || layout.getPaddingRight() != right
                                || layout.getPaddingBottom() != bottom) {
                            layout.setPadding(left, top, right, bottom);
                        }
                    }
                });
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
     * While a page is built, no item reaches past its edge (its turned cell may be near it);
     * the arrangement then gives each its spans. Stored sizes are untouched (hookModelWriter).
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
        return !movingToPortrait && container == CONTAINER_DESKTOP && current() != null && onDesktop(item)
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
        for (Map.Entry<Integer, Set<Object>> page : pages.entrySet()) {
            try {
                checkPage(modelWriter, page.getKey(), page.getValue(), grid);
            } catch (Throwable t) {
                Hooks.log("landscape grid check of page " + page.getKey() + " failed: " + t);
            }
        }
        // The page arranges itself again from the portrait places (no model reload).
        postArrange();
    }

    /** Moves written items that overlap others on the portrait page. */
    private void checkPage(Object modelWriter, int screenId, Set<Object> recent, Grid grid) {
        List<Object> items = desktopItems(screenId);
        if (items == null) {
            return;
        }
        boolean[][] taken = new boolean[grid.columns][grid.rows];
        for (Object item : items) {
            if (!recent.contains(item)) {
                int[] c = cells(item);
                mark(taken, c[0], c[1], c[2], c[3]);
            }
        }
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
            // A portrait place, written as it is (staysOnPage would keep the old one).
            movingToPortrait = true;
            try {
                XposedHelpers.callMethod(modelWriter, "moveItemInDatabase", item,
                        CONTAINER_DESKTOP, screenId, free[0], free[1]);
            } finally {
                movingToPortrait = false;
            }
            Hooks.log("landscape grid: moved " + item + " to " + free[0] + "," + free[1]
                    + " so it fits the portrait page");
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
