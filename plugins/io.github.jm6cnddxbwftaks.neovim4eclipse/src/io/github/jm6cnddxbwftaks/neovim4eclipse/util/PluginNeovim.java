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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The plug-in's own Neovim: where it lives and what counts as one.
 *
 * <h2>Layout</h2>
 * <pre>
 *   &lt;data home&gt;/neovim4eclipse-plugin/neovim/     {@link PluginFolders#neovimFolder()}
 *     la/          the one Neovim for Linux arm64: bin/nvim, lib/, share/nvim/runtime/
 *     lx/          another platform's (the same home folder on an x86_64 machine)
 *     la.unpacking/  an archive being unpacked and checked ({@link NeovimInstaller})
 *     la.new/      a checked archive, unpacked, waiting to replace la/
 *     .install.lock  held while an Eclipse installs, so two do not at once
 * </pre>
 * One Neovim per {@link NeovimPlatform} code, in a folder named after the code: no version folders,
 * no {@code current} link, no choice to make. The path to the plug-in's Neovim is stable
 * ({@code neovim/la/bin/nvim}), so a user can name it elsewhere. A user who needs another Neovim
 * (a system without an official build, such as Alpine) names its program in the preference,
 * unchecked and unsupported ({@link NvimExecutable}).
 *
 * <h2>Checked at start</h2>
 * The plug-in runs this Neovim only if it is {@link #verified}: every file matches the release's
 * {@link InstallTree tree hash}, pinned in {@code neovim-downloads.properties}. A Neovim left from an
 * older plug-in, a damaged copy or a file someone changed all fail the check alike, and Get Neovim
 * offers to replace it; nothing of it runs before (not even {@code --version}).
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

    /**
     * Suffix of the folder an archive is unpacked into and checked. Only a folder that passed every
     * check is renamed to {@link #NEW}, so an unpack cut short (Eclipse killed halfway) is never
     * taken for a waiting Neovim; the next install or clean-up deletes it.
     */
    public static final String UNPACKING = ".unpacking";

    /** Suffix of the folder a checked, unpacked archive waits in until it replaces the install. */
    public static final String NEW = ".new";

    /** Suffix of the folder the previous install moves to during the swap, then deleted. */
    public static final String OLD = ".old";

    /**
     * Start of a temporary file in {@code neovim/}: a download ({@link NeovimDownload}), or the
     * checked copy of an archive ({@link NeovimInstaller}).
     */
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
     * The plug-in's own Neovim that is installed but fails the check: older than the release this
     * plug-in pins (left from before an update of the plug-in), damaged, or changed.
     *
     * @param program where it is
     */
    public record Unverified(Path program) {
    }

    /**
     * The first of {@code homes} that holds an install that is not {@link #verified}. It is passed
     * over ({@link NvimExecutable}); the preference note and the "Neovim not started" notice say
     * that <i>Get Neovim</i> replaces it.
     */
    public static Optional<Unverified> unverified(List<Path> homes, boolean windows, NeovimRelease release) {
        for (Path home : homes) {
            if (isInstall(home, windows) && !verified(home, windows, release)) {
                return Optional.of(new Unverified(program(home, windows).toAbsolutePath()));
            }
        }
        return Optional.empty();
    }

    /** {@link #verified(Path, boolean, NeovimRelease)} against the release this plug-in pins. */
    public static boolean verified(Path home, boolean windows) {
        return verified(home, windows, NeovimRelease.bundled());
    }

    /**
     * Whether {@code home} holds an install whose files are exactly the release's for its platform
     * (the folder's name, {@code la}): its {@link InstallTree tree hash} equals the pinned one.
     * Hashing takes about 0.1 to 0.2 s, so the answer is kept until the install changes (its folders'
     * and program's modification times, which a swap or a reinstall changes) or {@link #forget} is
     * called: the Activator forgets before every Neovim start after the first, so each start is
     * checked in full (the first uses EarlyStartup's check, made in the background at Eclipse's
     * start). Call this off the UI thread where possible.
     */
    public static boolean verified(Path home, boolean windows, NeovimRelease release) {
        Optional<NeovimPlatform> platform = NeovimPlatform.byCode(String.valueOf(home.getFileName()));
        Optional<String> expected = platform.flatMap(release::forPlatform).map(NeovimRelease.Archive::tree);
        if (expected.isEmpty() || !isInstall(home, windows)) {
            return false;
        }
        Path key = home.toAbsolutePath().normalize();
        String stamp = expected.get() + " " + stamp(home, windows);
        Verdict known = VERDICTS.get(key);
        if (known != null && known.stamp().equals(stamp)) {
            return known.matches();
        }
        // A Finder or Explorer file is deleted, not skipped (InstallTree, point 1).
        boolean matches = InstallTree.removeSystemFiles(home)
                && InstallTree.of(home).map(expected.get()::equals).orElse(false);
        VERDICTS.put(key, new Verdict(stamp, matches));
        return matches;
    }

    /** Forgets every kept answer of {@link #verified}: after an install, so the next start checks again. */
    public static void forget() {
        VERDICTS.clear();
    }

    private record Verdict(String stamp, boolean matches) {
    }

    private static final Map<Path, Verdict> VERDICTS = new ConcurrentHashMap<>();

    /** What changes when the install is replaced or touched: modification times of its main parts. */
    private static String stamp(Path home, boolean windows) {
        StringBuilder s = new StringBuilder();
        for (Path part : List.of(home, home.resolve("bin"), program(home, windows),
                home.resolve("share").resolve("nvim").resolve("runtime"))) {
            try {
                s.append(Files.getLastModifiedTime(part).toMillis()).append(' ');
            } catch (IOException | SecurityException e) {
                s.append("- ");
            }
        }
        return s.toString();
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
     * {@code .old} folders, the lock file, download leftovers (cleaned up at each start) and the
     * file manager's own files ({@link #isSystemFile}), sorted by name. Example:
     * {@code [current, nvim-linux-arm64_0.12.6]} after an earlier hand install. Empty if the folder
     * does not exist or cannot be read.
     */
    public static List<String> unusedEntries(Path folder) {
        Set<String> known = new LinkedHashSet<>();
        known.add(LOCK);
        for (NeovimPlatform p : NeovimPlatform.ALL) {
            known.add(p.code());
            known.add(p.code() + UNPACKING);
            known.add(p.code() + NEW);
            known.add(p.code() + OLD);
        }
        List<String> unused = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                String name = String.valueOf(entry.getFileName());
                if (!known.contains(name) && !name.startsWith(DOWNLOAD_PREFIX) && !isReplaced(name)
                        && !isSystemFile(name)) {
                    unused.add(name);
                }
            }
        } catch (IOException | SecurityException e) {
            return List.of();
        }
        unused.sort(null);
        return unused;
    }

    /**
     * A replaced Neovim waiting to be deleted: {@code la.old}, or one set aside by
     * {@code NeovimInstaller.swap} ({@code la.old-123}).
     */
    static boolean isReplaced(String name) {
        return NeovimPlatform.ALL.stream()
                .anyMatch(p -> name.equals(p.code() + OLD) || name.startsWith(p.code() + OLD + "-"));
    }

    /**
     * A file the operating system's file manager puts into any folder it shows: macOS Finder's
     * {@code .DS_Store} and {@code ._name} (AppleDouble) files, Windows Explorer's
     * {@code desktop.ini} and {@code Thumbs.db}. Not the user's leftovers, so not listed for deleting.
     */
    static boolean isSystemFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals(".ds_store") || name.startsWith("._") || lower.equals("desktop.ini")
                || lower.equals("thumbs.db");
    }
}
