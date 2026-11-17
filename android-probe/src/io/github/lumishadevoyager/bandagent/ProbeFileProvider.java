package io.github.lumishadevoyager.bandagent;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Shares only this APK's embedded test RPK using Android's temporary URI grant. */
public final class ProbeFileProvider extends ContentProvider {
    private File cached;
    @Override public boolean onCreate() { return true; }
    private synchronized File rpk(Uri uri) throws FileNotFoundException {
        if (!"/wearable.rpk".equals(uri.getPath())) throw new FileNotFoundException("Unknown probe file");
        if (cached != null) return cached;
        File file = new File(getContext().getCacheDir(), "wearable.rpk");
        try (InputStream input = getContext().getAssets().open("wearable.rpk"); FileOutputStream output = new FileOutputStream(file)) {
            byte[] bytes = new byte[8192]; int count;
            while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
        } catch (Exception error) { throw new FileNotFoundException("Build and embed the matching RPK first"); }
        cached = file;
        return file;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(rpk(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor cursor = new MatrixCursor(new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE });
        try { cursor.addRow(new Object[] { "wearable.rpk", rpk(uri).length() }); } catch (FileNotFoundException ignored) {}
        return cursor;
    }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
