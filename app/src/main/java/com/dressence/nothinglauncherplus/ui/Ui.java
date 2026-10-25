package com.dressence.nothinglauncherplus.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.CoverProvider;
import com.dressence.nothinglauncherplus.anim.AnimatedEmojiView;
import com.dressence.nothinglauncherplus.anim.DotEffect;
import com.dressence.nothinglauncherplus.anim.Motion;

/**
 * Builds screens in the style of Nothing Launcher's own settings: black page, large serif title,
 * accent-coloured section labels, two-line rows and pill buttons. The system font is used, so a
 * device-wide Nothing font (Ndot, NType) carries over.
 */
final class Ui {

    static final int BACKGROUND = Color.BLACK;
    static final int SURFACE = 0xFF1B1B1D;
    static final int TEXT = Color.WHITE;
    static final int TEXT_SECONDARY = 0xFF9E9E9E;
    static final int DIVIDER = 0xFF2A2A2C;

    private final Activity activity;
    final int accent;
    final LinearLayout content;

    private Ui(Activity activity, LinearLayout content) {
        this.activity = activity;
        this.content = content;
        TypedArray a = activity.obtainStyledAttributes(new int[]{android.R.attr.colorAccent});
        accent = a.getColor(0, 0xFFD71921);
        a.recycle();
    }

    /** Sets a scrolling page with a back arrow and a large title as the activity content. */
    static Ui page(Activity activity, String title, boolean showBack) {
        // Edge-to-edge (target SDK 36): the black window shows behind the system bars.
        activity.getWindow().getDecorView().setBackgroundColor(BACKGROUND);
        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(BACKGROUND);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(activity, 32));
        scroll.addView(content);
        activity.setContentView(scroll);

