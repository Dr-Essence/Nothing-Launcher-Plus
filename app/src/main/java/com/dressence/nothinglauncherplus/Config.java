package com.dressence.nothinglauncherplus;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Custom drawer folder configuration, shared between the settings UI (module process) and the
 * launcher hooks (read through XSharedPreferences).
 */
public final class Config {

    public static final String MODULE_PKG = "com.dressence.nothinglauncherplus";
    public static final String LAUNCHER_PKG = "com.nothing.launcher";
    public static final String SETTINGS_ACTIVITY = MODULE_PKG + ".ui.MainActivity";

    public static final String PREFS_NAME = "config";
    public static final String KEY_FOLDERS = "folders";
    public static final String KEY_CUSTOM_FIRST = "custom_first";
    public static final String KEY_HIDE_BUILTIN = "hide_builtin";

    /** Broadcast sent to the launcher after the configuration changes. */
    public static final String ACTION_RELOAD = MODULE_PKG + ".action.RELOAD";

    /** Prefix of the category keys we inject into the Smart drawer. */
    public static final String CATEGORY_PREFIX = "nlplus:";

    public static final class Folder {
        public final String id;
        public String name;
        /** Flattened {@link android.content.ComponentName}s. */
        public final LinkedHashSet<String> apps = new LinkedHashSet<>();

        public Folder(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String categoryKey() {
            return CATEGORY_PREFIX + id;
        }
    }

    public final List<Folder> folders = new ArrayList<>();
    public boolean customFirst = true;
    public boolean hideBuiltin = false;

    private final Map<String, Folder> byComponent = new HashMap<>();
    private final Map<String, Folder> byKey = new HashMap<>();

    public static Config read(SharedPreferences prefs) {
        Config config = new Config();
        config.customFirst = prefs.getBoolean(KEY_CUSTOM_FIRST, true);
        config.hideBuiltin = prefs.getBoolean(KEY_HIDE_BUILTIN, false);
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_FOLDERS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                Folder folder = new Folder(obj.getString("id"), obj.optString("name", "Folder"));
                JSONArray apps = obj.optJSONArray("apps");
                if (apps != null) {
                    for (int j = 0; j < apps.length(); j++) {
                        folder.apps.add(apps.getString(j));
                    }
                }
                config.folders.add(folder);
            }
        } catch (JSONException ignored) {
            // Corrupt config: fall back to whatever was parsed so far.
        }
        config.reindex();
        return config;
    }

    public void write(SharedPreferences.Editor editor) {
        JSONArray array = new JSONArray();
        try {
            for (Folder folder : folders) {
                JSONObject obj = new JSONObject();
                obj.put("id", folder.id);
                obj.put("name", folder.name);
                obj.put("apps", new JSONArray(folder.apps));
                array.put(obj);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        editor.putString(KEY_FOLDERS, array.toString())
                .putBoolean(KEY_CUSTOM_FIRST, customFirst)
                .putBoolean(KEY_HIDE_BUILTIN, hideBuiltin);
        reindex();
    }

    /** An app lives in at most one custom folder; assigning it here removes it from the others. */
    public void assign(String component, Folder target) {
        for (Folder folder : folders) {
            if (folder != target) {
                folder.apps.remove(component);
            }
        }
        target.apps.add(component);
        reindex();
    }

    public void reindex() {
        byComponent.clear();
        byKey.clear();
        for (Folder folder : folders) {
            byKey.put(folder.categoryKey(), folder);
            for (String app : folder.apps) {
                byComponent.putIfAbsent(app, folder);
            }
        }
    }

    public Folder folderForComponent(String flattenedComponent) {
        return byComponent.get(flattenedComponent);
    }

    public Folder folderForKey(String categoryKey) {
        return byKey.get(categoryKey);
    }

    public List<String> categoryKeys() {
        if (folders.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> keys = new ArrayList<>(folders.size());
        for (Folder folder : folders) {
            keys.add(folder.categoryKey());
        }
        return keys;
    }
}
