// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
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
 *   <li><b>A folder</b>: a folder that holds Neovim, at its top ({@code bin/nvim}, as in
 *       {@code C:\Program Files\Neovim}) or further down (release archives extracted into it, as
 *       they are or by Windows Explorer's "Extract All"). See {@link #findInFolder} for the exact
 *       rules.</li>
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

    /** How deep {@link #installsIn} looks: {@code folder/a/b/bin/nvim} is depth 4. */
    private static final int SEARCH_DEPTH = 4;

    private NvimExecutable() {
    }

    /**
     * A Neovim found in a folder.
     *
     * @param program the program
     * @param among   how many working Neovims it was chosen from (1: there was no choice)
     */
    public record Found(Path program, int among) {
    }

    /**
     * What {@link #find(String)} settled on.
     *
     * @param command an absolute path, or a command name for the OS to look up
     * @param among   how many working Neovims in one folder it was chosen from (1: no choice)
     */
    public record Resolved(String command, int among) {
    }

    /**
     * Turns the configured preference value into a token {@link ProcessBuilder} can launch.
     *
     * <p>Only <em>absolute</em> paths are checked against the file system. A relative name such
     * as {@code nvim} is deliberately left alone so it is looked up on {@code PATH}; otherwise a
     * file called {@code nvim} in Eclipse's working directory would silently win.
     *
     * <p>Searching a folder runs {@code nvim --version} on what it finds ({@link InstalledVersions},
     * which remembers the answers), so call this off the SWT UI thread where possible.
     *
     * @param configured preference value: blank, a folder, an absolute path or a command name
     * @return an absolute path to the program, or a command name for the OS to look up (or to
     *         report as missing when the process is started)
     */
    public static String resolve(String configured) {
        return find(configured).command();
    }

    /** {@link #resolve(String)}, also saying how many Neovims the program was chosen from. */
    public static Resolved find(String configured) {
        return find(configured, System.getenv("PATH"), PlatformUtil.isWindows(), dedicatedFolder(),
                searchFolders(), InstalledVersions::of);
    }

    /**
     * Testable core of {@link #find(String)}.
     *
     * @param pathEnv       value of the {@code PATH} variable; may be {@code null}
     * @param windows       whether Windows rules apply ({@code nvim.exe}, {@code ;} separators, suffixes)
     * @param dedicated     the plug-in's own Neovim folder, searched first for a blank preference; may
     *                      be {@code null}
     * @param searchFolders folders checked in order, without searching inside them, when a bare name
     *                      is not on {@code PATH}
     * @param versionOf     a program's version, or {@code null} if it does not run as Neovim
     */
    static Resolved find(String configured, String pathEnv, boolean windows, Path dedicated,
            List<Path> searchFolders, Function<Path, NeovimVersion> versionOf) {
        String program = windows ? "nvim.exe" : "nvim";
        if (configured == null || configured.isBlank()) {
            Found found = dedicated == null ? null : findInFolder(dedicated, program, windows, true, versionOf);
            return found != null ? resolved(found)
                    : find(program, pathEnv, windows, null, searchFolders, versionOf);
        }
        String name = expandHome(configured.strip());
        File file = new File(name);
        if (file.isAbsolute()) {
            if (file.isFile()) {
                return new Resolved(file.getAbsolutePath(), 1); // the program itself: the user's choice
            }
            if (file.isDirectory()) {
                Found found = findInFolder(file.toPath(), program, windows, true, versionOf);
                // Not found: name the program where it was expected, so the start error says
                // "…/bin/nvim: No such file" rather than "is a directory".
                return found != null ? resolved(found)
                        : new Resolved(file.toPath().resolve("bin").resolve(program).toString(), 1);
            }
            return new Resolved(name, 1);
        }
        if (name.contains("/") || name.contains(File.separator) || isAvailable(name, pathEnv, windows)) {
            return new Resolved(name, 1);
        }
        for (Path dir : searchFolders) {
            Found found = findInFolder(dir, name, windows, false, versionOf);
            if (found != null) {
                return resolved(found);
            }
        }
        return new Resolved(name, 1);
    }

    private static Resolved resolved(Found found) {
        return new Resolved(found.program().toString(), found.among());
    }

    /**
     * Finds the newest working Neovim in a folder. The candidates are:
     * <ul>
     *   <li>{@code folder/bin/nvim} and {@code folder/nvim}: the folder is Neovim's install folder,
     *       or a {@code bin} folder itself (or Scoop's {@code shims}). On Windows also with
     *       {@code .exe}, {@code .cmd} and {@code .bat}.</li>
     *   <li>With {@code search}: every install below the folder ({@link #installsIn}), e.g.
     *       {@code ~/.local/opt/neovim/nvim-linux-x86_64/bin/nvim} or, after Windows
     *       Explorer's "Extract All", {@code nvim-win64\nvim-win64\bin\nvim.exe}.</li>
     * </ul>
     * All of them compete: each is asked its version ({@code nvim --version}), one that does not
     * answer as Neovim (an archive for another processor, a broken install, another program called
     * {@code nvim}) is skipped, and the newest wins. Names are not trusted: a folder renamed
     * {@code nvim-0.13.0} may hold 0.12.6. The same file reached twice counts once. On a tie the
     * first candidate in the order above wins.
     *
     * @param search    whether to search below the folder; {@code false} for the usual install
     *                  folders ({@link #searchFolders}), which may be large, such as {@code /usr/local}
     * @param versionOf a program's version, or {@code null} if it does not run as Neovim
     * @return the program and how many working installs it was chosen from, or {@code null}
     */
    static Found findInFolder(Path folder, String program, boolean windows, boolean search,
            Function<Path, NeovimVersion> versionOf) {
        Map<Path, Path> candidates = new LinkedHashMap<>(); // real path -> path, so each program counts once
        for (Path dir : List.of(folder.resolve("bin"), folder)) {
            addOnce(firstExisting(dir, program, windows), candidates);
        }
        if (search) {
            for (Path install : installsIn(folder, program, windows)) {
                addOnce(install, candidates);
            }
        }
        Path newest = null;
        NeovimVersion newestVersion = null;
        int working = 0;
        for (Path candidate : candidates.values()) {
            NeovimVersion version = versionOf.apply(candidate);
            if (version == null) {
                continue;
            }
            working++;
            if (newestVersion == null || version.compareTo(newestVersion) > 0) {
                newest = candidate;
                newestVersion = version;
            }
        }
        return newest == null ? null : new Found(newest, working);
    }

    /** Adds {@code program} unless it is {@code null} or the same file is already there. */
    private static void addOnce(Path program, Map<Path, Path> candidates) {
        if (program == null) {
            return;
        }
        try {
            candidates.putIfAbsent(program.toRealPath(), program);
        } catch (IOException | SecurityException e) {
            // vanished or unreadable meanwhile: not a candidate
        }
    }

    /**
     * How deep {@link #installsIn} looks below a folder, counted to the program:
     * {@code folder/a/b/bin/nvim} is depth 4. Deep enough for an archive extracted with an extra
     * folder around it, shallow enough that choosing a large folder by mistake stays quick.
     */
    public static int maxSearchDepth() {
        return SEARCH_DEPTH;
    }

    /**
     * The Neovim installs below {@code folder}, down to {@link #maxSearchDepth()}: each
     * {@code bin/nvim} ({@code bin\nvim.exe} on Windows) with Neovim's runtime files beside its
     * {@code bin} folder ({@code share/nvim/runtime}). Every release archive and installer has that
     * layout, so stray copies, wrapper scripts and half-built source trees are not taken for one.
     *
     * <p>Links are not followed (on Windows, junctions neither), so a link such as
     * {@code current -> nvim-0.12.6} does not count the same install twice, and a link loop or a
     * link to a large folder does not make the search slow. A folder that cannot be read is skipped.
     *
     * @return the programs, sorted by path so the result does not depend on the file system's order;
     *         empty if the folder does not exist
     */
    public static List<Path> installsIn(Path folder, String program, boolean windows) {
        String exe = windows && !program.toLowerCase(Locale.ROOT).endsWith(".exe") ? program + ".exe" : program;
        Path root;
        try {
            root = folder.toRealPath();
        } catch (IOException | SecurityException e) {
            return List.of();
        }
        Map<Path, Path> found = new LinkedHashMap<>(); // real path -> path, so each program counts once
        try {
            // The walk's depth counts folders; a bin folder at depth 3 holds the program at depth 4.
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), maxSearchDepth() - 1,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (!dir.equals(root) && !isPlainFolder(dir, attrs)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            addIfInstall(dir, exe, windows, found);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                            // Folders at the depth limit arrive here instead of in preVisitDirectory.
                            if (attrs.isDirectory() && isPlainFolder(path, attrs)) {
                                addIfInstall(path, exe, windows, found);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path path, IOException e) {
                            return FileVisitResult.CONTINUE; // unreadable: skip it
                        }
                    });
        } catch (IOException e) {
            return List.of();
        }
        List<Path> programs = new ArrayList<>(found.values());
        programs.sort(null);
        return programs;
    }

    /**
     * Whether {@code dir} is a folder of its own rather than a link to one. The walk does not follow
     * symbolic links, but Java may report a Windows junction as a plain folder; its real path gives
     * it away.
     */
    private static boolean isPlainFolder(Path dir, BasicFileAttributes attrs) {
        if (attrs.isSymbolicLink() || attrs.isOther()) {
            return false;
        }
        try {
            return dir.toRealPath().equals(dir);
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    /** Adds {@code dir/exe} if {@code dir} is a {@code bin} folder with Neovim's runtime beside it. */
    private static void addIfInstall(Path dir, String exe, boolean windows, Map<Path, Path> found) {
        String name = String.valueOf(dir.getFileName());
        if (!(windows ? name.equalsIgnoreCase("bin") : name.equals("bin")) || dir.getParent() == null) {
            return;
        }
        Path program = dir.resolve(exe);
        if (!Files.isRegularFile(program)
                || !Files.isDirectory(dir.getParent().resolve("share").resolve("nvim").resolve("runtime"))) {
            return;
        }
        try {
            found.putIfAbsent(program.toRealPath(), program);
        } catch (IOException | SecurityException e) {
            // vanished or unreadable meanwhile: not an install
        }
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
     * Install folders where Neovim is commonly found, checked (not searched, see
     * {@link #findInFolder}) when {@code nvim} is not on {@code PATH}. macOS and Linux: Homebrew
     * (Apple silicon macOS; Intel macOS and {@code make install} from source; Linux system-wide and
     * per-user), MacPorts, the per-user {@code ~/.local} and {@code ~/bin}, the {@code /opt} folders
     * of Neovim's own Linux instructions. Windows: the official installer (all users and per user),
     * Scoop and Chocolatey. Folders that do not exist are skipped.
     */
    static List<Path> searchFolders() {
        return searchFolders(PlatformUtil.isWindows());
    }

    /** {@link #searchFolders()} for the given platform. */
    static List<Path> searchFolders(boolean windows) {
        String home = System.getProperty("user.home", "");
        List<Path> dirs = new ArrayList<>();
        if (windows) {
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
            addIfSet(dirs, home, "bin");
            dirs.add(Path.of("/opt/nvim-linux-x86_64"));
            dirs.add(Path.of("/opt/nvim-linux-arm64"));
            dirs.add(Path.of("/opt/nvim"));
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
