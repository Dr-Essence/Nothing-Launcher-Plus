package com.dressence.nothinglauncherplus.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Display;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.UpdateReceiver;
import com.dressence.nothinglauncherplus.anim.FrameRates;
import com.dressence.nothinglauncherplus.sound.SoundEngine;
import com.dressence.nothinglauncherplus.sound.Style;

import java.util.ArrayList;
import java.util.List;

/** Module settings: drawer options and the list of custom folders. */
public class MainActivity extends Activity {

    /** Replaced with {@code true} by the module's own hook when LSPosed has it enabled. */
    public static boolean isModuleActive() {
        return false;
    }

    private static final int REQUEST_EXPORT = 1;
    private static final int REQUEST_IMPORT = 2;

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
        assignMissingMotions();
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
        ui.switchRow("Animate emoji covers", frameRateSummary(), config.animateCovers, checked -> {
            config.animateCovers = checked;
            store.save(this);
            render();
        });
        ui.switchRow("Dot-matrix emoji", "Covers drawn as a coloured Nothing dot matrix",
                config.dotMatrix, checked -> {
                    config.dotMatrix = checked;
                    store.save(this);
                    render();
                });
        ui.switchRow("Dot-matrix app icons",
                "Every app icon redrawn as glowing Nothing dots, colours kept",
                config.dotIcons, checked -> {
                    config.dotIcons = checked;
                    store.save(this);
                });
        ui.switchRow("Ndot status bar",
                "Clock and battery in glowing green Ndot · restart System UI to apply",
                config.ndotStatusBar, checked -> {
                    config.ndotStatusBar = checked;
                    store.save(this);
                });
        ui.switchRow("Date in status bar", "The date before the clock · restart System UI",
                config.statusBarDate, checked -> {
                    config.statusBarDate = checked;
                    store.save(this);
                });
        ui.row("Animation test", "Play any emoji's animation on a big dot screen", null,
                ui.chevron(), v -> startActivity(EmojiPickerActivity.testIntent(this)));

        addSoundSection(ui, config);

        ui.section("Landscape & controllers");
        ui.switchRow("Home screen rotation", "Home and app drawer follow auto-rotate (beta)",
                config.allowRotation, checked -> {
                    config.allowRotation = checked;
                    store.save(this);
                });
        ui.switchRow("Game controller navigation",
                "Y app drawer · L1/R1 pages · L2/R2 first/last · X menu · Start options",
                config.controllerNav, checked -> {
                    config.controllerNav = checked;
                    store.save(this);
                });

        ui.section("Folders · " + config.folders.size());
        if (config.folders.isEmpty()) {
            ui.note("Create a folder, pick its apps, and it appears in the Smart (Beta) drawer.");
        }
        if (config.folders.size() > 1) {
            ui.note("Drag ≡ to change the order in the drawer: folders fill its rows two at a"
                    + " time, in this order.");
        }
        List<String> ids = new ArrayList<>();
        for (Config.Folder folder : config.folders) {
            ids.add(folder.id);
        }
        ui.content.addView(new ReorderList(ui, ids, 72, id -> {
            Config.Folder folder = config.folderForId(id);
            int n = folder.apps.size();
            return ui.detachedRow(folder.name, n == 1 ? "1 app" : n + " apps",
                    ui.coverPreview(folder, 40, config.animateCovers, config.dotMatrix),
                    null, v -> FolderActivity.open(this, folder.id, false));
        }, order -> {
            List<Config.Folder> sorted = new ArrayList<>();
            for (String id : order) {
                sorted.add(config.folderForId(id));
            }
            config.folders.clear();
            config.folders.addAll(sorted);
            store.save(this);
        }));
        ui.pill("New folder", true, v -> Dialogs.promptText(this, "New folder", "", "Name",
                name -> {
                    Config.Folder folder = new Config.Folder(
                            Long.toString(System.currentTimeMillis(), 36), name);
                    config.folders.add(folder);
                    store.save(this);
                    FolderActivity.open(this, folder.id, true);
                }));

