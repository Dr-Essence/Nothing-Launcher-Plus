package com.dressence.nothinglauncherplus;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.regex.Pattern;

/**
 * Serves folder cover images to the launcher, which cannot read this app's private files.
 * Read-only; {@code content://<authority>/<folderId>}.
 */
public final class CoverProvider extends ContentProvider {

    private static final Pattern FOLDER_ID = Pattern.compile("[a-z0-9]{1,32}");

    public static File coverFile(Context context, String folderId) {
        return new File(new File(context.getFilesDir(), "covers"), folderId + ".png");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String id = uri.getLastPathSegment();
        if (!"r".equals(mode) || id == null || !FOLDER_ID.matcher(id).matches()) {
            throw new FileNotFoundException(uri.toString());
        }
        return ParcelFileDescriptor.open(coverFile(getContext(), id),
                ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
