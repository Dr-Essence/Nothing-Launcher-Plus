package com.dressence.nothinglauncherplus.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.dressence.nothinglauncherplus.Config;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    /** Replaced with {@code true} by the module's own hook when LSPosed has it enabled. */
    public static boolean isModuleActive() {
        return false;
    }

    private static final class App {
        final String component;
        final String label;

        App(String component, String label) {
            this.component = component;
            this.label = label;
        }
    }

    private SharedPreferences prefs;
    private boolean prefsShared;
    private Config config;
    private ArrayAdapter<String> folderAdapter;
    private List<App> apps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = openPrefs();
        config = Config.read(prefs);
        setContentView(buildLayout());
    }

    @SuppressWarnings("deprecation")
    private SharedPreferences openPrefs() {
        try {
            // LSPosed (xposedsharedprefs) makes this readable by the hooked launcher.
            SharedPreferences shared = getSharedPreferences(Config.PREFS_NAME, MODE_WORLD_READABLE);
            prefsShared = true;
            return shared;
        } catch (SecurityException e) {
            prefsShared = false;
            return getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE);
        }
    }

    private View buildLayout() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setFitsSystemWindows(true);

        TextView title = new TextView(this);
        title.setText("Custom drawer folders");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        root.addView(title);

        TextView status = new TextView(this);
        status.setPadding(0, dp(8), 0, dp(8));
        if (isModuleActive() && prefsShared) {
            status.setText("Module active. Set Drawer layout to Smart (Beta) in Nothing Launcher"
                    + " settings to see your folders.");
        } else {
            status.setText("Module not active: enable it in LSPosed with Nothing Launcher in"
                    + " scope, then force stop the launcher. Changes made now will not reach"
                    + " the launcher.");
        }
        root.addView(status);

        Switch customFirst = new Switch(this);
        customFirst.setText("Show custom folders first");
        customFirst.setChecked(config.customFirst);
        customFirst.setOnCheckedChangeListener((v, checked) -> {
            config.customFirst = checked;
            save();
        });
        root.addView(customFirst, matchWrap());

        Switch hideBuiltin = new Switch(this);
        hideBuiltin.setText("Hide automatic categories (other apps go to \"Others\")");
        hideBuiltin.setChecked(config.hideBuiltin);
        hideBuiltin.setOnCheckedChangeListener((v, checked) -> {
            config.hideBuiltin = checked;
            save();
        });
        root.addView(hideBuiltin, matchWrap());

        Button add = new Button(this);
        add.setText("New folder");
        add.setOnClickListener(v -> promptName("New folder", "", name -> {
            Config.Folder folder = new Config.Folder(
                    Long.toString(System.currentTimeMillis(), 36), name);
            config.folders.add(folder);
            save();
            editApps(folder);
        }));
        root.addView(add, matchWrap());

        TextView hint = new TextView(this);
        hint.setText("Tap a folder to choose its apps, long-press for more options.");
        hint.setPadding(0, dp(8), 0, dp(8));
        root.addView(hint);

        ListView list = new ListView(this);
        folderAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                new ArrayList<>());
        list.setAdapter(folderAdapter);
        list.setOnItemClickListener((parent, view, position, id) ->
                editApps(config.folders.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            folderOptions(config.folders.get(position));
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button restart = new Button(this);
        restart.setText("Restart Nothing Launcher (root)");
        restart.setOnClickListener(v -> restartLauncher());
        root.addView(restart, matchWrap());

        refreshFolders();
        return root;
    }

    private void refreshFolders() {
        folderAdapter.clear();
        for (Config.Folder folder : config.folders) {
            int n = folder.apps.size();
            folderAdapter.add(folder.name + "  (" + n + (n == 1 ? " app)" : " apps)"));
        }
    }

    private void save() {
        SharedPreferences.Editor editor = prefs.edit();
        config.write(editor);
        editor.commit();
        sendBroadcast(new Intent(Config.ACTION_RELOAD).setPackage(Config.LAUNCHER_PKG));
        refreshFolders();
    }

    private void folderOptions(Config.Folder folder) {
        String[] options = {"Rename", "Move up", "Move down", "Delete"};
        new AlertDialog.Builder(this)
                .setTitle(folder.name)
                .setItems(options, (d, which) -> {
                    int index = config.folders.indexOf(folder);
                    switch (which) {
                        case 0:
                            promptName("Rename folder", folder.name, name -> {
                                folder.name = name;
                                save();
                            });
                            return;
                        case 1:
                            if (index > 0) {
                                Collections.swap(config.folders, index, index - 1);
                            }
                            break;
                        case 2:
                            if (index < config.folders.size() - 1) {
                                Collections.swap(config.folders, index, index + 1);
                            }
                            break;
                        case 3:
                            config.folders.remove(folder);
                            break;
                    }
                    save();
                })
                .show();
    }

    private void editApps(Config.Folder folder) {
        List<App> all = loadApps();
        String[] labels = new String[all.size()];
        boolean[] checked = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            App app = all.get(i);
            Config.Folder owner = config.folderForComponent(app.component);
            checked[i] = owner == folder;
            labels[i] = owner == null || owner == folder
                    ? app.label : app.label + "  (in " + owner.name + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle(folder.name)
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) ->
                        checked[which] = isChecked)
                .setPositiveButton("Save", (d, w) -> {
                    folder.apps.clear();
                    for (int i = 0; i < all.size(); i++) {
                        if (checked[i]) {
                            config.assign(all.get(i).component, folder);
                        }
                    }
                    save();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private List<App> loadApps() {
        if (apps != null) {
            return apps;
        }
        PackageManager pm = getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<App> result = new ArrayList<>();
        for (ResolveInfo info : pm.queryIntentActivities(launcherIntent, 0)) {
            ComponentName cn = new ComponentName(
                    info.activityInfo.packageName, info.activityInfo.name);
            result.add(new App(cn.flattenToString(), String.valueOf(info.loadLabel(pm))));
        }
        Collator collator = Collator.getInstance();
        result.sort((a, b) -> collator.compare(a.label, b.label));
        apps = result;
        return result;
    }

    private interface NameCallback {
        void onName(String name);
    }

    private void promptName(String title, String initial, NameCallback callback) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setText(initial);
        input.setSelection(initial.length());
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("OK", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        callback.onName(name);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void restartLauncher() {
        new Thread(() -> {
            boolean ok;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{
                        "su", "-c", "am force-stop " + Config.LAUNCHER_PKG});
                ok = p.waitFor() == 0;
            } catch (Exception e) {
                ok = false;
            }
            boolean success = ok;
            runOnUiThread(() -> Toast.makeText(this, success ? "Launcher restarted"
                    : "Root not available; force stop Nothing Launcher from App info",
                    Toast.LENGTH_LONG).show());
        }).start();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
