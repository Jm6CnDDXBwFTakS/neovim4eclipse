// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Function;

/**
 * Installs a release archive as the plug-in's own Neovim ({@link PluginNeovim}), whether
 * {@link NeovimDownload} fetched it or the user picked the file (<i>Install from File…</i>).
 *
 * <h2>Steps</h2>
 * <ol>
 *   <li><b>Lock</b> {@code neovim/.install.lock}, so two Eclipses (or two clicks) never install at
 *       once.</li>
 *   <li><b>Check</b> the archive's size and SHA-256 against the pinned entry for this platform
 *       ({@link NeovimRelease}). Only that exact archive is accepted. If its hash belongs to another
 *       platform's entry, the message names the archive this machine needs.</li>
 *   <li><b>Unpack</b> into {@code neovim/<code>.new/} ({@link ArchiveExtractor}), replacing any
 *       leftover from an earlier attempt.</li>
 *   <li><b>Run</b> its {@code bin/nvim --version} and require exactly the release's version: a Linux
 *       build that cannot run here (on a musl system) fails now, with a clear message, instead of at
 *       the next start.</li>
 *   <li><b>Swap</b>: {@code <code>} becomes {@code <code>.old}, {@code <code>.new} becomes
 *       {@code <code>}, then {@code .old} is deleted. Unless Neovim runs from {@code <code>} right now:
 *       Windows refuses to move a running {@code nvim.exe}, and elsewhere a running Neovim would lose
 *       its runtime files. Then {@code <code>.new} waits, and {@link #finishPending} swaps it before the
 *       next Neovim starts (<i>Restart Neovim</i>).</li>
 * </ol>
 * The archive itself is only read: a downloaded one is the caller's temporary file, and a picked one
 * the user's, never moved or deleted. On any failure the {@code .new} folder is removed and the
 * installed Neovim stays as it was.
 *
 * <p>Example: {@code install(archive, release, la, folder, false, InstalledVersions::of, progress)}
 * leaves {@code folder/la/bin/nvim} answering {@code NVIM v0.12.6} and returns
 * {@code INSTALLED}.
 */
public final class NeovimInstaller {

    private NeovimInstaller() {
    }

    /** Whether the new Neovim is in use already, or waits for the next start. */
    public enum Outcome {
        /** In {@code neovim/<code>/}: the next Neovim started uses it. */
        INSTALLED,
        /** Checked and unpacked in {@code neovim/<code>.new/}; swapped in when Neovim restarts. */
        WAITS_FOR_RESTART
    }

    /**
     * What was installed.
     *
     * @param outcome  installed, or waiting for a restart
     * @param program  where the program is (or will be, after the restart): {@code neovim/la/bin/nvim}
     * @param sha256   the archive's SHA-256, for the done note
     */
    public record Result(Outcome outcome, Path program, String sha256) {
    }

    /**
     * Checks, unpacks and installs {@code archive}; see the class comment.
     *
     * @param folder    the plug-in's Neovim folder ({@link PluginFolders#neovimFolder()})
     * @param inUse     whether Neovim runs from {@code folder/<code>} right now
     * @param versionOf a program's version ({@link InstalledVersions#of}), {@code null} if it does not run
     * @throws NeovimInstallException with a message for the user
     */
    public static Result install(Path archive, NeovimRelease release, NeovimPlatform platform, Path folder,
            boolean inUse, Function<Path, NeovimVersion> versionOf, InstallProgress progress) {
        NeovimRelease.Archive expected = release.forPlatform(platform).orElseThrow(
                () -> new NeovimInstallException(NeovimInstallException.Reason.WRONG_PLATFORM,
                        "Neovim " + release.version() + " has no release archive for " + platform.describe() + "."));
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw files("Cannot create " + folder, e);
        }
        try (FileChannel channel = FileChannel.open(folder.resolve(PluginNeovim.LOCK), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock lock = tryLock(channel)) {
            if (lock == null) {
                throw new NeovimInstallException(NeovimInstallException.Reason.BUSY,
                        "Neovim is being installed into " + folder + " already, by this or another Eclipse. "
                                + "Try again when that is done.");
            }
            String sha = check(archive, expected, release, progress);
            Path home = PluginNeovim.home(folder, platform);
            Path staging = folder.resolve(platform.code() + PluginNeovim.NEW);
            boolean keep = false;
            try {
                deleteTree(staging);
                progress.step("Unpacking " + archive.getFileName(), -1);
                ArchiveExtractor.extract(archive, expected.kind(), staging, progress);
                progress.checkCancelled();
                Path program = PluginNeovim.program(staging, platform.windows());
                if (!PluginNeovim.isInstall(staging, platform.windows())) {
                    throw new NeovimInstallException(NeovimInstallException.Reason.WRONG_ARCHIVE,
                            "The archive holds no bin/nvim with its runtime files.");
                }
                progress.step("Checking that Neovim runs", -1);
                NeovimVersion version = versionOf.apply(program);
                if (version == null) {
                    throw new NeovimInstallException(NeovimInstallException.Reason.DOES_NOT_RUN,
                            "The unpacked Neovim does not run on this machine (" + program + " --version failed). "
                                    + "The official Linux builds need glibc; on a musl system such as Alpine, "
                                    + "install Neovim from the system's packages and enter its path in the "
                                    + "preferences.");
                }
                if (!version.equals(release.version())) {
                    throw new NeovimInstallException(NeovimInstallException.Reason.DOES_NOT_RUN,
                            "The unpacked Neovim reports version " + version + ", not " + release.version() + ".");
                }
                keep = true;
                Path finalProgram = PluginNeovim.program(home, platform.windows());
                if (inUse || !swap(folder, platform)) {
                    return new Result(Outcome.WAITS_FOR_RESTART, finalProgram, sha);
                }
                return new Result(Outcome.INSTALLED, finalProgram, sha);
            } catch (IOException e) {
                throw files("Cannot unpack into " + staging + ": " + e.getMessage(), e);
            } finally {
                if (!keep) {
                    deleteTreeQuietly(staging);
                }
            }
        } catch (IOException e) {
            throw files("Cannot lock " + folder.resolve(PluginNeovim.LOCK) + ": " + e.getMessage(), e);
        }
    }