        ui.section("Backup");
        ui.row("Export", "Save folders, options and covers to a file", null, null,
                v -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "nothing-launcher-plus-backup.json"),
                        REQUEST_EXPORT));
        ui.row("Import", "Replace the current setup with a backup file", null, null,
                v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*"), REQUEST_IMPORT));

        ui.section("Launcher");
        ui.row("Restart Nothing Launcher", "Applies everything immediately · needs root", null,
                null, v -> restartLauncher());
    }

    private void addSoundSection(Ui ui, Config config) {
        ui.section("Sound");
        ui.switchRow("Interface sounds", "Console-style chimes for the drawer, folders, apps,"
                + " pages and menus", config.uiSounds, checked -> {
                    config.uiSounds = checked;
                    store.save(this);
                });
        ui.switchRow("Background music", "Generated live and tuned to 432 Hz. Plays on the home"
                + " screen only and goes quiet while other audio or a call plays", config.music,
                checked -> {
                    config.music = checked;
                    store.save(this);
                });
        Style style = Style.parse(config.musicStyle);
        ui.row("Music style", style.title + " · " + style.summary, null, ui.chevron(),
                v -> chooseStyle(config));
        addSliderRow(ui, "Music volume", config.musicVolume, value -> value + "%", value -> {
            config.musicVolume = value;
            store.save(this);
            if (preview().isMusicPlaying()) {
                preview().playMusic(Style.parse(config.musicStyle), value / 100f);
            }
        });
        boolean previewing = preview().isMusicPlaying();
        ui.row(previewing ? "Stop preview" : "Preview music",
                previewing ? "Playing " + style.title : "Listen to the selected style here",
                null, null, v -> {
                    if (preview().isMusicPlaying()) {
                        preview().stopMusic();
                    } else {
                        preview().playMusic(Style.parse(config.musicStyle),
                                config.musicVolume / 100f);
                    }
                    render();
                });
    }

    private void chooseStyle(Config config) {
        Style[] styles = Style.values();
        String[] names = new String[styles.length];
        int checked = 0;
        for (int i = 0; i < styles.length; i++) {
            names[i] = styles[i].title + "\n" + styles[i].summary;
            if (styles[i].name().equals(config.musicStyle)) {
                checked = i;
            }
        }
        Dialogs.builder(this)
                .setTitle("Music style")
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    config.musicStyle = styles[which].name();
                    store.save(this);
                    if (preview().isMusicPlaying()) {
                        preview().playMusic(styles[which], config.musicVolume / 100f);
                    }
                    dialog.dismiss();
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private SoundEngine preview() {
        return SoundEngine.get(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        preview().stopMusic(); // Previews never outlive the settings screen.
    }

    private interface IntLabel {
        String label(int value);
    }

    private interface IntChange {
        void changed(int value);
    }

    /** A titled 0..100 slider; {@code onDone} runs when the finger lifts. */
    private void addSliderRow(Ui ui, String title, int value, IntLabel label, IntChange onDone) {
        LinearLayout row = ui.row(title, label.label(value), null, null, null);
        TextView summary = (TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(1);
        SeekBar slider = new SeekBar(this);
        slider.setMax(100);
        slider.setProgress(value);
        slider.setPadding(ui.dp(24), ui.dp(4), ui.dp(24), ui.dp(12));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                summary.setText(label.label(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                onDone.changed(seekBar.getProgress());
            }
        });
        ui.content.addView(slider);
    }

    private void addOpacityRow(Ui ui, Config config) {
        addSliderRow(ui, "Drawer background", config.drawerOpacity, MainActivity::opacityLabel,
                value -> {
                    config.drawerOpacity = value;
                    store.save(this);
                });
    }

    /** Reports what the panel offers, so it is clear what the animations will run at. */
    private String frameRateSummary() {
        Display display = getDisplay();
        int target = Math.round(FrameRates.target(display));
        int now = Math.round(FrameRates.current(display));
        return "Up to " + target + " fps on this " + target + " Hz panel"
                + (now != target ? " (idling at " + now + " Hz)" : "");
    }

    /** Emoji covers set before motions or effects existed get their automatic ones once. */
    private void assignMissingMotions() {
        boolean changed = false;
        for (Config.Folder folder : store.config.folders) {
            if (!folder.coverText.isEmpty() && folder.coverMotion.isEmpty()) {
                folder.coverMotion = EmojiCatalog.get(this).motionFor(folder.coverText).name();
                changed = true;
            }
            // Effects follow the emoji table (they aren't picked by hand), so a newer table
            // updates them.
            String effect = folder.coverText.isEmpty() ? ""
                    : EmojiCatalog.get(this).effectFor(folder.coverText);
            if (!effect.equals(folder.coverEffect)) {
                folder.coverEffect = effect;
                changed = true;
            }
        }
        if (changed) {
            store.save(this);
        }
    }

    private static String opacityLabel(int opacity) {
        return opacity >= 100 ? "Opaque (stock)"
                : opacity == 0 ? "Transparent" : opacity + "% opaque";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = data != null ? data.getData() : null;
        if (resultCode != RESULT_OK || uri == null) {
            return;
        }
        if (requestCode == REQUEST_EXPORT) {
            Config snapshot = store.config;
            runInBackground(() -> Backup.export(this, snapshot, uri), "Backup saved", null);
        } else if (requestCode == REQUEST_IMPORT) {
            Dialogs.builder(this)
                    .setTitle("Import backup?")
                    .setMessage("Your current folders and options will be replaced.")
                    .setPositiveButton("Import", (d, w) -> {
                        Config[] imported = new Config[1];
                        runInBackground(() -> imported[0] = Backup.restore(this, uri),
                                "Backup imported", () -> {
                                    store.config = imported[0];
                                    store.save(this);
                                    render();
                                });
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    private interface Work {
        void run() throws Exception;
    }

    /** Runs {@code work} off the main thread, then {@code onSuccess} on it, and reports. */
    private void runInBackground(Work work, String successMessage, Runnable onSuccess) {
        new Thread(() -> {
            String error = null;
            try {
                work.run();
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            String failure = error;
            runOnUiThread(() -> {
                if (failure == null && onSuccess != null) {
                    onSuccess.run();
                }
                Toast.makeText(this, failure == null ? successMessage : "Failed: " + failure,
                        Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private void restartLauncher() {
        new Thread(() -> {
            boolean success = UpdateReceiver.restartLauncher();
            runOnUiThread(() -> Toast.makeText(this, success ? "Launcher restarted"
                    : "Root not available; force stop Nothing Launcher from App info",
                    Toast.LENGTH_LONG).show());
        }).start();
    }

}
