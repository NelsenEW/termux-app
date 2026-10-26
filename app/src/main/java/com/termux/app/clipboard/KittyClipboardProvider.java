package com.termux.app.clipboard;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Read-only, URI-granted access to clipboard files. No exported filesystem access. */
public final class KittyClipboardProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    static File directory(android.content.Context context) {
        File directory = new File(context.getCacheDir(), "kitty-clipboard");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Clipboard cache unavailable");
        return directory;
    }
    static Uri uri(android.content.Context context, File file, String mime) throws IOException {
        File type = new File(file.getPath() + ".mime");
        if (!type.exists()) Files.write(type.toPath(), mime.getBytes(StandardCharsets.UTF_8));
        return Uri.parse("content://" + context.getPackageName() + ".clipboard/" + file.getName())
            .buildUpon().appendQueryParameter("mime", mime).build();
    }
    private File file(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (uri.getPathSegments().size() != 1 || name == null || !name.matches("[a-zA-Z0-9_.-]+") || name.endsWith(".mime"))
            throw new FileNotFoundException("Invalid clipboard URI");
        File file = new File(directory(getContext()), name);
        if (!file.isFile()) throw new FileNotFoundException("Clipboard data no longer available");
        return file;
    }
    @Override public String getType(Uri uri) {
        String declared = uri.getQueryParameter("mime");
        if (declared != null && KittyClipboardProtocol.validMime(declared)) return declared;
        try {
            return new String(Files.readAllBytes(new File(file(uri).getPath() + ".mime").toPath()), StandardCharsets.UTF_8);
        } catch (IOException error) { return "application/octet-stream"; }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Clipboard provider is read-only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File file = file(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
                else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
            }
            cursor.addRow(row);
            return cursor;
        } catch (IOException error) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
