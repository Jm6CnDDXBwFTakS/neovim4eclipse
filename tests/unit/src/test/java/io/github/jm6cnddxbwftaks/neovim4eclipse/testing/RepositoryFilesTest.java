// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which files {@link RepositoryFiles} counts as the repository's, with git and without: the tracked
 * and the new ones, never ignored files, build output or a copy of the repository inside it.
 */
class RepositoryFilesTest {

    /** A small checkout: tracked, new, ignored and deleted files, build output and a worktree copy. */
    private static void checkout(Path dir) throws IOException {
        write(dir, "pom.xml");
        write(dir, "docs/guide.md");
        write(dir, "docs/new.md"); // never added
        write(dir, "gone.md"); // added, then deleted
        write(dir, "target/classes/Built.class");
        write(dir, ".claude/worktrees/copy/docs/guide.md");
        write(dir, "notes.tmp");
        Files.writeString(dir.resolve(".gitignore"), "target/\n*.tmp\n");
    }

    private static void write(Path dir, String file) throws IOException {
        Files.createDirectories(dir.resolve(file).getParent());
        Files.writeString(dir.resolve(file), "x\n");
    }

    private static boolean git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(args));
        try {
            return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor() == 0;
        } catch (IOException e) {
            return false; // no git on PATH
        }
    }

    @Test
    void gitListsTrackedAndNewFilesButNothingIgnored(@TempDir Path dir) throws Exception {
        checkout(dir);
        assumeTrue(git(dir, "init", "-q"), "git is not installed");
        // .claude/ is ignored the way a tool ignores its worktrees: in .git/info/exclude, not .gitignore.
        Files.writeString(dir.resolve(".git/info/exclude"), ".claude/\n");
        assertTrue(git(dir, "add", "pom.xml", "docs/guide.md", "gone.md", ".gitignore"));
        Files.delete(dir.resolve("gone.md"));

        assertEquals(Optional.of(List.of(".gitignore", "docs/guide.md", "docs/new.md", "pom.xml")), RepositoryFiles.fromGit(dir));
        assertEquals(RepositoryFiles.fromGit(dir).orElseThrow(), RepositoryFiles.list(dir));
    }

    @Test
    void withoutGitTheFoldersAreWalkedWithoutBuildOutputOrCopies(@TempDir Path dir) throws Exception {
        checkout(dir);
        Files.delete(dir.resolve("gone.md"));
        assertEquals(Optional.empty(), RepositoryFiles.fromGit(dir.resolve("docs/no-such-folder")), "git cannot answer");
        assertEquals(List.of(".gitignore", "docs/guide.md", "docs/new.md", "notes.tmp", "pom.xml"), RepositoryFiles.walk(dir));
    }

    @Test
    void theRepositorysOwnListHasItsFilesAndNoBuildOutput() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isRegularFile(root.resolve("AGENTS.md"))) {
            root = root.getParent();
        }
        List<String> files = RepositoryFiles.list(root);
        assertTrue(files.contains("pom.xml") && files.contains("AGENTS.md"), "the root's files");
        assertTrue(files.contains("tests/unit/src/test/java/io/github/jm6cnddxbwftaks/neovim4eclipse/testing/RepositoryFilesTest.java"));
        assertTrue(files.stream().noneMatch(f -> f.startsWith("target/") || f.contains("/target/") || f.startsWith(".git/")
                || f.startsWith(".claude/")), "no build output, no Git or tool folders");
    }
}
