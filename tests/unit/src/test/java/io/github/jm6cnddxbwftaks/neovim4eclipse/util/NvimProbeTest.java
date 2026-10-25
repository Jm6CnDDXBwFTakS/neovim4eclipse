// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The note under the Neovim field on the preference page (see {@link NvimProbe}). */
class NvimProbeTest {

    private static final Path PROGRAM = Path.of("/home/you/.local/opt/neovim/bin/nvim");
    private static final Path DEDICATED = Path.of("/home/you/.local/opt/neovim");
    private static final NeovimVersion MIN = NeovimVersion.MINIMUM;

    @TempDir
    Path tmp;

    @Test
    void aSupportedNeovimShowsItsPathAndVersion() {
        assertEquals("Found " + PROGRAM + ", Neovim " + MIN + ".", NvimProbe.note(PROGRAM, MIN, 1, DEDICATED));
    }

    @Test
    void aChoiceAmongSeveralSaysSo() {
        assertEquals("Found " + PROGRAM + ", Neovim " + MIN + " (newest of 2 in this folder).",
                NvimProbe.note(PROGRAM, MIN, 2, DEDICATED));
    }

    @Test
    void anOldNeovimSaysItIsTooOld() {
        assertEquals("Found " + PROGRAM + ", Neovim 0.9.5: too old, Neovim for Eclipse needs " + MIN + " or newer.",
                NvimProbe.note(PROGRAM, new NeovimVersion(0, 9, 5), 1, DEDICATED));
    }

    @Test
    void nothingFoundSuggestsTheDedicatedFolder() {
        assertEquals("No Neovim found. Extract a release archive into " + DEDICATED
                + ", or choose the folder of another Neovim " + MIN + " or newer.", NvimProbe.note(null, null, 0, DEDICATED));
    }

    @Test
    void aProgramThatIsNotNeovimIsNamed() {
        assertEquals("Found " + PROGRAM + ", but it did not answer --version as Neovim does.",
                NvimProbe.note(PROGRAM, null, 1, DEDICATED));
    }

    @Test
    void siblingsCountsTheInstallsBesideAnArchiveProgram() throws IOException {
        Path a = Files.createDirectories(tmp.resolve("neovim/nvim-0.12.6/bin")).resolve("nvim");
        Files.createFile(a);
        assertEquals(1, NvimProbe.siblings(a));
        Files.createFile(Files.createDirectories(tmp.resolve("neovim/nvim-0.13.0/bin")).resolve("nvim"));
        assertEquals(2, NvimProbe.siblings(a));
        assertEquals(1, NvimProbe.siblings(PROGRAM), "an install folder's own bin/nvim: no choice");
    }
}
