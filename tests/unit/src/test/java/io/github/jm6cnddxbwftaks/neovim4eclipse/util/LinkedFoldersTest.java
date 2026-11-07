// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;

/**
 * The walks that change files below the plug-in's Neovim folder ({@link NeovimInstaller#deleteTree},
 * {@link InstallTree#removeSystemFiles}) never reach into a folder a link points to. Example: a user
 * made {@code neovim\wa} a junction to their own {@code C:\tools\nvim}; Get Neovim's swap renames it
 * {@code wa.old} and deletes that, and every start deletes {@code desktop.ini} and {@code Thumbs.db}
 * below {@code wa}. Both must leave {@code C:\tools\nvim} as it was.
 *
 * <p>Two kinds of link: a symbolic link (every system; on Windows only with the right to create
 * one, else the test is skipped), and a Windows <em>junction</em>, which Java does not report as a
 * link but as a folder that is also "other" ({@link InstallTree#isLink}). The junction tests run only
 * on Windows, made with {@code mklink /J} (no special right needed).
 */
class LinkedFoldersTest {

    @TempDir
    Path tmp;

    /** The user's own folder a link points to: a program, and files named like the system's own. */
    private Path usersFolder() throws IOException {
        Path theirs = tmp.resolve("tools/nvim");
        Files.createDirectories(theirs.resolve("bin"));
        Files.writeString(theirs.resolve("bin/nvim"), "theirs");
        Files.writeString(theirs.resolve("desktop.ini"), "[.ShellClassInfo]");
        Files.writeString(theirs.resolve("bin/Thumbs.db"), "x");
        return theirs;
    }

    private static void assertUntouched(Path theirs) {
        assertTrue(Files.isRegularFile(theirs.resolve("bin/nvim")), "the linked folder's program is kept");
        assertTrue(Files.isRegularFile(theirs.resolve("desktop.ini")), "the linked folder's desktop.ini is kept");
        assertTrue(Files.isRegularFile(theirs.resolve("bin/Thumbs.db")), "the linked folder's Thumbs.db is kept");
    }

    /** A symbolic link at {@code link} to {@code target}; the test is skipped where none can be made. */
    private static void symbolicLink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "no symbolic links here: " + e); // Windows without Developer Mode
        }
    }

    /** A Windows junction at {@code link} to {@code target}, by {@code mklink /J}. */
    private static void junction(Path link, Path target) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        assertTrue(p.waitFor(TestWait.millis(), TimeUnit.MILLISECONDS), "mklink did not finish");
        String output = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.exitValue(), "mklink /J failed: " + output);
        assertTrue(Files.isDirectory(link), "the junction reads as a folder");
    }

    /** An install folder {@code la} whose {@code share} folder holds {@code linked}, made by the caller. */
    private Path home() throws IOException {
        Path home = tmp.resolve("neovim/la");
        Files.createDirectories(home.resolve("share"));
        Files.writeString(home.resolve("share/desktop.ini"), "ours");
        return home;
    }

    // Symbolic links (POSIX everywhere; Windows with the right to create them)

    @Test
    void deletingAFolderDeletesASymbolicLinkInItNotWhatItPointsTo() throws IOException {
        Path theirs = usersFolder();
        Path home = home();
        symbolicLink(home.resolve("share/linked"), theirs);
        NeovimInstaller.deleteTree(home);
        assertFalse(Files.exists(home, LinkOption.NOFOLLOW_LINKS));
        assertUntouched(theirs);
    }

    @Test
    void deletingASymbolicLinkToAFolderDeletesOnlyTheLink() throws IOException {
        Path theirs = usersFolder();
        Path old = tmp.resolve("neovim/wa.old");
        Files.createDirectories(old.getParent());
        symbolicLink(old, theirs);
        NeovimInstaller.deleteTree(old);
        assertFalse(Files.exists(old, LinkOption.NOFOLLOW_LINKS));
        assertUntouched(theirs);
    }

    @Test
    void systemFilesBehindASymbolicLinkAreKept() throws IOException {
        Path theirs = usersFolder();
        Path home = home();
        symbolicLink(home.resolve("share/linked"), theirs);
        InstallTree.removeSystemFiles(home);
        assertFalse(Files.exists(home.resolve("share/desktop.ini")), "the install's own is deleted");
        assertUntouched(theirs);
    }

    // Windows junctions: run only on Windows

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void deletingAFolderDeletesAJunctionInItNotWhatItPointsTo() throws Exception {
        Path theirs = usersFolder();
        Path home = home();
        junction(home.resolve("share/linked"), theirs);
        NeovimInstaller.deleteTree(home);
        assertFalse(Files.exists(home, LinkOption.NOFOLLOW_LINKS));
        assertUntouched(theirs);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void deletingAJunctionDeletesOnlyTheJunction() throws Exception {
        Path theirs = usersFolder();
        Path old = tmp.resolve("neovim/wa.old");
        Files.createDirectories(old.getParent());
        junction(old, theirs);
        NeovimInstaller.deleteTree(old);
        assertFalse(Files.exists(old, LinkOption.NOFOLLOW_LINKS));
        assertUntouched(theirs);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void systemFilesBehindAJunctionAreKept() throws Exception {
        Path theirs = usersFolder();
        Path home = home();
        junction(home.resolve("share/linked"), theirs);
        InstallTree.removeSystemFiles(home);
        assertFalse(Files.exists(home.resolve("share/desktop.ini")), "the install's own is deleted");
        assertUntouched(theirs);
    }
}
