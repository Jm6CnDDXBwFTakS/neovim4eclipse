// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link DanglingLinks} on real links in a temporary folder. Skipped where this user may not make
 * symbolic links (Windows without Developer Mode or the privilege).
 */
class DanglingLinksTest {

    @TempDir
    Path dir;

    /** Makes {@code link} point to {@code target}, or skips the test where links cannot be made. */
    private static void link(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "cannot make a symbolic link here: " + e);
        }
    }

    @Test
    void aLinkToAMissingFileIsDangling() {
        Path link = dir.resolve("notes.local");
        link(link, dir.resolve("gone.txt"));
        assertFalse(Files.exists(link), "Files.exists follows the link: it counts as missing");
        assertTrue(DanglingLinks.isDangling(link));
    }

    @Test
    void aLinkToAnExistingFileAndAPlainMissingFileAreNot() throws IOException {
        Path target = Files.writeString(dir.resolve("here.txt"), "x");
        Path link = dir.resolve("ok.local");
        link(link, target);
        assertFalse(DanglingLinks.isDangling(link));
        assertFalse(DanglingLinks.isDangling(dir.resolve("new.txt")), "a missing file is no link");
        assertFalse(DanglingLinks.isDangling(target));
    }

    @Test
    void theFinalTargetIsTheMissingFileAWriteWouldCreate() throws IOException {
        Path absolute = dir.resolve("abs.local");
        link(absolute, dir.resolve("gone.txt"));
        assertEquals(dir.resolve("gone.txt"), DanglingLinks.finalTarget(absolute));

        Files.createDirectory(dir.resolve("sub"));
        Path relative = dir.resolve("sub").resolve("rel.local");
        link(relative, Path.of("..", "gone.txt"));
        Path found = DanglingLinks.finalTarget(relative);
        assertEquals(dir.resolve("gone.txt"), found.normalize(), "relative to the link's folder");
        Files.writeString(found, "x"); // written there, it is the file the link shows
        assertEquals("x", Files.readString(relative));
    }

    @Test
    void theFinalTargetFollowsAChainAndGivesUpOnALoop() {
        Path first = dir.resolve("first.local");
        Path second = dir.resolve("second.local");
        link(first, second);
        link(second, dir.resolve("gone.txt"));
        assertEquals(dir.resolve("gone.txt"), DanglingLinks.finalTarget(first));

        Path a = dir.resolve("a.local");
        Path b = dir.resolve("b.local");
        link(a, b);
        link(b, a);
        assertNull(DanglingLinks.finalTarget(a), "a loop has no file to write");
    }

    @Test
    void anythingButADanglingLinkHasNoFinalTarget() throws IOException {
        Path target = Files.writeString(dir.resolve("here.txt"), "x");
        Path link = dir.resolve("ok.local");
        link(link, target);
        assertNull(DanglingLinks.finalTarget(link));
        assertNull(DanglingLinks.finalTarget(dir.resolve("new.txt")));
    }

    @Test
    void theRefusalNamesTheTargetAndTheWayOut() {
        Path target = dir.resolve("autostart").resolve("x.desktop");
        Path link = dir.resolve("notes.local");
        link(link, target);
        assertEquals("\"notes.local\" is a link to " + target + ", which does not exist; add ! to write there",
                DanglingLinks.refusal("notes.local", link));
        assertEquals("(a link to " + target + ")", DanglingLinks.newFileNote(link));
    }
}
