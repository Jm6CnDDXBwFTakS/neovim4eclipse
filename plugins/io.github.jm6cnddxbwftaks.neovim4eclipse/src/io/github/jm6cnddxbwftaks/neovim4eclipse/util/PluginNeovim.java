// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The plug-in's own Neovim: where it lives and what counts as one.
 *
 * <h2>Layout</h2>
 * <pre>
 *   &lt;data home&gt;/neovim4eclipse-plugin/neovim/     {@link PluginFolders#neovimFolder()}
 *     la/          the one Neovim for Linux arm64: bin/nvim, lib/, share/nvim/runtime/
 *     lx/          another platform's (the same home folder on an x86_64 machine)
 *     la.new/      a checked archive, unpacked, waiting to replace la/ ({@link NeovimInstaller})
 *     .install.lock  held while an Eclipse installs, so two do not at once
 * </pre>
 * One Neovim per {@link NeovimPlatform} code, in a folder named after the code: no version folders,
 * no {@code current} link, no choice to make. The path to the plug-in's Neovim is stable
 * ({@code neovim/la/bin/nvim}), so a user can name it elsewhere. A user who wants another version
 * installs it anywhere else and names it in the preference.
 *
 * <h2>Strict</h2>
 * Only {@code neovim/<code>/bin/nvim} ({@code bin\nvim.exe} on Windows) with Neovim's runtime files
 * beside its {@code bin} ({@code share/nvim/runtime}) counts. Anything else in {@code neovim/}, such
 * as an archive extracted there by hand before 0.2.1 ({@code nvim-linux-arm64_0.12.6/},
 * {@code current/}), is not searched, and never moved or deleted: {@link #unusedEntries} lists it so
 * the preference page and the "Neovim not started" notice can say it is no longer used.
 */
public final class PluginNeovim {

    /** The lock file's name in {@code neovim/}. */
    public static final String LOCK = ".install.lock";

    /** Suffix of the folder a checked archive is unpacked into before it replaces the install. */
    public static final String NEW = ".new";

    /** Suffix of the folder the previous install moves to during the swap, then deleted. */
    public static final String OLD = ".old";

    /** Start of a download's temporary file in {@code neovim/} ({@link NeovimDownload}). */
    public static final String DOWNLOAD_PREFIX = ".download-";

    private PluginNeovim() {
    }

    /** {@code neovim/<code>} in {@code folder}. */
    public static Path home(Path folder, NeovimPlatform platform) {
        return folder.resolve(platform.code());
    }

    /** {@code home/bin/nvim}, or {@code home\bin\nvim.exe} on Windows. */
    public static Path program(Path home, boolean windows) {
        return home.resolve("bin").resolve(windows ? "nvim.exe" : "nvim");
    }

    /** Whether {@code home} holds an install: the program, and the runtime files beside {@code bin}. */
    public static boolean isInstall(Path home, boolean windows) {
        return Files.isRegularFile(program(home, windows))
                && Files.isDirectory(home.resolve("share").resolve("nvim").resolve("runtime"));
    }

    /**
     * The plug-in's own Neovim that is installed but older than {@link NeovimVersion#MINIMUM}.
     *
     * @param program where it is
     * @param version what it reports
     */
    public record Outdated(Path program, NeovimVersion version) {
    }

    /**
     * The first of {@code homes} that holds an install older than {@link NeovimVersion#MINIMUM}: left
     * from before an update of the plug-in raised the minimum. The search passes over it
     * ({@link NvimExecutable}); the preference note and the "Neovim not started" notice say that
     * <i>Get Neovim</i> replaces it. Runs {@code nvim --version} ({@code versionOf}).
     */
    public static Optional<Outdated> outdated(List<Path> homes, boolean windows,
            Function<Path, NeovimVersion> versionOf) {
        for (Path home : homes) {
            if (isInstall(home, windows)) {
                Path program = program(home, windows).toAbsolutePath();
                NeovimVersion version = versionOf.apply(program);
                if (version != null && !version.isSupported()) {
                    return Optional.of(new Outdated(program, version));
                }
            }
        }
        return Optional.empty();
    }

    /** {@link #searchHomes(Path, Optional, Optional)} for this machine's plug-in folder. */
    public static List<Path> searchHomes() {
        return searchHomes(PluginFolders.neovimFolder(), ProcessorProbe.known(), NeovimPlatform.current());
    }

    /**
     * The installs a blank preference looks at, in order: the real processor's
     * ({@link ProcessorProbe}), Java's own, then the same system's other processor. Usually all
     * three are the same one or two folders. Under emulation the native Neovim comes first, and the
     * emulated one still works if it is the only one (an x86_64 process can start an arm64 Neovim on
     * Apple silicon and arm64 Windows, and the other way round under emulation); one that cannot run
     * here is skipped because it does not answer {@code --version}.
     *
     * @param real the real platform, if known
     * @param java the platform Java reports
     */
    static List<Path> searchHomes(Path folder, Optional<NeovimPlatform> real, Optional<NeovimPlatform> java) {
        Set<NeovimPlatform> order = new LinkedHashSet<>();
        real.ifPresent(order::add);
        java.ifPresent(order::add);
        for (NeovimPlatform known : List.copyOf(order)) {
            for (NeovimPlatform p : NeovimPlatform.ALL) {
                if (p.os().equals(known.os())) {
                    order.add(p);
                }
            }
        }
        List<Path> homes = new ArrayList<>();
        for (NeovimPlatform p : order) {
            homes.add(home(folder, p));
        }
        return homes;
    }

    /**
     * What else is in {@code folder}: everything but the platform folders, their {@code .new} and
     * {@code .old} folders, the lock file and download leftovers, sorted by name. Example:
     * {@code [current, nvim-linux-arm64_0.12.6]} after an earlier hand install. Empty if the folder
     * does not exist or cannot be read.
     */
    public static List<String> unusedEntries(Path folder) {
        Set<String> known = new LinkedHashSet<>();
        known.add(LOCK);
        for (NeovimPlatform p : NeovimPlatform.ALL) {
            known.add(p.code());
            known.add(p.code() + NEW);
            known.add(p.code() + OLD);
        }
        List<String> unused = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                String name = String.valueOf(entry.getFileName());
                if (!known.contains(name) && !name.startsWith(DOWNLOAD_PREFIX)) {
                    unused.add(name);
                }
            }
        } catch (IOException | SecurityException e) {
            return List.of();
        }
        unused.sort(null);
        return unused;
    }
}
