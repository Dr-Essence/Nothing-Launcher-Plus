package com.dressence.nothinglauncherplus.wallpaper;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.ViewGroup;
import android.webkit.WebView;

import com.dressence.nothinglauncherplus.xposed.ConfigStore;
import com.dressence.nothinglauncherplus.xposed.Hooks;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import de.robv.android.xposed.XposedHelpers;

/**
 * Living Worlds' picture framed by the module, so a turn is only a new framing.
 *
 * <p>Living Worlds' "Alt wallpaper tech" (its default from Android 8, MyEngine.createPresentation)
 * shows its page in a WebView on a virtual display the size of the wallpaper's surface; on each
 * surface change (a turn) it tore both down and built them again, and the page reloaded. Even
 * resized in place, the page lays itself out again a few frames after the display has changed
 * size, so the new orientation first showed the old layout (a black side, then a black half).
 *
 * <p>Here the page is given a display of exactly its scene's size (App.sceneWidth x sceneHeight,
 * 345 x 455, density 160: one page pixel per display pixel), so its own App.resize fits the scene
 * at scale 1 and the page never changes size. Its frames arrive in an ImageReader and are drawn
 * on the wallpaper's surface (GPU, pixels kept sharp as its CSS image-rendering: pixelated does)
 * with App.resize's own framing worked out for the surface: zoomed to fill in cover mode ("Zoom
 * to fill screen", Living Worlds' switch) or fitted, the same sideways anchor for February, July
 * and December, and in landscape the module's slider top to bottom. Each frame is framed for
 * the surface's size as it is then, and a turn redraws the last frame at once for the new
 * size: the first frame in the new orientation is already the landscape (or portrait) picture.
 */
final class LivingWorldsView {

    static final String FIELD = "nlplus.view";
    private static final int SCENE_WIDTH = 345;
    private static final int SCENE_HEIGHT = 455;
    private static final int DENSITY = 160;

    private static Handler frames;
    /** Set when this phone cannot draw the page's frames: Living Worlds' own drawing is used. */
    static volatile boolean broken;

    private final Object engine;
    private final ConfigStore store;
    private final ReentrantLock lock = new ReentrantLock();
    /**
     * Sharp pixels, as the page's CSS image-rendering: pixelated keeps them: filtering off.
     * A new Paint filters bitmaps by default since Android 9 (its hidden default flags include
     * FILTER_BITMAP_FLAG), which smoothed the scene.
     */
    private final Paint paint = new Paint();
    private final RectF dst = new RectF();
    private final ImageReader reader;
    private final VirtualDisplay display;
    private SurfaceHolder holder;
    /** The surface drawn on, connected to the GPU renderer until closed. */
    private Surface drawn;
    private Image shownImage;
    private Bitmap shown;
    private Image previousImage;
    private Bitmap previous;
    private volatile boolean closed;
    private int framedWidth;
    private int framedHeight;
    volatile boolean visible = true;

