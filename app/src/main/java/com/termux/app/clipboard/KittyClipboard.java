package com.termux.app.clipboard;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.termux.app.TermuxActivity;
import com.termux.terminal.TerminalSession;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** OSC 5522 clipboard access and user-initiated rich paste for this activity's terminal sessions. */
public final class KittyClipboard {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private final TermuxActivity activity;
    // Accessed only on the UI thread. Write transactions are accessed only on IO.
    private final Map<TerminalSession, Access> access = new WeakHashMap<>();
    private final Map<TerminalSession, KittyClipboardWrite> writes = new WeakHashMap<>();
    private static final class Access {
        String pastePassword = "";
        long pasteExpiry;
        ClipData pasted;
        boolean reading;
    }
    public KittyClipboard(TermuxActivity activity) { this.activity = activity; }
    private ClipboardManager clipboard() { return (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE); }
    private Access access(TerminalSession session) { return access.computeIfAbsent(session, unused -> new Access()); }
    private boolean foreground() { return activity.isVisible() && !activity.isFinishing(); }

    public void handle(TerminalSession session, String request) {
        KittyClipboardProtocol.Packet packet = new KittyClipboardProtocol.Packet(request);
        if (!foreground()) { packet.status(session, packet.type().equals("read") ? "read" : "write", "EPERM"); return; }
        if (packet.type().equals("read")) read(session, packet);
        else if (packet.type().equals("write") || packet.type().equals("wdata") || packet.type().equals("walias")) {
            IO.execute(() -> {
                if (packet.type().equals("write")) {
                    KittyClipboardWrite old = writes.remove(session);
                    if (old != null) old.discard();
                    KittyClipboardWrite transaction = new KittyClipboardWrite(activity, session, packet);
                    writes.put(session, transaction);
                    if (!packet.get("loc").isEmpty() && !packet.get("loc").equals("clipboard")) transaction.fail("ENOSYS");
                } else {
                    KittyClipboardWrite transaction = writes.get(session);
                    if (transaction == null) return;
                    if (transaction.accept(packet)) {
                        try {
                            ClipData clip = transaction.finish();
                            if (clip == null) return;
                            MAIN.post(() -> {
                                if (!foreground()) { IO.execute(() -> transaction.fail("EPERM")); return; }
                                try { clipboard().setPrimaryClip(clip); transaction.done(); }
                                catch (Exception error) { IO.execute(() -> transaction.fail("EIO")); }
                            });
                            writes.remove(session);
                        } catch (IOException error) { transaction.fail("EIO"); }
                    }
                }
            });
        }
    }

    private void read(TerminalSession session, KittyClipboardProtocol.Packet packet) {
        if (!packet.get("loc").isEmpty() && !packet.get("loc").equals("clipboard")) { packet.status(session, "read", "ENOSYS"); return; }
        String wanted;
        String password;
        String name;
        try {
            wanted = KittyClipboardProtocol.utf8(KittyClipboardProtocol.decode(packet.payload)).trim();
            password = packet.decoded("pw");
            name = packet.decoded("name");
        } catch (IllegalArgumentException error) { return; }
        if (wanted.isEmpty()) return;
        Access state = access(session);
        if (state.reading) { packet.status(session, "read", "EBUSY"); return; }
        boolean listing = wanted.equals(".");
        boolean pasteAuthorized = state.pasteExpiry > SystemClock.elapsedRealtime() && state.pasted != null &&
            ((!password.isEmpty() && !name.isEmpty() && password.equals(state.pastePassword)) ||
             (password.isEmpty() && (listing || Arrays.stream(wanted.split("\\s+")).allMatch(type -> type.startsWith("image/")))));
        ClipData clip;
        if (pasteAuthorized) {
            // The keyboard image is a private paste snapshot, not a new system clipboard entry.
            // Listing and data must describe the same snapshot even if the clipboard changes.
            clip = state.pasted;
            if (!listing) {
                state.pastePassword = ""; state.pasteExpiry = 0; state.pasted = null;
            }
        } else {
            try { clip = clipboard().getPrimaryClip(); }
            catch (Exception error) { packet.status(session, "read", "EPERM"); return; }
        }
        // This personal build permits OSC 5522 clipboard reads without a dialog.
        // handle() still requires a visible activity; preserve explicit paste snapshots.
        state.reading = true;
        performRead(session, packet, wanted, clip, state);
    }

