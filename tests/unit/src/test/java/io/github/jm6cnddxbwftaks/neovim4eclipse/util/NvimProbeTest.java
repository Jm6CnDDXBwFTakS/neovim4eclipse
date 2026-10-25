// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** The note under the Neovim field on the preference page (see {@link NvimProbe}). */
class NvimProbeTest {

    private static final Path PROGRAM = Path.of("/home/you/.local/share/neovim4eclipse-plugin/neovim/bin/nvim");
    private static final Path DEDICATED = Path.of("/home/you/.local/share/neovim4eclipse-plugin/neovim");
    private static final NeovimVersion MIN = NeovimVersion.MINIMUM;

    @Test
    void aSupportedNeovimShowsItsVersionThenItsPath() {
        assertEquals("Found: Neovim " + MIN + ", " + PROGRAM, NvimProbe.note("", PROGRAM, MIN, 1, DEDICATED));
    }

    @Test
    void aChoiceAmongSeveralSaysSo() {
        assertEquals("Found: Neovim " + MIN + " (newest of 2 in this folder), " + PROGRAM,
                NvimProbe.note("", PROGRAM, MIN, 2, DEDICATED));
    }

    @Test
    void anOldNeovimSaysItIsTooOld() {
        assertEquals("Found: Neovim 0.9.5 (too old: Neovim for Eclipse needs " + MIN + " or newer), " + PROGRAM,
                NvimProbe.note("", PROGRAM, new NeovimVersion(0, 9, 5), 1, DEDICATED));
    }

    @Test
    void anOldNewestOfSeveralSaysBoth() {
        assertEquals("Found: Neovim 0.9.5 (newest of 3 in this folder; too old: Neovim for Eclipse needs " + MIN
                + " or newer), " + PROGRAM, NvimProbe.note("", PROGRAM, new NeovimVersion(0, 9, 5), 3, DEDICATED));
    }

    @Test
    void nothingFoundByTheDefaultSearchSuggestsTheDefaultNeovimFolder() {
        String expected = "No Neovim found in the default folders. Extract a Neovim release archive into " + DEDICATED;
        assertEquals(expected, NvimProbe.note("", null, null, 0, DEDICATED));
        assertEquals(expected, NvimProbe.note("  ", null, null, 0, DEDICATED));
        assertEquals(expected, NvimProbe.note(null, null, null, 0, DEDICATED));
    }

    @Test
    void nothingFoundAtAnEnteredValueSuggestsClearingTheField() {
        assertEquals("No Neovim found at /opt/nvim. Clear the field to search the default folders, or enter a value "
                + "as described.", NvimProbe.note("  /opt/nvim ", null, null, 0, DEDICATED));
    }

    @Test
    void aProgramThatIsNotNeovimIsNamed() {
        assertEquals("Found: no Neovim version (it did not answer --version), " + PROGRAM,
                NvimProbe.note("", PROGRAM, null, 1, DEDICATED));
    }
}
