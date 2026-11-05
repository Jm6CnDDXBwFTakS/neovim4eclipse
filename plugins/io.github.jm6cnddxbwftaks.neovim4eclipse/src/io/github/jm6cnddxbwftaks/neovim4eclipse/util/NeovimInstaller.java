// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Installs a release archive as the plug-in's own Neovim ({@link PluginNeovim}), whether
 * {@link NeovimDownload} fetched it or the user picked the file (<i>Install from File…</i>).
 *
 * <h2>Steps</h2>
 * <ol>
 *   <li><b>Lock</b> {@code neovim/.install.lock}, so two Eclipses (or two clicks) never install at
 *       once. The empty file stays after the install on purpose: the lock is the operating system's
 *       lock on the open file, not the file's presence, and deleting it could let a second Eclipse
 *       lock a new file while the first still holds the old one.</li>
 *   <li><b>Copy and check</b>: the archive is copied into a temporary file in {@code neovim/}
 *       (named {@link PluginNeovim#DOWNLOAD_PREFIX}{@code ...}) while its SHA-256 is computed, and the
 *       size and hash are compared with the pinned entry for this platform ({@link NeovimRelease}).
 *       Only that exact archive is accepted. If its hash belongs to another platform's entry, the
 *       message names the archive this machine needs. Why a copy: the archive is read once, and what is
 *       unpacked is exactly what was checked. Reading the user's file twice, to hash and then to unpack,
 *       would unpack whatever it holds by then (a browser still writing it, say). A file of the wrong
 *       size is only hashed, for the message, never copied.</li>
 *   <li><b>Unpack</b> the copy into {@code neovim/<code>.new/} ({@link ArchiveExtractor}), replacing
 *       any leftover from an earlier attempt.</li>
 *   <li><b>Run</b> its {@code bin/nvim --version} and require exactly the release's version: a Linux
 *       build that cannot run here (on a musl system) fails now, with a clear message, instead of at
 *       the next start.</li>
 *   <li><b>Swap</b>: {@code <code>} becomes {@code <code>.old}, {@code <code>.new} becomes
 *       {@code <code>}, then {@code .old} is deleted. Unless a Neovim runs from {@code <code>} right
 *       now, in this Eclipse or in any other process ({@link NeovimProcesses}: every Eclipse of the
 *       user shares the folder): Windows refuses to move a running {@code nvim.exe}, and elsewhere a
 *       running Neovim would lose its runtime files. Then {@code <code>.new} waits, and
 *       {@link #finishPending} swaps it before a Neovim starts once none runs from there (<i>Restart
 *       Neovim</i>, after closing the other Eclipse's). Whether Neovim runs from there is asked only
 *       now, through the caller's {@link SwapGate}, which also keeps this Eclipse's Neovim from
 *       starting during the two moves: a Neovim started while the archive downloaded counts too.</li>
 * </ol>
 * The archive itself is only read: a downloaded one is the caller's temporary file, and a picked one
 * the user's, never moved or deleted. On any failure the {@code .new} folder is removed and the
 * installed Neovim stays as it was.
 *
 * <p>Example: {@code install(archive, release, la, folder, SwapGate.FREE, InstalledVersions::of, progress)}
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
     * Decides, at the moment of the swap, whether Neovim runs from the folder being replaced. The
     * Eclipse side ({@code Activator.swapUnlessRunningFrom}) answers from the running session and
     * holds the lock that starting a session takes, so no Neovim starts between its answer and the
     * end of the two moves. Example: {@code swap -> running ? false : swap.getAsBoolean()}.
     */
    @FunctionalInterface
    public interface SwapGate {
        /** Nothing runs from the folder: swap at once (tests, and a plug-in without a session). */
        SwapGate FREE = BooleanSupplier::getAsBoolean;
        /** Neovim runs from the folder: never swap now, the new one waits for the restart. */
        SwapGate IN_USE = swap -> false;

        /**
         * Runs {@code swap} unless a Neovim runs from the folder it replaces.
         *
         * @return {@code swap}'s answer (whether the new Neovim took the folder's place), or
         *         {@code false} if it did not run
         */
        boolean swapUnlessInUse(BooleanSupplier swap);
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
     * @param gate      asked at the swap whether Neovim runs from {@code folder/<code>} ({@link SwapGate})
     * @param versionOf a program's version ({@link InstalledVersions#of}), {@code null} if it does not run
     * @throws NeovimInstallException with a message for the user
     */
    public static Result install(Path archive, NeovimRelease release, NeovimPlatform platform, Path folder,
            SwapGate gate, Function<Path, NeovimVersion> versionOf, InstallProgress progress) {
        return install(archive, release, platform, folder, gate, versionOf, progress, NeovimProcesses::runFrom);
    }

    /**
     * {@link #install}, with the question whether a process runs from a folder given (tests).
     *
     * @param inUse whether some process runs a program from the folder ({@link NeovimProcesses#runFrom})
     */
    static Result install(Path archive, NeovimRelease release, NeovimPlatform platform, Path folder,
            SwapGate gate, Function<Path, NeovimVersion> versionOf, InstallProgress progress,
            Predicate<Path> inUse) {
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
            Path copy = temporaryFile(folder);
            try {
                String sha = check(archive, copy, expected, release, progress);
                return unpackAndSwap(copy, archive.getFileName(), sha, expected, release, platform, folder, gate,
                        versionOf, progress, inUse);
            } finally {
                NeovimDownload.deleteQuietly(copy);
            }
        } catch (IOException e) {
            throw files("Cannot lock " + folder.resolve(PluginNeovim.LOCK) + ": " + e.getMessage(), e);
        }
    }

    /** A new temporary file in {@code folder}, for the checked copy of the archive. */
    private static Path temporaryFile(Path folder) {
        try {
            return Files.createTempFile(folder, PluginNeovim.DOWNLOAD_PREFIX, ".archive");
        } catch (IOException e) {
            throw files("Cannot write in " + folder + ": " + e.getMessage(), e);
        }
    }

    /** Steps 3 to 5 of the class comment, from the checked copy. The caller holds the lock. */
    private static Result unpackAndSwap(Path copy, Path name, String sha, NeovimRelease.Archive expected,
            NeovimRelease release, NeovimPlatform platform, Path folder, SwapGate gate,
            Function<Path, NeovimVersion> versionOf, InstallProgress progress, Predicate<Path> inUse) {
        Path home = PluginNeovim.home(folder, platform);
        Path staging = folder.resolve(platform.code() + PluginNeovim.NEW);
        Path old = folder.resolve(platform.code() + PluginNeovim.OLD);
        boolean keep = false;
        try {
            deleteTree(staging);
            progress.step("Unpacking " + name, -1);
            ArchiveExtractor.extract(copy, expected.kind(), staging, progress);
            progress.checkCancelled();
            Path program = PluginNeovim.program(staging, platform.windows());
            if (!PluginNeovim.isInstall(staging, platform.windows())) {
                throw new NeovimInstallException(NeovimInstallException.Reason.WRONG_ARCHIVE,
                        "The archive holds no bin/nvim with its runtime files.");
            }
            // The unpacked files must be the pinned ones, as the start check (PluginNeovim.verified)
            // will want them: the archive's SHA-256 matched, so a difference here is a full disk or a
            // file another program changed meanwhile, not a different Neovim.
            progress.step("Checking the unpacked files", -1);
            String tree = InstallTree.of(staging).orElse("none");
            if (!tree.equals(expected.tree())) {
                throw new NeovimInstallException(NeovimInstallException.Reason.FILES, "The unpacked files in "
                        + staging + " differ from Neovim's release (tree hash " + tree + ", expected "
                        + expected.tree() + "). Check the free disk space, and try again.");
            }
            progress.step("Checking that Neovim runs", -1);
            NeovimVersion version = versionOf.apply(program);
            if (version == null) {
                throw new NeovimInstallException(NeovimInstallException.Reason.DOES_NOT_RUN,
                        "The unpacked Neovim does not run on this machine (" + program + " --version failed). "
                                + "The official Linux builds need glibc; on a musl system such as Alpine, "
                                + "use a Neovim from the system's packages instead.");
            }
            if (!version.equals(release.version())) {
                throw new NeovimInstallException(NeovimInstallException.Reason.DOES_NOT_RUN,
                        "The unpacked Neovim reports version " + version + ", not " + release.version() + ".");
            }
            keep = true;
            Path finalProgram = PluginNeovim.program(home, platform.windows());
            // A leftover of an earlier swap whose delete failed; here, before the gate, so the
            // gate (which keeps Neovim from starting) is held only for the two moves.
            // The gate answers for this Eclipse's Neovim; inUse for any other process, such as
            // another Eclipse's Neovim running from the same folder (NeovimProcesses).
            if (!gate.swapUnlessInUse(() -> !inUse.test(home) && swap(folder, platform))) {
                return new Result(Outcome.WAITS_FOR_RESTART, finalProgram, sha);
            }
            deleteTreeQuietly(old);
            return new Result(Outcome.INSTALLED, finalProgram, sha);
        } catch (IOException e) {
            throw files("Cannot unpack into " + staging + ": " + e.getMessage(), e);
        } finally {
            if (!keep) {
                deleteTreeQuietly(staging);
            }
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
     * Copies the archive into {@code copy} and compares what was copied with the pinned entry. A file
     * of another size is only hashed, for the message: it cannot be the archive, and a large wrong
     * file is not worth copying.
     *
     * @return its SHA-256
     * @throws NeovimInstallException WRONG_PLATFORM or WRONG_ARCHIVE, naming both hashes
     */
    static String check(Path archive, Path copy, NeovimRelease.Archive expected, NeovimRelease release,
            InstallProgress progress) {
        long size;
        try {
            size = Files.size(archive);
        } catch (IOException e) {
            throw files("Cannot read " + archive + ": " + e.getMessage(), e);
        }
        progress.step("Checking the SHA-256 of " + archive.getFileName(), size);
        String sha;
        if (size == expected.size()) {
            Copied copied = copyAndHash(archive, copy, expected.size(), progress);
            sha = copied.sha256();
            size = copied.size(); // what was copied is what is unpacked, even if the file changed meanwhile
        } else {
            sha = sha256(archive, progress);
        }
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
        throw new NeovimInstallException(archive.getFileName() + " is not the Neovim " + release.version()
                + " archive for " + expected.platform().describe() + " (another version, or damaged).\n"
                + "Expected " + expected.asset() + ": " + expected.size() + " bytes, SHA-256 " + expected.sha256()
                + "\nThis file: " + size + " bytes, SHA-256 " + sha,
                new NeovimInstallException.Found(archive.getFileName().toString(), size, sha));
    }

    /** What {@link #copyAndHash} copied: its size and SHA-256. */
    private record Copied(long size, String sha256) {
    }

    /**
     * Copies {@code from} into {@code to} and hashes the same bytes, in one read. Stops after one byte
     * more than {@code limit}: a file that grew past the archive's size cannot match anyway.
     */
    private static Copied copyAndHash(Path from, Path to, long limit, InstallProgress progress) {
        MessageDigest digest = newDigest();
        long size = 0;
        try (InputStream in = Files.newInputStream(from); OutputStream out = Files.newOutputStream(to)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; size <= limit && (n = in.read(buffer)) > 0;) {
                progress.checkCancelled();
                digest.update(buffer, 0, n);
                out.write(buffer, 0, n);
                size += n;
                progress.worked(n);
            }
        } catch (IOException e) {
            throw files("Cannot copy " + from + " to " + to + ": " + e.getMessage(), e);
        }
        return new Copied(size, HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java has SHA-256", e);
        }
    }

    /** The file's SHA-256 as 64 lower-case hex digits. */
    public static String sha256(Path file, InstallProgress progress) {
        MessageDigest digest = newDigest();
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
     *
     * <p>Only moves happen here, as the caller is about to start Neovim and may be on the UI thread;
     * the replaced {@code <code>.old} (thousands of runtime files) is left for
     * {@link #deleteReplaced}, which the caller runs in the background. Not while another process
     * runs a Neovim from {@code <code>} (another Eclipse's, {@link NeovimProcesses}): the swap would
     * take its runtime files away, so it waits for a start when none does.
     *
     * @return whether a {@code <code>.old} is there for {@link #deleteReplaced} to delete
     */
    public static boolean finishPending(Path folder) {
        return finishPending(folder, NeovimProcesses::runFrom);
    }

    /** {@link #finishPending}, with the question whether a process runs from a folder given (tests). */
    static boolean finishPending(Path folder, Predicate<Path> inUse) {
        boolean replaced = false;
        for (NeovimPlatform platform : NeovimPlatform.ALL) {
            Path staging = folder.resolve(platform.code() + PluginNeovim.NEW);
            if (Files.isDirectory(staging) && PluginNeovim.isInstall(staging, platform.windows())) {
                try (FileChannel channel = FileChannel.open(folder.resolve(PluginNeovim.LOCK),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock lock = tryLock(channel)) {
                    if (lock != null && !inUse.test(PluginNeovim.home(folder, platform))) {
                        swap(folder, platform);
                    }
                } catch (IOException e) {
                    // next start
                }
            }
            replaced |= !replacedFolders(folder, platform).isEmpty();
        }
        return replaced;
    }

    /** {@code <code>.old}, and any {@code <code>.old-<n>} set aside by {@link #swap}, in {@code folder}. */
    private static List<Path> replacedFolders(Path folder, NeovimPlatform platform) {
        String old = platform.code() + PluginNeovim.OLD;
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                String name = String.valueOf(entry.getFileName());
                if (name.equals(old) || name.startsWith(old + "-")) {
                    found.add(entry);
                }
            }
        } catch (IOException | SecurityException e) {
            // none found
        }
        return found;
    }

    /**
     * How old a {@link PluginNeovim#DOWNLOAD_PREFIX} file must be before {@link #deleteReplaced} takes
     * it for a leftover. A download in progress writes to its file at least every
     * {@link NeovimDownload#STALL} (it stops after that long without data), and an install's copy is
     * unpacked within minutes, so an hour-old one belongs to an Eclipse that was killed meanwhile.
     */
    static final Duration STALE_DOWNLOAD = Duration.ofHours(1);

    /**
     * Cleans the folder up, under the lock, off the UI thread (a whole Neovim to delete): every
     * replaced {@code <code>.old} that {@link #finishPending} or an install left, unless a process
     * still runs from it (another Eclipse's Neovim), and every download or install copy older than
     * {@link #STALE_DOWNLOAD} (an Eclipse killed in the middle: about 10 MB each). Skipped while an
     * install holds the lock; the caller runs it at each Neovim start.
     */
    public static void deleteReplaced(Path folder) {
        deleteReplaced(folder, NeovimProcesses::runFrom, Instant.now());
    }

    /** {@link #deleteReplaced}, with the process question and the time given (tests). */
    static void deleteReplaced(Path folder, Predicate<Path> inUse, Instant now) {
        if (!Files.isDirectory(folder)) {
            return; // no dedicated Neovim: nothing to clean, and no lock file to create
        }
        try (FileChannel channel = FileChannel.open(folder.resolve(PluginNeovim.LOCK), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock lock = tryLock(channel)) {
            if (lock != null) {
                for (NeovimPlatform platform : NeovimPlatform.ALL) {
                    for (Path old : replacedFolders(folder, platform)) {
                        if (!inUse.test(old)) {
                            deleteTreeQuietly(old);
                        }
                    }
                }
                deleteStaleDownloads(folder, now);
            }
        } catch (IOException e) {
            // left for the next start
        }
    }

    private static void deleteStaleDownloads(Path folder, Instant now) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder, PluginNeovim.DOWNLOAD_PREFIX + "*")) {
            for (Path entry : entries) {
                try {
                    if (Files.isRegularFile(entry)
                            && Files.getLastModifiedTime(entry).toInstant().plus(STALE_DOWNLOAD).isBefore(now)) {
                        Files.deleteIfExists(entry);
                    }
                } catch (IOException e) {
                    // the next start tries again
                }
            }
        } catch (IOException | SecurityException e) {
            // nothing to list
        }
    }

    /**
     * {@code <code>} to {@code <code>.old}, then {@code <code>.new} to {@code <code>}: two moves,
     * quick, so the {@link SwapGate} around them is held only briefly. The caller holds the lock and
     * deletes {@code .old} afterwards.
     *
     * <p>A {@code <code>.old} still there from before (its delete failed: a file held open on Windows,
     * or a process ran from it) is first moved aside to {@code <code>.old-<n>}, a move as quick as
     * the others, so it does not hold the swap up until a later start. {@link #deleteReplaced}
     * deletes it with the rest.
     *
     * @return whether {@code <code>.new} is now {@code <code>}; {@code false} if a move failed (Windows:
     *         a file in use), with the old install back in place
     */
    static boolean swap(Path folder, NeovimPlatform platform) {
        Path home = PluginNeovim.home(folder, platform);
        Path staging = folder.resolve(platform.code() + PluginNeovim.NEW);
        Path old = folder.resolve(platform.code() + PluginNeovim.OLD);
        if (Files.exists(old)) {
            try {
                Files.move(old, folder.resolve(old.getFileName() + "-" + System.nanoTime()));
            } catch (IOException e) {
                return false; // cannot even move it (Windows: held open): the next start tries again
            }
        }
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
