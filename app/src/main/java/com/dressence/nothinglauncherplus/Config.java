package com.dressence.nothinglauncherplus;

import android.content.SharedPreferences;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Custom drawer folder configuration, shared between the settings UI (module process) and the
 * launcher hooks (read through XSharedPreferences).
 */
public final class Config {

    public static final String MODULE_PKG = "com.dressence.nothinglauncherplus";
    public static final String LAUNCHER_PKG = "com.nothing.launcher";
    public static final String SYSTEMUI_PKG = "com.android.systemui";
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
    public static final String KEY_ANIMATE_COVERS = "animate_covers";
    public static final String KEY_DOT_MATRIX = "dot_matrix";
    public static final String KEY_DOT_ICONS = "dot_icons";
    public static final String KEY_NDOT_STATUS = "ndot_status_bar";
    public static final String KEY_STATUS_DATE = "status_bar_date";
    public static final String KEY_SYSTEM_FONT = "system_font";
    public static final String KEY_NDOT_QS = "ndot_quick_settings";
    public static final String KEY_QS_AMBER = "qs_amber";
    public static final String KEY_UI_SOUNDS = "ui_sounds";
    public static final String KEY_MUSIC = "music";
    public static final String KEY_MUSIC_STYLE = "music_style";
    public static final String KEY_MUSIC_VOLUME = "music_volume";
    public static final String KEY_ALLOW_ROTATION = "allow_rotation";
    public static final String KEY_CONTROLLER_NAV = "controller_nav";
    public static final String KEY_LOCK_ROTATION = "lock_rotation";

    /** Broadcast sent to the launcher after the configuration changes. */
    public static final String ACTION_RELOAD = MODULE_PKG + ".action.RELOAD";
    /** Sent when the system font choice changes, for system_server to rebuild its font map. */
    public static final String ACTION_FONT = MODULE_PKG + ".SYSTEM_FONT";

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
        /** {@code anim.Motion} name played by the emoji cover; empty means not chosen yet. */
        public String coverMotion = "";
        /**
         * {@code anim.DotEffect} played inside the dots, as the emoji table gives it ("NAME" or
         * "NAME:anchor,points"); empty means not chosen yet.
         */
        public String coverEffect = "";
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
    /** Play emoji cover animations. */
    public boolean animateCovers = true;
    /** Emoji covers drawn as a Nothing-style dot matrix. */
    public boolean dotMatrix = true;
    /** Every app icon drawn as a glowing Nothing dot matrix. */
    public boolean dotIcons = false;
    /** Status bar clock and battery in glowing green NDot, like the folder screens. */
    public boolean ndotStatusBar = false;
    /** The date beside the status bar clock. */
    public boolean statusBarDate = false;
    /**
     * The font hooked apps draw their text in: "file:" and a font file's path, "res:" and a
     * font of Nothing's widgets app, or empty for the phone's own.
     */
    public String systemFont = "";
    /** Quick settings tile icons drawn as dot-matrix icons. */
    public boolean ndotQuickSettings = false;
    /** Quick settings tiles that are on lit amber, as a retro car dashboard, not green. */
    public boolean qsAmber = false;
    public boolean uiSounds = false;
    public boolean music = false;
    /** A {@code sound.Style} name. */
    public String musicStyle = "HOME_MENU";
    /** 0..100. */
    public int musicVolume = 45;
    /** Let the home screen and app drawer follow auto-rotate into landscape. */
    public boolean allowRotation = false;
    /** Game controller buttons (Y, L1/R1, X, Start) and focus animations. */
    public boolean controllerNav = true;
    /** Test: the lock screen follows auto-rotate too, and its landscape layout is logged. */
    public boolean lockRotation = false;

    private final Map<String, Folder> byComponent = new HashMap<>();
    private final Map<String, Folder> byKey = new HashMap<>();

    public static Config read(SharedPreferences prefs) {
        Config config = new Config();
        config.readSettings(new Source() {
            @Override
            public boolean bool(String key, boolean otherwise) {
                return prefs.getBoolean(key, otherwise);
            }

            @Override
            public int number(String key, int otherwise) {
                return prefs.getInt(key, otherwise);
            }

            @Override
            public String text(String key, String otherwise) {
                return prefs.getString(key, otherwise);
            }
        });
        try {
            config.readFolders(new JSONArray(prefs.getString(KEY_FOLDERS, "[]")));
        } catch (JSONException ignored) {
            // Corrupt config: keep whatever was parsed so far.
        }
        config.reindex();
        return config;
    }

    public void write(SharedPreferences.Editor editor) {
        editor.putString(KEY_FOLDERS, foldersJson().toString());
        writeSettings(new Sink() {
            @Override
            public void bool(String key, boolean value) {
                editor.putBoolean(key, value);
            }

            @Override
            public void number(String key, int value) {
                editor.putInt(key, value);
            }

            @Override
            public void text(String key, String value) {
                editor.putString(key, value);
            }
        });
        reindex();
    }

