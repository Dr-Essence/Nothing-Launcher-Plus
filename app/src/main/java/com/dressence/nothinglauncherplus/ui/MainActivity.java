package com.dressence.nothinglauncherplus.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.dressence.nothinglauncherplus.Config;

/** Module settings: drawer options and the list of custom folders. */
public class MainActivity extends Activity {

    /** Replaced with {@code true} by the module's own hook when LSPosed has it enabled. */
    public static boolean isModuleActive() {
        return false;
    }

    private SettingsStore store;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new SettingsStore(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.reload();
        render();
    }

    private void render() {
        Config config = store.config;
        Ui ui = Ui.page(this, "Custom folders", false);
        if (!isModuleActive() || !store.shared) {
            ui.note("Module not active. Enable it in LSPosed with Nothing Launcher in scope, then"
                    + " restart the launcher. Changes made now will not reach the launcher.");
        }

        ui.section("Drawer");
        ui.switchRow("Custom folders first", "Show your folders before Nothing's categories",
                config.customFirst, checked -> {
                    config.customFirst = checked;
                    store.save(this);
                });
        ui.switchRow("Hide automatic categories", "Apps outside your folders go to Others",
                config.hideBuiltin, checked -> {
                    config.hideBuiltin = checked;
                    store.save(this);
                });
        ui.switchRow("Apps without a folder as icons",
                "Replace the Others folder with plain app icons", config.looseApps, checked -> {
                    config.looseApps = checked;
                    store.save(this);
                });
        addOpacityRow(ui, config);

        ui.section("Folders · " + config.folders.size());
        if (config.folders.isEmpty()) {
            ui.note("Create a folder, pick its apps, and it appears in the Smart (Beta) drawer.");
        }
        for (Config.Folder folder : config.folders) {
            int n = folder.apps.size();
            ui.row(folder.name, n == 1 ? "1 app" : n + " apps", ui.coverPreview(folder, 40),
                    ui.chevron(), v -> FolderActivity.open(this, folder.id, false));
        }
        ui.pill("New folder", true, v -> Dialogs.promptText(this, "New folder", "", "Name",
                name -> {
                    Config.Folder folder = new Config.Folder(
                            Long.toString(System.currentTimeMillis(), 36), name);
                    config.folders.add(folder);
                    store.save(this);
                    FolderActivity.open(this, folder.id, true);
                }));

        ui.section("Launcher");
        ui.row("Restart Nothing Launcher", "Applies everything immediately · needs root", null,
                null, v -> restartLauncher());
    }

    private void addOpacityRow(Ui ui, Config config) {
        LinearLayout row = ui.row("Drawer background", opacityLabel(config.drawerOpacity), null,
                null, null);
        TextView summary = (TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(1);
        SeekBar slider = new SeekBar(this);
        slider.setMax(100);
        slider.setProgress(config.drawerOpacity);
        slider.setPadding(ui.dp(24), ui.dp(4), ui.dp(24), ui.dp(12));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                summary.setText(opacityLabel(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                config.drawerOpacity = seekBar.getProgress();
                store.save(MainActivity.this);
            }
        });
        ui.content.addView(slider);
    }

    private static String opacityLabel(int opacity) {
        return opacity >= 100 ? "Opaque (stock)"
                : opacity == 0 ? "Transparent" : opacity + "% opaque";
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
}
