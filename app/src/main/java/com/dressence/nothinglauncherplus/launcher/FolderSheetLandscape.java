package com.dressence.nothinglauncherplus.launcher;

import android.graphics.Rect;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * A drawer folder opened in landscape, laid out as it is in portrait: its name over its apps.
 *
 * <p>Nothing's folder sheet ({@code SmartFullSheet}) gives its app list at least four rows of
 * app cells and its name whatever height is left of the screen. In portrait that is plenty; on
 * a landscape screen four rows take nearly all of it and the name is squeezed out. Its grid is
 * also four apps across, which leaves a landscape screen nearly empty. In phone landscape the
 * sheet here has as many columns as the drawer has tiles, as many rows as its apps need, and
 * never more than leave its name room; a folder with more apps scrolls, as in portrait.
 */
final class FolderSheetLandscape {

    private static final String CLS_SHEET = SmartDrawer.SMART_PKG + "SmartFullSheet";
    /** Room over the name, under the status bar. */
    private static final int NAME_TOP_DP = 16;

    private final ClassLoader cl;

    FolderSheetLandscape(ClassLoader cl) {
        this.cl = cl;
    }

    void install() {
        Class<?> sheet = XposedHelpers.findClass(CLS_SHEET, cl);
        // The sheet's grid and its app list: classes of their own, found from its fields.
        Class<?> adapter = fieldType(sheet, "adapter");
        Class<?> list = fieldType(sheet, "appsList");
        XC_MethodHook columns = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object profile = profileOf(param.thisObject);
                if (profile != null && Landscape.isPhoneLandscape(profile)) {
                    param.setResult(Landscape.smartColumns(profile));
                }
            }
        };
        XposedHelpers.findAndHookMethod(adapter, "getAppCountPerRow", columns);
        XposedHelpers.findAndHookMethod(list, "getAppCountPerRow", columns);
        Hooks.deoptimizeAll(sheet, "setupSheet");

        Method displayRows = null;
        for (Method m : sheet.getDeclaredMethods()) {
            if (m.getName().equals("getDisplayRows") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == List.class) {
                displayRows = m;
            }
        }
        if (displayRows == null) {
            throw new IllegalStateException("SmartFullSheet.getDisplayRows not found");
        }
        XposedBridge.hookMethod(displayRows, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object profile = XposedHelpers.getObjectField(param.thisObject,
                        "matchedDeviceProfile");
                if (profile != null && Landscape.isPhoneLandscape(profile)) {
                    param.setResult(rows(param.thisObject, profile,
                            ((List<?>) param.args[0]).size()));
                }
            }
        });
    }

    /** The rows for {@code apps} apps: as many as they need, as many as leave the name room. */
    private static float rows(Object sheet, Object profile, int apps) {
        int columns = Landscape.smartColumns(profile);
        int cell = XposedHelpers.getIntField(profile, "allAppsCellHeightPx");
        int height = XposedHelpers.getIntField(profile, "heightPx");
        int fit = cell > 0 ? (height - nameRoom(sheet, profile)) / cell : 1;
        int need = (apps + columns - 1) / columns;
        return Math.max(1, Math.min(need, fit));
    }

    /** The height the name needs over the list: status bar, its own line and padding. */
    private static int nameRoom(Object sheet, Object profile) {
        View title = (View) XposedHelpers.getObjectField(sheet, "titleContainer");
        TextView name = (TextView) XposedHelpers.getObjectField(sheet, "folderName");
        Rect insets = (Rect) XposedHelpers.callMethod(profile, "getInsets");
        float top = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, NAME_TOP_DP,
                name.getResources().getDisplayMetrics());
        return insets.top + Math.round(top) + title.getPaddingBottom()
                + Math.round(name.getTextSize() * 1.3f);
    }

    private static Object profileOf(Object gridOrList) {
        try {
            Object context = XposedHelpers.getObjectField(gridOrList, "mActivityContext");
            return XposedHelpers.callMethod(context, "getDeviceProfile");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> fieldType(Class<?> cls, String name) {
        try {
            Field f = cls.getDeclaredField(name);
            return f.getType();
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(cls.getSimpleName() + "." + name + " not found");
        }
    }
}
