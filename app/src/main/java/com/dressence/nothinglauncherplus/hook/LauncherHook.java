package com.dressence.nothinglauncherplus.hook;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.dressence.nothinglauncherplus.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

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
public class LauncherHook implements IXposedHookLoadPackage {

    private static final String TAG = "NothingLauncher+: ";

    private static final String SMART_PKG = "com.nothing.launcher.allapps.smart.";
    private static final String CLS_ITEM_INFO = "com.android.launcher3.model.data.ItemInfo";
    private static final String CLS_ALPHA_LIST = "com.android.launcher3.allapps.AlphabeticalAppsList";
    private static final String CLS_GRID_ADAPTER = "com.android.launcher3.allapps.AllAppsGridAdapter";
    private static final String CLS_LAUNCHER = "com.android.launcher3.Launcher";
    private static final String CLS_FOLDER_ICON = SMART_PKG + "SmartFolderIcon";
    private static final String CLS_FULL_SHEET = SMART_PKG + "SmartFullSheet";
    private static final String CLS_SETTINGS_FRAGMENT =
            "com.nothing.launcher.setting.allapp.AllAppSettingsFragment";
    private static final String CLS_PREFERENCE = "androidx.preference.Preference";
    private static final String CLS_OPTIONS_POPUP = "com.android.launcher3.views.OptionsPopupView";
    private static final String CLS_LAUNCHER_EVENT =
            "com.android.launcher3.logging.StatsLogManager$LauncherEvent";
    private static final String CLS_FOLDER_INFO = "com.android.launcher3.model.data.FolderInfo";
    private static final String CLS_APP_INFO = "com.android.launcher3.model.data.AppInfo";
    private static final String CLS_EXECUTORS = "com.android.launcher3.util.Executors";
    private static final String CLS_LAUNCHER_STATE = "com.android.launcher3.LauncherState";

    private static final String CATEGORY_OTHERS = "others";
    private static final String CATEGORY_UNKNOWN = "unknown";
    private static final String CATEGORY_HIDDEN = "hidden icons";
    private static final String PREF_DRAWER_LAYOUT_CATEGORY = "pref_category_all_app_type";
    private static final String PREF_CUSTOM_FOLDERS = "nlplus_custom_folders";

    private XSharedPreferences prefs;
    private volatile Config config = new Config();

