package com.dressence.nothinglauncherplus.launcher;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
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
 * <p>The landscape page is the portrait page turned: a C x R portrait grid becomes R x C, the
 * same cells, so every page fits whatever the grid, screen or density. Each item sits at its
 * portrait place turned (its portrait row is its column, its portrait column its row) with
 * its own spans, so icons, folders and square widgets never meet; a widget of another shape
 * whose spot is taken goes to the nearest free spot that holds it whole. The cells fill the
 * page; icons, folders and widget contents are the portrait ones scaled as far as the cells
 * allow, the same both ways, and centred in their cells (widgets through Nothing's own widget
 * padding), so they keep their portrait shape. A widget as wide as the portrait page (a
 * calendar) fills the landscape page's width.
 *
 * <p>Moving or resizing an item within its page in landscape is remembered for landscape
 * (LandscapeMemory); the launcher database keeps the portrait layout, so portrait is not
 * changed by it. Remembered places are laid first, the rest turned around them. An item added in
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
    private static final String CLS_ACTIVITY_CONTEXT = "com.android.launcher3.views.ActivityContext";
    /** Nothing's widget views, which take their padding from the span they are told. */
    private static final String[] CLS_WIDGET_VIEWS = {
            "com.android.launcher3.widget.NavigableAppWidgetHostView",
            "com.nothing.launcher.card.NavigableCardWidgetHostView",
            "com.nothing.launcher.card.BaseLauncherCardHostView"};

    private static final int CONTAINER_DESKTOP = -100;
    private static final int CELL_LAYOUT_WORKSPACE = 0;
    /** The longest a rotation may hold the launcher's drawing while its pages are rebuilt. */
    private static final long HOLD_DRAW_MS = 1000;
    /** How Launcher.bindInflatedItems words the delete of an item bound onto a taken cell. */
    private static final String COLLISION_DELETE = "Collision while binding";

    /**
     * A portrait page of {@code columns} x {@code rows}, and its landscape page: the portrait
     * page turned, {@code rows} x {@code columns} cells filling the page. {@code scale} is how
     * large icons and widget contents are against portrait: as large as the cells allow, the
     * same both ways, so they keep their portrait shape.
     */
    private static final class Grid {
        final int columns;
        final int rows;
        /** The landscape cell and the space between cells; null if portrait is not known. */
        final Point cell;
        final Point border;
        final float scale;
        final PortraitGeometry portrait;
        /** The width the landscape grid is laid out in; for the log. */
        final int width;

        Grid(int columns, int rows, Point cell, Point border, float scale,
                PortraitGeometry portrait, int width) {
            this.columns = columns;
            this.rows = rows;
            this.cell = cell;
            this.border = border;
            this.scale = scale;
            this.portrait = portrait;
            this.width = width;
        }

        int landscapeColumns() {
            return rows;
        }

        int landscapeRows() {
            return columns;
        }
    }

    private final ClassLoader cl;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Class<?> launcherClass;
    private Class<?> activityContext;
    private Constructor<?> cellPos;
    private volatile Object bgDataModel;

    private boolean arrangePosted;
    /** Landscape places of items moved or resized in landscape; made with the launcher. */
    private LandscapeMemory memory;
    /** The landscape grid last logged; main thread. */
    private String loggedSize;
    /** Set once Nothing's widget padding method is hooked. */
    private boolean widgetPaddingHooked;
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
            activityContext = XposedHelpers.findClass(CLS_ACTIVITY_CONTEXT, cl);
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
        Hooks.install("landscape widget spans", this::hookDisplaySpan);
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
        PortraitGeometry portrait = PortraitGeometry.of(deviceProfile);
        if (portrait == null) {
            return new Grid(columns, rows, null, null, 1f, null, 0);
        }
        Rect pad = (Rect) XposedHelpers.getObjectField(deviceProfile, "cellLayoutPaddingPx");
        // Nothing's side padding only centres the portrait grid; the landscape grid is centred
        // in the whole width (hookPagePadding).
        int width = (Integer) XposedHelpers.callMethod(deviceProfile, "getCellLayoutWidth");
        int height = (Integer) XposedHelpers.callMethod(deviceProfile, "getCellLayoutHeight")
                - pad.top - pad.bottom;
        int across = rows;
        int down = columns;
        Point p = portrait.cell;
        Point pb = portrait.border;
        // The space between cells scales with the portrait cell; the cells fill the rest.
        float fit = Math.min(width / (float) (across * (p.x + pb.x) - pb.x),
                height / (float) (down * (p.y + pb.y) - pb.y));
        Point border = new Point(Math.round(pb.x * fit), Math.round(pb.y * fit));
        Point cell = new Point(
                Math.max(1, (width - (across - 1) * border.x) / across),
                Math.max(1, (height - (down - 1) * border.y) / down));
        float scale = Math.min(cell.x / (float) p.x, cell.y / (float) p.y);
        return new Grid(columns, rows, cell, border, scale, portrait, width);
    }

    /** The scale of icons and widget contents against portrait for {@code profile}, or 0. */
    static float contentScale(Object profile) {
        Grid grid = gridFor(profile);
        return grid != null && grid.cell != null ? grid.scale : 0;
    }

    /** The landscape cell height for {@code profile}, or 0. */
    static int cellHeight(Object profile) {
        Grid grid = gridFor(profile);
        return grid != null && grid.cell != null ? grid.cell.y : 0;
    }

    /** The key landscape places are kept under: the portrait grid and the landscape one. */
    private static String gridKey(Grid grid) {
        return grid.columns + "x" + grid.rows + ">" + grid.landscapeColumns() + "x"
                + grid.landscapeRows();
    }

    private LandscapeMemory memory() {
        if (memory == null) {
            Object launcher = createdLauncher();
            if (launcher != null) {
                memory = new LandscapeMemory((android.content.Context) launcher);
            }
        }
        return memory;
    }

    /**
     * The grid for {@code view}'s home screen, or null: only the launcher's own pages are
     * landscape pages. Nothing draws previews (the home screen layout in its settings) with
     * the same page classes in its own context, and those are left as they are.
     */
    private Grid gridOfView(View view) {
        Object launcher = createdLauncher();
        if (launcher == null || XposedHelpers.callStaticMethod(
                activityContext, "lookupContext",
                view.getContext()) != launcher) {
            return null;
        }
        return gridFor(XposedHelpers.callMethod(launcher, "getDeviceProfile"));
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
                        Object launcher = createdLauncher();
                        if (launcher == null || param.thisObject
                                != XposedHelpers.callMethod(launcher, "getCellPosMapper")) {
                            return; // A preview's mapper, or no launcher up.
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
                        int[] saved = saved(grid, item, screenId);
                        if (saved != null) {
                            param.setResult(cellPos.newInstance(saved[0], saved[1], screenId));
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
                                "mContainerType") == CELL_LAYOUT_WORKSPACE
                                && gridOfView((View) param.thisObject) != null) {
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
        String size = grid.landscapeColumns() + "x" + grid.landscapeRows()
                + (grid.cell != null ? " cells " + grid.cell.x + "x" + grid.cell.y
                        + " gap " + grid.border.x + "x" + grid.border.y
                        + " in " + grid.width + " px" : "");
        if (!size.equals(loggedSize)) {
            loggedSize = size;
            Hooks.log("landscape grid " + size + " for portrait " + grid.columns + "x"
                    + grid.rows);
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

    /**
     * The landscape place {@code x, y, spanX, spanY} kept for {@code item} on page
     * {@code screenId}, if it still fits the page; else null.
     */
    private int[] saved(Grid grid, Object item, int screenId) {
        LandscapeMemory memory = memory();
        if (memory == null) {
            return null;
        }
        int[] place = memory.get(gridKey(grid), XposedHelpers.getIntField(item, "id"), screenId);
        if (place == null || place[0] < 0 || place[1] < 0 || place[2] < 1 || place[3] < 1
                || place[0] + place[2] > grid.landscapeColumns()
                || place[1] + place[3] > grid.landscapeRows()) {
            return null;
        }
        return place;
    }

    /**
     * Keeps where {@code item} was put on the landscape page: where its view now is (a drop or
     * a resize has already laid it there), else the written cells.
     */
    private void remember(Object item, int screenId, int x, int y, int spanX, int spanY) {
        Grid grid = current();
        LandscapeMemory memory = memory();
        if (grid == null || memory == null || XposedHelpers.getIntField(item, "id") < 0) {
            return;
        }
        View shown = shownView(item, screenId);
        if (shown != null) {
            Object lp = shown.getLayoutParams();
            x = (Integer) XposedHelpers.callMethod(lp, "getCellX");
            y = (Integer) XposedHelpers.callMethod(lp, "getCellY");
            spanX = XposedHelpers.getIntField(lp, "cellHSpan");
            spanY = XposedHelpers.getIntField(lp, "cellVSpan");
        }
        memory.put(gridKey(grid), XposedHelpers.getIntField(item, "id"), screenId, x, y,
                spanX, spanY);
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
            // A page-wide widget takes what the others leave.
            boolean xWide = pageWide(x, grid);
            boolean yWide = pageWide(y, grid);
            if (xWide != yWide) {
                return xWide ? 1 : -1;
            }
            int area = Integer.compare(y[2] * y[3], x[2] * x[3]);
            if (area != 0) {
                return area;
            }
            return x[1] != y[1] ? Integer.compare(x[1], y[1]) : Integer.compare(x[0], y[0]);
        });
        boolean[][] taken = new boolean[grid.landscapeColumns()][grid.landscapeRows()];
        int[][] places = new int[views.size()][];
        // Places kept from landscape edits first, as they were left.
        for (int i = 0; i < views.size(); i++) {
            Object tag = views.get(i).getTag();
            int[] saved = saved(grid, tag, XposedHelpers.getIntField(tag, "screenId"));
            if (saved != null && isVacant(taken, saved[0], saved[1], saved[2], saved[3])) {
                mark(taken, saved[0], saved[1], saved[2], saved[3]);
                places[i] = saved;
            }
        }
        boolean changed = false;
        for (int i = 0; i < views.size(); i++) {
            if (places[i] == null) {
                places[i] = place(taken, cells(views.get(i).getTag()), grid);
            }
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
            XposedHelpers.callMethod(lp, "setCellX", places[i][0]);
            XposedHelpers.callMethod(lp, "setCellY", places[i][1]);
            XposedHelpers.setIntField(lp, "cellHSpan", places[i][2]);
            XposedHelpers.setIntField(lp, "cellVSpan", places[i][3]);
            XposedHelpers.callMethod(cellLayout, "markCellsAsOccupiedForView", view);
            // Nothing pads a widget for the span it is told: tell it the one it is shown at.
            try {
                XposedHelpers.callMethod(view, "applyDisplaySpan", places[i][2], places[i][3]);
            } catch (NoSuchMethodError notAWidget) {
                // Icons and folders.
            }
        }
        container.requestLayout();
    }

    /**
     * Where an item of portrait cells {@code c} goes on the landscape page, marked as taken:
     * cell x, y and the spans it is shown at.
     *
     * <p>The page is the portrait page turned: an item's portrait row is its landscape column
     * and its portrait column its landscape row, its spans its own. Icons, folders and square
     * widgets turned that way never meet; a widget of another shape whose spot is taken goes to
     * the nearest free spot that holds it whole, and is only shown smaller if none does. A
     * widget as wide as the portrait page is as wide as the landscape page and as tall as it
     * is in portrait, as far as the page allows.
     */
    private static int[] place(boolean[][] taken, int[] c, Grid grid) {
        int columns = grid.landscapeColumns();
        int rows = grid.landscapeRows();
        int spanX;
        int spanY;
        int x;
        int y;
        if (pageWide(c, grid)) {
            // As in portrait, where it fills the page: the whole width, and as many rows as
            // it has there (all of them, mostly). Placed last, it takes the room left.
            spanX = columns;
            spanY = Math.min(c[3], rows);
            x = 0;
            y = Math.min(Math.round(c[1] * rows / (float) grid.rows), rows - spanY);
        } else {
            spanX = Math.min(c[2], columns);
            spanY = Math.min(c[3], rows);
            x = Math.min(c[1], columns - spanX);
            y = Math.min(c[0], rows - spanY);
        }
        if (isVacant(taken, x, y, spanX, spanY)) {
            mark(taken, x, y, spanX, spanY);
            return new int[] {x, y, spanX, spanY};
        }
        for (int sy = spanY; sy >= 1; sy--) {
            for (int sx = spanX; sx >= 1; sx--) {
                int[] free = nearestVacant(taken, x, y, sx, sy);
                if (free != null) {
                    mark(taken, free[0], free[1], sx, sy);
                    return new int[] {free[0], free[1], sx, sy};
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
        });
        // getCellSize() is a one-line forwarder ART may inline into its callers.
        Hooks.deoptimizeAll(dp, "getCellSize");

        // Each portrait profile is remembered once made; each landscape one takes the portrait
        // space between cells (pages copy it as they are made).
        XposedBridge.hookAllConstructors(dp, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object o = param.thisObject;
                hookWidgetPadding(o.getClass());
                if (PortraitGeometry.isPortraitPhone(o)) {
                    PortraitGeometry.record(o, true);
                    return;
                }
                Grid grid = gridFor(o);
                if (grid != null && grid.border != null) {
                    XposedHelpers.setObjectField(o, "cellLayoutBorderSpacePx",
                            new Point(grid.border));
                }
            }
        });
    }

    /**
     * Nothing insets each widget in its cells by a padding its profile works out for the span
     * ({@code Rect o(int spanX, int spanY)} on its DeviceProfile subclass): app widgets, its own
     * card widgets and the size widgets are told all take it from there. It works that out
     * from its own idea of the cell, which in landscape is not the cell in use, and came out
     * negative: widgets grew past their cells into their neighbours. Landscape cells are the
     * portrait ones, so landscape asks the portrait profile.
     */
    private synchronized void hookWidgetPadding(Class<?> profileClass) {
        if (widgetPaddingHooked) {
            return;
        }
        widgetPaddingHooked = true;
        Method padding = null;
        for (Method m : profileClass.getDeclaredMethods()) {
            Class<?>[] types = m.getParameterTypes();
            if (m.getReturnType() == Rect.class && Modifier.isPublic(m.getModifiers())
                    && types.length == 2 && types[0] == int.class && types[1] == int.class) {
                if (padding != null) {
                    Hooks.log("landscape widget padding: more than one candidate, left as is");
                    return;
                }
                padding = m;
            }
        }
        if (padding == null) {
            Hooks.log("landscape widget padding: no Rect(int, int) method on "
                    + profileClass.getName());
            return;
        }
        Method portraitPadding = padding;
        XposedBridge.hookMethod(padding, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object o = param.thisObject;
                Grid grid = gridFor(o);
                PortraitGeometry portrait = grid != null && grid.cell != null
                        ? PortraitGeometry.of(o) : null;
                if (portrait == null || portrait.profile() == o) {
                    return;
                }
                Rect pad = (Rect) portraitPadding.invoke(portrait.profile(), param.args);
                param.setResult(landscapePadding(grid, pad, (Integer) param.args[0],
                        (Integer) param.args[1]));
            }
        });
    }

    /**
     * Nothing pads each widget for the span it is told ({@code applyDisplaySpan}), which it
     * takes from the item: the portrait span. On a landscape page a widget is told the span it
     * is shown at there (after a landscape resize, or kept within a smaller page).
     */
    private void hookDisplaySpan() {
        XC_MethodHook shownSpan = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View view = (View) param.thisObject;
                Object lp = view.getLayoutParams();
                if (lp == null || !XposedHelpers.findClass(CLS_LAYOUT_PARAMS, cl).isInstance(lp)
                        || gridOfView(view) == null) {
                    return;
                }
                param.args[0] = XposedHelpers.getIntField(lp, "cellHSpan");
                param.args[1] = XposedHelpers.getIntField(lp, "cellVSpan");
            }
        };
        for (String name : CLS_WIDGET_VIEWS) {
            Class<?> cls = XposedHelpers.findClassIfExists(name, cl);
            if (cls != null) {
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.getName().equals("applyDisplaySpan") && m.getParameterCount() == 2) {
                        XposedBridge.hookMethod(m, shownSpan);
                    }
                }
            }
        }
    }

    /**
     * The padding of a widget of portrait spans {@code spanX} x {@code spanY} on the landscape
     * page, from its portrait padding: the portrait widget scaled by the grid's scale, centred
     * on its landscape cells, so it keeps its portrait shape. One as wide as the portrait page
     * fills its landscape cells instead (see place).
     */
    private static Rect landscapePadding(Grid grid, Rect portraitPad, int spanX, int spanY) {
        Point p = grid.portrait.cell;
        Point pb = grid.portrait.border;
        int[] c = {0, 0, spanX, spanY};
        float s = grid.scale;
        if (pageWide(c, grid)) {
            return new Rect(Math.round(portraitPad.left * s), Math.round(portraitPad.top * s),
                    Math.round(portraitPad.right * s), Math.round(portraitPad.bottom * s));
        }
        int boxX = Math.min(spanX, grid.landscapeColumns()) * (grid.cell.x + grid.border.x)
                - grid.border.x;
        int boxY = Math.min(spanY, grid.landscapeRows()) * (grid.cell.y + grid.border.y)
                - grid.border.y;
        float portraitX = spanX * (p.x + pb.x) - pb.x;
        float portraitY = spanY * (p.y + pb.y) - pb.y;
        int contentX = Math.round((portraitX - portraitPad.left - portraitPad.right) * s);
        int contentY = Math.round((portraitY - portraitPad.top - portraitPad.bottom) * s);
        int left = Math.max(0, Math.round(portraitPad.left * s
                + (boxX - portraitX * s) / 2f));
        int top = Math.max(0, Math.round(portraitPad.top * s + (boxY - portraitY * s) / 2f));
        return new Rect(left, top, Math.max(0, boxX - left - contentX),
                Math.max(0, boxY - top - contentY));
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
                        if (launcher == null || XposedHelpers.callStaticMethod(
                                activityContext,
                                "lookupContext", layout.getContext()) != launcher) {
                            return; // A preview's page: as Nothing lays it out.
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
                            Point border = grid.border;
                            // The portrait space between cells, whatever the page copied.
                            if (!border.equals(XposedHelpers.getObjectField(layout,
                                    "mBorderSpace"))) {
                                XposedHelpers.setObjectField(layout, "mBorderSpace",
                                        new Point(border));
                            }
                            int columns = grid.landscapeColumns();
                            int rows = grid.landscapeRows();
                            // Centred in the whole width, in place of Nothing's side padding.
                            left = 0;
                            right = 0;
                            int spareX = View.MeasureSpec.getSize((Integer) param.args[0])
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
                        if (XposedHelpers.getIntField(layout, "mContainerType")
                                != CELL_LAYOUT_WORKSPACE || gridOfView((View) layout) == null) {
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
                            remember(item, (Integer) param.args[2], (Integer) param.args[3],
                                    (Integer) param.args[4], XposedHelpers.getIntField(item,
                                            "spanX"), XposedHelpers.getIntField(item, "spanY"));
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
                            // Kept as the landscape place; the database keeps its portrait
                            // place and size, whatever it was dragged or resized to here.
                            remember(item, (Integer) param.args[2], (Integer) param.args[3],
                                    (Integer) param.args[4], (Integer) param.args[5],
                                    (Integer) param.args[6]);
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
