// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link LocalFiles}: which places {@code Ctrl-O} and {@code Ctrl-I} take as gone. */
class LocalFilesTest {

    @Test
    void aMissingLocalFileIsGoneAndAnExistingOneIsNot(@TempDir Path folder) throws Exception {
        Path there = Files.writeString(folder.resolve("there.txt"), "x");
        assertFalse(LocalFiles.isMissing(there.toUri()));
        assertTrue(LocalFiles.isMissing(folder.resolve("gone.txt").toUri()));
    }

    @Test
    void anythingButALocalFileIsNeverGone() {
        assertFalse(LocalFiles.isMissing(null));
        assertFalse(LocalFiles.isMissing(URI.create("https://example.org/gone.txt")));
        assertFalse(LocalFiles.isMissing(URI.create("jar:file:/lib/a.jar!/gone.txt")));
    }

    /** A buffer's name after its file was renamed or moved: an absolute path no file has. */
    @Test
    void aBuffersPathIsMissingOnceItsFileIsGone(@TempDir Path folder) throws Exception {
        Path there = Files.writeString(folder.resolve("a.txt"), "x");
        assertFalse(LocalFiles.isMissingPath(there.toString()));
        assertTrue(LocalFiles.isMissingPath(folder.resolve("b.txt").toString()));
    }

    /** A name that is no absolute path (an untitled document's, a URI, none) counts as there. */
    @Test
    void aNameThatIsNoPathIsNeverMissing() {
        assertFalse(LocalFiles.isMissingPath(""));
        assertFalse(LocalFiles.isMissingPath("Untitled 1"));
        assertFalse(LocalFiles.isMissingPath("relative/gone.txt"));
        assertFalse(LocalFiles.isMissingPath("jar:file:/lib/a.jar!/gone.txt"));
    }

    /**
     * {@code Path.of} refuses a {@code file:} URI with a host outside Windows (on Windows it is a
     * UNC path, and checked); the step must not break off with that exception.
     */
    @Test
    void aFileUriWithAHostIsTakenAsThere() {
        assumeFalse(System.getProperty("os.name", "").startsWith("Windows"), "a UNC path on Windows");
        assertFalse(LocalFiles.isMissing(URI.create("file://server/share/notes.txt")));
    }
}