    /** AppStatusInfo field holding the app's launch intent URI. */
    private Field statusUriField;
    private final Map<String, String> uriToComponent = new ConcurrentHashMap<>();
    private final Map<Class<?>, Field> groupItemCategoryFields = new ConcurrentHashMap<>();
    private final Set<Class<?>> processedClasses = ConcurrentHashMap.newKeySet();
    private final Set<Object> smartLists = Collections.newSetFromMap(new WeakHashMap<>());
    private boolean receiverRegistered;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (Config.MODULE_PKG.equals(lpparam.packageName)) {
            XposedHelpers.findAndHookMethod(Config.SETTINGS_ACTIVITY, lpparam.classLoader,
                    "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }
        if (!Config.LAUNCHER_PKG.equals(lpparam.packageName) || !lpparam.isFirstApplication) {
            return;
        }
        ClassLoader cl = lpparam.classLoader;
        prefs = new XSharedPreferences(Config.MODULE_PKG, Config.PREFS_NAME);
        config = Config.read(prefs);

        install("group category", () -> hookGroupCategory(cl));
        install("smart list", () -> hookSmartList(cl));
        install("smart adapter", () -> hookSmartAdapter(cl));
        install("folder label", () -> hookFolderLabel(cl));
        install("sheet title", () -> hookSheetTitle(cl));
        install("folder menu", () -> hookFolderMenu(cl));
        install("settings entry", () -> hookSettingsEntry(cl));
        install("reload receiver", () -> hookReloadReceiver(cl));
    }

    private interface Installer {
        void run() throws Throwable;
    }

    private static void install(String name, Installer installer) {
        try {
            installer.run();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "failed to install hook '" + name + "': " + t);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Grouping: AppStatusInfo#getGroupCategory()
    // ---------------------------------------------------------------------------------------

    private void hookGroupCategory(ClassLoader cl) throws Throwable {
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
                Config c = config;
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
    // Ordering + live refresh: the smart AlphabeticalAppsList subclass
    // ---------------------------------------------------------------------------------------

    private void hookSmartList(ClassLoader cl) {
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
                param.setResult(mergeOrder((List<String>) param.getResult(), config));
            }
        });
        XposedHelpers.findAndHookMethod(listClass, "onAppsUpdated", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                reloadConfigIfChanged();
            }
        });
        // updateAdapterItems calls the tiny group category getter and the order method; make sure
        // ART did not inline them into compiled code that would bypass our hooks.
        for (Method m : listClass.getDeclaredMethods()) {
            if (m.getName().equals("updateAdapterItems")) {
                deoptimize(m);
            }
        }
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

    private void hookSmartAdapter(ClassLoader cl) {
        Class<?> gridAdapter = XposedHelpers.findClass(CLS_GRID_ADAPTER, cl);
        XposedBridge.hookAllConstructors(gridAdapter, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Class<?> adapterClass = param.thisObject.getClass();
                if (adapterClass.getName().startsWith(SMART_PKG)
                        && processedClasses.add(adapterClass)) {
                    // onBindViewHolder calls SmartFolderIcon#bind; keep that call un-inlined.
                    for (Method m : adapterClass.getDeclaredMethods()) {
                        if (m.getName().equals("onBindViewHolder")) {
                            deoptimize(m);
                        }
                    }
                }
            }
        });
    }

    // ---------------------------------------------------------------------------------------
    // Labels
    // ---------------------------------------------------------------------------------------

    private void hookFolderLabel(ClassLoader cl) {
        Class<?> folderIcon = XposedHelpers.findClass(CLS_FOLDER_ICON, cl);
        boolean hooked = false;
        for (Method m : folderIcon.getDeclaredMethods()) {
            if (!m.getName().equals("bind") || m.getParameterCount() != 1) {
                continue;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Config.Folder folder = folderForGroupItem(param.args[0]);
                    if (folder != null) {
                        TextView name = (TextView) XposedHelpers.callMethod(
                                param.thisObject, "getFolderName");
                        name.setText(folder.name);
                    }
                }
            });
            hooked = true;
        }
        if (!hooked) {
            throw new IllegalStateException("SmartFolderIcon#bind not found");
        }
    }

    private void hookSheetTitle(ClassLoader cl) {
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
                        Config.Folder folder =
                                folderForGroupItem(((View) param.args[1]).getTag());
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

    /** The adapter's group item holds its category key in its only String field. */
    private Config.Folder folderForGroupItem(Object groupItem) {
        if (groupItem == null) {
            return null;
        }
        Field field = groupItemCategoryFields.computeIfAbsent(groupItem.getClass(), cls -> {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType() == String.class && !Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    return f;
                }
            }
            return null;
        });
        if (field == null) {
            return null;
        }
        try {
            Object category = field.get(groupItem);
            return category instanceof String ? config.folderForKey((String) category) : null;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Long-press on a drawer folder: "Add to home screen" as a real Launcher3 folder
    // ---------------------------------------------------------------------------------------

    private void hookFolderMenu(ClassLoader cl) {
        Class<?> folderIcon = XposedHelpers.findClass(CLS_FOLDER_ICON, cl);
        // Resolve everything up front so a missing class disables the menu instead of crashing.
        Class<?> launcherClass = XposedHelpers.findClass(CLS_LAUNCHER, cl);
        Class<?> popupClass = XposedHelpers.findClass(CLS_OPTIONS_POPUP, cl);
        Class<?> optionItemClass = XposedHelpers.findClass(CLS_OPTIONS_POPUP + "$OptionItem", cl);
        Class<?> eventClass = XposedHelpers.findClass(CLS_LAUNCHER_EVENT, cl);
        Class<?> folderInfoClass = XposedHelpers.findClass(CLS_FOLDER_INFO, cl);
        Class<?> appInfoClass = XposedHelpers.findClass(CLS_APP_INFO, cl);
        Class<?> executorsClass = XposedHelpers.findClass(CLS_EXECUTORS, cl);
        Class<?> launcherStateClass = XposedHelpers.findClass(CLS_LAUNCHER_STATE, cl);
        FolderMenu menu = new FolderMenu(launcherClass, popupClass, optionItemClass,
                XposedHelpers.getStaticObjectField(eventClass, "IGNORE"), folderInfoClass,
                appInfoClass, executorsClass, launcherStateClass);

        XposedHelpers.findAndHookMethod(folderIcon, "onFinishInflate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                ((View) param.thisObject).setOnLongClickListener(menu::show);
            }
        });
    }

    private final class FolderMenu {
        private final Class<?> launcherClass;
        private final Class<?> popupClass;
        private final Class<?> optionItemClass;
        private final Object ignoreEvent;
        private final Class<?> folderInfoClass;
        private final Class<?> appInfoClass;
        private final Class<?> executorsClass;
        private final Class<?> launcherStateClass;

        FolderMenu(Class<?> launcherClass, Class<?> popupClass, Class<?> optionItemClass,
                Object ignoreEvent, Class<?> folderInfoClass, Class<?> appInfoClass,
                Class<?> executorsClass, Class<?> launcherStateClass) {
            this.launcherClass = launcherClass;
            this.popupClass = popupClass;
            this.optionItemClass = optionItemClass;
            this.ignoreEvent = ignoreEvent;
            this.folderInfoClass = folderInfoClass;
            this.appInfoClass = appInfoClass;
            this.executorsClass = executorsClass;
            this.launcherStateClass = launcherStateClass;
        }

        boolean show(View icon) {
            try {
                List<?> apps = appsOfGroupItem(icon.getTag());
                if (apps == null || apps.isEmpty()) {
                    return false;
                }
                CharSequence title = ((TextView) XposedHelpers.callMethod(
                        icon, "getFolderName")).getText();
                Context context = icon.getContext();
                Object launcher = XposedHelpers.callStaticMethod(
                        launcherClass, "getLauncher", context);

                Rect bounds = new Rect();
                XposedHelpers.callMethod(XposedHelpers.callMethod(launcher, "getDragLayer"),
                        "getDescendantRectRelativeToSelf", icon, bounds);

                // Reuse the launcher's own translated label and icon so the item looks native.
                int label = launcherResource(context, "string", "add_to_home_screen");
                int image = launcherResource(context, "drawable", "ic_install_no_shadow");
                if (label == 0 || image == 0) {
                    return false;
                }
                View.OnLongClickListener onPick = v -> {
                    addFolderToHome(launcher, title, apps);
                    return true;
                };
                ArrayList<Object> items = new ArrayList<>();
                items.add(XposedHelpers.newInstance(optionItemClass, context, label, image,
                        ignoreEvent, onPick));
                XposedHelpers.callStaticMethod(popupClass, "show", launcher, new RectF(bounds),
                        items, true);
                icon.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                return true;
            } catch (Throwable t) {
                XposedBridge.log(TAG + "folder menu failed: " + t);
                return false;
            }
        }

        /**
         * Builds a FolderInfo holding a workspace copy of every app and hands it to
         * LauncherModel#addAndBindAddedWorkspaceItems, the same path used when installed apps are
         * added to the home screen: it finds a free cell, writes the folder row and binds the
         * FolderIcon. That task stores only the folder itself, so the contents are written right
         * after it on the model thread, once the folder has its database id.
         */
        private void addFolderToHome(Object launcher, CharSequence title, List<?> apps) {
            Context context = (Context) launcher;
            Object folder = XposedHelpers.newInstance(folderInfoClass);
            XposedHelpers.setObjectField(folder, "title", title);
            List<Object> children = new ArrayList<>();
            for (Object app : apps) {
                if (!appInfoClass.isInstance(app)) {
                    continue; // e.g. the "hidden icons" entrance
                }
                Object item = XposedHelpers.callMethod(app, "makeWorkspaceItem", context);
                XposedHelpers.setIntField(item, "rank", children.size());
                XposedHelpers.callMethod(folder, "add", item);
                children.add(item);
            }
            if (children.isEmpty()) {
                return;
            }
            Object model = XposedHelpers.callMethod(launcher, "getModel");
            Object writer = XposedHelpers.callMethod(launcher, "getModelWriter");
            XposedHelpers.callMethod(model, "addAndBindAddedWorkspaceItems",
                    Collections.singletonList(android.util.Pair.create(folder, null)));

            java.util.concurrent.Executor modelExecutor = (java.util.concurrent.Executor)
                    XposedHelpers.getStaticObjectField(executorsClass, "MODEL_EXECUTOR");
            modelExecutor.execute(() -> {
                int folderId = XposedHelpers.getIntField(folder, "id");
                if (folderId < 0) {
                    XposedBridge.log(TAG + "folder was not added to the workspace");
                    return;
                }
                for (int i = 0; i < children.size(); i++) {
                    XposedHelpers.callMethod(writer, "addItemToDatabase",
                            children.get(i), folderId, 0, i, 0);
                }
            });

            Object normal = XposedHelpers.getStaticObjectField(launcherStateClass, "NORMAL");
            XposedHelpers.callMethod(XposedHelpers.callMethod(launcher, "getStateManager"),
                    "goToState", normal);
        }
    }

    private static int launcherResource(Context context, String type, String name) {
        return context.getResources().getIdentifier(name, type, Config.LAUNCHER_PKG);
    }

    /** The adapter's group item holds its apps in its only List field. */
    private static List<?> appsOfGroupItem(Object groupItem) throws IllegalAccessException {
        if (groupItem == null) {
            return null;
        }
        for (Field f : groupItem.getClass().getDeclaredFields()) {
            if (f.getType() == List.class && !Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                return new ArrayList<>((List<?>) f.get(groupItem));
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // "Custom folders" entry under Settings > App drawer & Search > Drawer layout
    // ---------------------------------------------------------------------------------------

    private void hookSettingsEntry(ClassLoader cl) {
        Class<?> preferenceClass = XposedHelpers.findClass(CLS_PREFERENCE, cl);
        XposedHelpers.findAndHookMethod(CLS_SETTINGS_FRAGMENT, cl, "onCreatePreferences",
                Bundle.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object fragment = param.thisObject;
                        Object group = XposedHelpers.callMethod(fragment, "findPreference",
                                PREF_DRAWER_LAYOUT_CATEGORY);
                        if (group == null) {
                            group = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
                        }
                        if (group == null || XposedHelpers.callMethod(group, "findPreference",
                                PREF_CUSTOM_FOLDERS) != null) {
                            return;
                        }
                        Context context = (Context) XposedHelpers.callMethod(
                                fragment, "requireContext");
                        reloadConfigIfChanged();

                        Object pref = XposedHelpers.newInstance(preferenceClass, context);
                        XposedHelpers.callMethod(pref, "setKey", PREF_CUSTOM_FOLDERS);
                        XposedHelpers.callMethod(pref, "setPersistent", false);
                        XposedHelpers.callMethod(pref, "setIconSpaceReserved", false);
                        XposedHelpers.callMethod(pref, "setTitle", (CharSequence) "Custom folders");
                        XposedHelpers.callMethod(pref, "setSummary",
                                (CharSequence) settingsSummary(config));
                        XposedHelpers.callMethod(pref, "setIntent", new Intent()
                                .setClassName(Config.MODULE_PKG, Config.SETTINGS_ACTIVITY));
                        // Appended after the Default / Smart (Beta) layout picker.
                        XposedHelpers.callMethod(group, "addPreference", pref);
                    }
                });
    }

    private static String settingsSummary(Config c) {
        int n = c.folders.size();
        String count = n == 0 ? "No custom folders yet"
                : n == 1 ? "1 custom folder" : n + " custom folders";
        return count + " · shown in the Smart (Beta) layout";
    }

    // ---------------------------------------------------------------------------------------
    // Config reload
    // ---------------------------------------------------------------------------------------

    private void hookReloadReceiver(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(CLS_LAUNCHER, cl, "onCreate", Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        registerReloadReceiver(((Activity) param.thisObject).getApplicationContext());
                    }
                });
    }

    private synchronized void registerReloadReceiver(Context appContext) {
        if (receiverRegistered) {
            return;
        }
        receiverRegistered = true;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                prefs.reload();
                config = Config.read(prefs);
                refreshSmartLists();
            }
        };
        appContext.registerReceiver(receiver, new IntentFilter(Config.ACTION_RELOAD),
                Context.RECEIVER_EXPORTED);
    }

    private void reloadConfigIfChanged() {
        if (prefs.hasFileChanged()) {
            prefs.reload();
            config = Config.read(prefs);
        }
    }

    /** Runs on the main thread (receiver callback). */
    private void refreshSmartLists() {
        List<Object> lists;
        synchronized (smartLists) {
            lists = new ArrayList<>(smartLists);
        }
        for (Object list : lists) {
            try {
                XposedHelpers.callMethod(list, "onAppsUpdated");
            } catch (Throwable t) {
                XposedBridge.log(TAG + "refresh failed: " + t);
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    private static Method deoptimizeMethod;
    private static boolean deoptimizeLookedUp;

    /** LSPosed exposes XposedBridge#deoptimizeMethod outside the public API 82 surface. */
    private static synchronized void deoptimize(Member member) {
        if (!deoptimizeLookedUp) {
            deoptimizeLookedUp = true;
            try {
                deoptimizeMethod = XposedBridge.class.getDeclaredMethod(
                        "deoptimizeMethod", Member.class);
            } catch (NoSuchMethodException e) {
                XposedBridge.log(TAG + "deoptimizeMethod unavailable; hooks may be bypassed"
                        + " if ART inlined them");
            }
        }
        if (deoptimizeMethod == null) {
            return;
        }
        try {
            deoptimizeMethod.invoke(null, member);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "deoptimize " + member + " failed: " + t);
        }
    }
}
