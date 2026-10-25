package com.dressence.nothinglauncherplus.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import com.dressence.nothinglauncherplus.Config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Edits one custom folder: name, cover, apps and their order. */
public class FolderActivity extends Activity {

    private static final String EXTRA_PICK_APPS = "pick_apps";
    private static final int REQUEST_IMAGE = 1;

    static void open(Context context, String folderId, boolean pickApps) {
        context.startActivity(new Intent(context, FolderActivity.class)
                .putExtra(Config.EXTRA_FOLDER_ID, folderId)
                .putExtra(EXTRA_PICK_APPS, pickApps));
    }

    private SettingsStore store;
    private String folderId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new SettingsStore(this);
        folderId = getIntent().getStringExtra(Config.EXTRA_FOLDER_ID);
        if (savedInstanceState == null && getIntent().getBooleanExtra(EXTRA_PICK_APPS, false)) {
            Config.Folder folder = store.config.folderForId(folderId);
            if (folder != null) {
                pickApps(folder);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.reload();
        render();
    }

    private void render() {
        Config.Folder folder = store.config.folderForId(folderId);
        if (folder == null) {
            finish();
            return;
        }
        Ui ui = Ui.page(this, folder.name, true);

        ui.section("Folder");
        ui.row("Name", folder.name, null, ui.chevron(), v ->
                Dialogs.promptText(this, "Rename folder", folder.name, "Name", name -> {
                    folder.name = name;
                    saveAndRender();
                }));

        ui.section("Cover");
        ui.row("Cover", coverSummary(folder), ui.coverPreview(folder, 40), null, null);
        ui.pill("Choose image", false, v -> startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("image/*"), REQUEST_IMAGE));
        ui.pill("Use emoji", false, v -> Dialogs.promptText(this, "Cover emoji",
                folder.coverText, "e.g. 🎮", text -> {
                    Covers.setText(this, folder, text);
                    saveAndRender();
                }));
        if (folder.hasCover()) {
            ui.pill("Remove cover", false, v -> {
                Covers.clear(this, folder);
                saveAndRender();
            });
        }

        int n = folder.apps.size();
        ui.section("Apps · " + n);
        if (n > 1) {
            ui.note("Drag ≡ to change the order inside the folder.");
        }
        ui.content.addView(new ReorderList(ui, new ArrayList<>(folder.apps), order -> {
            folder.apps.clear();
            folder.apps.addAll(order);
            store.save(this);
        }));
        ui.pill("Add or remove apps", true, v -> pickApps(folder));

        ui.divider();
        TextView delete = ui.pill("Delete folder", false, v -> Dialogs.builder(this)
                .setTitle("Delete " + folder.name + "?")
                .setMessage("Its apps go back to their automatic categories.")
                .setPositiveButton("Delete", (d, w) -> {
                    Covers.clear(this, folder);
                    store.config.folders.remove(folder);
                    store.save(this);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show());
        delete.setTextColor(Color.rgb(0xFF, 0x5A, 0x4F));
    }

    private static String coverSummary(Config.Folder folder) {
        if (folder.coverImageVersion != 0) {
            return "Image";
        }
        if (!folder.coverText.isEmpty()) {
            return "Emoji " + folder.coverText;
        }
        return "None · the tile shows app previews";
    }

    /** Multi-select of all apps; kept apps keep their order, new ones are appended. */
    private void pickApps(Config.Folder folder) {
        List<Apps.App> all = Apps.all(this);
        Config config = store.config;
        String[] labels = new String[all.size()];
        boolean[] checked = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            Apps.App app = all.get(i);
            Config.Folder owner = config.folderForComponent(app.component);
            checked[i] = owner == folder;
            labels[i] = owner == null || owner == folder
                    ? app.label : app.label + "  (in " + owner.name + ")";
        }
        Dialogs.builder(this)
                .setTitle(folder.name)
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) ->
                        checked[which] = isChecked)
                .setPositiveButton("Save", (d, w) -> {
                    Set<String> selected = new LinkedHashSet<>();
                    for (int i = 0; i < all.size(); i++) {
                        if (checked[i]) {
                            selected.add(all.get(i).component);
                        }
                    }
                    folder.apps.retainAll(selected);
                    for (String component : selected) {
                        config.assign(component, folder);
                    }
                    saveAndRender();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri image = data != null ? data.getData() : null;
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK || image == null) {
            return;
        }
        new Thread(() -> {
            String error = null;
            try {
                Covers.saveImage(this, folderId, image);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            String message = error;
            runOnUiThread(() -> {
                Config.Folder folder = store.config.folderForId(folderId);
                if (message != null) {
                    Toast.makeText(this, "Could not use that image: " + message,
                            Toast.LENGTH_LONG).show();
                } else if (folder != null) {
                    Covers.useImage(folder);
                    store.save(this);
                }
                render();
            });
        }).start();
    }

    private void saveAndRender() {
        store.save(this);
        render();
    }
}
