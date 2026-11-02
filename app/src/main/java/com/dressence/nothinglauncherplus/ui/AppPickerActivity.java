package com.dressence.nothinglauncherplus.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.dressence.nothinglauncherplus.Config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Chooses a custom folder's apps, in the module's Nothing look (black page, serif title,
 * rounded search, accent dots): every launchable app with its icon, a search over the names,
 * and a dot that fills in the accent for the apps chosen. Apps in another folder say which;
 * choosing one moves it here. Saved into the settings as they are when Save is pressed (the
 * folder is looked up again then), so a folder just made keeps what is chosen.
 */
public class AppPickerActivity extends Activity {

    private static final String EXTRA_FOLDER = "folder";

    static void open(Context context, String folderId) {
        context.startActivity(new Intent(context, AppPickerActivity.class)
                .putExtra(EXTRA_FOLDER, folderId));
    }

    private final List<Apps.App> all = new ArrayList<>();
    private final List<Apps.App> shown = new ArrayList<>();
    private final Set<String> chosen = new LinkedHashSet<>();
    private final ExecutorService icons = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Drawable> iconCache = new LruCache<>(256);
    private SettingsStore store;
    private String folderId;
    private String query = "";
    private int accent;
    private BaseAdapter adapter;
    private TextView count;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new SettingsStore(this);
        folderId = getIntent().getStringExtra(EXTRA_FOLDER);
        Config.Folder folder = store.config.folderForId(folderId);
        if (folder == null) {
            finish();
            return;
        }
        TypedArray a = obtainStyledAttributes(new int[] {android.R.attr.colorAccent});
        accent = a.getColor(0, 0xFFD71921);
        a.recycle();
        all.addAll(Apps.all(this));
        for (Apps.App app : all) {
            if (store.config.folderForComponent(app.component) == folder) {
                chosen.add(app.component);
            }
        }
        getWindow().getDecorView().setBackgroundColor(Ui.BACKGROUND);
        setContentView(build(folder.name));
        filter();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        icons.shutdownNow();
    }

    private View build(String folderName) {
        int pad = Ui.dp(this, 20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BACKGROUND);
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 12), pad, 0);
        TextView back = text("←", 26, Ui.TEXT);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        TextView title = text(folderName, 28, Ui.TEXT);
        title.setTypeface(Typeface.SERIF);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar);

        count = text("", 14, accent);
        count.setPadding(Ui.dp(this, 24), Ui.dp(this, 12), pad, Ui.dp(this, 8));
        root.addView(count);

        EditText search = new EditText(this);
        search.setHint("Search apps");
        search.setHintTextColor(Ui.TEXT_SECONDARY);
        search.setTextColor(Ui.TEXT);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setBackground(rounded(Ui.SURFACE, Ui.dp(this, 22)));
        search.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 12));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int c, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                filter();
            }
        });
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.setMargins(pad, 0, pad, Ui.dp(this, 8));
        root.addView(search, searchLp);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, Ui.dp(this, 8));
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return shown.size();
            }

            @Override
            public Object getItem(int position) {
                return shown.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                Row row = convertView instanceof Row ? (Row) convertView
                        : new Row(AppPickerActivity.this);
                row.bind(shown.get(position));
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            String component = shown.get(position).component;
            if (!chosen.remove(component)) {
                chosen.add(component);
            }
            ((Row) view).bind(shown.get(position));
            updateCount();
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0,
                1f));

        TextView save = text("Save", 16, Color.BLACK);
        save.setGravity(Gravity.CENTER);
        save.setMinHeight(Ui.dp(this, 52));
        save.setBackground(rounded(accent, Ui.dp(this, 26)));
        save.setOnClickListener(v -> save());
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveLp.setMargins(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), Ui.dp(this, 16));
        root.addView(save, saveLp);
        return root;
    }

    /** The search: chosen apps first while not searching, then the rest by name. */
    private void filter() {
        shown.clear();
        if (query.isEmpty()) {
            for (Apps.App app : all) {
                if (chosen.contains(app.component)) {
                    shown.add(app);
                }
            }
            for (Apps.App app : all) {
                if (!chosen.contains(app.component)) {
                    shown.add(app);
                }
            }
        } else {
            for (Apps.App app : all) {
                if (app.label.toLowerCase(Locale.ROOT).contains(query)) {
                    shown.add(app);
                }
            }
        }
        adapter.notifyDataSetChanged();
        updateCount();
    }

    private void updateCount() {
        count.setText(chosen.size() == 1 ? "1 app chosen" : chosen.size() + " apps chosen");
    }

    /** Into the settings as they are now; apps kept keep their order, new ones go last. */
    private void save() {
        store.reload();
        Config config = store.config;
        Config.Folder folder = config.folderForId(folderId);
        if (folder != null) {
            folder.apps.retainAll(chosen);
            for (String component : chosen) {
                config.assign(component, folder);
            }
            store.save(this);
        }
        finish();
    }

    /** One app: icon, name, where it is now, and the choice dot. */
    private final class Row extends LinearLayout {

        private final ImageView icon;
        private final TextView name;
        private final TextView where;
        private final View dot;
        private String component;

        Row(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(Ui.dp(context, 64));
            setPadding(Ui.dp(context, 24), Ui.dp(context, 10), Ui.dp(context, 24),
                    Ui.dp(context, 10));
            icon = new ImageView(context);
            LayoutParams iconLp = new LayoutParams(Ui.dp(context, 40), Ui.dp(context, 40));
            iconLp.setMarginEnd(Ui.dp(context, 16));
            addView(icon, iconLp);
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            name = text("", 18, Ui.TEXT);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(name);
            where = text("", 13, Ui.TEXT_SECONDARY);
            texts.addView(where);
            addView(texts, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            dot = new View(context);
            LayoutParams dotLp = new LayoutParams(Ui.dp(context, 22), Ui.dp(context, 22));
            dotLp.setMarginStart(Ui.dp(context, 16));
            addView(dot, dotLp);
        }

        void bind(Apps.App app) {
            component = app.component;
            name.setText(app.label);
            boolean on = chosen.contains(app.component);
            Config.Folder owner = store.config.folderForComponent(app.component);
            boolean elsewhere = owner != null && !owner.id.equals(folderId);
            where.setText(elsewhere ? (on ? "Moves here from " : "In ") + owner.name : "");
            where.setVisibility(elsewhere ? VISIBLE : GONE);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            if (on) {
                circle.setColor(accent);
            } else {
                circle.setStroke(Ui.dp(getContext(), 2), 0xFF5A5A5C);
            }
            dot.setBackground(circle);
            setContentDescription(app.label + (on ? ", chosen" : ""));
            Drawable cached = iconCache.get(app.component);
            icon.setImageDrawable(cached);
            if (cached == null) {
                loadIcon(app, this);
            }
        }
    }

    /** Icons are read off the main thread; a row reused meanwhile keeps its new app's. */
    private void loadIcon(Apps.App app, Row row) {
        PackageManager pm = getPackageManager();
        icons.execute(() -> {
            Drawable drawable;
            try {
                drawable = app.icon(pm);
            } catch (Throwable t) {
                return;
            }
            main.post(() -> {
                iconCache.put(app.component, drawable);
                if (app.component.equals(row.component)) {
                    row.icon.setImageDrawable(drawable);
                }
            });
        });
    }

    private TextView text(String value, int sp, int colour) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(colour);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return view;
    }

    private static GradientDrawable rounded(int colour, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        shape.setCornerRadius(radius);
        return shape;
    }
}
