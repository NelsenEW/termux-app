package com.termux.app;

import android.content.Context;
import android.system.Os;
import com.termux.shared.termux.shell.TermuxCompatibility;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Keeps import and compatibility tools outside the prefix being restored. */
final class KittyNativeRuntime {
    static synchronized void ensure(Context context) throws Exception {
        File root = new File(TermuxCompatibility.COMPAT_PATH);
        directory(root);
        String expected;
        try (InputStream input = context.getAssets().open("compat-runtime.sha256")) {
            expected = new String(readSmall(input), StandardCharsets.UTF_8).trim();
        }
        File marker = new File(root, "runtime-version");
        if (marker.isFile()) {
            try (InputStream input = new java.io.FileInputStream(marker)) {
                if (expected.equals(new String(readSmall(input), StandardCharsets.UTF_8).trim()) &&
                    new File(root, "bin/proot").canExecute() && new File(root, "bin/tar").canExecute()) {
                    directory(new File(root, "tmp"));
                    return;
                }
            }
        }
        File staging = new File(root, "runtime-staging-" + System.nanoTime());
        directory(staging);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String links = "";
            try (InputStream source = context.getAssets().open("compat-runtime.zip");
                 java.security.DigestInputStream checked = new java.security.DigestInputStream(source, digest);
                 ZipInputStream zip = new ZipInputStream(checked)) {
                ZipEntry entry;
                byte[] buffer = new byte[65536];
                while ((entry = zip.getNextEntry()) != null) {
                    File target = new File(staging, entry.getName());
                    if (!target.getCanonicalPath().startsWith(staging.getCanonicalPath() + "/"))
                        throw new IOException("Invalid runtime asset path.");
                    if (entry.isDirectory()) { directory(target); continue; }
                    if (entry.getName().equals("SYMLINKS.txt")) {
                        links = new String(readSmall(zip), StandardCharsets.UTF_8);
                        continue;
                    }
                    directory(target.getParentFile());
                    try (FileOutputStream output = new FileOutputStream(target)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) output.write(buffer, 0, count);
                    }
                    Os.chmod(target.getAbsolutePath(), 0700);
                }
                // Include the ZIP central directory in the digest.
                while (checked.read(buffer) != -1) {}
            }
            if (!expected.equals(hex(digest.digest()))) throw new IOException("Runtime asset checksum mismatch.");
            for (String line : links.split("\n")) {
                if (line.isEmpty()) continue;
                String[] pair = line.split("←", 2);
                if (pair.length != 2) throw new IOException("Invalid runtime symlink.");
                File target = new File(staging, pair[1]);
                if (!target.getCanonicalPath().startsWith(staging.getCanonicalPath() + "/"))
                    throw new IOException("Invalid runtime symlink path.");
                directory(target.getParentFile());
                Os.symlink(pair[0], target.getAbsolutePath());
            }
            // The ready marker is separate and is never overwritten by a runtime update.
            for (String name : new String[]{"bin", "lib", "libexec"}) {
                File incoming = new File(staging, name);
                if (!incoming.isDirectory()) continue;
                File old = new File(root, name + ".previous");
                remove(old);
                File current = new File(root, name);
                if (current.exists() && !current.renameTo(old)) throw new IOException("Cannot update runtime " + name);
                if (!incoming.renameTo(current)) {
                    if (old.exists()) old.renameTo(current);
                    throw new IOException("Cannot install runtime " + name);
                }
                remove(old);
            }
            directory(new File(root, "tmp"));
            atomicText(marker, expected + "\n");
        } finally { remove(staging); }
    }

    static void atomicText(File target, String value) throws Exception {
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        Os.chmod(temporary.getAbsolutePath(), 0600);
        Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath());
    }

    static void directory(File file) throws Exception {
        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("Cannot create " + file.getName());
        Os.chmod(file.getAbsolutePath(), 0700);
    }

    static byte[] readSmall(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (result.size() + count > 1024 * 1024) throw new IOException("Runtime metadata is too large.");
            result.write(buffer, 0, count);
        }
        return result.toByteArray();
    }

    static String hex(byte[] data) {
        StringBuilder result = new StringBuilder();
        for (byte value : data) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    /** Does not follow directory symlinks when removing task-owned staging files. */
    static void remove(File file) {
        try {
            if (android.system.OsConstants.S_ISDIR(Os.lstat(file.getAbsolutePath()).st_mode)) {
                File[] entries = file.listFiles();
                if (entries != null) for (File entry : entries) remove(entry);
            }
            file.delete();
        } catch (Exception ignored) {}
    }
}
