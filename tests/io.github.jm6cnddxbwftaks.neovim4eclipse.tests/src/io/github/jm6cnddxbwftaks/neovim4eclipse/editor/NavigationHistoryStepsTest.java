// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * The Eclipse half of {@code Ctrl-O} and {@code Ctrl-I} past Neovim's jumps
 * ({@link NavigationHistorySteps}), the parts that need no Neovim: which places count as gone.
 * The steps themselves are {@code HistoryStepsTest} and {@code EditorEndToEndTest}.
 */
class NavigationHistoryStepsTest {

    @Test
    void aMissingLocalFileIsGoneAndAnExistingOneIsNot() throws Exception {
        // A folder made by hand: JUnit's @TempDir is in a package the fragment does not import.
        Path folder = Files.createTempDirectory("neovim4eclipse-history");
        Path there = Files.writeString(folder.resolve("there.txt"), "x");
        try {
            assertFalse(NavigationHistorySteps.isMissingLocalFile(there.toUri()));
            assertTrue(NavigationHistorySteps.isMissingLocalFile(folder.resolve("gone.txt").toUri()));
        } finally {
            Files.delete(there);
            Files.delete(folder);
        }
    }

    @Test
    void anythingButALocalFileIsNeverGone() {
        assertFalse(NavigationHistorySteps.isMissingLocalFile(null));
        assertFalse(NavigationHistorySteps.isMissingLocalFile(URI.create("https://example.org/gone.txt")));
        assertFalse(NavigationHistorySteps.isMissingLocalFile(URI.create("jar:file:/lib/a.jar!/gone.txt")));
    }

    /**
     * {@code Path.of} refuses a {@code file:} URI with a host outside Windows (on Windows it is a
     * UNC path, and checked); the step must not break off with that exception.
     */
    @Test
    void aFileUriWithAHostIsTakenAsThere() {
        assumeFalse(System.getProperty("os.name", "").startsWith("Windows"), "a UNC path on Windows");
        assertFalse(NavigationHistorySteps.isMissingLocalFile(URI.create("file://server/share/notes.txt")));
    }
}
