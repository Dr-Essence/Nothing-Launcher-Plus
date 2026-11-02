package com.dressence.nothinglauncherplus.launcher;

import android.animation.ObjectAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedHelpers;

/**
 * The controller's focus ring: one thin rounded line around what the focused item actually
 * draws (an icon's own shape, a folder's disc, a widget's card or circle, a menu row's pill),
 * a little outside it. The shape is read from the item's own drawing: it is rendered small once,
 * and the outline of its visible pixels gives the bounds and the corner radius (a circle is a
 * rounded square whose radius is half its side). Small separate marks (a notification dot, a
 * label) are left out, so they don't pull the ring off the shape.
 *
 * <p>There is only ever one ring, and it is always taken off the item it was put on.
 */
final class FocusRing {

    /** The longer side of the small rendering the shape is read from, in pixels. */
    private static final int SAMPLE = 72;
    private static final int OPAQUE = 40;
    /** Marks smaller than this share of the largest one are not part of the shape. */
    private static final float MARK = 0.2f;
    private static final float GAP_DP = 3;
    private static final float STROKE_DP = 2;
    /** 1 - 1/sqrt(2): how far along its diagonal a rounded corner starts, per unit radius. */
    private static final float CORNER = 0.29289f;

    /** What an item draws: its bounds (in the item's coordinates) and corner radius. */
    static final class Shape {
        final RectF bounds;
        final float radius;
        final int width;
        final int height;

        Shape(RectF bounds, float radius, int width, int height) {
            this.bounds = bounds;
            this.radius = radius;
            this.width = width;
            this.height = height;
        }
    }

    private final Class<?> bubbleText;
    private final Map<View, Shape> shapes = new WeakHashMap<>();
    private WeakReference<View> host = new WeakReference<>(null);
    private Ring ring;

    FocusRing(Class<?> bubbleText) {
        this.bubbleText = bubbleText;
    }

    /** Puts the ring on {@code view} (taking it off wherever it was). */
    void show(View view) {
        hide();
        view.setDefaultFocusHighlightEnabled(false);
        Shape shape = shapeOf(view);
        if (shape == null) {
            return;
        }
        float dp = view.getResources().getDisplayMetrics().density;
        float stroke = STROKE_DP * dp;
        RectF r = new RectF(shape.bounds);
        float gap = GAP_DP * dp + stroke / 2;
        r.inset(-gap, -gap);
        // Inside the item, where it can't be cut off.
        r.intersect(stroke / 2, stroke / 2, view.getWidth() - stroke / 2,
                view.getHeight() - stroke / 2);
        float radius = Math.min(shape.radius + gap, Math.min(r.width(), r.height()) / 2);
        Ring next = new Ring(r, radius, stroke);
        next.setBounds(0, 0, view.getWidth(), view.getHeight());
        view.getOverlay().add(next);
        ObjectAnimator.ofInt(next, "alpha", 0, 255).setDuration(140).start();
        ring = next;
        host = new WeakReference<>(view);
    }

    /** Takes the ring off its item. */
    void hide() {
        View view = host.get();
        if (view != null) {
            if (ring != null) {
                view.getOverlay().remove(ring);
            }
            view.setDefaultFocusHighlightEnabled(true);
        }
        ring = null;
        host = new WeakReference<>(null);
    }

    /** What {@code view} draws, measured once per size; null if it draws nothing readable. */
    Shape shapeOf(View view) {
        int w = view.getWidth();
        int h = view.getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        Shape cached = shapes.get(view);
        if (cached != null && cached.width == w && cached.height == h) {
            return cached;
        }
        Rect area = new Rect(0, 0, w, h);
        if (bubbleText.isInstance(view)) {
            // An app icon: the icon, not its name.
            XposedHelpers.callMethod(view, "getIconBounds", area);
            if (!area.intersect(0, 0, w, h)) {
                return null;
            }
        }
        Shape shape;
        try {
            shape = silhouette(view, area, w, h);
        } catch (RuntimeException e) {
            shape = null; // Unreadable drawing (it never should be): the box below instead.
        }
        if (inPopup(view)) {
            // A menu row: its whole width, as round as the row or its card when the row draws
            // no background of its own.
            if (shape == null || shape.bounds.width() < w * 0.8f) {
                float radius = outlineRadius(view);
                if (radius <= 0 && view.getParent() instanceof View) {
                    radius = outlineRadius((View) view.getParent());
                }
                if (radius <= 0) {
                    radius = 12 * view.getResources().getDisplayMetrics().density;
                }
                shape = new Shape(new RectF(0, 0, w, h), radius, w, h);
            }
        } else if (shape == null) {
            shape = new Shape(new RectF(area), Math.min(area.width(), area.height()) * 0.2f,
                    w, h);
        }
        shapes.put(view, shape);
        return shape;
    }

