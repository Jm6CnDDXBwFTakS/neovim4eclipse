// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Says which Neovim the plug-in runs, and builds the {@code --embed} command line.
 *
 * <h2>What the option may hold</h2>
 * The value comes from the startup option {@code -Dneovim4eclipse.nvim} ({@link NeovimOption}; the
 * "Neovim program" preference until 0.2.1).
 * <ul>
 *   <li><b>Unset or blank</b> (the default): the dedicated Neovim, the one <i>Get Neovim</i> installs
 *       ({@link PluginNeovim}: {@code ~/.local/share/neovim4eclipse-plugin/neovim/la/bin/nvim} on
 *       Linux arm64), and only if it is {@link PluginNeovim#verified verified}: every file matches
 *       the release the plug-in pins. Nothing else is looked for: not {@code PATH}, not the usual
 *       install folders, not the folders below one.</li>
 *   <li><b>A program</b>: the absolute path of one Neovim program, used as it is, unchecked and
 *       unsupported: for a system with no official build (Alpine and other musl Linux, NixOS without
 *       nix-ld, FreeBSD), a Neovim built by hand, or the tests' own. A leading {@code ~} stands for
 *       the home folder ({@code ~/opt/nvim/bin/nvim}).</li>
 * </ul>
 * A dedicated Neovim's own program, named by the option, is that install: checked like the
 * dedicated Neovim, not run unchecked. Anything else is refused when Neovim starts ({@link NeovimNotFoundException}): a folder (the
 * program inside it must be named), a command name ({@code nvim}) or a relative path
 * ({@code bin/nvim}).
 *
 * <h2>Why nothing is searched</h2>
 * Until 0.2.1 a blank value also searched {@code PATH} and the usual install folders, and a
 * folder was searched up to four levels down, running {@code nvim --version} on every
 * {@code bin/nvim} it found there. The 0.2.1 security review found that this ran programs the user
 * never named: choosing {@code ~/src} by mistake would run a {@code bin/nvim} planted in a cloned
 * repository below it. Now the plug-in runs only a Neovim it installed and checked, or the one
 * program the user named; and searching {@code PATH} had its own trouble anyway (Eclipse started
 * from the macOS Dock or a desktop menu gets a minimal {@code PATH}, so a Homebrew Neovim was found
 * or not depending on how Eclipse started).
 */
public final class NvimExecutable {

    private NvimExecutable() {
    }

    /** What the option's value names, as {@link #find(String)} sees it. */
    public enum Kind {
        /** Blank, and the dedicated Neovim is installed and verified. */
        DEDICATED,
        /** Blank, and no verified dedicated Neovim is installed: Get Neovim is what helps. */
        NO_DEDICATED,
        /** An absolute path to a file: the user's own Neovim, unchecked. */
        PROGRAM,
        /** An absolute path to a folder: refused, the program inside it must be named. */
        FOLDER,
        /** An absolute path where nothing is. */
        MISSING,
        /** Not an absolute path ({@code nvim}, {@code bin/nvim}): refused. */
        NOT_ABSOLUTE
    }

    /**
     * What {@link #find(String)} settled on.
     *
     * @param command the absolute path of the program for {@link Kind#DEDICATED} and
     *                {@link Kind#PROGRAM}; otherwise what was looked for (the value as entered, or
     *                the dedicated program's path for a blank value), which {@link #embedCommand}
     *                refuses to start
     * @param kind    which case it is
     */
    public record Resolved(String command, Kind kind) {

        /** Whether Neovim can be started with it. */
        public boolean startable() {
            return kind == Kind.DEDICATED || kind == Kind.PROGRAM;
        }
    }

    /**
     * Turns the option's value into the program to start.
     *
     * <p>Verifying the dedicated Neovim hashes its files the first time (about 0.2 s,
     * {@link PluginNeovim#verified}), so call this off the SWT UI thread where possible.
     *
     * @param configured the option's value ({@link NeovimOption#value}): blank, or the absolute path of a Neovim program
     * @return the absolute path of the program; if there is none, what was looked for (see
     *         {@link Resolved#command})
     */
    public static String resolve(String configured) {
        return find(configured).command();
    }

    /** {@link #resolve(String)}, also saying which case the value is. */
    public static Resolved find(String configured) {
        boolean windows = PlatformUtil.isWindows();
        return find(configured, windows, PluginNeovim.searchHomes(), home -> PluginNeovim.verified(home, windows),
                InstalledVersions::of);
    }

    /**
     * Testable core of {@link #find(String)}.
     *
     * @param windows     whether Windows rules apply ({@code nvim.exe})
     * @param pluginHomes the dedicated Neovim's folders in the order to try ({@link PluginNeovim#searchHomes()})
     * @param verified    whether a folder holds a verified install ({@link PluginNeovim#verified})
     * @param versionOf   a program's version, or {@code null} if it does not run as Neovim: asked
     *                    only of a verified install, to pass over one for another processor that
     *                    cannot run here
     */
    static Resolved find(String configured, boolean windows, List<Path> pluginHomes, Predicate<Path> verified,
            Function<Path, NeovimVersion> versionOf) {
        if (configured == null || configured.isBlank()) {
            for (Path home : pluginHomes) {
                Path program = PluginNeovim.program(home, windows).toAbsolutePath();
                if (verified.test(home) && versionOf.apply(program) != null) {
                    return new Resolved(program.toString(), Kind.DEDICATED);
                }
            }
            Path expected = pluginHomes.isEmpty() ? Path.of(windows ? "nvim.exe" : "nvim")
                    : PluginNeovim.program(pluginHomes.get(0), windows).toAbsolutePath();
            return new Resolved(expected.toString(), Kind.NO_DEDICATED);
        }
        String name = expandHome(configured.strip());
        File file = new File(name);
        if (!file.isAbsolute()) {
            return new Resolved(name, Kind.NOT_ABSOLUTE);
        }
        // A dedicated Neovim's own program, named by the option (the tests' -Dnvim.path often is):
        // that install, checked before every start like the dedicated Neovim and called dedicated by
        // the notes, not run unchecked. That very install, not the first verified one: on Apple
        // silicon, naming neovim/mx/bin/nvim (to try the x86_64 build) runs mx, not ma.
        for (Path home : pluginHomes) {
            Path program = PluginNeovim.program(home, windows).toAbsolutePath();
            if (FilePaths.same(file.toPath().toAbsolutePath(), program)) {
                return verified.test(home) && versionOf.apply(program) != null
                        ? new Resolved(program.toString(), Kind.DEDICATED)
                        : new Resolved(program.toString(), Kind.NO_DEDICATED);
            }
        }
        if (file.isFile()) {
            return new Resolved(file.getAbsolutePath(), Kind.PROGRAM);
        }
        return new Resolved(name, file.isDirectory() ? Kind.FOLDER : Kind.MISSING);
    }

    /** {@code ~} or {@code ~/x} ({@code ~\x}) with the home folder in place of the {@code ~}. */
    public static String expandHome(String path) {
        if (path.equals("~") || path.startsWith("~/") || path.startsWith("~\\")) {
            return System.getProperty("user.home", "~") + path.substring(1);
        }
        return path;
    }

    /** The folder the dedicated Neovim lives in ({@link PluginFolders#neovimFolder()}). */
    public static Path dedicatedFolder() {
        return PluginFolders.neovimFolder();
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
     * @param configured the option's value ({@link NeovimOption#value}): blank, or the absolute path of a Neovim program
     * @param extraArgs  optional extra arguments, e.g. {@code --clean}; may be {@code null}
     * @throws NeovimNotFoundException if the value names no program to start ({@link Resolved#startable})
     */
    public static List<String> embedCommand(String configured, List<String> extraArgs) {
        Resolved resolved = find(configured);
        if (!resolved.startable()) {
            throw notFound(resolved, configured);
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(resolved.command());
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

    /**
     * Why {@link #embedCommand} refuses {@code configured} (the {@link NeovimOption} value). Example:
     * {@code /opt/nvim (from -Dneovim4eclipse.nvim) is a folder: name the Neovim program inside it,
     * such as /opt/nvim/bin/nvim}.
     */
    static NeovimNotFoundException notFound(Resolved resolved, String configured) {
        String value = configured == null ? "" : configured.strip();
        String program = defaultCommandName();
        return new NeovimNotFoundException(switch (resolved.kind()) {
            case NO_DEDICATED -> "The dedicated Neovim is missing, or its files differ from Neovim "
                    + NeovimRelease.bundled().version() + "'s release (" + resolved.command() + ")";
            case FOLDER -> value + " (from -D" + NeovimOption.PROPERTY + ") is a folder: name the Neovim program "
                    + "inside it, such as "
                    + Path.of(resolved.command()).resolve("bin").resolve(program);
            case MISSING -> "No Neovim program at " + value + " (from -D" + NeovimOption.PROPERTY + ")";
            case NOT_ABSOLUTE -> "-D" + NeovimOption.PROPERTY + " must name the absolute path of a Neovim program "
                    + "(a leading ~ is fine): " + value;
            case DEDICATED, PROGRAM -> throw new IllegalArgumentException("startable: " + resolved);
        });
    }

    /** Whether the configured value names a Neovim that can be started. */
    public static boolean looksAvailable(String configured) {
        return find(configured).startable();
    }

    /**
     * {@code resolved} as a path if it is an absolute path to a file, else {@code null}: which
     * {@code nvim} {@link #resolve(String)} found, e.g. for the preference page's note.
     */
    public static Path locate(String resolved) {
        if (resolved == null || !new File(resolved).isAbsolute()) {
            return null;
        }
        Path path = Path.of(resolved);
        return Files.isRegularFile(path) ? path : null;
    }
}
