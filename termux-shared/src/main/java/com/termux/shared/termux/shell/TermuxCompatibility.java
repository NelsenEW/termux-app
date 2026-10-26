package com.termux.shared.termux.shell;

import com.termux.shared.termux.TermuxConstants;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Gives an imported Termux prefix its original paths within this app's own private data. */
public final class TermuxCompatibility {
    public static final String ORIGINAL_DATA = "/data/data/com.termux";
    public static final String ORIGINAL_FILES = ORIGINAL_DATA + "/files";
    public static final String ORIGINAL_PREFIX = ORIGINAL_FILES + "/usr";
    public static final String COMPAT_PATH = TermuxConstants.TERMUX_FILES_DIR_PATH + "/compat";

    private TermuxCompatibility() {}

    public static boolean isImported() {
        return new File(COMPAT_PATH, "import-ready").isFile();
    }

    public static final class Launch {
        public final String executable;
        public final String cwd;
        public final String[] arguments;
        public final String[] environment;

        Launch(String executable, String cwd, String[] arguments, String[] environment) {
            this.executable = executable;
            this.cwd = cwd;
            this.arguments = arguments;
            this.environment = environment;
        }
    }

    public static Launch prepare(String executable, String cwd, String[] arguments,
                                  String[] environment) {
        String client = TermuxConstants.TERMUX_FILES_DIR_PATH + "/shared-termux/kitty-termux";
        if (arguments.length > 1 && client.equals(arguments[1]) &&
            executable.equals(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/bash")) {
            File root = new File(TermuxConstants.TERMUX_FILES_DIR_PATH, "shared-termux/runtime");
            if (!new File(root, "ready").isFile())
                throw new IllegalStateException("The companion connection tools are not ready.");
            return mapped(root, executable, cwd, arguments, environment);
        }
        if (!isImported()) return new Launch(executable, cwd, arguments, environment);
        return mapped(new File(TermuxConstants.TERMUX_FILES_DIR_PATH).getParentFile(),
            executable, cwd, arguments, environment);
    }

    public static void mapEnvironment(HashMap<String, String> environment) {
        String physical = new File(TermuxConstants.TERMUX_FILES_DIR_PATH).getParent();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (entry.getValue() != null) entry.setValue(entry.getValue().replace(physical, ORIGINAL_DATA));
        }
        environment.put("HOME", ORIGINAL_FILES + "/home");
        environment.put("PREFIX", ORIGINAL_PREFIX);
        environment.put("TMPDIR", ORIGINAL_PREFIX + "/tmp");
        environment.put("CLAUDE_CODE_TMPDIR", ORIGINAL_PREFIX + "/tmp");
        environment.put("CLAUDE_TMPDIR", ORIGINAL_PREFIX + "/tmp/claude");
        String path = environment.get("PATH");
        environment.put("PATH", ORIGINAL_FILES + "/compat/bin:" +
            (path == null || path.isEmpty() ? ORIGINAL_PREFIX + "/bin:/system/bin" : path));
        environment.put("TERM", "xterm-kitty");
        environment.put("TERM_PROGRAM", "termux-kitty");
    }

    /** Also used to check an extracted archive before replacing the current environment. */
    public static Launch mapped(File dataRoot, String executable, String cwd, String[] arguments,
                                String[] environment) {
        String physical = new File(TermuxConstants.TERMUX_FILES_DIR_PATH).getParent();
        String command = executable.replace(physical, ORIGINAL_DATA)
            .replace(dataRoot.getAbsolutePath(), ORIGINAL_DATA);
        String directory = cwd.replace(physical, ORIGINAL_DATA)
            .replace(dataRoot.getAbsolutePath(), ORIGINAL_DATA);
        HashMap<String, String> variables = new HashMap<>();
        for (String item : environment) {
            int equals = item.indexOf('=');
            if (equals > 0) variables.put(item.substring(0, equals), item.substring(equals + 1));
        }
        mapEnvironment(variables);
        variables.put("PWD", directory);
        variables.remove("LD_PRELOAD");
        variables.put("LD_LIBRARY_PATH", COMPAT_PATH + "/lib");
        variables.put("PROOT_LOADER", COMPAT_PATH + "/libexec/proot/loader");
        variables.put("PROOT_TMP_DIR", COMPAT_PATH + "/tmp");
        variables.put("PROOT_NO_SECCOMP", "1");
        variables.put("PROOT_ASSUME_NEW_SECCOMP", "1");

        List<String> commandLine = new ArrayList<>();
        commandLine.add(COMPAT_PATH + "/bin/proot");
        commandLine.add("--kill-on-exit");
        commandLine.add("-r"); commandLine.add("/");
        bind(commandLine, dataRoot.getAbsolutePath(), ORIGINAL_DATA);
        bind(commandLine, dataRoot.getAbsolutePath(), "/data/user/0/com.termux");
        bind(commandLine, dataRoot + "/files/usr", "/usr");
        bind(commandLine, dataRoot + "/files/usr/bin", "/bin");
        bind(commandLine, dataRoot + "/files/usr/tmp", "/tmp");
        if (!dataRoot.getAbsolutePath().equals(physical)) {
            bind(commandLine, COMPAT_PATH, ORIGINAL_FILES + "/compat");
            File companion = new File(TermuxConstants.TERMUX_FILES_DIR_PATH, "shared-termux");
            if (companion.isDirectory())
                bind(commandLine, companion.getAbsolutePath(), ORIGINAL_FILES + "/shared-termux");
        }
        commandLine.add("-w"); commandLine.add(directory);
        commandLine.add(ORIGINAL_PREFIX + "/bin/env");
        for (String name : new String[]{"LD_LIBRARY_PATH", "LD_PRELOAD", "PROOT_LOADER",
                "PROOT_TMP_DIR", "PROOT_NO_SECCOMP", "PROOT_ASSUME_NEW_SECCOMP"}) {
            commandLine.add("-u"); commandLine.add(name);
        }
        // GNU env preserves Termux's login-shell argv[0], including its leading '-'.
        commandLine.add("--argv0=" + (arguments.length == 0 ? new File(command).getName() : arguments[0]));
        commandLine.add(command);
        for (int i = 1; i < arguments.length; i++)
            commandLine.add(arguments[i].replace(physical, ORIGINAL_DATA));

        List<String> environ = new ArrayList<>();
        for (Map.Entry<String, String> entry : variables.entrySet())
            if (entry.getValue() != null) environ.add(entry.getKey() + "=" + entry.getValue());
        String hostDirectory = directory.startsWith(ORIGINAL_DATA + "/")
            ? dataRoot.getAbsolutePath() + directory.substring(ORIGINAL_DATA.length()) : directory;
        if (!new File(hostDirectory).isDirectory()) hostDirectory = TermuxConstants.TERMUX_HOME_DIR_PATH;
        return new Launch(commandLine.get(0), hostDirectory,
            commandLine.toArray(new String[0]), environ.toArray(new String[0]));
    }

    private static void bind(List<String> arguments, String source, String destination) {
        arguments.add("-b"); arguments.add(source + ":" + destination + "!");
    }
}
