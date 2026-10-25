// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimProbe.Note;

/**
 * The note under the Neovim field on the preference page (see {@link NvimProbe}): its text, and
 * whether it is a problem (shown in the error color, with an error mark on the field).
 */
class NvimProbeTest {

    private static final Path PROGRAM = Path.of("/home/you/.local/share/neovim4eclipse-plugin/neovim/bin/nvim");
    private static final Path DEDICATED = Path.of("/home/you/.local/share/neovim4eclipse-plugin/neovim");
    private static final NeovimVersion MIN = NeovimVersion.MINIMUM;
    private static final NeovimVersion OLD = new NeovimVersion(0, 9, 5);

    /** A note that Neovim would start with. */
    private static Note fine(String text) {
        return new Note(text, false);
    }

    /** A note that Neovim would not start with. */
    private static Note problem(String text) {
        return new Note(text, true);
    }

    @Test
    void aSupportedNeovimShowsItsVersionThenItsPath() {
        assertEquals(fine("Found: Neovim " + MIN + ", " + PROGRAM), NvimProbe.note("", PROGRAM, MIN, 1, DEDICATED));
    }

    @Test
    void aChoiceAmongSeveralSaysSo() {
        assertEquals(fine("Found: Neovim " + MIN + " (newest of 2 in this folder), " + PROGRAM),
                NvimProbe.note("", PROGRAM, MIN, 2, DEDICATED));
    }

    @Test
    void anOldNeovimSaysItIsTooOldAndIsAProblem() {
        // "Found:", yet Neovim would refuse to start: a problem all the same.
        assertEquals(problem("Found: Neovim 0.9.5 (too old: Neovim for Eclipse needs " + MIN + " or newer), "
                + PROGRAM), NvimProbe.note("", PROGRAM, OLD, 1, DEDICATED));
    }

    @Test
    void anOldNewestOfSeveralSaysBoth() {
        assertEquals(problem("Found: Neovim 0.9.5 (newest of 3 in this folder; too old: Neovim for Eclipse needs "
                + MIN + " or newer), " + PROGRAM), NvimProbe.note("", PROGRAM, OLD, 3, DEDICATED));
    }

    @Test
    void nothingFoundByTheDefaultSearchSuggestsTheDefaultNeovimFolder() {
        Note expected = problem("No Neovim found in the default folders. Extract a Neovim release archive into "
                + DEDICATED);
        assertEquals(expected, NvimProbe.note("", null, null, 0, DEDICATED));
        assertEquals(expected, NvimProbe.note("  ", null, null, 0, DEDICATED));
        assertEquals(expected, NvimProbe.note(null, null, null, 0, DEDICATED));
    }

    @Test
    void nothingFoundAtAnEnteredValueSuggestsClearingTheField() {
        assertEquals(problem("No Neovim found at /opt/nvim. Clear the field to search the default folders, or "
                + "enter a value as described."), NvimProbe.note("  /opt/nvim ", null, null, 0, DEDICATED));
    }

    @Test
    void aProgramThatIsNotNeovimIsNamed() {
        assertEquals(problem("Found: no Neovim version (it did not answer --version), " + PROGRAM),
                NvimProbe.note("", PROGRAM, null, 1, DEDICATED));
    }
}
