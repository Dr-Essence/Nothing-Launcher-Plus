package com.dressence.nothinglauncherplus.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Launchable apps, as the launcher shows them. */
final class Apps {

    static final class App {
        final String component;
        final String label;
        final ResolveInfo info;

        App(String component, String label, ResolveInfo info) {
            this.component = component;
            this.label = label;
            this.info = info;
        }

        Drawable icon(PackageManager pm) {
            return info.loadIcon(pm);
        }
    }

    private static List<App> all;
    private static Map<String, App> byComponent;

    private Apps() {
    }

    /** Sorted by label; cached for the life of the process. */
    static synchronized List<App> all(Context context) {
        if (all == null) {
            PackageManager pm = context.getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<App> result = new ArrayList<>();
            for (ResolveInfo info : pm.queryIntentActivities(intent, 0)) {
                ComponentName cn = new ComponentName(
                        info.activityInfo.packageName, info.activityInfo.name);
                result.add(new App(cn.flattenToString(), String.valueOf(info.loadLabel(pm)), info));
            }
            Collator collator = Collator.getInstance();
            result.sort((a, b) -> collator.compare(a.label, b.label));
            all = Collections.unmodifiableList(result);
            byComponent = new HashMap<>();
            for (App app : result) {
                byComponent.put(app.component, app);
            }
        }
        return all;
    }

    /** Null when the app is no longer installed. */
    static App find(Context context, String component) {
        all(context);
        return byComponent.get(component);
    }
}
