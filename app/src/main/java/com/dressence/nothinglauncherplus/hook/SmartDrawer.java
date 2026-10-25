package com.dressence.nothinglauncherplus.hook;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.dressence.nothinglauncherplus.Config;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Injects user-defined folders into the Nothing Launcher "Smart (Beta)" app drawer.
 *
 * <p>The Smart drawer groups apps by {@code AppStatusInfo#getGroupCategory()} (a category string
 * such as "games" or "tools") and lays the groups out in the order returned by a private method
 * of the smart {@code AlphabeticalAppsList} subclass. We return our own category keys for apps the
 * user assigned to a custom folder, append those keys to the order, and swap in the folder name
 * wherever the launcher would look up a built-in category label.
 *
 * <p>Most of the launcher is R8-obfuscated, so obfuscated members are located structurally or by
 * probing behaviour instead of by name. See README.md for the class map of 4.0.20.
 */
final class SmartDrawer {

    static final String SMART_PKG = "com.nothing.launcher.allapps.smart.";
    static final String CLS_FOLDER_ICON = SMART_PKG + "SmartFolderIcon";
    private static final String CLS_FULL_SHEET = SMART_PKG + "SmartFullSheet";
    private static final String CLS_ITEM_INFO = "com.android.launcher3.model.data.ItemInfo";
    private static final String CLS_ALPHA_LIST = "com.android.launcher3.allapps.AlphabeticalAppsList";
    private static final String CLS_GRID_ADAPTER = "com.android.launcher3.allapps.AllAppsGridAdapter";
    private static final String CLS_ADAPTER_ITEM =
            "com.android.launcher3.allapps.BaseAllAppsAdapter$AdapterItem";
    private static final String CLS_ALL_APPS_CONTAINER =
            "com.nothing.launcher.allapps.search.NTLauncherAllAppsContainerView";
    private static final String CLS_DIFF_UTIL = "androidx.recyclerview.widget.DiffUtil";

    static final String CATEGORY_OTHERS = "others";
    private static final String CATEGORY_UNKNOWN = "unknown";
    private static final String CATEGORY_HIDDEN = "hidden icons";

    /** BaseAllAppsAdapter view types used by the Smart drawer. */
    private static final int VIEW_TYPE_ICON = 2;
    private static final int VIEW_TYPE_SPACER = 256;
    private static final int VIEW_TYPE_HIDDEN_TILE = 65536;
    private static final int VIEW_TYPE_FOLDER_TILE = 131072;

    /**
     * The stock Smart grid has two folder tiles per row. With more tiles in landscape
     * ({@link Landscape#smartColumns}) and loose apps (two icons per tile column), the grid gets
     * {@code tiles * (looseApps ? 2 : 1)} columns and tiles span accordingly.
     */
    private static final int STOCK_TILE_COLUMNS = 2;

    private final ClassLoader cl;
    private final ConfigStore store;
    private final FolderCovers covers;

    /** AppStatusInfo field holding the app's launch intent URI. */
    private Field statusUriField;
    private final Map<String, String> uriToComponent = new ConcurrentHashMap<>();
    private final Set<Class<?>> processedClasses = ConcurrentHashMap.newKeySet();
    private final Set<Object> smartLists = Collections.newSetFromMap(new WeakHashMap<>());

    SmartDrawer(ClassLoader cl, ConfigStore store, FolderCovers covers) {
        this.cl = cl;
        this.store = store;
        this.covers = covers;
    }

