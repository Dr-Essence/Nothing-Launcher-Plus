package com.dressence.nothinglauncherplus.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Where items were put on the landscape home screen: moved or resized there, each keeps that
 * place and size in landscape, while the launcher's database (and so portrait) keeps its
 * portrait one. Kept per item and per grid (a landscape place means nothing on another grid),
 * in the launcher's own data.
 */
final class LandscapeMemory {

    private static final String FILE = "nlplus_landscape_places";

    private final SharedPreferences prefs;

    LandscapeMemory(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** {@code {x, y, spanX, spanY}} of item {@code id} on page {@code screenId}, or null. */
    int[] get(String grid, int id, int screenId) {
        String value = prefs.getString(key(grid, id), null);
        if (value == null) {
            return null;
        }
        String[] parts = value.split(",");
        if (parts.length != 5 || Integer.parseInt(parts[0]) != screenId) {
            return null;
        }
        return new int[] {Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3]), Integer.parseInt(parts[4])};
    }

    void put(String grid, int id, int screenId, int x, int y, int spanX, int spanY) {
        prefs.edit().putString(key(grid, id),
                screenId + "," + x + "," + y + "," + spanX + "," + spanY).apply();
    }

    private static String key(String grid, int id) {
        return grid + "/" + id;
    }
}