    /** Everything as one JSON object, for backups. */
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject().put(KEY_FOLDERS, foldersJson());
        writeSettings(new Sink() {
            @Override
            public void bool(String key, boolean value) {
                put(key, value);
            }

            @Override
            public void number(String key, int value) {
                put(key, value);
            }

            @Override
            public void text(String key, String value) {
                put(key, value);
            }

            private void put(String key, Object value) {
                try {
                    json.put(key, value);
                } catch (JSONException e) {
                    // Only for a null key or a NaN: neither is ever written here.
                    throw new IllegalArgumentException(key, e);
                }
            }
        });
        return json;
    }

    public static Config fromJson(JSONObject json) throws JSONException {
        Config config = new Config();
        config.readSettings(new Source() {
            @Override
            public boolean bool(String key, boolean otherwise) {
                return json.optBoolean(key, otherwise);
            }

            @Override
            public int number(String key, int otherwise) {
                return json.optInt(key, otherwise);
            }

            @Override
            public String text(String key, String otherwise) {
                return json.optString(key, otherwise);
            }
        });
        config.readFolders(json.getJSONArray(KEY_FOLDERS));
        config.reindex();
        return config;
    }

    /** Where settings are read from: the saved preferences, or a backup. */
    private interface Source {
        boolean bool(String key, boolean otherwise);

        int number(String key, int otherwise);

        String text(String key, String otherwise);
    }

    /** Where settings are written to. */
    private interface Sink {
        void bool(String key, boolean value);

        void number(String key, int value);

        void text(String key, String value);
    }

    /** Every setting but the folders, with its default: the one list of them. */
    private void readSettings(Source in) {
        customFirst = in.bool(KEY_CUSTOM_FIRST, true);
        hideBuiltin = in.bool(KEY_HIDE_BUILTIN, false);
        looseApps = in.bool(KEY_LOOSE_APPS, false);
        drawerOpacity = Math.max(0, Math.min(100, in.number(KEY_DRAWER_OPACITY, 100)));
        animateCovers = in.bool(KEY_ANIMATE_COVERS, true);
        dotMatrix = in.bool(KEY_DOT_MATRIX, true);
        dotIcons = in.bool(KEY_DOT_ICONS, false);
        ndotStatusBar = in.bool(KEY_NDOT_STATUS, false);
        statusBarDate = in.bool(KEY_STATUS_DATE, false);
        systemFont = in.text(KEY_SYSTEM_FONT, "");
        ndotQuickSettings = in.bool(KEY_NDOT_QS, false);
        qsAmber = in.bool(KEY_QS_AMBER, false);
        uiSounds = in.bool(KEY_UI_SOUNDS, false);
        music = in.bool(KEY_MUSIC, false);
        musicStyle = in.text(KEY_MUSIC_STYLE, "HOME_MENU");
        musicVolume = Math.max(0, Math.min(100, in.number(KEY_MUSIC_VOLUME, 45)));
        allowRotation = in.bool(KEY_ALLOW_ROTATION, false);
        controllerNav = in.bool(KEY_CONTROLLER_NAV, true);
        lockRotation = in.bool(KEY_LOCK_ROTATION, false);
    }

    private void writeSettings(Sink out) {
        out.bool(KEY_CUSTOM_FIRST, customFirst);
        out.bool(KEY_HIDE_BUILTIN, hideBuiltin);
        out.bool(KEY_LOOSE_APPS, looseApps);
        out.number(KEY_DRAWER_OPACITY, drawerOpacity);
        out.bool(KEY_ANIMATE_COVERS, animateCovers);
        out.bool(KEY_DOT_MATRIX, dotMatrix);
        out.bool(KEY_DOT_ICONS, dotIcons);
        out.bool(KEY_NDOT_STATUS, ndotStatusBar);
        out.bool(KEY_STATUS_DATE, statusBarDate);
        out.text(KEY_SYSTEM_FONT, systemFont);
        out.bool(KEY_NDOT_QS, ndotQuickSettings);
        out.bool(KEY_QS_AMBER, qsAmber);
        out.bool(KEY_UI_SOUNDS, uiSounds);
        out.bool(KEY_MUSIC, music);
        out.text(KEY_MUSIC_STYLE, musicStyle);
        out.number(KEY_MUSIC_VOLUME, musicVolume);
        out.bool(KEY_ALLOW_ROTATION, allowRotation);
        out.bool(KEY_CONTROLLER_NAV, controllerNav);
        out.bool(KEY_LOCK_ROTATION, lockRotation);
    }

    private void readFolders(JSONArray array) throws JSONException {
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);
            Folder folder = new Folder(obj.getString("id"), obj.optString("name", "Folder"));
            folder.coverText = obj.optString("coverText", "");
            folder.coverMotion = obj.optString("coverMotion", "");
            folder.coverEffect = obj.optString("coverEffect", "");
            folder.coverImageVersion = obj.optLong("coverImage", 0);
            JSONArray apps = obj.optJSONArray("apps");
            if (apps != null) {
                for (int j = 0; j < apps.length(); j++) {
                    folder.apps.add(apps.getString(j));
                }
            }
            folders.add(folder);
        }
    }

    private JSONArray foldersJson() {
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
                if (!folder.coverMotion.isEmpty()) {
                    obj.put("coverMotion", folder.coverMotion);
                }
                if (!folder.coverEffect.isEmpty()) {
                    obj.put("coverEffect", folder.coverEffect);
                }
                if (folder.coverImageVersion != 0) {
                    obj.put("coverImage", folder.coverImageVersion);
                }
                array.put(obj);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return array;
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