    private void performRead(TerminalSession session, KittyClipboardProtocol.Packet packet, String wanted, ClipData clip, Access state) {
        IO.execute(() -> {
            try {
                Map<String, KittyClipboardData.Source> sources = KittyClipboardData.sources(activity, clip);
                if (wanted.equals(".")) {
                    packet.status(session, "read", "OK");
                    byte[] listing = String.join(" ", sources.keySet()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    for (int offset = 0; offset < listing.length; offset += KittyClipboardProtocol.CHUNK_BYTES)
                        KittyClipboardProtocol.data(session, packet, ".", Arrays.copyOfRange(listing, offset,
                            Math.min(listing.length, offset + KittyClipboardProtocol.CHUNK_BYTES)));
                } else {
                    // Open streams before OK so a denied URI grant is reported as EPERM.
                    boolean opened = false;
                    long total = 0;
                    Set<String> seen = new HashSet<>();
                    for (String mime : wanted.split("\\s+")) {
                        if (!seen.add(mime) || !sources.containsKey(mime)) continue;
                        try (InputStream input = sources.get(mime).open()) {
                            if (!opened) { packet.status(session, "read", "OK"); opened = true; }
                            byte[] buffer = new byte[KittyClipboardProtocol.CHUNK_BYTES];
                            int count;
                            while ((count = input.read(buffer)) != -1) {
                                if (count == 0) continue;
                                total += count;
                                if (total > KittyClipboardProtocol.MAX_BYTES) throw new IOException("Clipboard limit exceeded");
                                KittyClipboardProtocol.data(session, packet, mime, Arrays.copyOf(buffer, count));
                            }
                        }
                    }
                    if (!opened) packet.status(session, "read", "OK");
                }
                packet.status(session, "read", "DONE");
            } catch (SecurityException error) { packet.status(session, "read", "EPERM"); }
            catch (Exception error) { packet.status(session, "read", "ENOSYS"); }
            finally { MAIN.post(() -> state.reading = false); }
        });
    }

    public void paste(TerminalSession session) {
        if (!foreground() || session == null || !session.isRunning()) return;
        ClipData clip;
        try { clip = clipboard().getPrimaryClip(); } catch (Exception error) { return; }
        paste(session, clip);
    }

    /** Paste the picker result directly, independent of subsequent clipboard changes. */
    public void paste(TerminalSession session, ClipData clip) {
        if (!foreground() || session == null || !session.isRunning() || clip == null || clip.getItemCount() == 0) return;
        if (session.getEmulator().isClipboardPasteMode()) { pasteEvent(session, clip); return; }
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            Uri uri = item.getUri();
            String mime = uri == null ? null : activity.getContentResolver().getType(uri);
            if (mime != null && mime.startsWith("image/")) { acceptImage(session, uri, mime, () -> {}); return; }
        }
        CharSequence text = clip.getItemAt(0).coerceToText(activity);
        if (text != null) session.getEmulator().paste(text.toString());
    }

    private void pasteEvent(TerminalSession session, ClipData clip) {
        Access state = access(session);
        state.pastePassword = UUID.randomUUID().toString();
        state.pasteExpiry = SystemClock.elapsedRealtime() + 60000;
        state.pasted = clip;
        String password = KittyClipboardProtocol.encode(state.pastePassword);
        IO.execute(() -> {
            try {
                String types = String.join(" ", KittyClipboardData.sources(activity, clip).keySet());
                String suffix = ":pw=" + password;
                KittyClipboardProtocol.reply(session, "type=read:status=OK" + suffix, "");
                KittyClipboardProtocol.reply(session, "type=read:status=DATA:mime=Lg==" + suffix, KittyClipboardProtocol.encode(types));
                KittyClipboardProtocol.reply(session, "type=read:status=DONE" + suffix, "");
            } catch (Exception error) { activity.runOnUiThread(() -> activity.showToast("Clipboard image is unavailable", true)); }
        });
    }

    public boolean acceptImage(TerminalSession session, Uri uri, String offeredMime, Runnable release) {
        if (!foreground() || session == null || !session.isRunning()) { release.run(); return false; }
        IO.execute(() -> {
            try {
                String resolved = activity.getContentResolver().getType(uri);
                String mime = resolved == null ? offeredMime : resolved;
                if (mime == null || !mime.startsWith("image/") || !KittyClipboardProtocol.validMime(mime)) throw new IOException("Not an image");
                File file = KittyClipboardData.copy(activity, uri, mime);
                Uri cached = KittyClipboardProvider.uri(activity, file, mime);
                ClipData clip = new ClipData("Pasted image", new String[]{mime}, new ClipData.Item(cached));
                MAIN.post(() -> {
                    if (!foreground() || !session.isRunning()) return;
                    try {
                        if (session.getEmulator().isClipboardPasteMode()) pasteEvent(session, clip);
                        else {
                            // Legacy tmux can bind Ctrl-V to an OSC 5522 receiver for its active agent pane.
                            Access state = access(session);
                            state.pasted = clip; state.pastePassword = UUID.randomUUID().toString();
                            state.pasteExpiry = SystemClock.elapsedRealtime() + 60000;
                            session.write("\026");
                        }
                    } catch (Exception error) { activity.showToast("Unable to paste the clipboard image", true); }
                });
            } catch (Exception error) { MAIN.post(() -> activity.showToast("Unable to read the pasted image", true)); }
            finally { MAIN.post(release); }
        });
        return true;
    }
}
