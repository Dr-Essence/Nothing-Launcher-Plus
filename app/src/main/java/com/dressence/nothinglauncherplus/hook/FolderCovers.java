package com.dressence.nothinglauncherplus.hook;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.net.Uri;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.anim.AnimatedEmojiView;
import com.dressence.nothinglauncherplus.anim.Motion;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Draws a custom folder's cover (an image or an emoji) over the app previews of its Smart drawer
 * tile. The cover is a square centred in the preview area, so it never stretches, and images are
 * decoded at full resolution from the module's cover provider.
 */
final class FolderCovers {

    private static final String COVER_TAG = "nlplus_cover";
    /** Cover side relative to the smaller side of the preview area. */
    private static final float COVER_SCALE = 0.86f;
    private static final float CORNER_RATIO = 0.22f;
    private static final float EMOJI_RATIO = 0.62f;

    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getAllocationByteCount();
        }
    };

    /**
     * Shows {@code folder}'s cover on the tile, or restores the stock previews. Emoji covers play
     * their motion when {@code animate} is set and are drawn as dots when {@code dotMatrix} is.
     */
    void apply(FrameLayout tile, View previews, Config.Folder folder, boolean animate,
            boolean dotMatrix) {
        CoverView cover = tile.findViewWithTag(COVER_TAG);
        if (folder == null || !folder.hasCover()) {
            if (cover != null) {
                cover.setVisibility(View.GONE);
            }
            previews.setVisibility(View.VISIBLE);
            return;
        }
        if (cover == null) {
            cover = new CoverView(tile.getContext());
            cover.setTag(COVER_TAG);
            // Same box as the preview grid, so the folder name stays where it is.
            tile.addView(cover, new FrameLayout.LayoutParams(
                    (FrameLayout.LayoutParams) previews.getLayoutParams()));
        }
        previews.setVisibility(View.INVISIBLE);
        cover.setVisibility(View.VISIBLE);
        if (folder.coverImageVersion != 0) {
            showImage(cover, folder.coverImageUri());
        } else {
            cover.showText(folder.coverText,
                    animate ? Motion.parse(folder.coverMotion) : Motion.NONE, folder.id.hashCode(),
                    dotMatrix);
        }
    }

    private void showImage(CoverView cover, Uri uri) {
        String key = uri.toString();
        cover.pendingKey = key;
        Bitmap cached = cache.get(key);
        if (cached != null) {
            cover.showImage(cached);
            return;
        }
        cover.showImage(null);
        Context context = cover.getContext().getApplicationContext();
        loader.execute(() -> {
            Bitmap bitmap = decode(context, uri);
            if (bitmap == null) {
                return;
            }
            cache.put(key, bitmap);
            cover.post(() -> {
                // The tile may have been recycled for another folder meanwhile.
                if (key.equals(cover.pendingKey)) {
                    cover.showImage(bitmap);
                }
            });
        });
    }

    private static Bitmap decode(Context context, Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            return in != null ? BitmapFactory.decodeStream(in) : null;
        } catch (Exception e) {
            Hooks.log("cover " + uri + " unavailable: " + e);
            return null;
        }
    }

    /** Lays out its single visible child as a centred square. */
    private static final class CoverView extends FrameLayout {
        private final ImageView image;
        private final AnimatedEmojiView text;
        String pendingKey;

        CoverView(Context context) {
            super(context);
            image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setClipToOutline(true);
            image.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            view.getWidth() * CORNER_RATIO);
                }
            });
            text = new AnimatedEmojiView(context);
            // Let a hopping emoji leave the square; the tile itself still clips it.
            setClipChildren(false);
            addView(image, new LayoutParams(0, 0, Gravity.CENTER));
            addView(text, new LayoutParams(0, 0, Gravity.CENTER));
        }

        void showImage(Bitmap bitmap) {
            text.setEmoji("", Motion.NONE, 0);
            image.setImageBitmap(bitmap);
            image.setVisibility(VISIBLE);
            text.setVisibility(GONE);
        }

        void showText(String value, Motion motion, long seed, boolean dotMatrix) {
            pendingKey = null;
            text.setDotMatrix(dotMatrix);
            text.setEmoji(value, motion, seed);
            text.setVisibility(VISIBLE);
            image.setVisibility(GONE);
            image.setImageBitmap(null);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int height = MeasureSpec.getSize(heightSpec);
            int side = (int) (Math.min(width, height) * COVER_SCALE);
            size(image, side);
            size(text, side);
            float textSize = side * EMOJI_RATIO;
            if (text.getTextSize() != textSize) {
                text.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
            }
            super.onMeasure(widthSpec, heightSpec);
        }

        private static void size(View child, int side) {
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp.width != side || lp.height != side) {
                lp.width = side;
                lp.height = side;
            }
        }
    }
}
