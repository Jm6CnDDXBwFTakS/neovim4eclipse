// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the Neovim executable and builds the {@code --embed} command line.
 *
 * <p>The preference value may be blank (use the default), a bare command name resolved through
 * {@code PATH} (e.g. {@code nvim}), or an absolute path.
 *
 * <h2>Why search outside {@code PATH}</h2>
 * A program inherits {@code PATH} from whatever started it. Eclipse started from the macOS Dock or
 * Finder, from a Linux desktop menu, or from a PDE launch inside another Eclipse, gets a minimal
 * system {@code PATH} instead of the one your shell sets up, so package managers' folders such as
 * Homebrew's {@code /opt/homebrew/bin} are missing. A bare name that is not on {@code PATH} is
 * therefore also looked for in the usual install folders ({@link #fallbackDirectories}).
 */
public final class NvimExecutable {

    private NvimExecutable() {
    }

    /**
     * Turns the configured preference value into a token {@link ProcessBuilder} can launch.
     *
     * <p>Only <em>absolute</em> paths are checked against the file system. A relative name such
     * as {@code nvim} is deliberately left alone so it is looked up on {@code PATH}; otherwise a
     * file called {@code nvim} in Eclipse's working directory would silently win.
     *
     * @param configured preference value; may be {@code null} or blank
     * @return the absolute path, the trimmed command name, or {@link #defaultCommandName()}
     */
    public static String resolve(String configured) {
        return resolve(configured, System.getenv("PATH"), PlatformUtil.isWindows(), fallbackDirectories());
    }

    /**
     * Testable core of {@link #resolve(String)}.
     *
     * @param pathEnv      value of the {@code PATH} variable; may be {@code null}
     * @param windows      whether Windows rules apply ({@code nvim.exe}, {@code ;} separators, suffixes)
     * @param fallbackDirs folders searched, in order, when a bare name is not on {@code PATH}
     * @return an absolute path, or the command name when it is on {@code PATH} or found nowhere (the
     *         OS then reports the failure when the process is started)
     */
    static String resolve(String configured, String pathEnv, boolean windows, List<Path> fallbackDirs) {
        String name = configured == null || configured.isBlank()
                ? (windows ? "nvim.exe" : "nvim")
                : configured.strip();
        File file = new File(name);
        if (file.isAbsolute()) {
            return file.isFile() ? file.getAbsolutePath() : name;
        }
        if (name.contains("/") || name.contains(File.separator) || isAvailable(name, pathEnv, windows)) {
            return name;
        }
        for (Path dir : fallbackDirs) {
            for (String candidate : candidates(name, windows)) {
                Path path = dir.resolve(candidate);
                if (Files.isRegularFile(path)) {
                    return path.toAbsolutePath().toString();
                }
            }
        }
        return name;
    }

    /**
     * Folders where Neovim is commonly installed, for when they are not on {@code PATH}. macOS and
     * Linux: Homebrew (Apple silicon macOS; Intel macOS; Linux system-wide and per-user), MacPorts,
     * user-local installs, snap. Windows: the official installer, Scoop and Chocolatey. Folders that
     * do not exist on a platform are simply skipped by the search.
     */
    static List<Path> fallbackDirectories() {
        String home = System.getProperty("user.home", "");
        List<Path> dirs = new ArrayList<>();
        if (PlatformUtil.isWindows()) {
            addIfSet(dirs, System.getenv("ProgramFiles"), "Neovim", "bin");
            addIfSet(dirs, home, "scoop", "shims");
            addIfSet(dirs, System.getenv("ProgramData"), "chocolatey", "bin");
        } else {
            dirs.add(Path.of("/opt/homebrew/bin"));
            dirs.add(Path.of("/usr/local/bin"));
            dirs.add(Path.of("/home/linuxbrew/.linuxbrew/bin"));
            addIfSet(dirs, home, ".linuxbrew", "bin");
            dirs.add(Path.of("/opt/local/bin"));
            addIfSet(dirs, home, ".local", "bin");
            addIfSet(dirs, home, "bin");
            dirs.add(Path.of("/snap/bin"));
        }
        return dirs;
    }

    private static void addIfSet(List<Path> dirs, String base, String... more) {
        if (base != null && !base.isBlank()) {
            dirs.add(Path.of(base, more));
        }
    }

    /** {@code nvim.exe} on Windows, {@code nvim} elsewhere. */
    public static String defaultCommandName() {
        return PlatformUtil.isWindows() ? "nvim.exe" : "nvim";
    }

    /**
     * The global variable that tells the user's config it runs inside Eclipse:
     * {@code if vim.g.neovim4eclipse then ... end} in {@code init.lua}.
     */
    public static final String GUARD_VARIABLE = "neovim4eclipse";

    /**
     * Builds {@code <nvim> --embed --cmd let g:neovim4eclipse=1 [extraArgs...]}. Null/blank extra
     * args are skipped.
     *
     * <h2>Why {@code --cmd}</h2>
     * The user's {@code init.lua} needs a way to skip what does not belong in Eclipse (UI plug-ins,
     * language servers, a {@code 'foldmethod'}), but everything the plug-in sets itself
     * ({@code g:eclipse_channel}, its mappings) arrives after the config has run. {@code --cmd}
     * runs before any config file, also with {@code --clean} or {@code -u}, so {@link #GUARD_VARIABLE}
     * is there when {@code init.lua} looks. It is a {@code g:} variable rather than an environment
     * variable because Neovim passes its environment on to {@code :terminal} and {@code :!}: a
     * Neovim started in a terminal inside Eclipse would think it was embedded too. The command is one
     * argument with a space in it; {@link ProcessBuilder} quotes it on Windows, and
     * {@code NeovimSessionIntegrationTest} checks that Neovim receives it whole.
     *
     * @param configured preference path or command name
     * @param extraArgs  optional extra arguments, e.g. {@code --clean}; may be {@code null}
     */
    public static List<String> embedCommand(String configured, List<String> extraArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolve(configured));
        cmd.add("--embed");
        cmd.add("--cmd");
        cmd.add("let g:" + GUARD_VARIABLE + "=1");
        if (extraArgs != null) {
            for (String arg : extraArgs) {
                if (arg != null && !arg.isBlank()) {
                    cmd.add(arg);
                }
            }
        }
        return cmd;
    }

    /**
     * Adjusts the environment Neovim inherits (the {@link ProcessBuilder#environment()} map).
     *
     * <h2>Why {@code SHELL} is dropped on Windows</h2>
     * Neovim takes its {@code 'shell'} option from {@code $SHELL}, but on Windows it sets matching
     * {@code 'shellcmdflag'} and quoting options only for its default, {@code cmd.exe}. Eclipse started
     * from Git Bash (or MSYS2, Cygwin) inherits {@code SHELL=/usr/bin/bash}, so {@code :!echo hi} ran
     * {@code bash /s /c "echo hi"} and failed with "/s: No such file or directory". Without
     * {@code SHELL}, Neovim uses {@code cmd.exe} as when Eclipse is started from Explorer or
     * PowerShell. A user who wants another shell sets {@code 'shell'} and its flags in
     * {@code init.lua} ({@code :help shell-powershell}).
     *
     * @param env     the child's environment, changed in place
     * @param windows whether Windows rules apply
     */
    public static void prepareEnvironment(Map<String, String> env, boolean windows) {
        if (windows) {
            env.remove("SHELL");
        }
    }

    /** Best-effort check that the configured executable exists, searching the real {@code PATH}. */
    public static boolean looksAvailable(String configured) {
        return isAvailable(resolve(configured), System.getenv("PATH"), PlatformUtil.isWindows());
    }

    /**
     * Testable core of {@link #looksAvailable}.
     *
     * @param command command name or absolute path (already resolved)
     * @param pathEnv value of the {@code PATH} variable; may be {@code null}
     * @param windows whether to use {@code ;} separators and try Windows executable suffixes
     */
    static boolean isAvailable(String command, String pathEnv, boolean windows) {
        if (new File(command).isAbsolute()) {
            return Files.isRegularFile(Path.of(command));
        }
        if (pathEnv == null || pathEnv.isBlank()) {
            return false;
        }
        String separator = windows ? ";" : ":";
        for (String dir : pathEnv.split(separator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String candidate : candidates(command, windows)) {
                if (Files.isRegularFile(Path.of(dir, candidate))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The file names a command can have: on Windows also with {@code .exe}, {@code .cmd}, {@code .bat}. */
    private static List<String> candidates(String command, boolean windows) {
        List<String> candidates = new ArrayList<>();
        candidates.add(command);
        if (windows && !hasWindowsExecutableSuffix(command)) {
            for (String ext : new String[] { ".exe", ".cmd", ".bat" }) {
                candidates.add(command + ext);
            }
        }
        return candidates;
    }

    private static boolean hasWindowsExecutableSuffix(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        return lower.endsWith(".exe") || lower.endsWith(".cmd") || lower.endsWith(".bat");
    }
}
