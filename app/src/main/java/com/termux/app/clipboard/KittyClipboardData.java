package com.termux.app.clipboard;

import android.content.ClipData;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Android clipboard formats exposed as streams; binary data never crosses Binder as strings. */
final class KittyClipboardData {
    interface Source { InputStream open() throws IOException; }
    private KittyClipboardData() {}
    private static Source text(String value) {
        return () -> new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
    static Map<String, Source> sources(Context context, ClipData clip) {
        Map<String, Source> result = new LinkedHashMap<>();
        if (clip == null) return result;
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            if (item.getText() != null) result.putIfAbsent("text/plain", text(item.getText().toString()));
            if (item.getHtmlText() != null) result.putIfAbsent("text/html", text(item.getHtmlText()));
            Uri uri = item.getUri();
            if (uri == null || !"content".equals(uri.getScheme())) continue;
            String mime = context.getContentResolver().getType(uri);
            if (mime == null && clip.getDescription().getMimeTypeCount() == 1)
                mime = clip.getDescription().getMimeType(0);
            if (mime != null && KittyClipboardProtocol.validMime(mime) && !mime.contains("*")) {
                result.putIfAbsent(mime, () -> {
                    InputStream input = context.getContentResolver().openInputStream(uri);
                    if (input == null) throw new IOException("Clipboard data unavailable");
                    return input;
                });
                if (mime.matches("image/(?:jpeg|jpg|webp|gif|bmp|x-ms-bmp|heic|heif)"))
                    result.putIfAbsent("image/png", () -> png(context, uri));
            }
        }
        return result;
    }
    static File copy(Context context, Uri uri, String mime) throws IOException {
        String extension = "image/png".equals(mime) ? ".png" : "image/jpeg".equals(mime) ? ".jpg" : ".bin";
        File file = new File(KittyClipboardProvider.directory(context), UUID.randomUUID() + extension);
        boolean complete = false;
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(file)) {
            if (input == null) throw new IOException("Image read grant unavailable");
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > KittyClipboardProtocol.MAX_BYTES) throw new IOException("Image exceeds clipboard limit");
                output.write(buffer, 0, count);
            }
            complete = true;
        } finally { if (!complete) file.delete(); }
        return file;
    }
    private static InputStream png(Context context, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(input, null, bounds); }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || (long) bounds.outWidth * bounds.outHeight > 16777216)
            throw new IOException("Clipboard image cannot be converted to PNG");
        Bitmap bitmap;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(input); }
        if (bitmap == null) throw new IOException("Invalid clipboard image");
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("PNG encoding failed");
            return new ByteArrayInputStream(output.toByteArray());
        } finally { bitmap.recycle(); }
    }
}
