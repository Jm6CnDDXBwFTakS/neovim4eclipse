// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link NeovimProcesses}: is a program running from a folder. */
class NeovimProcessesTest {

    private static final FilePaths.Platform HERE = FilePaths.Platform.current();

    @TempDir
    Path tmp;

    @Test
    void aProgramInsideTheFolderCounts() {
        Path la = tmp.resolve("neovim/la");
        assertTrue(NeovimProcesses.anyInside(Stream.of("/usr/bin/bash", la.resolve("bin/nvim").toString()), la, HERE));
    }

    @Test
    void aProgramElsewhereOrInANeighbourDoesNot() {
        Path la = tmp.resolve("neovim/la");
        assertFalse(NeovimProcesses.anyInside(Stream.of("/usr/bin/nvim", tmp.resolve("neovim/la.new/bin/nvim").toString()),
                la, HERE), "la.new is not inside la");
        assertFalse(NeovimProcesses.anyInside(Stream.empty(), la, HERE));
    }

    /** The OS reports real paths: a link in the folder's path must not hide the match. */
    @Test
    void aLinkInTheFolderPathIsResolved() throws IOException {
        Path real = Files.createDirectories(tmp.resolve("real/la"));
        Path link;
        try {
            link = Files.createSymbolicLink(tmp.resolve("link"), tmp.resolve("real"));
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "no symbolic links here: " + e);
            return;
        }
        String program = real.toRealPath().resolve("bin/nvim").toString();
        assertTrue(NeovimProcesses.anyInside(Stream.of(program), link.resolve("la"), HERE));
    }

    /** This test's own JVM runs from the Java home, never from a temporary folder. */
    @Test
    void theRealProcessListAnswers() throws IOException {
        assertFalse(NeovimProcesses.runFrom(Files.createDirectories(tmp.resolve("empty"))));
        ProcessHandle.current().info().command().ifPresent(java -> assertTrue(
                NeovimProcesses.runFrom(Path.of(java).getParent()), "this JVM runs from " + java));
    }
}
