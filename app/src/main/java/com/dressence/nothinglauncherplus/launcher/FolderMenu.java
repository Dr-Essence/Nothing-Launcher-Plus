package com.dressence.nothinglauncherplus.launcher;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Pair;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.TextView;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Long-press on a Smart drawer folder opens the launcher's own options popup with
 * "Add to home screen" (any folder) and "Edit" (custom folders).
 */
final class FolderMenu {

    private static final String CLS_LAUNCHER = "com.android.launcher3.Launcher";
    private static final String CLS_OPTIONS_POPUP = "com.android.launcher3.views.OptionsPopupView";
    private static final String CLS_LAUNCHER_EVENT =
            "com.android.launcher3.logging.StatsLogManager$LauncherEvent";
    private static final String CLS_FOLDER_INFO = "com.android.launcher3.model.data.FolderInfo";
    private static final String CLS_APP_INFO = "com.android.launcher3.model.data.AppInfo";
    private static final String CLS_EXECUTORS = "com.android.launcher3.util.Executors";
    private static final String CLS_LAUNCHER_STATE = "com.android.launcher3.LauncherState";

    private final ConfigStore store;
    private final Class<?> launcherClass;
    private final Class<?> popupClass;
    private final Class<?> optionItemClass;
    private final Object ignoreEvent;
    private final Class<?> folderInfoClass;
    private final Class<?> appInfoClass;
    private final Class<?> executorsClass;
    private final Class<?> launcherStateClass;

    /** Resolves everything up front so a missing class disables the menu instead of crashing. */
    FolderMenu(ClassLoader cl, ConfigStore store) {
        this.store = store;
        launcherClass = XposedHelpers.findClass(CLS_LAUNCHER, cl);
        popupClass = XposedHelpers.findClass(CLS_OPTIONS_POPUP, cl);
        optionItemClass = XposedHelpers.findClass(CLS_OPTIONS_POPUP + "$OptionItem", cl);
        ignoreEvent = XposedHelpers.getStaticObjectField(
                XposedHelpers.findClass(CLS_LAUNCHER_EVENT, cl), "IGNORE");
        folderInfoClass = XposedHelpers.findClass(CLS_FOLDER_INFO, cl);
        appInfoClass = XposedHelpers.findClass(CLS_APP_INFO, cl);
        executorsClass = XposedHelpers.findClass(CLS_EXECUTORS, cl);
        launcherStateClass = XposedHelpers.findClass(CLS_LAUNCHER_STATE, cl);
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass(SmartDrawer.CLS_FOLDER_ICON, cl),
                "onFinishInflate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        ((View) param.thisObject).setOnLongClickListener(FolderMenu.this::safeShow);
                    }
                });
    }

    /** The long press of a folder tile: never lets a failure reach the launcher. */
    private boolean safeShow(View icon) {
        try {
            return show(icon);
        } catch (Throwable t) {
            Hooks.log("folder menu failed: " + t);
            return false;
        }
    }

    private boolean show(View icon) {
        try {
            Object groupItem = icon.getTag();
            List<?> apps = GroupItems.apps(groupItem);
            if (apps == null || apps.isEmpty()) {
                return false;
            }
            List<?> snapshot = new ArrayList<>(apps);
            CharSequence title = ((TextView) XposedHelpers.callMethod(
                    icon, "getFolderName")).getText();
            Config.Folder custom = store.get().folderForKey(GroupItems.category(groupItem));
            Context context = icon.getContext();
            Object launcher = XposedHelpers.callStaticMethod(launcherClass, "getLauncher", context);

            // Reuse the launcher's own translated labels and icons so the items look native.
            ArrayList<Object> items = new ArrayList<>();
            addItem(items, context, "add_to_home_screen", "ic_install_no_shadow", v -> {
                addFolderToHome(launcher, title, snapshot);
                return true;
            });
            if (custom != null) {
                addItem(items, context, "share_card_drawer_detail_edit", "ic_setting", v -> {
                    context.startActivity(new Intent()
                            .setClassName(Config.MODULE_PKG, Config.FOLDER_ACTIVITY)
                            .putExtra(Config.EXTRA_FOLDER_ID, custom.id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return true;
                });
            }
            if (items.isEmpty()) {
                return false;
            }

            Rect bounds = new Rect();
            XposedHelpers.callMethod(XposedHelpers.callMethod(launcher, "getDragLayer"),
                    "getDescendantRectRelativeToSelf", icon, bounds);
            XposedHelpers.callStaticMethod(popupClass, "show", launcher, new RectF(bounds),
                    items, true);
            icon.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            return true;
        } catch (Throwable t) {
            Hooks.log("folder menu failed: " + t);
            return false;
        }
    }

    private void addItem(List<Object> items, Context context, String label, String image,
            View.OnLongClickListener onPick) {
        int labelRes = launcherResource(context, "string", label);
        int imageRes = launcherResource(context, "drawable", image);
        if (labelRes != 0 && imageRes != 0) {
            items.add(XposedHelpers.newInstance(optionItemClass, context, labelRes, imageRes,
                    ignoreEvent, onPick));
        }
    }

    private static int launcherResource(Context context, String type, String name) {
        return context.getResources().getIdentifier(name, type, Config.LAUNCHER_PKG);
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
                Collections.singletonList(Pair.create(folder, null)));

        Executor modelExecutor =
                (Executor) XposedHelpers.getStaticObjectField(executorsClass, "MODEL_EXECUTOR");
        modelExecutor.execute(Hooks.guard("folder to home screen", () -> {
            int folderId = XposedHelpers.getIntField(folder, "id");
            if (folderId < 0) {
                Hooks.log("folder was not added to the workspace");
                return;
            }
            for (int i = 0; i < children.size(); i++) {
                XposedHelpers.callMethod(writer, "addItemToDatabase",
                        children.get(i), folderId, 0, i, 0);
            }
        }));

        Object normal = XposedHelpers.getStaticObjectField(launcherStateClass, "NORMAL");
        XposedHelpers.callMethod(XposedHelpers.callMethod(launcher, "getStateManager"),
                "goToState", normal);
    }
}
