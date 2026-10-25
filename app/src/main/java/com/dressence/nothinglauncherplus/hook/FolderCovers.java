package com.dressence.nothinglauncherplus.hook;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
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
import android.widget.TextView;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.anim.AnimatedEmojiView;
import com.dressence.nothinglauncherplus.anim.DotEffect;
import com.dressence.nothinglauncherplus.anim.Motion;

import java.io.InputStream;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Draws a custom folder's cover (an image or an emoji) over the app previews of its Smart drawer
 * tile. The cover is a square centred in the preview area, so it never stretches, and images are
 * decoded at full resolution from the module's cover provider.
 */
final class FolderCovers {

    private static final String COVER_TAG = "nlplus_cover";
    private static final String PANEL_TAG = "nlplus_dot_panel";
    /** Tile corner radius relative to its smaller side, when its background doesn't say. */
    private static final float TILE_CORNER_RATIO = 0.085f;
    /** Cover side relative to the smaller side of the preview area. */
    private static final float COVER_SCALE = 0.86f;
    private static final float CORNER_RATIO = 0.22f;
    private static final float EMOJI_RATIO = 0.62f;

    /** The name display's LED colour. */
    private static final int LED_GREEN = 0xFF4CFF7A;

    /** The real colour of folder names hidden behind a dot panel (to restore and to light). */
    private static final Map<TextView, Integer> NAME_COLORS = new WeakHashMap<>();

    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getAllocationByteCount();
        }
    };

    /**
     * Shows {@code folder}'s cover on the tile, or restores the stock previews. Emoji covers play
     * their motion and effect when {@code animate} is set and are drawn as dots when
     * {@code dotMatrix} is; then the folder's {@code name} is lit on the same dot screen too.
     */
    void apply(FrameLayout tile, View previews, TextView name, Config.Folder folder,
            boolean animate, boolean dotMatrix) {
        CoverView cover = tile.findViewWithTag(COVER_TAG);
        DotPanel panel = tile.findViewWithTag(PANEL_TAG);
        boolean dots = folder != null && dotMatrix && folder.coverImageVersion == 0
                && !folder.coverText.isEmpty();
        if (panel != null && !dots) {
            panel.hide();
        }
        // In dots the name is drawn by the panel; the real one keeps its place, in clear ink
        // (not alpha 0: the launcher animates view alpha itself).
        if (dots && !NAME_COLORS.containsKey(name)) {
            NAME_COLORS.put(name, name.getCurrentTextColor());
            name.setTextColor(Color.TRANSPARENT);
        } else if (!dots && NAME_COLORS.containsKey(name)) {
            name.setTextColor(NAME_COLORS.remove(name));
        }
        if (folder == null || !folder.hasCover()) {
            if (cover != null) {
                cover.setVisibility(View.GONE);
            }
            previews.setVisibility(View.VISIBLE);
            return;
        }
        if (dots) {
            if (cover != null) {
                cover.setVisibility(View.GONE);
            }
            if (panel == null) {
                panel = new DotPanel(tile, previews, name);
                panel.setTag(PANEL_TAG);
                // Behind the name and everything else: the tile itself becomes the screen.
                tile.addView(panel, 0, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            previews.setVisibility(View.INVISIBLE);
            panel.show(folder.coverText,
                    animate ? Motion.parse(folder.coverMotion) : Motion.NONE,
                    animate ? DotEffect.parse(folder.coverEffect) : DotEffect.NONE,
                    DotEffect.anchor(folder.coverEffect), folder.id.hashCode());
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

    /**
     * The whole tile as a dot screen: unlit dots everywhere, the emoji lit in a square where the
     * app previews would be and the folder name lit where the name would be. Both follow the
     * hidden views' layout. Pressing the tile makes the emoji react.
     */
    private static final class DotPanel extends AnimatedEmojiView
            implements View.OnLayoutChangeListener {
        private final ViewGroup tile;
        private final View previews;
        private final TextView name;
        private final android.graphics.Rect square = new android.graphics.Rect();
        private final android.graphics.Rect card = new android.graphics.Rect();
        private final android.graphics.Rect label = new android.graphics.Rect();
        private String shownLabel;
        private boolean pressed;

        DotPanel(ViewGroup tile, View previews, TextView name) {
            super(tile.getContext());
            this.tile = tile;
            this.previews = previews;
            this.name = name;
            setDotMatrix(true);
            // Follows the tile's pressed state, so a touch on the tile reaches drawableStateChanged.
            setDuplicateParentStateEnabled(true);
            previews.addOnLayoutChangeListener(this);
            name.addOnLayoutChangeListener(this);
        }

        void show(String text, Motion motion, DotEffect effect, float[] anchor, long seed) {
            setEmoji(text, motion, effect, anchor, seed);
            setVisibility(VISIBLE);
            shownLabel = null;
            place();
        }

        void hide() {
            setEmoji("", Motion.NONE, 0);
            setDotLabel(null, 0, null, null, 0);
            shownLabel = null;
            setVisibility(GONE);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            boolean now = isPressed();
            if (now && !pressed) {
                react();
            }
            pressed = now;
        }

        @Override
        public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or,
                int ob) {
            place();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            place();
        }

        /**
         * The emoji goes where the image cover would (centred in the hidden preview grid); the
         * dot screen fills the card the launcher actually draws, taken from the tile's outline
         * (the shape it clips and casts shadows with), which can sit inside the view's bounds.
         */
        private void place() {
            int pw = previews.getWidth();
            int ph = previews.getHeight();
            if (pw == 0 || ph == 0 || getWidth() == 0) {
                return;
            }
            int side = (int) (Math.min(pw, ph) * COVER_SCALE);
            int cx = previews.getLeft() + pw / 2 - getLeft();
            int cy = previews.getTop() + ph / 2 - getTop();
            square.set(cx - side / 2, cy - side / 2, cx - side / 2 + side, cy - side / 2 + side);
            float corner = cardOutline(card);
            card.offset(-getLeft(), -getTop()); // Tile to panel coordinates (tile padding).
            card.intersect(0, 0, getWidth(), getHeight());
            setDotPanel(square, card, corner);
            placeLabel();
        }

        /** Lights the folder name where the (hidden) name view sits, in its colour. */
        private void placeLabel() {
            if (name.getWidth() == 0 || name.getParent() == null) {
                return;
            }
            label.set(0, 0, name.getWidth(), name.getHeight());
            try {
                tile.offsetDescendantRectToMyCoords(name, label);
            } catch (IllegalArgumentException e) {
                return; // The name is no longer inside this tile.
            }
            label.offset(-getLeft(), -getTop());
            String text = String.valueOf(name.getText());
            // The name display is a green LED panel, whatever the launcher's text colour.
            int ink = LED_GREEN;
            String key = text + label.toShortString() + ink;
            if (key.equals(shownLabel)) {
                return; // Unchanged: keep a scrolling name where it is.
            }
            shownLabel = key;
            // Nothing's own NDot at the launcher's own name size; dots if the phone lacks it.
            setDotLabel(text, ink, label, NdotFont.get(getContext()), name.getTextSize());
        }

        /** Sets {@code out} to the tile's card in tile coordinates; returns its corner radius. */
        private float cardOutline(android.graphics.Rect out) {
            out.set(0, 0, tile.getWidth(), tile.getHeight());
            float fallback = Math.min(tile.getWidth(), tile.getHeight()) * TILE_CORNER_RATIO;
            android.view.ViewOutlineProvider provider = tile.getOutlineProvider();
            if (provider == null) {
                return fallback;
            }
            android.graphics.Outline outline = new android.graphics.Outline();
            try {
                provider.getOutline(tile, outline);
            } catch (RuntimeException e) {
                return fallback;
            }
            android.graphics.Rect shape = new android.graphics.Rect();
            if (!outline.getRect(shape) || shape.isEmpty()) {
                return fallback; // No outline, or not a rounded rectangle.
            }
            out.set(shape);
            float radius = outline.getRadius();
            return radius > 0 ? radius : fallback;
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