        Ui ui = new Ui(activity, content);
        if (showBack) {
            TextView back = new TextView(activity);
            back.setText("←");
            back.setTextColor(TEXT);
            back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            back.setGravity(Gravity.CENTER);
            back.setBackground(ui.ripple(Color.TRANSPARENT, dp(activity, 24)));
            back.setContentDescription("Back");
            back.setOnClickListener(v -> activity.finish());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    dp(activity, 48), dp(activity, 48));
            lp.setMargins(dp(activity, 12), dp(activity, 16), 0, 0);
            content.addView(back, lp);
        }
        TextView heading = new TextView(activity);
        heading.setText(title);
        heading.setTextColor(TEXT);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        heading.setTypeface(Typeface.SERIF);
        heading.setPadding(dp(activity, 24), dp(activity, showBack ? 40 : 88),
                dp(activity, 24), dp(activity, 24));
        content.addView(heading);
        return ui;
    }

    Context context() {
        return activity;
    }

    int dp(int value) {
        return dp(activity, value);
    }

    static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    /** Small accent-coloured section label, like "Drawer layout". */
    TextView section(String label) {
        TextView view = new TextView(activity);
        view.setText(label);
        view.setTextColor(accent);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setPadding(dp(24), dp(28), dp(24), dp(8));
        content.addView(view);
        return view;
    }

    TextView note(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(TEXT_SECONDARY);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setPadding(dp(24), dp(4), dp(24), dp(12));
        content.addView(view);
        return view;
    }

    /** A two-line row; {@code trailing} (switch, chevron, icon) sits on the right. */
    LinearLayout row(String title, String summary, View leading, View trailing,
            View.OnClickListener onClick) {
        LinearLayout row = detachedRow(title, summary, leading, trailing, onClick);
        content.addView(row);
        return row;
    }

    /** {@link #row} without adding it to the page (e.g. for a {@link ReorderList}). */
    LinearLayout detachedRow(String title, String summary, View leading, View trailing,
            View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setPadding(dp(24), dp(14), dp(24), dp(14));
        if (onClick != null) {
            row.setBackground(ripple(Color.TRANSPARENT, 0));
            row.setOnClickListener(onClick);
        }
        if (leading != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
            lp.setMarginEnd(dp(16));
            row.addView(leading, lp);
        }
        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(TEXT);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        texts.addView(titleView);
        if (summary != null) {
            TextView summaryView = new TextView(activity);
            summaryView.setText(summary);
            summaryView.setTextColor(TEXT_SECONDARY);
            summaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            summaryView.setPadding(0, dp(2), 0, 0);
            texts.addView(summaryView);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (trailing != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(16));
            row.addView(trailing, lp);
        }
        return row;
    }

    interface Toggle {
        void onToggle(boolean checked);
    }

    Switch switchRow(String title, String summary, boolean checked, Toggle onToggle) {
        Switch toggle = new Switch(activity);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener((v, isChecked) -> onToggle.onToggle(isChecked));
        row(title, summary, null, toggle, v -> toggle.toggle());
        return toggle;
    }

    TextView chevron() {
        TextView view = new TextView(activity);
        view.setText("›");
        view.setTextColor(TEXT_SECONDARY);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        return view;
    }

    /** Full-width pill button; filled buttons use the accent, outlined ones a thin white rim. */
    TextView pill(String label, boolean filled, View.OnClickListener onClick) {
        TextView button = new TextView(activity);
        button.setText(label);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setTextColor(filled ? Color.BLACK : TEXT);
        button.setMinHeight(dp(52));
        button.setPadding(dp(24), 0, dp(24), 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(26));
        if (filled) {
            shape.setColor(accent);
        } else {
            shape.setColor(Color.TRANSPARENT);
            shape.setStroke(dp(1), 0xFF5A5A5C);
        }
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), shape, null));
        button.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(24), dp(8), dp(24), dp(8));
        content.addView(button, lp);
        return button;
    }

    void divider() {
        View line = new View(activity);
        line.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(dp(24), dp(8), dp(24), dp(8));
        content.addView(line, lp);
    }

    Drawable ripple(int base, int radius) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radius);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(base);
        shape.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), shape, mask);
    }

    /**
     * Rounded square showing a folder's cover: its image, its emoji, or else the icon of its
     * first app.
     */
    View coverPreview(Config.Folder folder, int sizeDp, boolean animate, boolean dotMatrix) {
        int size = dp(sizeDp);
        FrameLayout badge = new FrameLayout(activity);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SURFACE);
        bg.setCornerRadius(size * 0.22f);
        badge.setBackground(bg);
        badge.setClipToOutline(true);
        if (folder.coverImageVersion != 0) {
            ImageView image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, Covers.SIZE / Math.max(1, size));
            image.setImageBitmap(BitmapFactory.decodeFile(
                    CoverProvider.coverFile(activity, folder.id).getPath(), options));
            badge.addView(image, new FrameLayout.LayoutParams(size, size));
        } else if (!folder.coverText.isEmpty()) {
            AnimatedEmojiView text = new AnimatedEmojiView(activity);
            text.setDotMatrix(dotMatrix);
            if (dotMatrix) {
                // The whole badge is the dot screen, dots kept inside its rounded corners.
                Rect badgeRect = new Rect(0, 0, size, size);
                text.setDotPanel(badgeRect, badgeRect, size * 0.22f);
            }
            text.setEmoji(folder.coverText,
                    animate ? Motion.parse(folder.coverMotion) : Motion.NONE,
                    animate ? DotEffect.parse(folder.coverEffect) : DotEffect.NONE,
                    DotEffect.anchor(folder.coverEffect), folder.id.hashCode());
            text.setOnClickListener(v -> text.react());
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.6f);
            // The emoji may hop past the badge edge mid-animation.
            badge.setClipToOutline(false);
            badge.addView(text, new FrameLayout.LayoutParams(size, size));
        } else if (!folder.apps.isEmpty()) {
            Apps.App first = Apps.find(activity, folder.apps.iterator().next());
            if (first != null) {
                ImageView icon = new ImageView(activity);
                icon.setImageDrawable(first.icon(activity.getPackageManager()));
                int pad = size / 6;
                icon.setPadding(pad, pad, pad, pad);
                badge.addView(icon, new FrameLayout.LayoutParams(size, size));
            }
        }
        return badge;
    }
}
