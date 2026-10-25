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
import com.dressence.nothinglauncherplus.anim.Motion;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Edits one custom folder: name, cover, apps and their order. */
public class FolderActivity extends Activity {

    private static final String EXTRA_PICK_APPS = "pick_apps";
    private static final int REQUEST_IMAGE = 1;
    private static final int REQUEST_EMOJI = 2;

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
        ui.row("Cover", coverSummary(folder), ui.coverPreview(folder, 56, true), null, null);
        if (!folder.coverText.isEmpty()) {
            Motion motion = Motion.parse(folder.coverMotion);
            boolean automatic = motion == EmojiCatalog.get(this).motionFor(folder.coverText);
            ui.row("Animation", motion.title + (automatic ? " · automatic" : ""), null,
                    ui.chevron(), v -> chooseMotion(folder));
        }
        ui.pill("Choose emoji", false, v -> startActivityForResult(EmojiPickerActivity.intent(
                this, folder.coverText, folder.id.hashCode()), REQUEST_EMOJI));
        ui.pill("Type emoji or text", false, v -> Dialogs.promptText(this, "Cover emoji",
                folder.coverText, "Any emoji, including skin tones", text -> {
                    useText(folder, text);
                    saveAndRender();
                }));
        ui.pill("Choose image", false, v -> startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("image/*"), REQUEST_IMAGE));
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

    /** An emoji cover starts with the motion that suits it; the user can change it later. */
    private void useText(Config.Folder folder, String text) {
        Covers.setText(this, folder, text, EmojiCatalog.get(this).motionFor(text).name());
    }

    /** Automatic first, then every motion by name, then no animation. */
    private void chooseMotion(Config.Folder folder) {
        Motion automatic = EmojiCatalog.get(this).motionFor(folder.coverText);
        List<Motion> options = new ArrayList<>();
        options.add(automatic);
        for (Motion m : Motion.values()) {
            if (m != Motion.NONE && m != automatic) {
                options.add(m);
            }
        }
        options.subList(1, options.size()).sort((a, b) -> a.title.compareTo(b.title));
        options.add(Motion.NONE);
        String[] labels = new String[options.size()];
        int checked = 0;
        for (int i = 0; i < options.size(); i++) {
            Motion m = options.get(i);
            labels[i] = i == 0 ? m.title + " (automatic)"
                    : m == Motion.NONE ? "No animation" : m.title;
            if (m.name().equals(folder.coverMotion)) {
                checked = i;
            }
        }
        Dialogs.builder(this)
                .setTitle("Animation")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    folder.coverMotion = options.get(which).name();
                    d.dismiss();
                    saveAndRender();
                })
                .setNegativeButton("Cancel", null)
                .show();
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
        if (requestCode == REQUEST_EMOJI && resultCode == RESULT_OK && data != null) {
            String emoji = data.getStringExtra(EmojiPickerActivity.EXTRA_EMOJI);
            Config.Folder folder = store.config.folderForId(folderId);
            if (emoji != null && folder != null) {
                useText(folder, emoji);
                saveAndRender();
            }
            return;
        }
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
