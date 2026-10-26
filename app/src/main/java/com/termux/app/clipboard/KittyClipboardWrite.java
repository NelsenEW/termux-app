package com.termux.app.clipboard;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.net.Uri;
import com.termux.terminal.TerminalSession;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One streaming clipboard write transaction, owned by the clipboard IO worker. */
final class KittyClipboardWrite {
    private final Context context;
    private final TerminalSession session;
    private final KittyClipboardProtocol.Packet initial;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, String> aliases = new LinkedHashMap<>();
    private long bytes;
    private String currentMime;
    private boolean aborted;

    private static final class Entry {
        final File file;
        final FileOutputStream stream;
        String carry = "";
        boolean padded;
        Entry(File file) throws IOException { this.file = file; stream = new FileOutputStream(file); }
    }

    KittyClipboardWrite(Context context, TerminalSession session, KittyClipboardProtocol.Packet initial) {
        this.context = context; this.session = session; this.initial = initial;
    }
    void fail(String status) {
        if (aborted) return;
        aborted = true;
        initial.status(session, "write", status);
        discard();
    }
    void discard() {
        for (Entry entry : entries.values()) {
            try { entry.stream.close(); } catch (IOException ignored) {}
            entry.file.delete();
        }
        entries.clear();
    }
    boolean accept(KittyClipboardProtocol.Packet packet) {
        if (aborted) return false;
        try {
            if ("walias".equals(packet.type())) {
                String target = packet.decoded("mime");
                if (!KittyClipboardProtocol.validMime(target)) throw new IllegalArgumentException();
                for (String alias : KittyClipboardProtocol.utf8(KittyClipboardProtocol.decode(packet.payload)).trim().split("\\s+")) {
                    if (!KittyClipboardProtocol.validMime(alias)) throw new IllegalArgumentException();
                    aliases.put(alias, target);
                }
                if (aliases.size() > 128) throw new IllegalArgumentException();
                return false;
            }
            if (!"wdata".equals(packet.type())) return false;
            if (packet.get("mime").isEmpty() && packet.payload.isEmpty()) return true;
            String mime = packet.decoded("mime");
            if (!KittyClipboardProtocol.validMime(mime) || !packet.payload.matches("[A-Za-z0-9+/=]*"))
                throw new IllegalArgumentException();
            if (!mime.equals(currentMime)) {
                if (currentMime != null && !entries.get(currentMime).carry.isEmpty()) throw new IllegalArgumentException();
                if (entries.containsKey(mime)) throw new IllegalArgumentException();
                currentMime = mime;
            }
            Entry entry = entries.get(mime);
            if (entry == null) {
                if (entries.size() >= 128) throw new IllegalArgumentException();
                entry = new Entry(new File(KittyClipboardProvider.directory(context), UUID.randomUUID() + ".bin"));
                entries.put(mime, entry);
            }
            if (entry.padded && !packet.payload.isEmpty()) throw new IllegalArgumentException();
            String encoded = entry.carry + packet.payload;
            int complete = encoded.length() / 4 * 4;
            entry.carry = encoded.substring(complete);
            byte[] decoded = KittyClipboardProtocol.decode(encoded.substring(0, complete));
            entry.padded |= complete > 0 && encoded.substring(0, complete).contains("=");
            if (entry.padded && !entry.carry.isEmpty()) throw new IllegalArgumentException();
            bytes += decoded.length;
            if (bytes > KittyClipboardProtocol.MAX_BYTES) { fail("EFBIG"); return false; }
            entry.stream.write(decoded);
        } catch (IllegalArgumentException error) { fail("EINVAL"); }
        catch (IOException error) { fail("EIO"); }
        return false;
    }
    ClipData finish() throws IOException {
        if (aborted) return null;
        for (Entry entry : entries.values()) {
            if (!entry.carry.isEmpty()) { fail("EINVAL"); return null; }
            entry.stream.close();
        }
        Map<String, Entry> all = new LinkedHashMap<>(entries);
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            Entry target = entries.get(alias.getValue());
            if (target == null) { fail("EINVAL"); return null; }
            all.put(alias.getKey(), target);
        }
        if (all.isEmpty()) return ClipData.newPlainText("Terminal clipboard", "");
        ClipData result = null;
        for (Map.Entry<String, Entry> value : all.entrySet()) {
            String mime = value.getKey();
            File file = value.getValue().file;
            ClipData.Item item;
            // Binder cannot carry large strings. Larger text also uses a streamed content URI.
            if (mime.equals("text/plain") && file.length() <= 32768) {
                item = new ClipData.Item(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            } else {
                Uri uri = KittyClipboardProvider.uri(context, file, value.getKey());
                item = new ClipData.Item(uri);
            }
            if (result == null) result = new ClipData(new ClipDescription("Terminal clipboard", all.keySet().toArray(new String[0])), item);
            else result.addItem(item);
        }
        return result;
    }
    void done() { initial.status(session, "write", "DONE"); }
}