    /** The lock, or {@code null} if another process (or another thread of this one) holds it. */
    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return null; // held by this Eclipse already
        }
    }

    /**
     * Compares the archive with the pinned entry.
     *
     * @return its SHA-256
     * @throws NeovimInstallException WRONG_PLATFORM or WRONG_ARCHIVE, naming both hashes
     */
    static String check(Path archive, NeovimRelease.Archive expected, NeovimRelease release,
            InstallProgress progress) {
        long size;
        try {
            size = Files.size(archive);
        } catch (IOException e) {
            throw files("Cannot read " + archive + ": " + e.getMessage(), e);
        }
        progress.step("Checking the SHA-256 of " + archive.getFileName(), size);
        String sha = sha256(archive, progress);
        if (sha.equals(expected.sha256()) && size == expected.size()) {
            return sha;
        }
        var other = release.bySha256(sha);
        if (other.isPresent()) {
            throw new NeovimInstallException(NeovimInstallException.Reason.WRONG_PLATFORM,
                    "This is the archive for " + other.get().platform().describe() + " (" + other.get().asset()
                            + "); this Eclipse needs " + expected.asset() + ", for " + expected.platform().describe()
                            + ".");
        }
        throw new NeovimInstallException(NeovimInstallException.Reason.WRONG_ARCHIVE,
                archive.getFileName() + " is not the Neovim " + release.version() + " archive for "
                        + expected.platform().describe() + " (another version, or damaged).\n"
                        + "Expected " + expected.asset() + ": " + expected.size() + " bytes, SHA-256 " + expected.sha256()
                        + "\nThis file: " + size + " bytes, SHA-256 " + sha);
    }

    /** The file's SHA-256 as 64 lower-case hex digits. */
    public static String sha256(Path file, InstallProgress progress) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java has SHA-256", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0;) {
                progress.checkCancelled();
                digest.update(buffer, 0, n);
                progress.worked(n);
            }
        } catch (IOException e) {
            throw files("Cannot read " + file + ": " + e.getMessage(), e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Swaps a waiting {@code <code>.new} in for every platform that has one: called before a Neovim
     * starts, so a download made while Neovim ran is used after <i>Restart Neovim</i>. A folder that
     * is not a complete install (an interrupted unpack) is left for the next install to replace.
     * Failures are ignored: the old Neovim stays, and the swap is tried again at the next start.
     */
    public static void finishPending(Path folder) {
        for (NeovimPlatform platform : NeovimPlatform.ALL) {
            Path staging = folder.resolve(platform.code() + PluginNeovim.NEW);
            if (Files.isDirectory(staging) && PluginNeovim.isInstall(staging, platform.windows())) {
                try (FileChannel channel = FileChannel.open(folder.resolve(PluginNeovim.LOCK),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock lock = tryLock(channel)) {
                    if (lock != null) {
                        swap(folder, platform);
                    }
                } catch (IOException e) {
                    // next start
                }
            }
        }
    }

    /**
     * {@code <code>} to {@code <code>.old}, {@code <code>.new} to {@code <code>}, delete {@code .old}.
     * The caller holds the lock.
     *
     * @return whether {@code <code>.new} is now {@code <code>}; {@code false} if a move failed (Windows:
     *         a file in use), with the old install back in place
     */
    static boolean swap(Path folder, NeovimPlatform platform) {
        Path home = PluginNeovim.home(folder, platform);
        Path staging = folder.resolve(platform.code() + PluginNeovim.NEW);
        Path old = folder.resolve(platform.code() + PluginNeovim.OLD);
        deleteTreeQuietly(old); // a leftover of an earlier swap whose delete failed
        boolean hadOld = Files.exists(home);
        try {
            if (hadOld) {
                Files.move(home, old);
            }
        } catch (IOException e) {
            return false;
        }
        try {
            Files.move(staging, home);
        } catch (IOException e) {
            if (hadOld) {
                try {
                    Files.move(old, home);
                } catch (IOException back) {
                    // both moves failed: the next start's finishPending tries again from .new
                }
            }
            return false;
        }
        deleteTreeQuietly(old);
        return true;
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteTreeQuietly(Path root) {
        try {
            deleteTree(root);
        } catch (IOException e) {
            // left behind; the next install or swap deletes it
        }
    }

    private static NeovimInstallException files(String message, IOException e) {
        return new NeovimInstallException(NeovimInstallException.Reason.FILES, message, e);
    }
}
