package com.dressence.nothinglauncherplus.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XposedHelpers;

/**
 * Brings back home screen items the launcher still has in another grid's database.
 *
 * <p>Nothing Launcher keeps one database per grid size (launcher_5_by_8.db, launcher_5_by_9.db,
 * …). When the grid changes it migrates the items into the new grid's database and drops any
 * it cannot place there; the old grid's database keeps them (DebuG16: the clock and three
 * Nothing widgets, the App Stores, Games and Books folders and Claude were in
 * launcher_5_by_8.db, all from row 4 down, and missing from the 5×9 one in use).
 *
 * <p>On request (the settings app's button), inside the launcher, on its own model thread and
 * through its own database connection (ModelDbController), the most recently changed other
 * grid database with the same number of columns and no more rows than the grid in use is read:
 * its home screen app icons, shortcuts and folders (with their contents) that the grid in use
 * does not have are added at their own page and cells, where those cells are free, and the
 * launcher reloads its model. Nothing is removed or moved; an app already on the home screen
 * or dock is not added twice. Widgets are not brought back (their widget ids belong to the
 * widget service, which let them go); their names are listed so they can be added again.
 */
final class HomeRestore {

    private static final String CLS_APP_STATE = "com.android.launcher3.LauncherAppState";
    private static final String CLS_EXECUTORS = "com.android.launcher3.util.Executors";
    private static final Pattern GRID_DB = Pattern.compile("launcher_(\\d+)_by_(\\d+)\\.db");
    private static final int DESKTOP = -100;
    private static final int HOTSEAT = -101;
    /** Item types brought back: app, shortcut, folder, deep shortcut. */
    private static final Set<Integer> RESTORED_TYPES = new HashSet<>(
            java.util.Arrays.asList(0, 1, 2, 6));
    private static final int FOLDER = 2;

    private static boolean registered;

    private HomeRestore() {
    }

