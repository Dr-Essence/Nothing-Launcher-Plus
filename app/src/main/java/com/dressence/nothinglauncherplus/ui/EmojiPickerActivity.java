package com.dressence.nothinglauncherplus.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.dressence.nothinglauncherplus.anim.AnimatedEmojiView;
import com.dressence.nothinglauncherplus.anim.DotEffect;
import com.dressence.nothinglauncherplus.anim.Motion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Browse or search every emoji the system can draw; the selected one plays its animation in the
 * preview. Returns the emoji in {@link #EXTRA_EMOJI}.
 */
public class EmojiPickerActivity extends Activity {

    static final String EXTRA_EMOJI = "emoji";
    private static final String EXTRA_SEED = "seed";

    static Intent intent(Context context, String current, long seed) {
        return new Intent(context, EmojiPickerActivity.class)
                .putExtra(EXTRA_EMOJI, current)
                .putExtra(EXTRA_SEED, seed);
    }

    private EmojiCatalog catalog;
    private final List<EmojiCatalog.Entry> shown = new ArrayList<>();
    private final List<TextView> tabs = new ArrayList<>();
    private BaseAdapter adapter;
    private AnimatedEmojiView preview;
    private TextView previewName;
    private TextView previewMotion;
    private EmojiCatalog.Entry selected;
    private int group;
    private String query = "";
    private long seed;
    private boolean dotMatrix;
    private int accent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        catalog = EmojiCatalog.get(this);
        seed = getIntent().getLongExtra(EXTRA_SEED, 0);
        dotMatrix = new SettingsStore(this).config.dotMatrix;
        TypedArray a = obtainStyledAttributes(new int[]{android.R.attr.colorAccent});
        accent = a.getColor(0, 0xFFD71921);
        a.recycle();
        getWindow().getDecorView().setBackgroundColor(Ui.BACKGROUND);
        setContentView(buildLayout());

        EmojiCatalog.Entry current = catalog.find(getIntent().getStringExtra(EXTRA_EMOJI));
        if (current != null) {
            group = current.group;
        }
        select(current != null ? current : firstOf(group));
        showGroup(group);
    }

    private View buildLayout() {
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
        TextView title = text("Choose emoji", 28, Ui.TEXT);
        title.setTypeface(Typeface.SERIF);
        bar.addView(title);
        root.addView(bar);

        // Preview: the selected emoji playing its animation.
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(rounded(Ui.SURFACE, Ui.dp(this, 24)));
        preview = new AnimatedEmojiView(this);
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 52);
        preview.setDotMatrix(dotMatrix);
        preview.setOnClickListener(v -> preview.react());
        card.addView(preview, new LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 96)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(this, 16), 0, 0, 0);
        previewName = text("", 19, Ui.TEXT);
        previewMotion = text("", 14, accent);
        texts.addView(previewName);
        texts.addView(previewMotion);
        card.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(card, margins(pad, Ui.dp(this, 16), pad, Ui.dp(this, 12)));

        EditText search = new EditText(this);
        search.setHint("Search emoji");
        search.setHintTextColor(Ui.TEXT_SECONDARY);
        search.setTextColor(Ui.TEXT);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setBackground(rounded(Ui.SURFACE, Ui.dp(this, 22)));
        search.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 12));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                showGroup(group);
            }
        });
        root.addView(search, margins(pad, 0, pad, Ui.dp(this, 8)));

        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        for (int i = 0; i < EmojiCatalog.GROUP_ICONS.length; i++) {
            int index = i;
            TextView tab = text(EmojiCatalog.GROUP_ICONS[i], 24, Ui.TEXT);
            tab.setGravity(Gravity.CENTER);
            tab.setContentDescription(EmojiCatalog.GROUP_TITLES[i]);
            tab.setOnClickListener(v -> {
                group = index;
                showGroup(index);
            });
            tabs.add(tab);
            tabRow.addView(tab, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        }
        tabScroll.addView(tabRow);
        root.addView(tabScroll);

        GridView grid = new GridView(this);
        grid.setColumnWidth(Ui.dp(this, 52));
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setStretchMode(GridView.STRETCH_SPACING_UNIFORM);
        grid.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        grid.setClipToPadding(false);
        grid.setSelector(rounded(0x22FFFFFF, Ui.dp(this, 14)));
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
                EmojiCatalog.Entry entry = shown.get(position);
                TextView cell = (TextView) convertView;
                if (cell == null) {
                    // As dots, the cells show exactly what the cover will look like.
                    if (dotMatrix) {
                        AnimatedEmojiView dots = new AnimatedEmojiView(parent.getContext());
                        dots.setDotMatrix(true);
                        cell = dots;
                    } else {
                        cell = text("", 28, Ui.TEXT);
                        cell.setGravity(Gravity.CENTER);
                    }
                    cell.setLayoutParams(new AbsListView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(parent.getContext(), 56)));
                }
                if (cell instanceof AnimatedEmojiView) {
                    ((AnimatedEmojiView) cell).setEmoji(entry.emoji, Motion.NONE, 0);
                } else {
                    cell.setText(entry.emoji);
                }
                cell.setContentDescription(entry.name);
                return cell;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> select(shown.get(position)));
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView use = text("Use emoji", 16, Color.BLACK);
        use.setGravity(Gravity.CENTER);
        use.setMinHeight(Ui.dp(this, 52));
        use.setBackground(rounded(accent, Ui.dp(this, 26)));
        use.setOnClickListener(v -> {
            if (selected != null) {
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_EMOJI, selected.emoji));
            }
            finish();
        });
        root.addView(use, margins(pad, Ui.dp(this, 8), pad, Ui.dp(this, 16)));
        return root;
    }

    private void showGroup(int index) {
        shown.clear();
        for (EmojiCatalog.Entry entry : catalog.entries) {
            boolean match = query.isEmpty()
                    ? entry.group == index
                    : entry.name.toLowerCase(Locale.ROOT).contains(query);
            if (match) {
                shown.add(entry);
            }
        }
        for (int i = 0; i < tabs.size(); i++) {
            boolean active = query.isEmpty() && i == index;
            tabs.get(i).setBackground(active ? rounded(Ui.SURFACE, Ui.dp(this, 16)) : null);
        }
        adapter.notifyDataSetChanged();
    }

    private void select(EmojiCatalog.Entry entry) {
        if (entry == null) {
            return;
        }
        selected = entry;
        preview.setEmoji(entry.emoji, entry.motion, entry.effect,
                DotEffect.anchor(entry.effectSpec), seed);
        previewName.setText(capitalize(entry.name));
        previewMotion.setText("Animation · " + entry.motion.title
                + (dotMatrix ? " · " + entry.effect.title() : ""));
    }

    private EmojiCatalog.Entry firstOf(int index) {
        for (EmojiCatalog.Entry entry : catalog.entries) {
            if (entry.group == index) {
                return entry;
            }
        }
        return catalog.entries.isEmpty() ? null : catalog.entries.get(0);
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return view;
    }

    private static GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private static LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(l, t, r, b);
        return lp;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
