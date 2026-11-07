// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A throwaway copy of your Eclipse, and p2's director to install into it: what
 * {@code checks/ManualChecksTool} (the checks' Eclipse, with the build under test) and
 * {@code checks/InstallCheckTool} (a release from the update site) both need. Neither ever
 * installs into the Eclipse you work in.
 *
 * <h2>A copy, made cheap with hard links</h2>
 * Installing straight into your Eclipse would change the one you work in. A copy costs little:
 * the files under {@code plugins/} and {@code features/} (nearly all of an Eclipse, about 1 GB)
 * are <i>hard links</i> to yours, a second name for the same bytes, so they take no new space.
 * They are safe to share because p2 never rewrites a bundle in place: it adds new files and
 * deletes old ones, and deleting a link leaves your file alone. Everything p2 and Eclipse do
 * write ({@code configuration/}, {@code p2/}, {@code eclipse.ini}, {@code artifacts.xml}) is a
 * real copy. Where a link cannot be made (the copy on another disk), the file is copied.
 * Two things are not copied as they are: the OSGi framework's cache
 * ({@code configuration/org.eclipse.osgi}, about 30 MB, which a running Eclipse rewrites and the
 * copy rebuilds at its first start), and an absolute symbolic link into your Eclipse, which
 * would let the copy write through it into yours: it is pointed at the same place in the copy.
 * Example, on Linux: {@code /opt/eclipse/eclipse-committers-2026-09-R-linux-gtk-aarch64} becomes
 * {@code ~/var/n4e-checks/eclipse/eclipse-committers-2026-09-R-linux-gtk-aarch64}; on macOS
 * {@code /Applications/Eclipse.app} becomes {@code ~/var/n4e-checks/eclipse/Eclipse.app}.
 *
 * <h2>p2's director</h2>
 * The director ({@code -application org.eclipse.equinox.p2.director}) is <i>Install New
 * Software</i> without a window. Run from the copy ({@link #director}), it installs into the copy
 * itself: p2 takes the install folder from where it runs, not from the copied profile. Each tool
 * gives it its own repository and trust arguments; {@link #installedVersion} reads what it lists.
 */
public final class EclipseCopy {

    private EclipseCopy() {
    }

    /** The feature's installable unit, as p2 names a feature: its id plus {@code .feature.group}. */
    public static final String FEATURE = "io.github.jm6cnddxbwftaks.neovim4eclipse.feature.feature.group";

    /**
     * Your Eclipse program as a path: as given if it names a file, else looked up on {@code PATH}
     * (the Linux default is the bare {@code eclipse}).
     */
    public static Path program(String name) {
        Path given = Path.of(name);
        if (given.getNameCount() > 1 || Files.isRegularFile(given)) {
            if (!Files.isRegularFile(given)) {
                throw new ToolFailure("no Eclipse program at " + given + "; name it with --eclipse or the ECLIPSE environment variable");
            }
            return given.toAbsolutePath();
        }
        for (String folder : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (!folder.isEmpty() && Files.isExecutable(Path.of(folder, name))) {
                return Path.of(folder, name);
            }
        }
        throw new ToolFailure("no Eclipse program " + name + " on PATH; name it with --eclipse or the ECLIPSE environment variable");
    }

    /**
     * The installation a program belongs to, the folder to copy: on macOS the {@code .app} around
     * it ({@code /Applications/Eclipse.app/Contents/MacOS/eclipse} gives {@code /Applications/Eclipse.app}),
     * elsewhere the program's folder ({@code C:\eclipse\eclipse.exe} gives {@code C:\eclipse}).
     */
    public static Path installation(Path program) {
        for (Path folder = program.getParent(); folder != null; folder = folder.getParent()) {
            if (folder.getFileName() != null && folder.getFileName().toString().endsWith(".app")) {
                return folder;
            }
        }
        return program.getParent();
    }

    /**
     * Whether a file of the installation may be a hard link to yours: one under the folder with
     * {@code eclipse.ini} ({@code inner}: empty, or {@code Contents/Eclipse} in a macOS app), in
     * {@code plugins/} or {@code features/}. Example: {@code plugins/org.eclipse.swt_3.130.jar}
     * yes, {@code configuration/config.ini} no.
     */
    public static boolean shared(Path relative, Path inner) {
        return relative.startsWith(inner.resolve("plugins")) || relative.startsWith(inner.resolve("features"));
    }

    /**
     * The folder inside an installation that holds {@code eclipse.ini}, {@code plugins/} and
     * {@code configuration/}: {@code Contents/Eclipse} in a macOS app, else the installation itself.
     */
    public static Path inner(Path installation) {
        Path mac = Path.of("Contents", "Eclipse");
        return Files.isRegularFile(installation.resolve(mac).resolve("eclipse.ini")) ? mac : Path.of("");
    }

    /**
     * Whether {@code installation} is an Eclipse: an {@code eclipse.ini} and a {@code plugins/}
     * folder where {@link #inner} says. Example: {@code /usr/bin}, the folder of a wrapper script
     * named {@code eclipse} on {@code PATH}, is not, and must not be copied.
     */
    public static boolean isEclipse(Path installation) {
        Path inner = installation.resolve(inner(installation));
        return Files.isRegularFile(inner.resolve("eclipse.ini")) && Files.isDirectory(inner.resolve("plugins"));
    }

    /**
     * Whether a file of the installation is left out of the copy: the OSGi framework's cache,
     * {@code configuration/org.eclipse.osgi}, which a running Eclipse rewrites (a copy could catch
     * it half written) and an Eclipse rebuilds when it is missing.
     */
    public static boolean skipped(Path relative, Path inner) {
        return relative.startsWith(inner.resolve("configuration").resolve("org.eclipse.osgi"));
    }

    /**
     * Where a symbolic link of the copy points: an absolute link into the installation is turned
     * to the same place in the copy, so nothing written through it reaches yours; any other link
     * is kept as it is. Example: {@code /opt/eclipse/plugins/x} in {@code /opt/eclipse} copied to
     * {@code ~/c} becomes {@code ~/c/plugins/x}; {@code ../lib/y} stays {@code ../lib/y}.
     */
    public static Path linkTarget(Path target, Path source, Path copy) {
        return target.isAbsolute() && target.startsWith(source)
                ? copy.resolve(source.relativize(target).toString()) : target;
    }

    /** The feature's version in the director's {@code -listInstalledRoots} output, if installed. */
    public static Optional<String> installedVersion(String listing) {
        return listing.lines().map(String::strip).filter(l -> l.startsWith(FEATURE + "/"))
                .map(l -> l.substring(FEATURE.length() + 1)).findFirst();
    }

    /**
     * The program to run the director with: on Windows {@code eclipsec.exe} beside
     * {@code eclipse.exe}, the console launcher, since {@code eclipse.exe} detaches from the
     * terminal and its output is lost.
     */
    public static Path director(Path program) {
        Path console = program.resolveSibling("eclipsec.exe");
        return Files.isRegularFile(console) ? console : program;
    }

    /** Copies the tree, hard-linking the {@link #shared} files; returns {linked, copied, bytes copied}. */
    public static long[] copyTree(Path source, Path target, Path inner) throws IOException {
        return copyTree(source, target, inner, true);
    }

    /**
     * {@link #copyTree}, with {@code symlinks} false acting as a Windows account that may not make
     * symbolic links: each link of the source is then copied as what it points to, a file or a
     * whole folder (tested on every platform this way).
     */
    public static long[] copyTree(Path source, Path target, Path inner, boolean symlinks) throws IOException {
        long[] counts = new long[3];
        Files.walkFileTree(source, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                    java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                if (skipped(relative, inner)) {
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(relative.toString()));
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                Path to = target.resolve(relative.toString());
                if (skipped(relative, inner)) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                if (attrs.isSymbolicLink()) {
                    if (symlinks) {
                        try {
                            Files.createSymbolicLink(to, linkTarget(Files.readSymbolicLink(file), source, target));
                            counts[1]++;
                            return java.nio.file.FileVisitResult.CONTINUE;
                        } catch (IOException | UnsupportedOperationException e) {
                            // Windows without the right to make links: copy what it points to instead.
                        }
                    }
                    // Never a hard link here, even under plugins/: a hard link to a symbolic link is
                    // that same link, still pointing into your Eclipse.
                    copyResolved(file, to, counts);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                if (shared(relative, inner)) {
                    try {
                        Files.createLink(to, file);
                        counts[0]++;
                        return java.nio.file.FileVisitResult.CONTINUE;
                    } catch (IOException | UnsupportedOperationException e) {
                        // Another disk, or a file system without hard links: copy instead.
                    }
                }
                Files.copy(file, to, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                counts[1]++;
                counts[2] += attrs.size();
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return counts;
    }

    /**
     * Copies what the symbolic link {@code link} points to into {@code to}: a file as a file, a
     * folder with all it holds (a plain {@code Files.copy} would make an empty folder). A link to
     * nothing is left out: there is nothing to copy, and Eclipse cannot have used it.
     */
    private static void copyResolved(Path link, Path to, long[] counts) throws IOException {
        if (!Files.exists(link)) {
            return;
        }
        Path resolved = link.toRealPath();
        try (Stream<Path> paths = Files.walk(resolved)) { // a file walks as itself alone
            for (Path from : paths.toList()) {
                Path into = to.resolve(resolved.relativize(from).toString());
                if (Files.isDirectory(from)) {
                    Files.createDirectories(into);
                } else {
                    Files.copy(from, into, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                    counts[1]++;
                    counts[2] += Files.size(from);
                }
            }
        }
    }

    /**
     * Whether an Eclipse runs in {@code workspace}: Eclipse holds an operating-system lock on
     * {@code <workspace>/.metadata/.lock} while it runs, so a lock this program cannot take means
     * one does. No lock file, no Eclipse.
     */
    public static boolean inUse(Path workspace) {
        Path lock = workspace.resolve(".metadata/.lock");
        if (!Files.isRegularFile(lock)) {
            return false;
        }
        try (var channel = java.nio.channels.FileChannel.open(lock, java.nio.file.StandardOpenOption.WRITE);
                var held = channel.tryLock()) {
            return held == null;
        } catch (java.nio.channels.OverlappingFileLockException e) {
            return true; // held by this very program (a test)
        } catch (IOException e) {
            // Not an Eclipse at work but a lock file this program may not open: say so, not "close it".
            throw new ToolFailure("cannot check whether an Eclipse runs in " + workspace + ": " + lock + " cannot be opened ("
                    + e.getMessage() + "). Fix its permissions, or delete it if no Eclipse uses the workspace");
        }
    }

    /** Runs a command to its end; returns its output, also appended to {@code log}. */
    public static String run(List<String> command, Path log) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(log.getParent().toFile()) // the log's folder: stray files land there
                .redirectErrorStream(true).start();
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = String.join("\n", reader.lines().toList());
        }
        process.waitFor();
        Files.writeString(log, String.join(" ", command) + "\n" + output + "\n\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        return output;
    }

    /**
     * The Eclipse program used when none is named (with {@code --eclipse} or {@code ECLIPSE}): where
     * the installers put it on macOS and Windows, and {@code eclipse} on {@code PATH} on Linux.
     */
    public static String defaultEclipse() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        return os.startsWith("mac") ? "/Applications/Eclipse.app/Contents/MacOS/eclipse"
                : os.startsWith("windows") ? "C:\\eclipse\\eclipse.exe" : "eclipse";
    }
}
