package com.dressence.nothinglauncherplus.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.CoverProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Turns a picked image into a folder cover: centre-cropped square, stored as PNG. */
final class Covers {

    /** Stored side length; well above any drawer tile, so covers stay sharp. */
    static final int SIZE = 1024;

    private Covers() {
    }

    /** Writes the cover file; call {@link #useImage} on the main thread afterwards. */
    static void saveImage(Context context, String folderId, Uri source) throws IOException {
        Bitmap decoded = ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(context.getContentResolver(), source),
                (decoder, info, src) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    // Decode no larger than needed: the short side ends up at SIZE at most.
                    int shortSide = Math.min(info.getSize().getWidth(), info.getSize().getHeight());
                    if (shortSide > SIZE) {
                        float scale = (float) SIZE / shortSide;
                        decoder.setTargetSize(
                                Math.round(info.getSize().getWidth() * scale),
                                Math.round(info.getSize().getHeight() * scale));
                    }
                });
        int side = Math.min(decoded.getWidth(), decoded.getHeight());
        Bitmap square = Bitmap.createBitmap(decoded,
                (decoded.getWidth() - side) / 2, (decoded.getHeight() - side) / 2, side, side);

        File file = CoverProvider.coverFile(context, folderId);
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            // PNG keeps transparency of icon-style covers.
            square.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    static void useImage(Config.Folder folder) {
        folder.coverText = "";
        folder.coverMotion = "";
        folder.coverImageVersion = System.currentTimeMillis();
    }

    static void setText(Context context, Config.Folder folder, String text, String motion) {
        deleteImage(context, folder);
        folder.coverText = text;
        folder.coverMotion = motion;
    }

    static void clear(Context context, Config.Folder folder) {
        deleteImage(context, folder);
        folder.coverText = "";
        folder.coverMotion = "";
    }

    static void deleteImage(Context context, Config.Folder folder) {
        //noinspection ResultOfMethodCallIgnored
        CoverProvider.coverFile(context, folder.id).delete();
        folder.coverImageVersion = 0;
    }
}
