package com.dressence.nothinglauncherplus.hook;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * The status bar's and quick settings' icons drawn as Nothing dot-matrix icons. Each frame the
 * icon is drawn as it would be (whatever state or animation it is in), then shown as a grid of
 * round dots: in the status bar lit the green of the clock with a soft glow round each dot; on
 * the quick settings tiles in the icon's own colours, so on and off states and the tiles'
 * animations all still show, now in dots.
 */
final class DotStatusIcons {

    private static final int NONE = 0;
    private static final int STATUS = 1;
    private static final int TILE = 2;
    /** Dots across an icon's height. */
    private static final int ROWS_STATUS = 9;
    private static final int ROWS_TILE = 12;
    private static final int GREEN = 0xFF4CFF7A;

    /** Per view: which kind of icon it is, worked out once it is in its window. */
    private static final Map<View, Integer> KIND = new WeakHashMap<>();
    private static final Map<ImageView, Bitmap> BUFFERS = new WeakHashMap<>();
    private static final Paint DOT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint HALO = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static int[] pixels = new int[0];

    private DotStatusIcons() {
    }

    static void install(ConfigStore store) {
        XposedHelpers.findAndHookMethod(ImageView.class, "onDraw", Canvas.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        ImageView view = (ImageView) param.thisObject;
                        int kind = kind(view);
                        if (kind == NONE) {
                            return;
                        }
                        boolean on = kind == STATUS ? store.get().ndotStatusBar
                                : store.get().ndotQuickSettings;
                        if (on && draw(view, (Canvas) param.args[0], kind)) {
                            param.setResult(null);
                        }
                    }
                });
        // The status bar's small texts (network speed and the like) in green NDot too.
        XposedHelpers.findAndHookMethod(TextView.class, "onDraw", Canvas.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        TextView view = (TextView) param.thisObject;
                        if (kind(view) == STATUS && store.get().ndotStatusBar
                                && !StatusBar.styled(view)) {
                            StatusBar.styleText(view, true);
                        }
                    }
                });
    }

    /** What {@code view} is: a status bar icon, a quick settings tile icon, or neither. */
    private static int kind(View view) {
        Integer known = KIND.get(view);
        if (known != null) {
            return known;
        }
        if (!view.isAttachedToWindow()) {
            return NONE;
        }
        int kind = NONE;
        int depth = 0;
        for (ViewParent p = view.getParent(); p instanceof View && depth < 14;
                p = p.getParent(), depth++) {
            String name = p.getClass().getSimpleName();
            if (name.contains("Battery")) {
                break; // Drawn green by its own filter.
            }
            if (name.contains("QSTile") || name.contains("QSIconView")
                    || name.contains("TileView") || name.contains("QSTileView")) {
                kind = TILE;
                break;
            }
            if (name.contains("StatusBar") || name.contains("StatusIcon")
                    || name.contains("SystemIcons") || name.contains("ShadeHeader")
                    || name.contains("QuickStatusBarHeader") || name.contains("NotificationIcon")) {
                kind = STATUS;
                break;
            }
        }
        KIND.put(view, kind);
        return kind;
    }

    /** Draws {@code view}'s icon as dots; false to let it draw itself. */
    private static boolean draw(ImageView view, Canvas canvas, int kind) {
        Drawable d = view.getDrawable();
        int w = view.getWidth();
        int h = view.getHeight();
        if (d == null || w <= 0 || h <= 0 || w > 512 || h > 512) {
            return false;
        }
        Bitmap buffer = BUFFERS.get(view);
        if (buffer == null || buffer.getWidth() != w || buffer.getHeight() != h) {
            buffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            BUFFERS.put(view, buffer);
        }
        buffer.eraseColor(Color.TRANSPARENT);
        // The icon exactly as the view would draw it: padding, scaling and tint included.
        Canvas c = new Canvas(buffer);
        c.translate(view.getPaddingLeft(), view.getPaddingTop());
        Matrix m = view.getImageMatrix();
        if (m != null) {
            c.concat(m);
        }
        d.draw(c);
        if (pixels.length < w * h) {
            pixels = new int[w * h];
        }
        buffer.getPixels(pixels, 0, w, 0, 0, w, h);
        int top = view.getPaddingTop();
        int content = Math.max(1, h - top - view.getPaddingBottom());
        float pitch = Math.max(2f, content / (float) (kind == STATUS ? ROWS_STATUS : ROWS_TILE));
        int cols = (int) Math.ceil(w / pitch);
        int rows = (int) Math.ceil(h / pitch);
        float ox = (w - cols * pitch) / 2;
        float oy = (h - rows * pitch) / 2;
        float radius = pitch * 0.4f;
        float alpha = view.getImageAlpha() / 255f;
        for (int gy = 0; gy < rows; gy++) {
            int y0 = Math.max(0, Math.round(oy + gy * pitch));
            int y1 = Math.min(h, Math.round(oy + (gy + 1) * pitch));
            for (int gx = 0; gx < cols; gx++) {
                int x0 = Math.max(0, Math.round(ox + gx * pitch));
                int x1 = Math.min(w, Math.round(ox + (gx + 1) * pitch));
                long a = 0, r = 0, g = 0, b = 0;
                int n = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int p = pixels[y * w + x];
                        int pa = p >>> 24;
                        a += pa;
                        r += ((p >> 16) & 0xFF) * pa;
                        g += ((p >> 8) & 0xFF) * pa;
                        b += (p & 0xFF) * pa;
                        n++;
                    }
                }
                if (n == 0 || a == 0) {
                    continue;
                }
                float cover = a / (255f * n);
                if (cover < 0.28f) {
                    continue;
                }
                float level = Math.min(1, 0.35f + cover) * alpha;
                int colour = kind == STATUS ? GREEN
                        : Color.rgb((int) (r / a), (int) (g / a), (int) (b / a));
                float cx = ox + (gx + 0.5f) * pitch;
                float cy = oy + (gy + 0.5f) * pitch;
                if (kind == STATUS) {
                    // The soft glow of a lit dot on dark glass.
                    HALO.setColor(colour);
                    HALO.setAlpha(Math.round(70 * level));
                    canvas.drawCircle(cx, cy, pitch * 0.85f, HALO);
                }
                DOT.setColor(colour);
                DOT.setAlpha(Math.round(255 * level));
                canvas.drawCircle(cx, cy, radius, DOT);
            }
        }
        return true;
    }
}
