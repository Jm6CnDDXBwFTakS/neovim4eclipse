// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimProbe.Note;

/**
 * The note under the Neovim field on the preference page (see {@link NvimProbe}): its text, and
 * whether it is a problem (shown in the error color, with an error mark on the field).
 */
class NvimProbeTest {

    /** A Neovim the user installed. */
    private static final Path PROGRAM = Path.of("/opt/nvim-linux-arm64/bin/nvim");
    private static final Path DEDICATED = Path.of("/home/you/.local/share/neovim4eclipse-plugin/neovim");
    /** The dedicated Neovim, the one Get Neovim installs. */
    private static final Path OWN = DEDICATED.resolve("la/bin/nvim");
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
        assertEquals(fine("Found: Neovim " + MIN + ", " + PROGRAM), NvimProbe.note("", PROGRAM, MIN, 1, DEDICATED, MIN));
    }

    @Test
    void aChoiceAmongSeveralSaysSo() {
        assertEquals(fine("Found: Neovim " + MIN + " (newest of 2 in this folder), " + PROGRAM),
                NvimProbe.note("", PROGRAM, MIN, 2, DEDICATED, MIN));
    }

    @Test
    void anOldNeovimSaysItIsTooOldAndIsAProblem() {
        // "Found:", yet Neovim would refuse to start: a problem all the same.
        assertEquals(problem("Found: Neovim 0.9.5 (too old: Neovim for Eclipse needs " + MIN + " or newer), "
                + PROGRAM), NvimProbe.note("", PROGRAM, OLD, 1, DEDICATED, MIN));
    }

    @Test
    void anOldNewestOfSeveralSaysBoth() {
        assertEquals(problem("Found: Neovim 0.9.5 (newest of 3 in this folder; too old: Neovim for Eclipse needs "
                + MIN + " or newer), " + PROGRAM), NvimProbe.note("", PROGRAM, OLD, 3, DEDICATED, MIN));
    }

    @Test
    void nothingFoundByTheDefaultSearchSuggestsTheDefaultNeovimFolder() {
        Note expected = problem("No Neovim found in the default folders. Get Neovim " + MIN + " installs it into "
                + DEDICATED);
        assertEquals(expected, NvimProbe.note("", null, null, 0, DEDICATED, MIN));
        assertEquals(expected, NvimProbe.note("  ", null, null, 0, DEDICATED, MIN));
        assertEquals(expected, NvimProbe.note(null, null, null, 0, DEDICATED, MIN));
    }

    @Test
    void nothingFoundAtAnEnteredValueSuggestsClearingTheField() {
        assertEquals(problem("No Neovim found at /opt/nvim. Clear the field to search the default folders, or "
                + "enter a value as described."), NvimProbe.note("  /opt/nvim ", null, null, 0, DEDICATED, MIN));
    }

    @Test
    void aProgramThatIsNotNeovimIsNamed() {
        assertEquals(problem("Found: no Neovim version (it did not answer --version), " + PROGRAM),
                NvimProbe.note("", PROGRAM, null, 1, DEDICATED, MIN));
    }

    @Test
    void theDedicatedNeovimIsCalledSoAndWhenCurrentThePageOffersOnlyAReinstall() {
        Note current = NvimProbe.note("", OWN, MIN, 1, DEDICATED, MIN);
        assertEquals(new Note("Found: dedicated Neovim " + MIN + ", " + OWN, false, true), current);
        NeovimVersion newer = new NeovimVersion(MIN.major(), MIN.minor() + 1, 0);
        assertTrue(NvimProbe.note("", OWN, newer, 1, DEDICATED, MIN).current(), "newer than Get Neovim's");
        assertFalse(NvimProbe.note("", OWN, MIN, 1, DEDICATED, newer).current(), "Get Neovim has a newer one");
        assertFalse(NvimProbe.note("", OWN, null, 1, DEDICATED, MIN).current(),
                "a copy that does not answer --version gets the Get Neovim button back");
        assertFalse(NvimProbe.note("", PROGRAM, MIN, 1, DEDICATED, MIN).current(), "not the dedicated one");
    }

    @Test
    void unusedEntriesAreListedOneFullPathALine() {
        assertNull(NvimProbe.unusedLines(List.of(), DEDICATED));
        assertEquals("Not used by the plug-in, you can delete:\n  " + DEDICATED.resolve("current") + "\n  "
                + DEDICATED.resolve("nvim-linux-arm64_0.12.6"),
                NvimProbe.unusedLines(List.of("current", "nvim-linux-arm64_0.12.6"), DEDICATED));
    }
}
