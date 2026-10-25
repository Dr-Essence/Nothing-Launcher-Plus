package com.dressence.nothinglauncherplus.ui;

import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Vertical list (apps in a folder, or the folders themselves) that can be reordered by dragging
 * the handle at the end of each row; the rest of a row keeps its own click. The container owns
 * the gesture, so moving rows around never cancels the touch stream.
 */
@SuppressLint("ViewConstructor")
final class ReorderList extends LinearLayout {

    interface Listener {
        void onReordered(List<String> keys);
    }

    /** Builds the row for one key (without the handle, which the list adds). */
    interface RowFactory {
        LinearLayout create(String key);
    }

    private final Listener listener;
    private final List<String> order;
    private final int rowHeight;
    private final int handleWidth;
    private final int edge;
    private final int scrollStep;
    private final GradientDrawable dragBackground = new GradientDrawable();

    private View dragRow;
    private int startIndex;
    private int currentIndex;
    private float downLocalY;
    private float lastRawY;
    private ScrollView scroller;

    private final Runnable autoScroll = new Runnable() {
        @Override
        public void run() {
            if (dragRow == null || scroller == null) {
                return;
            }
            int[] location = new int[2];
            scroller.getLocationOnScreen(location);
            float y = lastRawY - location[1];
            int step = y < edge ? -scrollStep : y > scroller.getHeight() - edge ? scrollStep : 0;
            if (step != 0 && scroller.canScrollVertically(step)) {
                scroller.scrollBy(0, step);
                updateDrag();
            }
            postOnAnimation(this);
        }
    };

    /** A list of apps (flattened component names) in the given order. */
    ReorderList(Ui ui, List<String> components, Listener listener) {
        this(ui, components, 64, null, listener);
    }

    /** A list of {@code keys}, each row {@code rowHeightDp} tall, built by {@code factory}. */
    ReorderList(Ui ui, List<String> keys, int rowHeightDp, RowFactory factory,
            Listener listener) {
        super(ui.context());
        setOrientation(VERTICAL);
        this.listener = listener;
        order = new ArrayList<>(keys);
        rowHeight = ui.dp(rowHeightDp);
        handleWidth = ui.dp(72);
        edge = ui.dp(72);
        scrollStep = ui.dp(10);
        dragBackground.setColor(Ui.SURFACE);
        dragBackground.setCornerRadius(ui.dp(16));
        PackageManager pm = getContext().getPackageManager();
        for (String key : order) {
            LinearLayout row = factory != null ? factory.create(key) : createRow(ui, pm, key);
            row.addView(handle(ui), new LayoutParams(ui.dp(40), ViewGroup.LayoutParams.MATCH_PARENT));
            addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight));
        }
    }

    private TextView handle(Ui ui) {
        TextView handle = new TextView(getContext());
        handle.setText("≡");
        handle.setTextColor(Ui.TEXT_SECONDARY);
        handle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        handle.setGravity(Gravity.CENTER);
        handle.setContentDescription("Drag to reorder");
        return handle;
    }

    private LinearLayout createRow(Ui ui, PackageManager pm, String component) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(24), 0, ui.dp(24), 0);

        Apps.App app = Apps.find(getContext(), component);
        ImageView icon = new ImageView(getContext());
        if (app != null) {
            icon.setImageDrawable(app.icon(pm));
        }
        LayoutParams iconLp = new LayoutParams(ui.dp(40), ui.dp(40));
        iconLp.setMarginEnd(ui.dp(16));
        row.addView(icon, iconLp);

        TextView label = new TextView(getContext());
        label.setText(app != null ? app.label : component + " (not installed)");
        label.setTextColor(app != null ? Ui.TEXT : Ui.TEXT_SECONDARY);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(label, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return dragRow != null;
        }
        boolean onHandle = getLayoutDirection() == LAYOUT_DIRECTION_RTL
                ? ev.getX() < handleWidth : ev.getX() > getWidth() - handleWidth;
        int index = (int) (ev.getY() / rowHeight);
        if (!onHandle || index < 0 || index >= getChildCount()) {
            return false;
        }
        startDrag(index, ev);
        return true;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (dragRow == null) {
            return false;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                lastRawY = ev.getRawY();
                updateDrag();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                endDrag();
                return true;
            default:
                return true;
        }
    }

    private void startDrag(int index, MotionEvent ev) {
        dragRow = getChildAt(index);
        startIndex = currentIndex = index;
        lastRawY = ev.getRawY();
        downLocalY = localY(lastRawY);
        dragRow.setBackground(dragBackground);
        dragRow.setElevation(rowHeight / 8f);
        dragRow.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        getParent().requestDisallowInterceptTouchEvent(true);
        scroller = findScroller();
        postOnAnimation(autoScroll);
    }

    /** Places the dragged row under the finger and moves it to the slot it is hovering. */
    private void updateDrag() {
        int last = getChildCount() - 1;
        float top = startIndex * rowHeight + (localY(lastRawY) - downLocalY);
        top = Math.max(0, Math.min(top, last * rowHeight));
        int target = Math.max(0, Math.min(Math.round(top / rowHeight), last));
        if (target != currentIndex) {
            removeView(dragRow);
            addView(dragRow, target);
            order.add(target, order.remove(currentIndex));
            currentIndex = target;
        }
        dragRow.setTranslationY(top - currentIndex * rowHeight);
    }

    private void endDrag() {
        removeCallbacks(autoScroll);
        View row = dragRow;
        dragRow = null;
        row.animate().translationY(0).setDuration(150)
                .withEndAction(() -> {
                    row.setElevation(0);
                    row.setBackground(null);
                })
                .start();
        getParent().requestDisallowInterceptTouchEvent(false);
        if (currentIndex != startIndex) {
            listener.onReordered(new ArrayList<>(order));
        }
    }

    private float localY(float rawY) {
        int[] location = new int[2];
        getLocationOnScreen(location);
        return rawY - location[1];
    }

    private ScrollView findScroller() {
        for (ViewParent p = getParent(); p != null; p = p.getParent()) {
            if (p instanceof ScrollView) {
                return (ScrollView) p;
            }
        }
        return null;
    }
}
