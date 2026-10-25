// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Resolves the Neovim executable and builds the {@code --embed} command line.
 *
 * <h2>What the preference may hold</h2>
 * <ul>
 *   <li><b>Blank</b> (the default): the plug-in's own Neovim folder {@link #dedicatedFolder}
 *       ({@code ~/.local/opt/neovim}), then {@code nvim} on {@code PATH}, then the usual install
 *       folders ({@link #searchFolders}). The dedicated folder comes first so that an older Neovim
 *       from the system's package manager, on {@code PATH}, cannot shadow the one installed for
 *       Eclipse (the plug-in raises its minimum Neovim readily; docs/UPGRADING.md).</li>
 *   <li><b>A folder</b>: Neovim's install folder, the one that holds {@code bin/nvim}. Every official
 *       install has that layout: the release archives ({@code nvim-linux-x86_64/},
 *       {@code nvim-macos-arm64/}, {@code nvim-win64/}), the Windows installer
 *       ({@code C:\Program Files\Neovim}), Homebrew ({@code /opt/homebrew}). See
 *       {@link #findInFolder} for the exact rules.</li>
 *   <li><b>A program</b>: an absolute path to {@code nvim} (what this preference held before it
 *       accepted folders), or a bare command name such as {@code nvim}, looked up on {@code PATH}
 *       and then in the usual install folders.</li>
 * </ul>
 * A leading {@code ~} stands for the home folder: {@code ~/.local/opt/neovim}.
 *
 * <h2>Why search outside {@code PATH}</h2>
 * A program inherits {@code PATH} from whatever started it. Eclipse started from the macOS Dock or
 * Finder, from a Linux desktop menu, or from a PDE launch inside another Eclipse, gets a minimal
 * system {@code PATH} instead of the one your shell sets up, so package managers' folders such as
 * Homebrew's {@code /opt/homebrew/bin} are missing.
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
     * @param configured preference value: blank, a folder, an absolute path or a command name
     * @return an absolute path to the program, or a command name for the OS to look up (or to
     *         report as missing when the process is started)
     */
    public static String resolve(String configured) {
        return resolve(configured, System.getenv("PATH"), PlatformUtil.isWindows(), dedicatedFolder(),
                searchFolders());
    }

    /** {@link #resolve(String, String, boolean, Path, List)} without a dedicated folder. */
    static String resolve(String configured, String pathEnv, boolean windows, List<Path> searchFolders) {
        return resolve(configured, pathEnv, windows, null, searchFolders);
    }

    /**
     * Testable core of {@link #resolve(String)}.
     *
     * @param pathEnv       value of the {@code PATH} variable; may be {@code null}
     * @param windows       whether Windows rules apply ({@code nvim.exe}, {@code ;} separators, suffixes)
     * @param dedicated     the plug-in's own Neovim folder, tried first for a blank preference; may be
     *                      {@code null}
     * @param searchFolders folders searched in order ({@link #findInFolder}) when a bare name is not
     *                      on {@code PATH}
     */
    static String resolve(String configured, String pathEnv, boolean windows, Path dedicated,
            List<Path> searchFolders) {
        String program = windows ? "nvim.exe" : "nvim";
        if (configured == null || configured.isBlank()) {
            Path found = dedicated == null ? null : findInFolder(dedicated, program, windows);
            return found != null ? found.toString() : resolve(program, pathEnv, windows, null, searchFolders);
        }
        String name = expandHome(configured.strip());
        File file = new File(name);
        if (file.isAbsolute()) {
            if (file.isFile()) {
                return file.getAbsolutePath();
            }
            if (file.isDirectory()) {
                Path found = findInFolder(file.toPath(), program, windows);
                // Not found: name the program where it was expected, so the start error says
                // "…/bin/nvim: No such file" rather than "is a directory".
                return (found != null ? found : file.toPath().resolve("bin").resolve(program)).toString();
            }
            return name;
        }
        if (name.contains("/") || name.contains(File.separator) || isAvailable(name, pathEnv, windows)) {
            return name;
        }
        for (Path dir : searchFolders) {
            Path found = findInFolder(dir, name, windows);
            if (found != null) {
                return found.toString();
            }
        }
        return name;
    }

    /**
     * Finds {@code program} in a folder, trying in order:
     * <ol>
     *   <li>{@code folder/bin/nvim}: the folder is Neovim's install folder;</li>
     *   <li>{@code folder/nvim}: the folder is a {@code bin} folder itself (or Scoop's {@code shims});</li>
     *   <li>{@code folder/nvim-*}{@code /bin/nvim}: a release archive extracted into the folder as
     *       it is, without renaming its top folder, e.g.
     *       {@code ~/.local/opt/neovim/nvim-linux-x86_64/bin/nvim}. With several (versions side by
     *       side, renamed {@code nvim-0.12.6/} and {@code nvim-0.13.0/}), each is asked its version
     *       ({@link InstalledVersions}) and the newest wins. A program that does not run here (an
     *       archive for another processor) or is not Neovim is skipped. Only one candidate: it is
     *       taken without running it.</li>
     * </ol>
     * The first two are a deliberate choice, so they win without asking for a version. On Windows
     * each name is also tried with {@code .exe}, {@code .cmd} and {@code .bat}.
     *
     * @return the absolute path of the program, or {@code null}
     */
    static Path findInFolder(Path folder, String program, boolean windows) {
        return findInFolder(folder, program, windows, InstalledVersions::of);
    }

    /**
     * Testable core of {@link #findInFolder(Path, String, boolean)}.
     *
     * @param versionOf a program's version, or {@code null} if it does not run as Neovim
     */
    static Path findInFolder(Path folder, String program, boolean windows,
            Function<Path, NeovimVersion> versionOf) {
        Path inBin = firstExisting(folder.resolve("bin"), program, windows);
        if (inBin != null) {
            return inBin;
        }
        Path direct = firstExisting(folder, program, windows);
        if (direct != null) {
            return direct;
        }
        List<Path> archives = archivesIn(folder, program, windows);
        if (archives.size() <= 1) {
            return archives.isEmpty() ? null : archives.get(0);
        }
        Path newest = null;
        NeovimVersion newestVersion = null;
        for (Path candidate : archives) {
            NeovimVersion version = versionOf.apply(candidate);
            if (version != null && (newestVersion == null || version.compareTo(newestVersion) > 0)) {
                newest = candidate;
                newestVersion = version;
            }
        }
        return newest;
    }

    /**
     * The programs {@code folder/nvim-*}{@code /bin/nvim} in a folder, sorted by path (so the result
     * does not depend on the file system's order); empty if the folder does not exist or cannot be read.
     */
    public static List<Path> archivesIn(Path folder, String program, boolean windows) {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> archives = Files.newDirectoryStream(folder, "nvim-*")) {
            for (Path archive : archives) {
                Path exe = Files.isDirectory(archive) ? firstExisting(archive.resolve("bin"), program, windows) : null;
                if (exe != null) {
                    found.add(exe);
                }
            }
        } catch (IOException | SecurityException e) {
            return List.of();
        }
        found.sort(null);
        return found;
    }

    private static Path firstExisting(Path dir, String program, boolean windows) {
        for (String candidate : candidates(program, windows)) {
            Path path = dir.resolve(candidate);
            if (Files.isRegularFile(path)) {
                return path.toAbsolutePath();
            }
        }
        return null;
    }

    /** {@code ~} or {@code ~/x} ({@code ~\x}) with the home folder in place of the {@code ~}. */
    public static String expandHome(String path) {
        if (path.equals("~") || path.startsWith("~/") || path.startsWith("~\\")) {
            return System.getProperty("user.home", "~") + path.substring(1);
        }
        return path;
    }

    /**
     * The folder meant for a Neovim used by this plug-in alone: {@code ~/.local/opt/neovim} on macOS
     * and Linux, {@code %USERPROFILE%\.local\opt\neovim} on Windows. Extract a release archive
     * there (its top folder may stay, see {@link #findInFolder}) and leave the preference blank.
     */
    public static Path dedicatedFolder() {
        return Path.of(System.getProperty("user.home", ""), ".local", "opt", "neovim");
    }

    /**
     * Install folders where Neovim is commonly found, searched with {@link #findInFolder} when
     * {@code nvim} is not on {@code PATH}. macOS and Linux: Homebrew (Apple silicon macOS; Intel
     * macOS; Linux system-wide and per-user), MacPorts, user-local installs ({@code ~/.local/bin},
     * {@code ~/bin}, and archives extracted in the home folder, as Neovim's macOS instructions do),
     * the {@code /opt} folders of Neovim's own Linux instructions, snap. Windows: the official
     * installer (all users and per user), Scoop and Chocolatey. Folders that do not exist on a
     * platform are skipped by the search.
     */
    static List<Path> searchFolders() {
        String home = System.getProperty("user.home", "");
        List<Path> dirs = new ArrayList<>();
        if (PlatformUtil.isWindows()) {
            addIfSet(dirs, System.getenv("ProgramFiles"), "Neovim");
            addIfSet(dirs, System.getenv("LOCALAPPDATA"), "Programs", "Neovim");
            addIfSet(dirs, home, "scoop", "shims");
            addIfSet(dirs, home, "scoop", "apps", "neovim", "current");
            addIfSet(dirs, System.getenv("ProgramData"), "chocolatey", "bin");
        } else {
            dirs.add(Path.of("/opt/homebrew"));
            dirs.add(Path.of("/usr/local"));
            dirs.add(Path.of("/home/linuxbrew/.linuxbrew"));
            addIfSet(dirs, home, ".linuxbrew");
            dirs.add(Path.of("/opt/local"));
            addIfSet(dirs, home, ".local");
            addIfSet(dirs, home);
            dirs.add(Path.of("/opt/nvim-linux-x86_64"));
            dirs.add(Path.of("/opt/nvim-linux-arm64"));
            dirs.add(Path.of("/opt/nvim"));
            dirs.add(Path.of("/snap"));
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
     * ({@code g:neovim4eclipse_channel}, its mappings) arrives after the config has run. {@code --cmd}
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
        return locate(command, pathEnv, windows) != null;
    }

    /**
     * The file a resolved command stands for: the path itself if it is absolute, else the first match
     * on the real {@code PATH}. Used to show which {@code nvim} was found, e.g. on the preference page.
     *
     * @param resolved what {@link #resolve(String)} returned
     * @return the absolute path, or {@code null} if no such file exists
     */
    public static Path locate(String resolved) {
        return locate(resolved, System.getenv("PATH"), PlatformUtil.isWindows());
    }

    /** Testable core of {@link #locate(String)}. */
    static Path locate(String command, String pathEnv, boolean windows) {
        if (new File(command).isAbsolute()) {
            Path path = Path.of(command);
            return Files.isRegularFile(path) ? path : null;
        }
        if (pathEnv == null || pathEnv.isBlank()) {
            return null;
        }
        String separator = windows ? ";" : ":";
        for (String dir : pathEnv.split(separator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path found = firstExisting(Path.of(dir), command, windows);
            if (found != null) {
                return found;
            }
        }
        return null;
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
