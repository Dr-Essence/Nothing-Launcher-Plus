package com.dressence.nothinglauncherplus.launcher;

import android.graphics.Canvas;
import android.text.Layout;
import android.text.TextUtils;
import android.widget.TextView;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * With dot-matrix icons on, an app name too long for its icon scrolls through its place like
 * a drawer folder's name: a dot's width at a time, pausing at the start, and back to the start
 * once its end has shown. Only the text moves; the icon above it stays where it is.
 */
final class LabelScroll {

    private static final String CLS_BUBBLE = "com.android.launcher3.BubbleTextView";
    /** One step: a dot of NDot (a tenth of the text size). */
    private static final float STEP_EM = 0.1f;
    private static final long STEP_MS = 90;
    private static final long PAUSE_START_MS = 1800;
    private static final long PAUSE_END_MS = 1200;

    /** The labels scrolling now, and how far each has to go. */
    private static final Map<TextView, Scroller> SCROLLING =
            Collections.synchronizedMap(new WeakHashMap<>());

    private LabelScroll() {
    }

    static void install(ClassLoader cl, ConfigStore store) {
        XposedHelpers.findAndHookMethod(CLS_BUBBLE, cl, "onDraw", Canvas.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        update((TextView) param.thisObject, store.get().dotIcons);
                    }
                });
    }

    /** Starts or stops {@code label} scrolling, as its text, width and the option say. */
    private static void update(TextView label, boolean on) {
        CharSequence text = label.getText();
        int room = label.getWidth() - label.getCompoundPaddingLeft()
                - label.getCompoundPaddingRight();
        float width = text == null ? 0 : label.getPaint().measureText(text, 0, text.length());
        boolean wanted = on && room > 0 && width > room + 1 && label.isAttachedToWindow();
        Scroller scroller = SCROLLING.get(label);
        if (!wanted) {
            if (scroller != null) {
                scroller.stop();
                SCROLLING.remove(label);
            }
            return;
        }
        // The two are always set together: no ellipsis means it already scrolls.
        if (label.getEllipsize() != null) {
            label.setEllipsize(null);
            label.setHorizontallyScrolling(true);
        }
        int travel = (int) Math.ceil(width - room);
        if (scroller == null) {
            scroller = new Scroller(label);
            SCROLLING.put(label, scroller);
        }
        scroller.start(travel);
    }

    /** Steps one label's text along while it is shown. */
    private static final class Scroller implements Runnable {
        private final TextView label;
        private int travel;
        private int at;
        private boolean posted;

        Scroller(TextView label) {
            this.label = label;
        }

        void start(int travel) {
            this.travel = travel;
            if (!posted) {
                posted = true;
                label.postDelayed(Hooks.guard("label scroll", this), PAUSE_START_MS);
            }
        }

        void stop() {
            posted = false;
            at = 0;
            label.setScrollX(0);
            if (TextUtils.TruncateAt.END != label.getEllipsize()) {
                label.setHorizontallyScrolling(false);
                label.setEllipsize(TextUtils.TruncateAt.END);
            }
        }

        /**
         * Where the text starts: a centred line too long for its view is laid out centred on
         * a very wide line, and the view scrolled to its start; steps count from there.
         */
        private int start() {
            Layout layout = label.getLayout();
            return layout == null || layout.getLineCount() == 0 ? 0
                    : (int) layout.getLineLeft(0);
        }

        @Override
        public void run() {
            if (!posted) {
                return; // Stopped meanwhile.
            }
            if (!label.isAttachedToWindow() || !label.isShown()) {
                posted = false; // Drawn again later, it starts over.
                at = 0;
                label.setScrollX(start());
                return;
            }
            int step = Math.max(1, Math.round(label.getTextSize() * STEP_EM));
            long next;
            if (at >= travel) {
                at = 0; // Back to the start, and a pause there.
                next = PAUSE_START_MS;
            } else {
                at = Math.min(travel, at + step);
                next = at >= travel ? PAUSE_END_MS : STEP_MS;
            }
            label.setScrollX(start() + at);
            label.postDelayed(Hooks.guard("label scroll", this), next);
        }
    }
}