    static synchronized void register(Context appContext, ClassLoader cl) {
        if (registered) {
            return;
        }
        registered = true;
        appContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Hooks.guard("home restore", () -> {
                    Executor model = (Executor) XposedHelpers.getStaticObjectField(
                            XposedHelpers.findClass(CLS_EXECUTORS, cl), "MODEL_EXECUTOR");
                    model.execute(Hooks.guard("home restore run",
                            () -> restore(appContext, cl)));
                }).run();
            }
        }, new IntentFilter(Config.ACTION_RESTORE_ITEMS), Context.RECEIVER_EXPORTED);
    }

    /** On the launcher's model thread. */
    private static void restore(Context context, ClassLoader cl) {
        Object app = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_APP_STATE, cl),
                "getInstance", context);
        Object model = XposedHelpers.callMethod(app, "getModel");
        Object controller = XposedHelpers.callMethod(model, "getModelDbController");
        SQLiteDatabase db = (SQLiteDatabase) XposedHelpers.callMethod(controller, "getDb");
        File current = new File(db.getPath());
        Matcher m = GRID_DB.matcher(current.getName());
        if (!m.matches()) {
            tell(context, "Home restore: the launcher's database (" + current.getName()
                    + ") is not a grid database");
            return;
        }
        int columns = Integer.parseInt(m.group(1));
        int rows = Integer.parseInt(m.group(2));
        File source = null;
        File[] files = current.getParentFile() != null ? current.getParentFile().listFiles()
                : null;
        for (File f : files != null ? files : new File[0]) {
            Matcher g = GRID_DB.matcher(f.getName());
            if (!g.matches() || f.equals(current)
                    || Integer.parseInt(g.group(1)) != columns
                    || Integer.parseInt(g.group(2)) > rows) {
                continue; // Another column count would need its cells worked out again.
            }
            if (source == null || f.lastModified() > source.lastModified()) {
                source = f;
            }
        }
        if (source == null) {
            tell(context, "Home restore: no other " + columns + "-column grid to restore from");
            return;
        }
        Hooks.log("home restore: from " + source.getName() + " into " + current.getName());
        List<String> restored = new ArrayList<>();
        List<String> widgets = new ArrayList<>();
        // Read only, on a connection of its own: the launcher's connection is not touched.
        try (SQLiteDatabase src = SQLiteDatabase.openDatabase(source.getPath(), null,
                SQLiteDatabase.OPEN_READONLY)) {
            restoreFrom(db, src, controller, columns, rows, restored, widgets);
        }
        if (!restored.isEmpty()) {
            XposedHelpers.callMethod(model, "forceReload");
        }
        StringBuilder message = new StringBuilder("Home restore: ");
        message.append(restored.isEmpty() ? "nothing missing to bring back"
                : restored.size() + " brought back (" + String.join(", ", restored) + ")");
        if (!widgets.isEmpty()) {
            message.append(". Add again: ").append(String.join(", ", widgets));
        }
        Hooks.log(message.toString());
        tell(context, message.toString());
    }

    private static void restoreFrom(SQLiteDatabase db, SQLiteDatabase src, Object controller,
            int columns, int rows, List<String> restored, List<String> widgets) {
        // What the grid in use has: ids, intents, and the desktop cells taken.
        Set<Integer> ids = new HashSet<>();
        Set<String> intents = new HashSet<>();
        Map<Integer, boolean[][]> taken = new HashMap<>();
        int maxId = 0;
        try (Cursor c = db.rawQuery("SELECT _id, container, screen, cellX, cellY, spanX, spanY,"
                + " intent FROM favorites", null)) {
            while (c.moveToNext()) {
                int id = c.getInt(0);
                ids.add(id);
                maxId = Math.max(maxId, id);
                int container = c.getInt(1);
                if ((container == DESKTOP || container == HOTSEAT) && !c.isNull(7)) {
                    intents.add(component(c.getString(7)));
                }
                if (container == DESKTOP) {
                    mark(taken, c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5), c.getInt(6),
                            columns, rows);
                }
            }
        }
        // The other grid's desktop items the grid in use does not have.
        Map<Integer, Integer> newIds = new HashMap<>();
        List<String> titles = new ArrayList<>();
        try (Cursor c = src.rawQuery("SELECT _id, screen, cellX, cellY, spanX, spanY, itemType,"
                + " title, intent, appWidgetProvider FROM favorites WHERE container = "
                + DESKTOP + " ORDER BY screen, cellY, cellX", null)) {
            while (c.moveToNext()) {
                int id = c.getInt(0);
                if (ids.contains(id)) {
                    continue;
                }
                int type = c.getInt(6);
                String title = c.isNull(7) ? null : c.getString(7);
                if (!RESTORED_TYPES.contains(type)) {
                    String provider = c.isNull(9) ? null : c.getString(9);
                    widgets.add(title != null ? title : provider != null
                            ? provider.substring(provider.lastIndexOf('.') + 1) : "a widget");
                    continue;
                }
                if (!c.isNull(8) && intents.contains(component(c.getString(8)))) {
                    continue; // That app is on the home screen already.
                }
                int screen = c.getInt(1);
                int x = c.getInt(2);
                int y = c.getInt(3);
                int spanX = c.getInt(4);
                int spanY = c.getInt(5);
                if (x < 0 || y < 0 || x + spanX > columns || y + spanY > rows
                        || !free(taken, screen, x, y, spanX, spanY, columns, rows)) {
                    Hooks.log("home restore: no room for " + title + " at " + screen + ":" + x
                            + "," + y);
                    continue;
                }
                mark(taken, screen, x, y, spanX, spanY, columns, rows);
                newIds.put(id, id <= maxId ? id : -1);
                titles.add(title != null ? title : "item " + id);
            }
        }
        if (newIds.isEmpty()) {
            return;
        }
        // Folders bring their contents.
        Map<Integer, Integer> children = new HashMap<>(); // child id -> folder id
        StringBuilder folders = new StringBuilder();
        for (Integer id : newIds.keySet()) {
            folders.append(folders.length() == 0 ? "" : ",").append(id);
        }
        try (Cursor c = src.rawQuery("SELECT _id, container, itemType FROM favorites"
                + " WHERE container IN (" + folders + ")", null)) {
            while (c.moveToNext()) {
                if (!ids.contains(c.getInt(0)) && RESTORED_TYPES.contains(c.getInt(2))
                        && c.getInt(2) != FOLDER) {
                    children.put(c.getInt(0), c.getInt(1));
                }
            }
        }
        Set<String> wanted = columns(db);
        // Through Nothing's own insert (ModelDbController.insert: its id bookkeeping, modified
        // time); folders first, so their contents find them.
        for (Map.Entry<Integer, Integer> e : newIds.entrySet()) {
            int to = e.getValue() >= 0 ? e.getValue()
                    : (Integer) XposedHelpers.callMethod(controller, "generateNewItemId");
            e.setValue(to);
            insert(src, controller, wanted, e.getKey(), to, null);
        }
        for (Map.Entry<Integer, Integer> e : children.entrySet()) {
            int id = e.getKey();
            int to = id <= maxId ? id
                    : (Integer) XposedHelpers.callMethod(controller, "generateNewItemId");
            insert(src, controller, wanted, id, to, newIds.get(e.getValue()));
        }
        restored.addAll(titles);
    }

    /** One row of the other grid's database, as Nothing's insert takes it. */
    private static void insert(SQLiteDatabase src, Object controller, Set<String> wanted, int id,
            int to, Integer container) {
        android.content.ContentValues values = new android.content.ContentValues();
        try (Cursor c = src.rawQuery("SELECT * FROM favorites WHERE _id = " + id, null)) {
            if (!c.moveToFirst()) {
                return;
            }
            for (int i = 0; i < c.getColumnCount(); i++) {
                String name = c.getColumnName(i);
                if (!wanted.contains(name)) {
                    continue; // A column the grid in use does not have.
                }
                switch (c.getType(i)) {
                    case Cursor.FIELD_TYPE_INTEGER:
                        values.put(name, c.getLong(i));
                        break;
                    case Cursor.FIELD_TYPE_FLOAT:
                        values.put(name, c.getDouble(i));
                        break;
                    case Cursor.FIELD_TYPE_STRING:
                        values.put(name, c.getString(i));
                        break;
                    case Cursor.FIELD_TYPE_BLOB:
                        values.put(name, c.getBlob(i));
                        break;
                    default:
                        values.putNull(name);
                }
            }
        }
        values.put("_id", to);
        if (container != null) {
            values.put("container", container);
        }
        int result = (Integer) XposedHelpers.callMethod(controller, "insert", values);
        if (result < 0) {
            Hooks.log("home restore: item " + id + " not added");
        }
    }

    private static Set<String> columns(SQLiteDatabase db) {
        Set<String> names = new HashSet<>();
        try (Cursor c = db.rawQuery("PRAGMA table_info(favorites)", null)) {
            while (c.moveToNext()) {
                names.add(c.getString(1));
            }
        }
        return names;
    }

    /** An item's app (its intent's component), so one app is not added twice. */
    private static String component(String intent) {
        int at = intent.indexOf("component=");
        if (at < 0) {
            return intent;
        }
        int end = intent.indexOf(';', at);
        return intent.substring(at, end < 0 ? intent.length() : end);
    }

    private static void mark(Map<Integer, boolean[][]> taken, int screen, int x, int y,
            int spanX, int spanY, int columns, int rows) {
        boolean[][] page = taken.get(screen);
        if (page == null) {
            page = new boolean[columns][rows];
            taken.put(screen, page);
        }
        for (int i = Math.max(0, x); i < Math.min(columns, x + spanX); i++) {
            for (int j = Math.max(0, y); j < Math.min(rows, y + spanY); j++) {
                page[i][j] = true;
            }
        }
    }

    private static boolean free(Map<Integer, boolean[][]> taken, int screen, int x, int y,
            int spanX, int spanY, int columns, int rows) {
        boolean[][] page = taken.get(screen);
        if (page == null) {
            return true;
        }
        for (int i = x; i < Math.min(columns, x + spanX); i++) {
            for (int j = y; j < Math.min(rows, y + spanY); j++) {
                if (page[i][j]) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void tell(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(Hooks.guard("home restore message",
                () -> Toast.makeText(context, message, Toast.LENGTH_LONG).show()));
    }
}
