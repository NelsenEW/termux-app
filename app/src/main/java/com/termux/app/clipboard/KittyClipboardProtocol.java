package com.termux.app.clipboard;

import android.util.Base64;
import com.termux.terminal.TerminalSession;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** OSC 5522 wire framing shared by clipboard reads, writes, and paste events. */
public final class KittyClipboardProtocol {
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    public static final int CHUNK_BYTES = 4096;
    private KittyClipboardProtocol() {}

    public static final class Packet {
        final Map<String, String> fields = new LinkedHashMap<>();
        final String payload;
        final String id;

        Packet(String request) {
            int separator = request.indexOf(';');
            String metadata = separator < 0 ? request : request.substring(0, separator);
            payload = separator < 0 ? "" : request.substring(separator + 1);
            for (String field : metadata.split(":")) {
                int equals = field.indexOf('=');
                if (equals > 0) fields.put(field.substring(0, equals), field.substring(equals + 1));
            }
            id = get("id").replaceAll("[^a-zA-Z0-9_+.-]", "");
        }

        String get(String key) { return fields.getOrDefault(key, ""); }
        String type() { return get("type"); }
        String suffix() { return id.isEmpty() ? "" : ":id=" + id; }
        String decoded(String key) { return utf8(decode(get(key))); }
        void status(TerminalSession session, String type, String status) {
            reply(session, "type=" + type + ":status=" + status + suffix(), "");
        }
    }

    static byte[] decode(String value) {
        if (value.length() % 4 != 0) throw new IllegalArgumentException("Invalid base64 length");
        int padding = value.endsWith("==") ? 2 : value.endsWith("=") ? 1 : 0;
        for (int i = 0; i < value.length() - padding; i++) {
            char c = value.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
                (c >= '0' && c <= '9') || c == '+' || c == '/'))
                throw new IllegalArgumentException("Invalid base64 character");
        }
        if (padding > 0 && value.length() < 4) throw new IllegalArgumentException("Invalid base64 padding");
        byte[] decoded = Base64.decode(value, Base64.NO_WRAP);
        if (!encode(decoded).equals(value)) throw new IllegalArgumentException("Invalid base64 padding");
        return decoded;
    }

    static String encode(byte[] value) { return Base64.encodeToString(value, Base64.NO_WRAP); }
    static String encode(String value) { return encode(value.getBytes(StandardCharsets.UTF_8)); }
    static String utf8(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
    static boolean validMime(String value) {
        return value.length() <= 255 && value.matches("[A-Za-z0-9!#$&^_.+*-]+/[A-Za-z0-9!#$&^_.+*-]+");
    }
    static void reply(TerminalSession session, String metadata, String payload) {
        session.write("\033]5522;" + metadata + (payload.isEmpty() ? "" : ";" + payload) + "\033\\");
    }
    static void data(TerminalSession session, Packet packet, String mime, byte[] bytes) {
        reply(session, "type=read:status=DATA:mime=" + encode(mime) + packet.suffix(), encode(bytes));
    }
}