    private LivingWorldsView(Object engine, ConfigStore store, SurfaceHolder holder) {
        paint.setFilterBitmap(false);
        this.engine = engine;
        this.store = store;
        this.holder = holder;
        reader = ImageReader.newInstance(SCENE_WIDTH, SCENE_HEIGHT, PixelFormat.RGBA_8888, 4,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
        Context context = (Context) XposedHelpers.getObjectField(engine, "myContext");
        display = context.getSystemService(DisplayManager.class).createVirtualDisplay(
                "MyVirtualDisplay", SCENE_WIDTH, SCENE_HEIGHT, DENSITY, reader.getSurface(), 0);
        reader.setOnImageAvailableListener(r -> onFrame(), frames());
    }

    /**
     * Living Worlds' createPresentation, done here: the page on a display of the scene's size.
     * With one already running for this engine, only a new framing (a turn). False when Living
     * Worlds should build its own (no surface yet, or this phone cannot).
     */
    static boolean present(Object engine, ConfigStore store) {
        SurfaceHolder holder = (SurfaceHolder) XposedHelpers.getObjectField(engine, "myHolder");
        if (broken || holder == null) {
            return false;
        }
        LivingWorldsView view = (LivingWorldsView) XposedHelpers.getAdditionalInstanceField(
                engine, FIELD);
        WebView web = (WebView) XposedHelpers.getObjectField(engine, "myWebView");
        if (view != null && !view.closed && web != null && web.isAttachedToWindow()) {
            view.holder = holder;
            view.redrawNow(); // The turn: the last frame, framed for the new size.
            return true;
        }
        // As Living Worlds' own: what was there goes first.
        if (view != null) {
            view.close();
        }
        if (web != null) {
            web.destroy();
            XposedHelpers.setObjectField(engine, "myWebView", null);
        }
        VirtualDisplay old = (VirtualDisplay) XposedHelpers.getObjectField(engine,
                "myVirtualDisplay");
        if (old != null) {
            old.release();
            XposedHelpers.setObjectField(engine, "myVirtualDisplay", null);
        }
        LivingWorldsView built = null;
        try {
            built = new LivingWorldsView(engine, store, holder);
            XposedHelpers.setAdditionalInstanceField(engine, FIELD, built);
            XposedHelpers.setObjectField(engine, "myVirtualDisplay", built.display);
            Context context = (Context) XposedHelpers.getObjectField(engine, "myContext");
            Presentation presentation = new Presentation(context, built.display.getDisplay());
            XposedHelpers.callMethod(engine, "createWebView", presentation.getContext());
            WebView created = (WebView) XposedHelpers.getObjectField(engine, "myWebView");
            presentation.setContentView(created,
                    new ViewGroup.LayoutParams(SCENE_WIDTH, SCENE_HEIGHT));
            presentation.getWindow().getDecorView().setBackgroundColor(Color.BLACK);
            presentation.show();
        } catch (Throwable t) {
            // Nothing half-built is left beside Living Worlds' own, which runs instead.
            broken = true;
            if (built != null) {
                built.close();
            }
            XposedHelpers.setAdditionalInstanceField(engine, FIELD, null);
            Hooks.log("living worlds: framing not possible here (" + t + "); its own drawing"
                    + " is used");
            return false;
        }
        Hooks.log("living worlds: page at " + SCENE_WIDTH + "x" + SCENE_HEIGHT
                + ", framed by the module");
        return true;
    }

    /** The surface is going: nothing more is drawn on it (main thread, before Living Worlds). */
    static void surfaceGoing(Object engine) {
        LivingWorldsView view = (LivingWorldsView) XposedHelpers.getAdditionalInstanceField(
                engine, FIELD);
        if (view != null) {
            view.close();
            XposedHelpers.setAdditionalInstanceField(engine, FIELD, null);
        }
    }

    static void setVisible(Object engine, boolean visible) {
        LivingWorldsView view = (LivingWorldsView) XposedHelpers.getAdditionalInstanceField(
                engine, FIELD);
        if (view != null) {
            view.visible = visible;
            if (visible) {
                frames().post(view::redraw);
            }
        }
    }

    private static synchronized Handler frames() {
        if (frames == null) {
            HandlerThread thread = new HandlerThread("nlplus-living-worlds",
                    Process.THREAD_PRIORITY_DISPLAY);
            thread.start();
            frames = new Handler(thread.getLooper());
        }
        return frames;
    }

    /** A new frame from the page (frames thread). */
    private void onFrame() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            Image image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }
            Bitmap bitmap;
            HardwareBuffer buffer = image.getHardwareBuffer();
            try {
                bitmap = buffer == null ? null : Bitmap.wrapHardwareBuffer(buffer,
                        ColorSpace.get(ColorSpace.Named.SRGB));
            } finally {
                if (buffer != null) {
                    buffer.close(); // The bitmap holds its own reference.
                }
            }
            if (bitmap == null) {
                image.close();
                fail("its frames cannot be drawn");
                return;
            }
            // The frame before last goes back to the page now; the last one may still be read
            // by the GPU from the previous draw.
            release(previousImage, previous);
            previousImage = shownImage;
            previous = shown;
            shownImage = image;
            shown = bitmap;
            if (visible) {
                draw();
            }
        } catch (Throwable t) {
            Hooks.log("living worlds: frame failed: " + t);
        } finally {
            lock.unlock();
        }
    }

    private void redraw() {
        lock.lock();
        try {
            if (!closed) {
                draw();
            }
        } catch (Throwable t) {
            Hooks.log("living worlds: redraw failed: " + t);
        } finally {
            lock.unlock();
        }
    }

    /**
     * A turn (main thread, from onSurfaceChanged): the last frame drawn at once for the new
     * size, before Android shows the wallpaper again. Should the frames thread be drawing, the
     * frame it draws next is framed for the new size anyway.
     */
    private void redrawNow() {
        boolean locked = false;
        try {
            locked = lock.tryLock(50, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!locked) {
            frames().post(this::redraw);
            return;
        }
        try {
            if (!closed) {
                draw();
            }
        } catch (Throwable t) {
            Hooks.log("living worlds: turn redraw failed: " + t);
        } finally {
            lock.unlock();
        }
    }

    /** The shown frame on the wallpaper's surface, framed as App.resize frames it (locked). */
    private void draw() {
        SurfaceHolder h = holder;
        Surface surface = h != null ? h.getSurface() : null;
        if (shown == null || surface == null || !surface.isValid()) {
            return;
        }
        Canvas canvas = surface.lockHardwareCanvas();
        drawn = surface;
        try {
            int width = canvas.getWidth();
            int height = canvas.getHeight();
            boolean cover = XposedHelpers.getBooleanField(engine, "myCoverMode");
            float sx = (float) width / SCENE_WIDTH;
            float sy = (float) height / SCENE_HEIGHT;
            float scale = cover ? Math.max(sx, sy) : Math.min(sx, sy);
            float across = 0.5f;
            if (cover) {
                Object key = XposedHelpers.getObjectField(engine, "mySceneKey");
                across = "feb".equals(key) ? 0.82f : "jul".equals(key) ? 0.9f
                        : "dec".equals(key) ? 0.8f : 0.5f;
            }
            float down = width > height ? store.get().livingWorldsLandscapeY / 100f : 0.5f;
            float left = (width - SCENE_WIDTH * scale) * across;
            float top = (height - SCENE_HEIGHT * scale) * down;
            dst.set(left, top, left + SCENE_WIDTH * scale, top + SCENE_HEIGHT * scale);
            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(shown, null, dst, paint);
            if (width != framedWidth || height != framedHeight) {
                framedWidth = width;
                framedHeight = height;
                Hooks.log("living worlds: framed for " + width + "x" + height + " at "
                        + String.format(java.util.Locale.ROOT, "%.2f", scale) + "x");
            }
        } finally {
            surface.unlockCanvasAndPost(canvas);
        }
    }

    /** Stops drawing, ends the display and frees the frames; Living Worlds destroys the page. */
    private void close() {
        boolean locked = false;
        try {
            // A draw in progress finishes first; never waited on for long (main thread).
            locked = lock.tryLock(150, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            closed = true;
            holder = null;
            reader.setOnImageAvailableListener(null, null);
            display.release(); // No more frames (Living Worlds' own release is then a no-op).
            if (locked) {
                freeFrames();
            } else {
                // The frames thread still holds them: it closes them when done.
                frames().post(this::closeLater);
            }
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    private void closeLater() {
        lock.lock();
        try {
            freeFrames();
        } finally {
            lock.unlock();
        }
    }

    /**
     * The frames, the reader, and the surface's GPU renderer (so Living Worlds' own drawing can
     * use the surface again should it take over); locked.
     */
    private void freeFrames() {
        release(previousImage, previous);
        release(shownImage, shown);
        previousImage = null;
        previous = null;
        shownImage = null;
        shown = null;
        reader.close();
        Surface surface = drawn;
        drawn = null;
        if (surface != null) {
            try {
                XposedHelpers.callMethod(surface, "hwuiDestroy"); // Hidden API.
            } catch (Throwable t) {
                Hooks.log("living worlds: surface renderer not released: " + t);
            }
        }
    }

    private static void release(Image image, Bitmap bitmap) {
        if (bitmap != null) {
            bitmap.recycle();
        }
        if (image != null) {
            image.close();
        }
    }

    /** This phone cannot draw the page's frames: Living Worlds' own drawing from now on. */
    private void fail(String why) {
        broken = true;
        Hooks.log("living worlds: " + why + "; its own drawing is used");
        Object e = engine;
        new Handler(android.os.Looper.getMainLooper()).post(Hooks.guard("living worlds own",
                () -> {
                    surfaceGoing(e);
                    if (XposedHelpers.getObjectField(e, "myHolder") != null) {
                        XposedHelpers.callMethod(e, "createPresentation");
                    }
                }));
    }
}
