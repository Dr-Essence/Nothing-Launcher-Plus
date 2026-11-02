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
    /**
     * A shape covers at least this share of its bounds (a disc about 0.79, a card nearly 1);
     * less is scattered marks, such as a calendar's text on a clear background.
     */
    private static final float SOLID = 0.35f;
    /** A shape whose bounds are less than this share of all that is drawn is a detail. */
    private static final float EXTENT = 0.5f;
    /** Corners at least this round are taken as fully round. */
    private static final float ROUND = 0.85f;
    /** A row or column belongs to the shape when it holds this share of the fullest one. */
    private static final float PROJECTION = 0.25f;
    /** 1 - 1/sqrt(2): how far along its diagonal a rounded corner starts, per unit radius. */
    private static final float CORNER = 0.29289f;

    /** What an item draws: its bounds (in the item's coordinates) and corner radius. */
    static final class Shape {
        final RectF bounds;
        final float radius;
        final int width;
        final int height;

        /** False when what was drawn is scattered marks (text, lines), not one solid shape. */
        final boolean solid;

        Shape(RectF bounds, float radius, int width, int height) {
            this(bounds, radius, width, height, true);
        }

        Shape(RectF bounds, float radius, int width, int height, boolean solid) {
            this.bounds = bounds;
            this.radius = radius;
            this.width = width;
            this.height = height;
            this.solid = solid;
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
        if (!isWidget(view) && isContainer(view)) {
            return; // A folder's or menu's whole sheet: focus goes on into its items.
        }
        view.setDefaultFocusHighlightEnabled(false);
        Shape shape = shapeOf(view);
        if (shape == null) {
            return;
        }
        float dp = view.getResources().getDisplayMetrics().density;
        float stroke = STROKE_DP * dp;
        // The same gap all round, inside the item (where nothing cuts it off): closer to the
        // shape, or just on its edge, where the item leaves no room, never squashed to fit.
        RectF b = shape.bounds;
        float half = stroke / 2;
        float gap = Math.min(GAP_DP * dp + half, Math.min(Math.min(b.left, b.top),
                Math.min(view.getWidth() - b.right, view.getHeight() - b.bottom)) - half);
        gap = Math.max(gap, -half);
        RectF r = new RectF(b);
        r.inset(-gap, -gap);
        float radius = Math.max(0, Math.min(shape.radius + gap,
                Math.min(r.width(), r.height()) / 2));
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
        boolean icon = bubbleText.isInstance(view);
        boolean widget = isWidget(view);
        if (icon) {
            // An app icon: the icon, not its name.
            XposedHelpers.callMethod(view, "getIconBounds", area);
            if (!area.intersect(0, 0, w, h)) {
                return null;
            }
        } else if (widget) {
            // A widget: its cell less the launcher's padding round it.
            area.set(view.getPaddingLeft(), view.getPaddingTop(), w - view.getPaddingRight(),
                    h - view.getPaddingBottom());
            if (area.isEmpty()) {
                area.set(0, 0, w, h);
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
            if (shape == null || !shape.solid || shape.bounds.width() < w * 0.8f) {
                float radius = outlineRadius(view);
                if (radius <= 0 && view.getParent() instanceof View) {
                    radius = outlineRadius((View) view.getParent());
                }
                if (radius <= 0) {
                    radius = 12 * view.getResources().getDisplayMetrics().density;
                }
                shape = new Shape(new RectF(0, 0, w, h), radius, w, h);
            }
        } else if (shape == null || !shape.solid) {
            // Nothing solid drawn (a clear widget of text and lines, an icon of a lone letter):
            // the widget's card as Android rounds widgets, the icon's circle, else the area.
            float radius;
            if (widget) {
                radius = view.getResources().getDimension(
                        android.R.dimen.system_app_widget_background_radius);
            } else if (icon) {
                radius = Math.min(area.width(), area.height()) / 2f;
            } else {
                radius = Math.min(area.width(), area.height()) * 0.2f;
            }
            shape = new Shape(new RectF(area), radius, w, h);
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
        return new Shape(bounds, radius, w, h, o[5] >= SOLID);
    }

    /**
     * The shape in a mask: {left, top, right, bottom, corner radius, share of its bounds it
     * covers} in mask pixels, or null when it is empty. Its bounds are the rows and columns
     * that carry a fair part of it, so a mark joined to its edge (a notification dot touching
     * a folder's disc) does not widen it.
     */
    static float[] outline(boolean[] mask, int bw, int bh) {
        // Everything drawn, before the small marks are left out.
        int allLeft = bw;
        int allTop = bh;
        int allRight = -1;
        int allBottom = -1;
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                if (mask[y * bw + x]) {
                    allLeft = Math.min(allLeft, x);
                    allRight = Math.max(allRight, x);
                    allTop = Math.min(allTop, y);
                    allBottom = Math.max(allBottom, y);
                }
            }
        }
        if (allRight < 0) {
            return null;
        }
        int largest = keepShape(mask, bw, bh);
        int[] columns = new int[bw];
        int[] rows = new int[bh];
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                if (mask[y * bw + x]) {
                    columns[x]++;
                    rows[y]++;
                }
            }
        }
        int[] across = span(columns);
        int[] down = span(rows);
        if (across == null || down == null) {
            return null;
        }
        int left = across[0];
        int right = across[1];
        int top = down[0];
        int bottom = down[1];
        // The corner radius: how far in along each corner's diagonal the shape starts.
        int reach = Math.min(right - left, bottom - top) / 2;
        float[] steps = {
                diagonal(mask, bw, left, top, 1, 1, reach),
                diagonal(mask, bw, right, top, -1, 1, reach),
                diagonal(mask, bw, left, bottom, 1, -1, reach),
                diagonal(mask, bw, right, bottom, -1, -1, reach)};
        java.util.Arrays.sort(steps);
        float step = (steps[1] + steps[2]) / 2; // Middle two: a stray mark can't skew it.
        float area = (float) (right - left + 1) * (bottom - top + 1);
        float radius = step / CORNER;
        float half = (Math.min(right - left, bottom - top) + 1) / 2f;
        if (radius >= half * ROUND) {
            radius = half; // Round all the way: a disc, a pill.
        }
        // Solid: the shape fills its bounds, and its bounds are most of what is drawn (a
        // calendar's highlighted day is solid, but a small part of the widget).
        float all = (float) (allRight - allLeft + 1) * (allBottom - allTop + 1);
        float cover = Math.min(1, largest / area);
        if (area < all * EXTENT) {
            cover = 0;
        }
        return new float[] {left, top, right + 1, bottom + 1, radius, cover};
    }

    /** First and last index holding at least {@link #PROJECTION} of the fullest; or null. */
    private static int[] span(int[] counts) {
        int max = 0;
        for (int c : counts) {
            max = Math.max(max, c);
        }
        if (max == 0) {
            return null;
        }
        float least = Math.max(1, max * PROJECTION);
        int first = 0;
        while (counts[first] < least) {
            first++;
        }
        int last = counts.length - 1;
        while (counts[last] < least) {
            last--;
        }
        return new int[] {first, last};
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

    /**
     * Keeps the shape's own pixels: every mark at least {@link #MARK} of the largest. Returns
     * the largest mark's size.
     */
    private static int keepShape(boolean[] mask, int bw, int bh) {
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
        return largest;
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

    /** A widget's host (Launcher3's app widgets and Nothing's cards). */
    static boolean isWidget(View view) {
        for (Class<?> c = view.getClass(); c != null; c = c.getSuperclass()) {
            String name = c.getName();
            if (name.equals("android.appwidget.AppWidgetHostView")
                    || name.equals("com.nothing.cardhost.CardWidgetHostView")) {
                return true;
            }
        }
        return false;
    }

    /**
     * A view whose own items take focus (a folder's sheet, a menu): not an item itself.
     * Widgets are items although their parts may take focus (DebuG22: Nothing's cards).
     */
    private static boolean isContainer(View view) {
        if (!(view instanceof android.view.ViewGroup)) {
            return false;
        }
        android.view.ViewGroup group = (android.view.ViewGroup) view;
        return group.getDescendantFocusability()
                != android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                && group.getFocusables(View.FOCUS_FORWARD).size() > 1;
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
