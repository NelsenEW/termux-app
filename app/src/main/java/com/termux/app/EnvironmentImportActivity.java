package com.termux.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.system.Os;
import android.system.OsConstants;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.TermuxCompatibility;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/** Imports a standard Termux home/usr tar.gz through Android's selected-file permission. */
public final class EnvironmentImportActivity extends AppCompatActivity {
    private static final int PICK_EXPORT = 2101;
    private static final File FILES = new File(TermuxConstants.TERMUX_FILES_DIR_PATH);
    private static final File JOURNAL = new File(TermuxCompatibility.COMPAT_PATH, "import-transaction.json");
    private static volatile boolean busy;
    private TextView message;
    private Button choose;
    private Button cancel;
    private volatile boolean cancelled;
    private boolean importing;
    private Process tarProcess;
    private int callbackPort;
    private String callbackToken;
    private String expectedHash;
    private long expectedBytes;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 48, 32, 32);
        message = new TextView(this);
        message.setTextSize(19);
        message.setText("Import your full Termux environment from a home/usr .tar.gz export.\n\n" +
            "The copy includes your files, packages and settings. Completing import closes Termux Kitty's sessions and saves its current environment.");
        layout.addView(message);
        choose = new Button(this);
        choose.setText("Choose export");
        choose.setOnClickListener(view -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            pick.putExtra("android.provider.extra.INITIAL_URI", Uri.parse(
                "content://com.termux.documents/document/%2Fdata%2Fdata%2Fcom.termux%2Ffiles%2Fhome%2F.local%2Fshare%2Ftermux-kitty%2Fexports"));
            startActivityForResult(pick, PICK_EXPORT);
        });
        layout.addView(choose);
        cancel = new Button(this);
        cancel.setText("Cancel import");
        cancel.setVisibility(android.view.View.GONE);
        cancel.setOnClickListener(view -> { cancelled = true; if (tarProcess != null) tarProcess.destroy(); });
        layout.addView(cancel);
        setContentView(layout);
        Intent intent = getIntent();
        if (intent != null) {
            callbackPort = intent.getIntExtra("com.termyx.import.callback_port", 0);
            callbackToken = intent.getStringExtra("com.termyx.import.callback_token");
            expectedHash = intent.getStringExtra("com.termyx.import.sha256");
            expectedBytes = intent.getLongExtra("com.termyx.import.source_bytes", 0);
            // Automatic transfer is limited to the owner's private export folder.
            // Other archives require an explicit selection in Android's file picker.
            if (intent.getData() != null && isPrivateExport(intent.getData()) &&
                expectedHash != null && expectedHash.matches("[0-9a-fA-F]{64}")) begin(intent.getData());
            else if (intent.getBooleanExtra("com.termyx.import.check_only", false)) checkRuntime();
        }
    }

    private static boolean isPrivateExport(Uri uri) {
        if (!"content".equals(uri.getScheme()) || !"com.termux.documents".equals(uri.getAuthority())) return false;
        try {
            String document = android.provider.DocumentsContract.getDocumentId(uri);
            return !document.contains("/../") && document.endsWith(".tar.gz") &&
                (document.startsWith("/data/data/com.termux/files/home/.local/share/termux-kitty/exports/") ||
                 document.startsWith("/data/user/0/com.termux/files/home/.local/share/termux-kitty/exports/"));
        } catch (Exception ignored) { return false; }
    }

    private void checkRuntime() {
        TermuxInstaller.setupBootstrapIfNeeded(this, this::checkInstalledRuntime);
    }

    private void checkInstalledRuntime() {
        choose.setEnabled(false);
        message.setText("Checking the package compatibility layer…");
        new Thread(() -> {
            try {
                KittyNativeRuntime.ensure(this);
                File dataRoot = FILES.getParentFile();
                check(dataRoot, TermuxCompatibility.ORIGINAL_PREFIX + "/bin/bash", "--version");
                String ssh = check(dataRoot, TermuxCompatibility.ORIGINAL_PREFIX + "/bin/ssh", "-V");
                report("runtime-ready", ssh.trim(), 0);
                runOnUiThread(() -> {
                    message.setText("Package compatibility check passed. Choose your full Termux export to import files, packages and settings.");
                    choose.setEnabled(true);
                });
            } catch (Exception error) {
                report("error", error.getMessage(), 0);
                runOnUiThread(() -> {
                    message.setText("The compatibility layer could not start: " + error.getMessage());
                    choose.setEnabled(true);
                });
            }
        }, "termux-import-runtime-check").start();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_EXPORT && result == RESULT_OK && data != null && data.getData() != null)
            begin(data.getData());
    }

    @Override public void onBackPressed() {
        if (importing) Toast.makeText(this, "Use Cancel import to stop the transfer.", Toast.LENGTH_SHORT).show();
        else super.onBackPressed();
    }

    private void begin(Uri uri) {
        synchronized (EnvironmentImportActivity.class) {
            if (busy) { message.setText("An import is already running."); return; }
            busy = true;
        }
        importing = true;
        cancelled = false;
        choose.setEnabled(false);
        cancel.setEnabled(true);
        cancel.setVisibility(android.view.View.VISIBLE);
        new Thread(() -> {
            PowerManager.WakeLock wake = ((PowerManager)getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.termyx:environment-import");
            wake.acquire(2L * 60 * 60 * 1000);
            try {
                importArchive(uri);
                runOnUiThread(() -> {
                    importing = false;
                    cancel.setVisibility(android.view.View.GONE);
                    message.setText("Full environment imported. Open a new Termux Kitty session to use your files and packages.");
                    choose.setText("Open terminal");
                    choose.setEnabled(true);
                    choose.setOnClickListener(view -> {
                        startActivity(new Intent(this, TermuxActivity.class));
                        finish();
                    });
                });
            } catch (Exception error) {
                String detail = cancelled ? "Import cancelled." : error.getMessage();
                report("error", detail, 0);
                runOnUiThread(() -> {
                    importing = false;
                    cancel.setVisibility(android.view.View.GONE);
                    message.setText(detail == null ? "Import could not finish." : detail);
                    choose.setEnabled(true);
                });
            } finally {
                if (wake.isHeld()) wake.release();
                busy = false;
            }
        }, "termux-environment-import").start();
    }

    private void importArchive(Uri uri) throws Exception {
        if (!"content".equals(uri.getScheme())) throw new IOException("Choose the export using Android's file picker.");
        if (expectedHash != null && !expectedHash.matches("[0-9a-fA-F]{64}"))
            throw new IOException("Invalid export checksum.");
        KittyNativeRuntime.ensure(this);
        recoverTransaction();
        if (expectedBytes > 0 && FILES.getUsableSpace() < expectedBytes + 2147483648L)
            throw new IOException("More free storage is needed for the full imported environment.");
        File stage = new File(FILES, "import-stage-" + System.nanoTime());
        File unpack = new File(stage, "files");
        KittyNativeRuntime.directory(unpack);
        try {
            update("Importing files and installed packages…");
            report("started", "Importing files and installed packages", 0);
            ProcessBuilder builder = new ProcessBuilder(TermuxCompatibility.COMPAT_PATH + "/bin/tar",
                "--no-same-owner", "--same-permissions", "--delay-directory-restore", "--ignore-zeros",
                "-xpf", "-", "-C", unpack.getAbsolutePath());
            builder.environment().remove("LD_PRELOAD");
            builder.environment().put("LD_LIBRARY_PATH", TermuxCompatibility.COMPAT_PATH + "/lib");
            builder.environment().put("TMPDIR", TermuxCompatibility.COMPAT_PATH + "/tmp");
            builder.redirectErrorStream(true);
            tarProcess = builder.start();
            ByteArrayOutputStream tarLog = new ByteArrayOutputStream();
            Thread logReader = capture(tarProcess.getInputStream(), tarLog);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            long lastUpdate = 0;
            long nextSpaceCheck = 8L * 1024 * 1024;
            try (InputStream selected = getContentResolver().openInputStream(uri)) {
                if (selected == null) throw new IOException("The selected export cannot be opened.");
                try (DigestInputStream checked = new DigestInputStream(selected, digest);
                     GZIPInputStream gzip = new GZIPInputStream(checked, 65536);
                     OutputStream output = tarProcess.getOutputStream()) {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = gzip.read(buffer)) != -1) {
                        if (cancelled) throw new IOException("Import cancelled.");
                        output.write(buffer, 0, count);
                        total += count;
                        if (total >= nextSpaceCheck) {
                            if (FILES.getUsableSpace() < 1073741824L)
                                throw new IOException("More storage is needed to finish the full copy. Free space and import again.");
                            nextSpaceCheck = total + 8L * 1024 * 1024;
                        }
                        long now = System.currentTimeMillis();
                        if (now - lastUpdate > 2000) {
                            update(String.format(java.util.Locale.ROOT, "Importing… %.2f GB read", total / 1073741824.0));
                            report("progress", "Importing files and installed packages", total);
                            lastUpdate = now;
                        }
                    }
                    // GZIPInputStream checks CRC; finish the compressed stream's SHA-256 too.
                    while (checked.read(buffer) != -1) {}
                }
            }
            if (!tarProcess.waitFor(60, TimeUnit.SECONDS)) throw new IOException("Archive extraction did not finish.");
            logReader.join(5000);
            KittyNativeRuntime.atomicText(new File(TermuxCompatibility.COMPAT_PATH, "import-last.log"),
                tarLog.toString("UTF-8"));
            if (tarProcess.exitValue() != 0) throw new IOException("Archive extraction failed. The current environment was kept.");
            String actualHash = KittyNativeRuntime.hex(digest.digest());
            if (expectedHash != null && !actualHash.equalsIgnoreCase(expectedHash))
                throw new IOException("The export checksum does not match. Import was stopped.");
            validateLayout(unpack);
            KittyNativeRuntime.directory(new File(unpack, "usr/tmp"));
            KittyNativeRuntime.directory(new File(unpack, "usr/tmp/claude"));
            update("Checking the imported shell, SSH and Claude Code…");
            check(stage, TermuxCompatibility.ORIGINAL_PREFIX + "/bin/bash", "--version");
            String ssh = check(stage, TermuxCompatibility.ORIGINAL_PREFIX + "/bin/ssh", "-V");
            String claude = "";
            File claudeCli = new File(unpack, "usr/lib/node_modules/@anthropic-ai/claude-code/cli.js");
            if (claudeCli.isFile()) {
                claude = check(stage, TermuxCompatibility.ORIGINAL_PREFIX + "/bin/node",
                    TermuxCompatibility.ORIGINAL_PREFIX + "/lib/node_modules/@anthropic-ai/claude-code/cli.js", "--version");
            }
            if (cancelled) throw new IOException("Import cancelled.");
            runOnUiThread(() -> cancel.setEnabled(false));
            closeKittySessions();
            commit(unpack, stage, actualHash);
            TermuxShellEnvironment.writeEnvironmentToFile(this);
            report("ready", ssh.trim() + (claude.isEmpty() ? "" : "\n" + claude.trim()), total);
        } finally {
            if (tarProcess != null && tarProcess.isAlive()) tarProcess.destroy();
            tarProcess = null;
            if (!JOURNAL.isFile()) KittyNativeRuntime.remove(stage);
        }
    }

    private static void validateLayout(File root) throws Exception {
        for (String directory : new String[]{"home", "usr"}) {
            File path = new File(root, directory);
            if (!OsConstants.S_ISDIR(Os.lstat(path.getAbsolutePath()).st_mode))
                throw new IOException("The export must contain home and usr directories.");
        }
        for (String binary : new String[]{"usr/bin/bash", "usr/bin/ssh", "usr/bin/coreutils"}) {
            File path = new File(root, binary);
            if (!OsConstants.S_ISREG(Os.lstat(path.getAbsolutePath()).st_mode) || !path.canExecute())
                throw new IOException("The export is missing a required Termux executable.");
        }
    }

    private String check(File root, String executable, String... args) throws Exception {
        String[] arguments = new String[args.length + 1];
        arguments[0] = executable;
        System.arraycopy(args, 0, arguments, 1, args.length);
        HashMap<String, String> environment = new TermuxShellEnvironment().getEnvironment(this, false);
        ArrayList<String> items = new ArrayList<>();
        for (Map.Entry<String, String> entry : environment.entrySet())
            if (entry.getValue() != null) items.add(entry.getKey() + "=" + entry.getValue());
        TermuxCompatibility.Launch launch = TermuxCompatibility.mapped(root, executable,
            TermuxCompatibility.ORIGINAL_FILES + "/home", arguments, items.toArray(new String[0]));
        Process process = Runtime.getRuntime().exec(launch.arguments, launch.environment, new File(launch.cwd));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = capture(process.getInputStream(), output);
        Thread errors = capture(process.getErrorStream(), output);
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroy();
            throw new IOException("An imported command did not start within 30 seconds.");
        }
        reader.join(5000); errors.join(5000);
        if (process.exitValue() != 0) {
            KittyNativeRuntime.atomicText(new File(TermuxCompatibility.COMPAT_PATH, "import-check.log"), output.toString("UTF-8"));
            throw new IOException("Imported command could not start: " + new File(executable).getName() + "\n" + output.toString("UTF-8"));
        }
        return output.toString("UTF-8");
    }

    private void closeKittySessions() throws Exception {
        java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        runOnUiThread(() -> {
            TermuxShellManager manager = TermuxShellManager.getShellManager();
            if (manager != null) {
                for (AppShell task : new ArrayList<>(manager.mTermuxTasks)) task.kill();
                Intent stop = new Intent(this, TermuxService.class);
                stop.setAction(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_STOP_SERVICE);
                startService(stop);
            }
            closed.countDown();
        });
        if (!closed.await(10, TimeUnit.SECONDS)) throw new IOException("Termux Kitty sessions could not be closed.");
        // Wait for the service's stop action rather than replacing binaries still used by a session.
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            TermuxShellManager manager = TermuxShellManager.getShellManager();
            boolean running = false;
            if (manager != null) for (com.termux.shared.termux.shell.command.runner.terminal.TermuxSession session :
                    new ArrayList<>(manager.mTermuxSessions))
                if (session.getTerminalSession().isRunning()) running = true;
            if (!running) return;
            Thread.sleep(100);
        }
        throw new IOException("Close the Termux Kitty sessions and import again.");
    }

    private void commit(File incoming, File stage, String hash) throws Exception {
        File backup = new File(FILES, "environment-before-import-" + System.currentTimeMillis());
        KittyNativeRuntime.directory(backup);
        JSONObject journal = new JSONObject();
        journal.put("backup", backup.getName());
        journal.put("stage", stage.getName());
        journal.put("had_ready", TermuxCompatibility.isImported());
        File ready = new File(TermuxCompatibility.COMPAT_PATH, "import-ready");
        if (ready.isFile()) try (InputStream input = new java.io.FileInputStream(ready)) {
            journal.put("previous_ready", new String(KittyNativeRuntime.readSmall(input), StandardCharsets.UTF_8));
        }
        journal.put("phase", "moving");
        KittyNativeRuntime.atomicText(JOURNAL, journal.toString());
        try {
            for (String name : new String[]{"home", "usr"}) {
                File current = new File(FILES, name);
                File previous = new File(backup, name);
                if (current.exists() && !current.renameTo(previous)) throw new IOException("Cannot save the current " + name);
                if (!new File(incoming, name).renameTo(current)) throw new IOException("Cannot install the imported " + name);
            }
            KittyNativeRuntime.atomicText(new File(TermuxCompatibility.COMPAT_PATH, "import-ready"), hash + "\n");
            journal.put("phase", "committed");
            KittyNativeRuntime.atomicText(JOURNAL, journal.toString());
            JOURNAL.delete();
        } catch (Exception error) {
            recoverTransaction();
            throw error;
        }
    }

    static synchronized void recoverTransaction() throws Exception {
        if (!JOURNAL.isFile()) {
            // A killed extraction has not changed home/usr; reclaim only its task-owned stage.
            File[] abandoned = FILES.listFiles();
            if (abandoned != null) for (File entry : abandoned)
                if (entry.getName().matches("import-stage-[0-9]+")) KittyNativeRuntime.remove(entry);
            return;
        }
        JSONObject journal;
        try (InputStream input = new java.io.FileInputStream(JOURNAL)) {
            journal = new JSONObject(new String(KittyNativeRuntime.readSmall(input), StandardCharsets.UTF_8));
        }
        String backupName = journal.getString("backup");
        String stageName = journal.getString("stage");
        if (!backupName.matches("environment-before-import-[0-9]+") || !stageName.matches("import-stage-[0-9]+"))
            throw new IOException("Import recovery metadata is invalid.");
        File backup = new File(FILES, backupName);
        File stage = new File(FILES, stageName);
        if (!"committed".equals(journal.getString("phase"))) {
            for (String name : new String[]{"home", "usr"}) {
                File previous = new File(backup, name);
                File current = new File(FILES, name);
                if (previous.exists()) {
                    KittyNativeRuntime.remove(current);
                    if (!previous.renameTo(current)) throw new IOException("Cannot recover the previous " + name);
                }
            }
            File ready = new File(TermuxCompatibility.COMPAT_PATH, "import-ready");
            if (journal.optBoolean("had_ready", false))
                KittyNativeRuntime.atomicText(ready, journal.getString("previous_ready"));
            else ready.delete();
        }
        KittyNativeRuntime.remove(stage);
        JOURNAL.delete();
    }

    private static Thread capture(InputStream input, ByteArrayOutputStream output) {
        Thread reader = new Thread(() -> {
            try (InputStream stream = input) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = stream.read(buffer)) != -1) synchronized (output) {
                    if (output.size() < 65536) output.write(buffer, 0, Math.min(count, 65536 - output.size()));
                }
            } catch (IOException ignored) {}
        }, "termux-import-output");
        reader.start();
        return reader;
    }

    private void update(String text) { runOnUiThread(() -> message.setText(text)); }

    private void report(String status, String detail, long bytes) {
        if (callbackPort < 1024 || callbackPort > 65535 || callbackToken == null || !callbackToken.matches("[0-9a-f]{32}")) return;
        try (DatagramSocket socket = new DatagramSocket()) {
            JSONObject data = new JSONObject();
            data.put("token", callbackToken); data.put("status", status);
            data.put("detail", detail == null ? "" : detail); data.put("bytes", bytes);
            byte[] encoded = data.toString().getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(encoded, encoded.length, InetAddress.getByName("127.0.0.1"), callbackPort));
        } catch (Exception ignored) {}
    }
}
