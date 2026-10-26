package com.termux.app;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Base64;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Map;

/** Owner-granted, private diagnostics requests. No listener or startup command. */
public final class KittyDiagnostics {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private KittyDiagnostics() {}

    static boolean handle(TermuxActivity activity, Intent intent) {
        if (intent == null || !"com.termyx.action.DIAGNOSTICS".equals(intent.getAction())) return false;
        try {
            Uri uri = intent.getData();
            if (uri == null || !"content".equals(uri.getScheme()) || !"com.termux.documents".equals(uri.getAuthority()))
                throw new SecurityException("A private original-Termux request is required.");
            String document = DocumentsContract.getDocumentId(uri);
            if (document.contains("/../") || !document.endsWith(".json") ||
                !(document.startsWith("/data/data/com.termux/files/home/.local/share/termux-kitty/requests/") ||
                  document.startsWith("/data/user/0/com.termux/files/home/.local/share/termux-kitty/requests/")))
                throw new SecurityException("The request must be in the private diagnostics folder.");
            String expected = intent.getStringExtra("com.termyx.diagnostics.sha256");
            byte[] data;
            try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new SecurityException("The request read grant is missing.");
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 65536) throw new SecurityException("Request too large.");
                    output.write(buffer, 0, count);
                }
                data = output.toByteArray();
            }
            String actual = KittyNativeRuntime.hex(MessageDigest.getInstance("SHA-256").digest(data));
            if (expected == null || !actual.equalsIgnoreCase(expected)) throw new SecurityException("Request checksum mismatch.");
            JSONObject request = new JSONObject(new String(data, StandardCharsets.UTF_8));
            if (!request.getString("token").matches("[0-9a-f]{32}") || request.getInt("port") < 1024 || request.getInt("port") > 65535)
                throw new SecurityException("Invalid local response destination.");
            // Give the terminal view time to bind and lay out after a cold activity start.
            MAIN.postDelayed(() -> execute(activity, request), 700);
        } catch (Exception error) {
            android.widget.Toast.makeText(activity, error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
        return true;
    }

    private static void execute(TermuxActivity activity, JSONObject request) {
        TerminalSession previous = activity.getCurrentSession();
        try {
            String action = request.optString("mode", "inspect");
            if ("cleanup".equals(action)) {
                TermuxService service = activity.getTermuxService();
                if (service == null) throw new IllegalStateException("Terminal service is not connected.");
                TermuxShellManager manager = TermuxShellManager.getShellManager();
                if (manager != null) for (TermuxSession item : new ArrayList<>(manager.mTermuxSessions)) {
                    TerminalSession terminal = item.getTerminalSession();
                    if ("Diagnostics".equals(terminal.mSessionName) && !terminal.isRunning())
                        service.onTermuxSessionExited(item);
                }
                restoreTerminal(activity, service, previous, null);
            } else if ("input".equals(action)) {
                if (previous == null) throw new IllegalStateException("No current terminal.");
                previous.write(request.getString("input"));
            } else if ("command".equals(action)) {
                TermuxService service = activity.getTermuxService();
                if (service == null) throw new IllegalStateException("Terminal service is not connected.");
                TermuxSession created = service.createTermuxSession(
                    TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash",
                    new String[]{"--noprofile", "--norc", "-c", request.getString("command")}, null,
                    TermuxConstants.TERMUX_HOME_DIR_PATH, false, "Diagnostics");
                if (created == null) throw new IllegalStateException("The diagnostic command could not start.");
                TerminalSession terminal = created.getTerminalSession();
                TerminalEmulator reference = previous == null ? null : previous.getEmulator();
                terminal.updateSize(reference == null ? 80 : reference.mColumns,
                    reference == null ? 24 : reference.mRows,
                    reference == null ? 12 : reference.getCellWidthPixels(),
                    reference == null ? 24 : reference.getCellHeightPixels());
                if (request.optBoolean("show", false)) activity.getTerminalView().attachSession(terminal);
                if (request.has("input")) MAIN.postDelayed(() -> terminal.write(request.optString("input")),
                    Math.max(500, Math.min(30000, request.optInt("input_delay_ms", 2000))));
                MAIN.postDelayed(() -> {
                    deliver(activity, request, terminal, previous);
                    if (!request.optBoolean("keep", false)) {
                        // Removing a still-running session is ignored by TermuxSession.finish().
                        // Process its owned command result before its asynchronous exit callback.
                        created.killIfExecuting(activity, true);
                        if (service.getIndexOfSession(terminal) >= 0 && !terminal.isRunning())
                            service.onTermuxSessionExited(created);
                        restoreTerminal(activity, service, previous, terminal);
                    }
                }, Math.max(500, Math.min(60000, request.optInt("wait_ms", 4000))));
                return;
            } else if (!"inspect".equals(action)) throw new IllegalArgumentException("Unknown diagnostics mode.");
            MAIN.postDelayed(() -> deliver(activity, request, activity.getCurrentSession(), previous),
                Math.max(0, Math.min(10000, request.optInt("wait_ms", 0))));
        } catch (Exception error) {
            JSONObject failure = new JSONObject();
            try { failure.put("error", error.toString()); } catch (Exception ignored) {}
            new Thread(() -> send(request, failure), "diagnostics-error").start();
        }
    }

    private static void restoreTerminal(TermuxActivity activity, TermuxService service,
                                        TerminalSession previous, TerminalSession diagnostic) {
        TerminalSession visible = activity.getCurrentSession();
        if (visible != null && visible != diagnostic && visible.isRunning() &&
            service.getIndexOfSession(visible) >= 0) return;
        TerminalSession replacement = previous;
        if (replacement == null || !replacement.isRunning() || service.getIndexOfSession(replacement) < 0) {
            replacement = null;
            TermuxShellManager manager = TermuxShellManager.getShellManager();
            if (manager != null) for (TermuxSession item : new ArrayList<>(manager.mTermuxSessions)) {
                TerminalSession candidate = item.getTerminalSession();
                if (candidate != diagnostic && candidate.isRunning() &&
                    !"Diagnostics".equals(candidate.mSessionName)) replacement = candidate;
            }
        }
        if (replacement == null) {
            TermuxSession normal = service.createTermuxSession(null, null, null,
                TermuxConstants.TERMUX_HOME_DIR_PATH, false, null);
            if (normal != null) replacement = normal.getTerminalSession();
        }
        if (replacement != null) activity.getTermuxTerminalSessionClient().setCurrentSession(replacement);
    }

    private static JSONObject session(TerminalSession terminal) throws Exception {
        JSONObject item = new JSONObject();
        item.put("pid", terminal.getPid()); item.put("running", terminal.isRunning());
        item.put("name", terminal.mSessionName); item.put("cwd", terminal.getCwd());
        TerminalEmulator emulator = terminal.getEmulator();
        if (emulator != null) {
            item.put("columns", emulator.mColumns); item.put("rows", emulator.mRows);
            item.put("cell_width", emulator.getCellWidthPixels()); item.put("cell_height", emulator.getCellHeightPixels());
            String transcript = emulator.getScreen().getTranscriptText();
            item.put("transcript", transcript.substring(Math.max(0, transcript.length() - 24000)));
            JSONObject graphics = new JSONObject();
            for (String name : new String[]{"mKittyImages", "mKittyPlaceholders", "mKittyImagesTotalSize"}) {
                Field field = TerminalEmulator.class.getDeclaredField(name); field.setAccessible(true);
                Object value = field.get(emulator);
                graphics.put(name, value instanceof Map ? ((Map<?, ?>)value).size() : value);
            }
            item.put("graphics", graphics);
        }
        return item;
    }

    private static void deliver(TermuxActivity activity, JSONObject request, TerminalSession selected, TerminalSession previous) {
        JSONObject result = new JSONObject();
        try {
            result.put("uid", android.os.Process.myUid()); result.put("app_pid", android.os.Process.myPid());
            if (selected != null) result.put("selected", session(selected));
            JSONArray sessions = new JSONArray();
            TermuxShellManager manager = TermuxShellManager.getShellManager();
            if (manager != null) for (TermuxSession item : new ArrayList<>(manager.mTermuxSessions))
                sessions.put(session(item.getTerminalSession()));
            result.put("sessions", sessions);
            if (request.optBoolean("screenshot", true)) {
                TerminalView view = activity.getTerminalView();
                if (view != null && view.getWidth() > 0 && view.getHeight() > 0) {
                    TerminalSession visible = activity.getCurrentSession();
                    if (selected != null && selected != visible) view.attachSession(selected);
                    Bitmap bitmap = null;
                    try {
                        bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
                        view.draw(new Canvas(bitmap));
                        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded);
                        result.put("screen_png", Base64.encodeToString(encoded.toByteArray(), Base64.NO_WRAP));
                    } finally {
                        if (bitmap != null) bitmap.recycle();
                        if (visible != null && visible != selected) view.attachSession(visible);
                    }
                }
            }
        } catch (Exception error) { try { result.put("error", error.toString()); } catch (Exception ignored) {} }
        new Thread(() -> {
            try {
                JSONArray processes = new JSONArray();
                File[] entries = new File("/proc").listFiles();
                if (entries != null) for (File directory : entries) {
                    if (!directory.getName().matches("[0-9]+")) continue;
                    try {
                        String status = new String(Files.readAllBytes(new File(directory, "status").toPath()), StandardCharsets.UTF_8);
                        if (!status.matches("(?s).*\nUid:\\s+" + android.os.Process.myUid() + "\\s+.*")) continue;
                        JSONObject process = new JSONObject(); process.put("pid", Integer.parseInt(directory.getName()));
                        for (String line : status.split("\n")) if (line.startsWith("Name:") || line.startsWith("State:") || line.startsWith("PPid:") || line.startsWith("Threads:")) {
                            String[] pair = line.split(":", 2); process.put(pair[0], pair[1].trim());
                        }
                        for (String name : new String[]{"cmdline", "wchan", "stat"}) try {
                            String text = new String(Files.readAllBytes(new File(directory, name).toPath()), StandardCharsets.UTF_8).replace('\u0000', ' ');
                            process.put(name, text.substring(0, Math.min(4096, text.length())));
                        } catch (Exception ignored) {}
                        processes.put(process);
                    } catch (Exception ignored) {}
                }
                result.put("processes", processes);
            } catch (Exception ignored) {}
            send(request, result);
        }, "terminal-diagnostics").start();
    }

    private static void send(JSONObject request, JSONObject result) {
        try (DatagramSocket channel = new DatagramSocket()) {
            byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
            int total = (bytes.length + 8191) / 8192;
            String hash = KittyNativeRuntime.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
            for (int index = 0; index < total; index++) {
                int offset = index * 8192, length = Math.min(8192, bytes.length - offset);
                JSONObject packet = new JSONObject();
                packet.put("token", request.getString("token")); packet.put("index", index); packet.put("total", total); packet.put("sha256", hash);
                packet.put("data", Base64.encodeToString(bytes, offset, length, Base64.NO_WRAP));
                byte[] encoded = packet.toString().getBytes(StandardCharsets.UTF_8);
                channel.send(new DatagramPacket(encoded, encoded.length, InetAddress.getByName("127.0.0.1"), request.getInt("port")));
                Thread.sleep(3);
            }
        } catch (Exception ignored) {}
    }
}