    /** The outline of the visible pixels {@code view} draws in {@code area}. */
    private static Shape silhouette(View view, Rect area, int w, int h) {
        float scale = (float) SAMPLE / Math.max(area.width(), area.height());
        int bw = Math.max(1, Math.round(area.width() * scale));
        int bh = Math.max(1, Math.round(area.height() * scale));
        // Recorded first: a picture may hold hardware bitmaps (widgets' images), which it
        // renders through the GPU and copies back; a plain software canvas would refuse them.
        Picture picture = new Picture();
        Canvas canvas = picture.beginRecording(bw, bh);
        canvas.scale(scale, scale);
        canvas.translate(-area.left, -area.top);
        view.draw(canvas);
        picture.endRecording();
        Bitmap bitmap = Bitmap.createBitmap(picture, bw, bh, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[bw * bh];
        bitmap.getPixels(pixels, 0, bw, 0, 0, bw, bh);
        bitmap.recycle();
        boolean[] mask = new boolean[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            mask[i] = (pixels[i] >>> 24) > OPAQUE;
        }
        float[] o = outline(mask, bw, bh);
        if (o == null) {
            return null;
        }
        RectF bounds = new RectF(area.left + o[0] / scale, area.top + o[1] / scale,
                area.left + o[2] / scale, area.top + o[3] / scale);
        float radius = Math.min(o[4] / scale, Math.min(bounds.width(), bounds.height()) / 2);
        return new Shape(bounds, radius, w, h);
    }

    /**
     * The shape in a mask: {left, top, right, bottom, corner radius} in mask pixels, or null
     * when it is empty.
     */
    static float[] outline(boolean[] mask, int bw, int bh) {
        keepShape(mask, bw, bh);
        int left = bw;
        int top = bh;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                if (mask[y * bw + x]) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        if (right < 0) {
            return null;
        }
        // The corner radius: how far in along each corner's diagonal the shape starts.
        int reach = Math.min(right - left, bottom - top) / 2;
        float[] steps = {
                diagonal(mask, bw, left, top, 1, 1, reach),
                diagonal(mask, bw, right, top, -1, 1, reach),
                diagonal(mask, bw, left, bottom, 1, -1, reach),
                diagonal(mask, bw, right, bottom, -1, -1, reach)};
        java.util.Arrays.sort(steps);
        float step = (steps[1] + steps[2]) / 2; // Middle two: a stray mark can't skew it.
        return new float[] {left, top, right + 1, bottom + 1, step / CORNER};
    }

    /** Steps from a corner along its diagonal until the shape starts (at most {@code reach}). */
    private static float diagonal(boolean[] mask, int bw, int x, int y, int dx, int dy,
            int reach) {
        for (int k = 0; k <= reach; k++) {
            if (mask[(y + k * dy) * bw + x + k * dx]) {
                return k;
            }
        }
        return reach;
    }

    /** Keeps the shape's own pixels: every mark at least {@link #MARK} of the largest. */
    private static void keepShape(boolean[] mask, int bw, int bh) {
        int[] label = new int[mask.length];
        int[] queue = new int[mask.length];
        int[] sizes = new int[mask.length + 1];
        int count = 0;
        int largest = 0;
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || label[start] != 0) {
                continue;
            }
            count++;
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            label[start] = count;
            while (head < tail) {
                int p = queue[head++];
                int px = p % bw;
                int py = p / bw;
                for (int d = 0; d < 4; d++) {
                    int nx = px + (d == 0 ? 1 : d == 1 ? -1 : 0);
                    int ny = py + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (nx < 0 || ny < 0 || nx >= bw || ny >= bh) {
                        continue;
                    }
                    int n = ny * bw + nx;
                    if (mask[n] && label[n] == 0) {
                        label[n] = count;
                        queue[tail++] = n;
                    }
                }
            }
            sizes[count] = tail;
            largest = Math.max(largest, tail);
        }
        for (int i = 0; i < mask.length; i++) {
            if (mask[i] && sizes[label[i]] < largest * MARK) {
                mask[i] = false;
            }
        }
    }

    /** The corner radius of a view's own rounded outline, or -1 when it has none. */
    private static float outlineRadius(View view) {
        ViewOutlineProvider provider = view.getOutlineProvider();
        if (provider == null) {
            return -1;
        }
        Outline outline = new Outline();
        try {
            provider.getOutline(view, outline);
        } catch (Throwable t) {
            return -1;
        }
        Rect rect = new Rect();
        return outline.getRect(rect) && !rect.isEmpty() ? outline.getRadius() : -1;
    }

    /** True inside a long-press menu (Nothing's popups). */
    static boolean inPopup(View view) {
        for (ViewParent p = view.getParent(); p instanceof View; p = p.getParent()) {
            if (p.getClass().getName().contains("Popup")) {
                return true;
            }
        }
        return false;
    }

    /** The ring: a rounded white line, no fill. */
    private static final class Ring extends Drawable {
        private final RectF rect;
        private final float radius;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Ring(RectF rect, float radius, float stroke) {
            this.rect = rect;
            this.radius = radius;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(0xF2FFFFFF);
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawRoundRect(rect, radius, radius, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha * 0xF2 / 255);
            invalidateSelf();
        }

        @Override
        public int getAlpha() {
            return paint.getAlpha() * 255 / 0xF2;
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
