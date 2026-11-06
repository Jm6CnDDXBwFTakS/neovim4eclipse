// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The repository's own files, for the tests that read the whole repository (the folder name in
 * every doc, the manual checks' areas, the Eclipse project layout).
 *
 * <h2>Why git's list, not the folders on disk</h2>
 * A checkout holds more than the repository: build output under {@code target/}, and sometimes whole
 * copies of it, such as a worktree another tool keeps under {@code .claude/worktrees/}. A test that
 * walks the folders reads those copies too, and a copy of a test that quotes the text it looks for
 * (as {@code DedicatedFolderNameTest} does in its comments) then fails it. Skipping folders by name
 * only moves the problem to the next copy. Git already knows which files belong:
 * <pre>
 *   git ls-files --cached --others --exclude-standard
 * </pre>
 * lists the tracked files and the new ones not added yet (so a new doc is checked before its first
 * commit), and leaves out whatever {@code .gitignore} and {@code .git/info/exclude} ignore. Files
 * deleted but not committed yet are dropped, as they are gone from the disk.
 *
 * <h2>Without git</h2>
 * Where git cannot answer (not installed, or a source tree without {@code .git}, such as an unpacked
 * ZIP), the folders are walked, without {@link #SKIPPED_FOLDERS}: the tests still run anywhere.
 */
public final class RepositoryFiles {

    /** The folders the walk without git leaves out: Git's own, build output, and tools' copies. */
    static final Set<String> SKIPPED_FOLDERS = Set.of(".git", "target", "bin", "node_modules", ".claude");

    private static final long GIT_SECONDS = 60;

    private RepositoryFiles() {
    }

    /** The files of the repository at {@code root}, relative to it, sorted (with {@code /}, on Windows too). */
    public static List<String> list(Path root) throws IOException {
        Optional<List<String>> fromGit = fromGit(root);
        return fromGit.isPresent() ? fromGit.get() : walk(root);
    }

    /** {@link #list} as paths under {@code root}, for a test that reads the files. */
    public static List<Path> paths(Path root) throws IOException {
        return list(root).stream().map(root::resolve).toList();
    }

    /**
     * Git's list for {@code root}, or empty when git cannot give one: no git, or {@code root} is not
     * the top of a repository of its own. The second case matters for a source tree without
     * {@code .git} that lies inside some other repository (an unpacked ZIP in a home folder kept in
     * git): git would answer for that other repository, whose rules ignore neither {@code target/}
     * nor anything else of this one.
     */
    static Optional<List<String>> fromGit(Path root) {
        Optional<String> top = git(root, "rev-parse", "--show-toplevel");
        try {
            if (top.isEmpty() || !Path.of(top.get().strip()).toRealPath().equals(root.toRealPath())) {
                return Optional.empty();
            }
        } catch (IOException | InvalidPathException e) {
            return Optional.empty();
        }
        // -z: names end with NUL and are never quoted, so a name with a space or an umlaut reads as it is.
        return git(root, "ls-files", "-z", "--cached", "--others", "--exclude-standard").map(out -> {
            List<String> files = new ArrayList<>();
            for (String name : out.split("\0")) {
                // A tracked file deleted on disk is still listed until the deletion is committed.
                if (!name.isEmpty() && Files.isRegularFile(root.resolve(name))) {
                    files.add(name);
                }
            }
            return files.stream().distinct().sorted().toList();
        });
    }

    /** What {@code git -C root <args>} prints, or empty when it fails or git is missing. */
    private static Optional<String> git(Path root, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        try {
            Process git = pb.start();
            git.getOutputStream().close();
            CompletableFuture<byte[]> err = CompletableFuture.supplyAsync(() -> {
                try {
                    return git.getErrorStream().readAllBytes(); // drained so git never blocks on it
                } catch (IOException e) {
                    return new byte[0];
                }
            });
            String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!git.waitFor(GIT_SECONDS, TimeUnit.SECONDS)) {
                git.destroyForcibly();
                return Optional.empty();
            }
            err.join();
            return git.exitValue() == 0 ? Optional.of(out) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty(); // no git on PATH
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** The files under {@code root}, outside {@link #SKIPPED_FOLDERS}. */
    static List<String> walk(Path root) throws IOException {
        List<String> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(root) && SKIPPED_FOLDERS.contains(String.valueOf(dir.getFileName()))
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    files.add(root.relativize(file).toString().replace('\\', '/'));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files.stream().sorted().toList();
    }
}
