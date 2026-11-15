// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.gallery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FilePaths.Platform;

/**
 * Where {@link PicturesFolder#root()} checks the folder's owner: only outside the home folder, so a
 * {@code target/} made by an elevated run on Windows (owned by {@code BUILTIN\Administrators}) passes
 * in the next plain run, and the other way round. The rule is tested with fake platforms (no link
 * is resolved unless the fake says so); the last test runs {@code root()} on this machine.
 */
class PicturesFolderTest {

    private static final Platform LINUX = new Platform(false, false, path -> path);
    private static final Platform WINDOWS = new Platform(true, true, path -> path);

    @Test
    void aFolderInsideTheHomeFolderIsNotChecked() {
        assertFalse(PicturesFolder.ownerChecked("/home/you/src/n4e/tests/t/target", "/home/you", LINUX));
        assertFalse(PicturesFolder.ownerChecked("/home/you", "/home/you", LINUX));
    }

    @Test
    void aSharedFolderIsChecked() {
        assertTrue(PicturesFolder.ownerChecked("/tmp/neovim4eclipse-pictures", "/home/you", LINUX));
        assertTrue(PicturesFolder.ownerChecked("/home/younger/target", "/home/you", LINUX),
                "a folder whose name only starts like the home folder's is outside it");
        assertTrue(PicturesFolder.ownerChecked("C:\\Users\\Public\\neovim4eclipse-pictures", "C:\\Users\\you",
                WINDOWS));
    }

    @Test
    void windowsSpellingsOfTheHomeFolderAreOneFolder() {
        assertFalse(PicturesFolder.ownerChecked("c:/users/YOU/src/n4e/tests/t/target", "C:\\Users\\you", WINDOWS));
    }

    @Test
    void aLinkInTheHomeFolderToASharedFolderIsChecked() {
        Platform linked = new Platform(false, false, path -> path.startsWith("/home/you/pictures")
                ? "/tmp/neovim4eclipse-pictures" + path.substring("/home/you/pictures".length())
                : path);
        assertTrue(PicturesFolder.ownerChecked("/home/you/pictures/data", "/home/you", linked));
    }

    @Test
    void noHomeFolderMeansChecked() {
        assertTrue(PicturesFolder.ownerChecked("/home/you/target", null, LINUX));
        assertTrue(PicturesFolder.ownerChecked("/home/you/target", " ", LINUX));
    }

    @Test
    void aHomeThatIsTheRootLeavesEveryFolderChecked() {
        assertTrue(PicturesFolder.ownerChecked("/tmp/neovim4eclipse-pictures", "/", LINUX));
        assertTrue(PicturesFolder.ownerChecked("C:\\Users\\Public\\neovim4eclipse-pictures", "C:\\", WINDOWS));
    }

    @Test
    void theUiTestsFolderIsAcceptedAsItIs() throws Exception {
        Path root = PicturesFolder.root();
        assertTrue(Files.isDirectory(root));
        assertEquals(Path.of(System.getProperty("test.isolated.root")).toAbsolutePath().normalize(), root);
    }
}
