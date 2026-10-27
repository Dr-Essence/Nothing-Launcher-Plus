package com.dressence.nothinglauncherplus.launcher;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accessors for the Smart drawer's group adapter item (SmartAppsGridAdapter.GroupAdapterItem),
 * whose members are obfuscated: it holds its category key in its only String field and its apps
 * in its only List field.
 */
final class GroupItems {

    private static final Map<Class<?>, Field> categoryFields = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field> appsFields = new ConcurrentHashMap<>();

    private GroupItems() {
    }

    static String category(Object groupItem) {
        Object value = read(groupItem, categoryFields, String.class);
        return value instanceof String ? (String) value : null;
    }

    /** The item's live app list. */
    static List<?> apps(Object groupItem) {
        Object value = read(groupItem, appsFields, List.class);
        return value instanceof List ? (List<?>) value : null;
    }

    private static Object read(Object groupItem, Map<Class<?>, Field> cache, Class<?> type) {
        if (groupItem == null) {
            return null;
        }
        Field field = cache.computeIfAbsent(groupItem.getClass(), cls -> {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType() == type && !Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    return f;
                }
            }
            return null;
        });
        try {
            return field != null ? field.get(groupItem) : null;
        } catch (IllegalAccessException e) {
            return null;
        }
    }
}
