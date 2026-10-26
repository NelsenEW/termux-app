package com.termux.app;

import android.content.Context;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Removes only unchanged files installed by the early connection-specific prototype. */
final class KittyCleanup {
    private static final String PROFILE =
        "export PATH=\"$PREFIX/bin:/system/bin\"\n" +
        "export LD_PRELOAD=\"$PREFIX/lib/libtermux-exec.so\"\n" +
        "export TERM=xterm-kitty TERM_PROGRAM=termux-kitty\n" +
        "export SHELL=\"$PREFIX/bin/bash\"\n" +
        "umask 077\n" +
        "if [ -r \"$HOME/.bashrc\" ]; then . \"$HOME/.bashrc\"; fi\n";

    static void removePrototypeHelpers(Context context) {
        try (InputStream input = context.getAssets().open("prototype-files.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            JSONObject owned = new JSONObject(bytes.toString("UTF-8"));
            for (String name : new String[]{"bin/foodlink", "bin/foodlink-browser", "bin/import-termux", "etc/profile"}) {
                File file = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, name);
                if (!file.isFile() || file.length() > 65536) continue;
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream existing = new FileInputStream(file)) {
                    while ((count = existing.read(buffer)) != -1) digest.update(buffer, 0, count);
                }
                StringBuilder hash = new StringBuilder();
                for (byte value : digest.digest()) hash.append(String.format("%02x", value & 255));
                if (!hash.toString().equals(owned.getString(name))) continue;
                if (name.equals("etc/profile")) {
                    try (FileOutputStream output = new FileOutputStream(file)) {
                        output.write(PROFILE.getBytes(StandardCharsets.UTF_8));
                    }
                } else if (!file.delete()) {
                    Logger.logWarn("KittyCleanup", "Could not remove prototype helper " + name);
                }
            }
        } catch (Exception error) {
            Logger.logWarn("KittyCleanup", "Prototype migration could not finish: " + error.getMessage());
        }
    }
}