    void install() {
        Hooks.install("group category", this::hookGroupCategory);
        Hooks.install("smart list", this::hookSmartList);
        Hooks.install("smart adapter", this::hookSmartAdapter);
        Hooks.install("folder app order", this::hookFolderAppOrder);
        Hooks.install("folder tile", this::hookFolderTile);
        Hooks.install("sheet title", this::hookSheetTitle);
        Hooks.install("loose app spans", this::hookLooseAppSpans);
        Hooks.install("loose app spacing", this::hookLooseAppSpacing);
        store.addChangeListener(this::refresh);
        // Rotation changes the tile count; Nothing's drawer does not ask again by itself.
        Hooks.install("smart grid rotation", () -> XposedHelpers.findAndHookMethod(
                XposedHelpers.findClass("com.android.launcher3.Launcher", cl),
                "dispatchDeviceProfileChanged", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        refresh();
                    }
                }));
    }

    // ---------------------------------------------------------------------------------------
    // Grouping: AppStatusInfo#getGroupCategory()
    // ---------------------------------------------------------------------------------------

    private void hookGroupCategory() throws Throwable {
        Class<?> statusClass = XposedHelpers.findClass(CLS_ITEM_INFO, cl)
                .getField("mStatusInfo").getType();

        // A fresh AppStatusInfo has intentUri == "" and category == "others". The group category
        // getter maps "unknown" to "others" while the plain getter returns the field as is, which
        // tells the two String getters apart without relying on obfuscated names.
        Object probe = statusClass.getDeclaredConstructor().newInstance();
        Field categoryField = null;
        for (Field f : statusClass.getDeclaredFields()) {
            if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            Object value = f.get(probe);
            if (CATEGORY_OTHERS.equals(value)) {
                categoryField = f;
            } else if ("".equals(value)) {
                statusUriField = f;
            }
        }
        if (categoryField == null || statusUriField == null) {
            throw new IllegalStateException("AppStatusInfo layout not recognised");
        }
        categoryField.set(probe, CATEGORY_UNKNOWN);

        Method groupMethod = null;
        for (Method m : statusClass.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0
                    || m.getReturnType() != String.class || m.getName().equals("toString")) {
                continue;
            }
            m.setAccessible(true);
            if (CATEGORY_OTHERS.equals(m.invoke(probe))) {
                if (groupMethod != null) {
                    throw new IllegalStateException("ambiguous group category getter");
                }
                groupMethod = m;
            }
        }
        if (groupMethod == null) {
            throw new IllegalStateException("group category getter not found");
        }

        XposedBridge.hookMethod(groupMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                String original = (String) param.getResult();
                if (CATEGORY_HIDDEN.equals(original)) {
                    return;
                }
                Config c = store.get();
                if (c.folders.isEmpty()) {
                    return;
                }
                Config.Folder folder = c.folderForComponent(
                        componentForUri((String) statusUriField.get(param.thisObject)));
                if (folder != null) {
                    param.setResult(folder.categoryKey());
                } else if (c.hideBuiltin) {
                    param.setResult(CATEGORY_OTHERS);
                }
            }
        });
    }

    private String componentForUri(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "";
        }
        String cached = uriToComponent.get(uri);
        if (cached != null) {
            return cached;
        }
        String component = "";
        try {
            ComponentName cn = Intent.parseUri(uri, 0).getComponent();
            if (cn != null) {
                component = cn.flattenToString();
            }
        } catch (Exception ignored) {
            // Not an intent URI; leave the app in its built-in category.
        }
        uriToComponent.put(uri, component);
        return component;
    }

    // ---------------------------------------------------------------------------------------
    // The smart AlphabeticalAppsList subclass: category order, loose apps, live refresh
    // ---------------------------------------------------------------------------------------

    private void hookSmartList() {
        Class<?> alphaList = XposedHelpers.findClass(CLS_ALPHA_LIST, cl);
        XposedBridge.hookAllConstructors(alphaList, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Class<?> listClass = param.thisObject.getClass();
                if (!listClass.getName().startsWith(SMART_PKG)) {
                    return;
                }
                Method orderMethod = findOrderMethod(listClass);
                if (orderMethod == null) {
                    return; // The plain 4-column list inside an open folder sheet.
                }
                synchronized (smartLists) {
                    smartLists.add(param.thisObject);
                }
                if (processedClasses.add(listClass)) {
                    hookSmartListClass(listClass, orderMethod);
                }
            }
        });
    }

    /** The category order source: the only private, non-static, no-arg method returning a List. */
    private static Method findOrderMethod(Class<?> listClass) {
        Method found = null;
        for (Method m : listClass.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (Modifier.isPrivate(mod) && !Modifier.isStatic(mod) && m.getParameterCount() == 0
                    && m.getReturnType() == List.class) {
                if (found != null) {
                    return null;
                }
                found = m;
            }
        }
        return found;
    }

    private void hookSmartListClass(Class<?> listClass, Method orderMethod) {
        XposedBridge.hookMethod(orderMethod, new XC_MethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(MethodHookParam param) {
                param.setResult(mergeOrder((List<String>) param.getResult(), store.get()));
            }
        });
        XposedHelpers.findAndHookMethod(listClass, "onAppsUpdated", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                store.reloadIfChanged();
            }
        });
        Hooks.install("loose apps", () -> {
            LooseApps loose = new LooseApps(listClass);
            XposedHelpers.findAndHookMethod(listClass, "updateAdapterItems", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (store.get().looseApps) {
                        loose.apply(param.thisObject);
                    }
                }
            });
        });
        // updateAdapterItems calls the tiny group category getter, the order method and the group
        // item factory; make sure ART did not inline them into code that bypasses our hooks.
        Hooks.deoptimizeAll(listClass, "updateAdapterItems");
    }

    private static List<String> mergeOrder(List<String> original, Config c) {
        List<String> custom = c.categoryKeys();
        List<String> merged = new ArrayList<>(original.size() + custom.size());
        for (String category : original) {
            if (!category.startsWith(Config.CATEGORY_PREFIX)) {
                merged.add(category);
            }
        }
        if (custom.isEmpty()) {
            return merged;
        }
        if (c.customFirst) {
            merged.addAll(0, custom);
        } else {
            int others = merged.indexOf(CATEGORY_OTHERS);
            merged.addAll(others < 0 ? merged.size() : others, custom);
        }
        return merged;
    }

    /**
     * Replaces the "Others" folder tile with plain app icons. The grid then has four columns:
     * folder tiles span two, icons one, and a zero-height spacer starts the icons on a new row.
     */
    private final class LooseApps {
        private final Class<?> adapterItemClass;
        private final Constructor<?> diffCallback;
        private final Class<?> diffUtil;

        LooseApps(Class<?> listClass) throws NoSuchMethodException {
            adapterItemClass = XposedHelpers.findClass(CLS_ADAPTER_ITEM, cl);
            diffUtil = XposedHelpers.findClass(CLS_DIFF_UTIL, cl);
            // The list's own DiffUtil.Callback(oldItems, newItems).
            Constructor<?> found = null;
            for (Class<?> inner : listClass.getDeclaredClasses()) {
                for (Constructor<?> ctor : inner.getDeclaredConstructors()) {
                    Class<?>[] p = ctor.getParameterTypes();
                    if (p.length == 2 && p[0] == List.class && p[1] == List.class) {
                        found = ctor;
                    }
                }
            }
            if (found == null) {
                throw new NoSuchMethodException("smart list DiffUtil callback");
            }
            found.setAccessible(true);
            diffCallback = found;
        }

        @SuppressWarnings("unchecked")
        void apply(Object list) throws Throwable {
            List<Object> items = (List<Object>) XposedHelpers.getObjectField(list, "mAdapterItems");
            int index = -1;
            for (int i = 0; i < items.size(); i++) {
                Object item = items.get(i);
                if (XposedHelpers.getIntField(item, "viewType") == VIEW_TYPE_FOLDER_TILE
                        && CATEGORY_OTHERS.equals(GroupItems.category(item))) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                return;
            }
            List<?> apps = GroupItems.apps(items.get(index));
            List<Object> before = new ArrayList<>(items);
            items.remove(index);
            items.add(XposedHelpers.newInstance(adapterItemClass, VIEW_TYPE_SPACER));
            for (Object app : apps) {
                items.add(XposedHelpers.callStaticMethod(adapterItemClass, "asApp", app));
            }

            int accessible = 0;
            for (Object item : items) {
                if ((Boolean) XposedHelpers.callMethod(item, "isCountedForAccessibility")) {
                    accessible++;
                }
            }
            XposedHelpers.setIntField(list, "mAccessibilityResultsCount", accessible);

            // The stock code already dispatched old -> grouped; dispatch grouped -> loose on top.
            Object adapter = XposedHelpers.getObjectField(list, "mAdapter");
            if (adapter != null) {
                Object diff = XposedHelpers.callStaticMethod(diffUtil, "calculateDiff",
                        diffCallback.newInstance(before, items), false);
                XposedHelpers.callMethod(diff, "dispatchUpdatesTo", adapter);
            }
        }
    }

    /** Main thread: re-applies grid columns and regroups every live Smart list. */
    private void refresh() {
        List<Object> lists;
        synchronized (smartLists) {
            lists = new ArrayList<>(smartLists);
        }
        for (Object list : lists) {
            Object adapter = XposedHelpers.getObjectField(list, "mAdapter");
            if (adapter != null) {
                // The Smart adapter ignores the argument and asks getAppCountPerRow().
                XposedHelpers.callMethod(adapter, "setAppsPerRow", STOCK_TILE_COLUMNS);
            }
            XposedHelpers.callMethod(list, "onAppsUpdated");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Adapter: deoptimization and loose-app grid
    // ---------------------------------------------------------------------------------------

    private void hookSmartAdapter() {
        Class<?> gridAdapter = XposedHelpers.findClass(CLS_GRID_ADAPTER, cl);
        XposedBridge.hookAllConstructors(gridAdapter, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Class<?> adapterClass = param.thisObject.getClass();
                if (!adapterClass.getName().startsWith(SMART_PKG)) {
                    return;
                }
                if (processedClasses.add(adapterClass)) {
                    // onBindViewHolder calls SmartFolderIcon#bind; keep that call un-inlined.
                    Hooks.deoptimizeAll(adapterClass, "onBindViewHolder");
                    Hooks.install("smart grid columns", () -> hookColumns(adapterClass));
                }
                // The constructor sized the grid before our column hook existed; size it again.
                XposedHelpers.callMethod(param.thisObject, "setAppsPerRow", STOCK_TILE_COLUMNS);
            }
        });
    }

    private void hookColumns(Class<?> adapterClass) {
        XposedHelpers.findAndHookMethod(adapterClass, "getAppCountPerRow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                // Only the folder grid (2 tiles), not the 4-column list inside a folder sheet.
                if ((Integer) param.getResult() != STOCK_TILE_COLUMNS) {
                    return;
                }
                int tiles = Landscape.smartColumns(deviceProfileOf(param.thisObject));
                param.setResult(store.get().looseApps ? tiles * 2 : tiles);
            }
        });
    }

    private static Object deviceProfileOf(Object adapter) {
        try {
            return XposedHelpers.callMethod(
                    XposedHelpers.getObjectField(adapter, "mActivityContext"), "getDeviceProfile");
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isTile(View view) {
        String name = view.getClass().getName();
        return name.equals(CLS_FOLDER_ICON) || name.endsWith(".SmartEntranceIcon");
    }

    /** In loose-apps mode folder tiles span two icon columns; icons keep one column each. */
    private void hookLooseAppSpans() {
        Class<?> gridAdapter = XposedHelpers.findClass(CLS_GRID_ADAPTER, cl);
        Class<?> spanSizer = XposedHelpers.findClass(CLS_GRID_ADAPTER + "$GridSpanSizer", cl);
        Field outer = null;
        for (Field f : spanSizer.getDeclaredFields()) {
            if (gridAdapter.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                outer = f;
            }
        }
        if (outer == null) {
            throw new IllegalStateException("GridSpanSizer outer adapter field not found");
        }
        Field adapterField = outer;
        XposedHelpers.findAndHookMethod(spanSizer, "getSpanSize", int.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!store.get().looseApps) {
                    return;
                }
                Object adapter = adapterField.get(param.thisObject);
                if (adapter == null || !adapter.getClass().getName().startsWith(SMART_PKG)) {
                    return;
                }
                List<?> items = (List<?>) XposedHelpers.callMethod(
                        XposedHelpers.getObjectField(adapter, "mApps"), "getAdapterItems");
                int position = (Integer) param.args[0];
                if (position >= items.size()) {
                    return;
                }
                int viewType = XposedHelpers.getIntField(items.get(position), "viewType");
                if (viewType == VIEW_TYPE_FOLDER_TILE || viewType == VIEW_TYPE_HIDDEN_TILE) {
                    int spanCount = (Integer) XposedHelpers.callMethod(
                            XposedHelpers.getObjectField(adapter, "mGridLayoutMgr"),
                            "getSpanCount");
                    int tiles = Landscape.smartColumns(deviceProfileOf(adapter));
                    param.setResult(Math.max(1, spanCount / tiles));
                }
            }
        });
    }

    /**
     * The Smart grid's ItemDecoration assumes every cell is one column wide. In loose-apps mode
     * gaps are derived from each child's span instead: folder tiles keep the stock gap between
     * them, icons get none (their own padding already spaces them).
     */
    private void hookLooseAppSpacing() throws Throwable {
        Class<?> decoration = XposedHelpers.findClass(CLS_ALL_APPS_CONTAINER, cl)
                .getDeclaredField("gridDirection").getType();
        Field gapField = null;
        Field rtlField = null;
        for (Field f : decoration.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            if (f.getType() == int.class) {
                gapField = f;
            } else if (f.getType() == boolean.class) {
                rtlField = f;
            }
        }
        if (gapField == null || rtlField == null) {
            throw new IllegalStateException("grid decoration layout not recognised");
        }
        Field gap = gapField;
        Field rtl = rtlField;
        Method getItemOffsets = null;
        for (Method m : decoration.getDeclaredMethods()) {
            if (m.getName().equals("getItemOffsets") && m.getParameterCount() == 4
                    && m.getParameterTypes()[0] == Rect.class) {
                getItemOffsets = m;
            }
        }
        if (getItemOffsets == null) {
            throw new IllegalStateException("getItemOffsets not found");
        }
        XposedBridge.hookMethod(getItemOffsets, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!store.get().looseApps) {
                    return;
                }
                Rect out = (Rect) param.args[0];
                View view = (View) param.args[1];
                ViewGroup parent = (ViewGroup) param.args[2];
                Object layoutManager = XposedHelpers.callMethod(parent, "getLayoutManager");
                int spanCount = (Integer) XposedHelpers.callMethod(layoutManager, "getSpanCount");
                Object lp = view.getLayoutParams();
                int spanIndex = (Integer) XposedHelpers.callMethod(lp, "getSpanIndex");
                int spanSize = (Integer) XposedHelpers.callMethod(lp, "getSpanSize");
                if (!isTile(view)) {
                    out.set(0, 0, 0, 0); // Icons and the spacer: their own padding spaces them.
                    return;
                }
                if (spanSize < 2) {
                    return; // Columns not switched yet; the stock offsets are still right.
                }
                int half = gap.getInt(param.thisObject) / 2;
                boolean isRtl = rtl.getBoolean(param.thisObject);
                boolean first = spanIndex == 0;
                boolean last = spanIndex + spanSize >= spanCount;
                int startGap = first ? 0 : half;
                int endGap = last ? 0 : half;
                out.left = isRtl ? endGap : startGap;
                out.right = isRtl ? startGap : endGap;
                int position = (Integer) XposedHelpers.callMethod(
                        parent, "getChildAdapterPosition", view);
                out.top = position >= 0 && position < spanCount / spanSize ? 0 : half;
                out.bottom = half;
            }
        });
    }

    // ---------------------------------------------------------------------------------------
    // Custom folder contents: app order, label, cover
    // ---------------------------------------------------------------------------------------

    private Class<?> groupItemClass() {
        Class<?> folderIcon = XposedHelpers.findClass(CLS_FOLDER_ICON, cl);
        for (Method m : folderIcon.getDeclaredMethods()) {
            if (m.getName().equals("bind") && m.getParameterCount() == 1) {
                return m.getParameterTypes()[0];
            }
        }
        throw new IllegalStateException("SmartFolderIcon#bind not found");
    }

    /** Sorts a custom folder's apps into the user's order when its group item is created. */
    private void hookFolderAppOrder() {
        Class<?> groupItem = groupItemClass();
        boolean hooked = false;
        // GroupAdapterItem.Companion.create(viewType, category, apps)
        for (Class<?> inner : groupItem.getDeclaredClasses()) {
            for (Method m : inner.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 3 || p[0] != int.class || p[1] != String.class
                        || p[2] != List.class || m.getReturnType() != groupItem) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Config.Folder folder = store.get().folderForKey((String) param.args[1]);
                        if (folder != null) {
                            param.args[2] = sortedByUserOrder((List<?>) param.args[2], folder);
                        }
                    }
                });
                hooked = true;
            }
        }
        if (!hooked) {
            throw new IllegalStateException("group item factory not found");
        }
    }

    private static List<Object> sortedByUserOrder(List<?> apps, Config.Folder folder) {
        Map<String, Integer> rank = new HashMap<>();
        int i = 0;
        for (String component : folder.apps) {
            rank.put(component, i++);
        }
        List<Object> sorted = new ArrayList<>(apps);
        // Stable: apps the user has not ordered keep the launcher's (usage) order at the end.
        sorted.sort((a, b) -> Integer.compare(rankOf(a, rank), rankOf(b, rank)));
        return sorted;
    }

    private static int rankOf(Object app, Map<String, Integer> rank) {
        Object cn = XposedHelpers.getObjectField(app, "componentName");
        Integer r = cn instanceof ComponentName
                ? rank.get(((ComponentName) cn).flattenToString()) : null;
        return r != null ? r : Integer.MAX_VALUE;
    }

    private void hookFolderTile() {
        Class<?> folderIcon = XposedHelpers.findClass(CLS_FOLDER_ICON, cl);
        XposedHelpers.findAndHookMethod(folderIcon, "bind", groupItemClass(), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Config.Folder folder =
                        store.get().folderForKey(GroupItems.category(param.args[0]));
                if (folder != null) {
                    TextView name = (TextView) XposedHelpers.callMethod(
                            param.thisObject, "getFolderName");
                    name.setText(folder.name);
                }
                View previews = (View) XposedHelpers.callMethod(
                        param.thisObject, "getFolderIconContainer");
                resizeTile((View) param.thisObject, previews);
                covers.apply((FrameLayout) param.thisObject, previews, folder,
                        store.get().animateCovers);
            }
        });
    }

    /**
     * Tiles get their size once, when created; after a rotation recycled tiles would keep the
     * old orientation's size. Re-apply the current Smart profile's tile, name and preview sizes
     * (only when they differ, so binding stays layout-free in the common case).
     */
    private void resizeTile(View tile, View previews) {
        try {
            Object profile = XposedHelpers.callMethod(XposedHelpers.callMethod(
                    XposedHelpers.callMethod(tile, "getActivityContext"), "getDeviceProfile"), "z");
            setSize(tile, (Integer) XposedHelpers.callMethod(profile, "i"),
                    (Integer) XposedHelpers.callMethod(profile, "c"));
            ViewGroup.LayoutParams previewLp = previews.getLayoutParams();
            if (previewLp != null) {
                setSize(previews, previewLp.width, (Integer) XposedHelpers.callMethod(profile, "e"));
            }
        } catch (Throwable t) {
            // Profile layout not recognised: keep the creation-time sizes.
        }
    }

    private static void setSize(View view, int width, int height) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null && (lp.width != width || lp.height != height)) {
            lp.width = width;
            lp.height = height;
            view.setLayoutParams(lp);
        }
    }

    private void hookSheetTitle() {
        Class<?> sheet = XposedHelpers.findClass(CLS_FULL_SHEET, cl);
        Class<?> folderIcon = XposedHelpers.findClass(CLS_FOLDER_ICON, cl);
        boolean hooked = false;
        // SmartFullSheet.Companion.show(ActivityContext, SmartFolderIcon): SmartFullSheet
        for (Class<?> inner : sheet.getDeclaredClasses()) {
            for (Method m : inner.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 2 || params[1] != folderIcon || m.getReturnType() != sheet) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object opened = param.getResult();
                        if (opened == null) {
                            return;
                        }
                        Config.Folder folder = store.get().folderForKey(
                                GroupItems.category(((View) param.args[1]).getTag()));
                        if (folder != null) {
                            TextView title = (TextView) XposedHelpers.getObjectField(
                                    opened, "folderName");
                            title.setText(folder.name);
                        }
                    }
                });
                hooked = true;
            }
        }
        if (!hooked) {
            throw new IllegalStateException("SmartFullSheet companion opener not found");
        }
    }
}
