package com.dressence.nothinglauncherplus;

import android.content.SharedPreferences;
import android.net.Uri;

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
    public static final String FOLDER_ACTIVITY = MODULE_PKG + ".ui.FolderActivity";
    /** String extra naming the folder to edit in {@link #FOLDER_ACTIVITY}. */
    public static final String EXTRA_FOLDER_ID = "folder_id";

    public static final String PREFS_NAME = "config";
    public static final String KEY_FOLDERS = "folders";
    public static final String KEY_CUSTOM_FIRST = "custom_first";
    public static final String KEY_HIDE_BUILTIN = "hide_builtin";
    public static final String KEY_LOOSE_APPS = "loose_apps";
    public static final String KEY_DRAWER_OPACITY = "drawer_opacity";

    /** Broadcast sent to the launcher after the configuration changes. */
    public static final String ACTION_RELOAD = MODULE_PKG + ".action.RELOAD";

    /** Prefix of the category keys we inject into the Smart drawer. */
    public static final String CATEGORY_PREFIX = "nlplus:";

    /** Read-only provider serving folder cover images to the launcher. */
    public static final String COVER_AUTHORITY = MODULE_PKG + ".covers";

    public static final class Folder {
        public final String id;
        public String name;
        /** Flattened {@link android.content.ComponentName}s, in display order. */
        public final LinkedHashSet<String> apps = new LinkedHashSet<>();
        /** Emoji or short text shown as the tile cover; empty for none. */
        public String coverText = "";
        /** Last-modified time of the cover image; 0 when the folder has no cover image. */
        public long coverImageVersion;

        public Folder(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String categoryKey() {
            return CATEGORY_PREFIX + id;
        }

        public boolean hasCover() {
            return coverImageVersion != 0 || !coverText.isEmpty();
        }

        /** The version query parameter makes every image change a new cache key. */
        public Uri coverImageUri() {
            return new Uri.Builder().scheme("content").authority(COVER_AUTHORITY)
                    .appendPath(id).appendQueryParameter("v", Long.toString(coverImageVersion))
                    .build();
        }
    }

    public final List<Folder> folders = new ArrayList<>();
    public boolean customFirst = true;
    public boolean hideBuiltin = false;
    /** Show apps of the "Others" category as plain icons instead of a folder. */
    public boolean looseApps = false;
    /** App drawer background opacity in percent (100 = stock). */
    public int drawerOpacity = 100;

    private final Map<String, Folder> byComponent = new HashMap<>();
    private final Map<String, Folder> byKey = new HashMap<>();

    public static Config read(SharedPreferences prefs) {
        Config config = new Config();
        config.customFirst = prefs.getBoolean(KEY_CUSTOM_FIRST, true);
        config.hideBuiltin = prefs.getBoolean(KEY_HIDE_BUILTIN, false);
        config.looseApps = prefs.getBoolean(KEY_LOOSE_APPS, false);
        config.drawerOpacity = Math.max(0, Math.min(100, prefs.getInt(KEY_DRAWER_OPACITY, 100)));
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_FOLDERS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                Folder folder = new Folder(obj.getString("id"), obj.optString("name", "Folder"));
                folder.coverText = obj.optString("coverText", "");
                folder.coverImageVersion = obj.optLong("coverImage", 0);
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
                if (!folder.coverText.isEmpty()) {
                    obj.put("coverText", folder.coverText);
                }
                if (folder.coverImageVersion != 0) {
                    obj.put("coverImage", folder.coverImageVersion);
                }
                array.put(obj);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        editor.putString(KEY_FOLDERS, array.toString())
                .putBoolean(KEY_CUSTOM_FIRST, customFirst)
                .putBoolean(KEY_HIDE_BUILTIN, hideBuiltin)
                .putBoolean(KEY_LOOSE_APPS, looseApps)
                .putInt(KEY_DRAWER_OPACITY, drawerOpacity);
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

    public Folder folderForId(String id) {
        return byKey.get(CATEGORY_PREFIX + id);
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
