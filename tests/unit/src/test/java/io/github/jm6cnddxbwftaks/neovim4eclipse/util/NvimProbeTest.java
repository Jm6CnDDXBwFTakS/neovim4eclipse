// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

    private static final NeovimVersion NEWER = new NeovimVersion(MIN.major(), MIN.minor() + 1, 0);

    private static NvimExecutable.Resolved resolved(Path program, NvimExecutable.Kind kind) {
        return new NvimExecutable.Resolved(program.toString(), kind);
    }

    private static Note note(NvimExecutable.Resolved r, NeovimVersion version) {
        return NvimProbe.note(r, version, true, Optional.empty(), DEDICATED, MIN);
    }

    /** The verified dedicated Neovim: "checked", and the page offers only a reinstall. */
    @Test
    void theVerifiedDedicatedNeovimIsCheckedAndCurrent() {
        assertEquals(new Note("Found: dedicated Neovim " + MIN + ", checked, " + OWN, false, true),
                note(resolved(OWN, NvimExecutable.Kind.DEDICATED), null));
    }

    @Test
    void noDedicatedNeovimPointsToGetNeovim() {
        assertEquals(new Note("No dedicated Neovim yet. Get Neovim " + MIN + " installs it into " + DEDICATED, true),
                note(resolved(OWN, NvimExecutable.Kind.NO_DEDICATED), null));
    }

    @Test
    void anUnverifiedDedicatedNeovimSaysGetNeovimReplacesIt() {
        Note n = NvimProbe.note(resolved(OWN, NvimExecutable.Kind.NO_DEDICATED), null, true,
                Optional.of(new PluginNeovim.Unverified(OWN)), DEDICATED, MIN);
        assertEquals(new Note("The dedicated Neovim's files differ from Neovim " + MIN + "'s release (an older one, "
                + "or damaged): Get Neovim replaces it (" + OWN + ")", true), n);
    }

    /** A program the user names runs unchecked, and the note says so; it is not a problem. */
    @Test
    void aNamedProgramIsUncheckedAndUnsupported() {
        Note n = note(resolved(PROGRAM, NvimExecutable.Kind.PROGRAM), NEWER);
        assertEquals(new Note("Found: Neovim " + NEWER + ", " + PROGRAM + ". Not the dedicated Neovim: not checked "
                + "by the plug-in, and unsupported. Clear the field to use the dedicated one.", false), n);
        assertFalse(note(resolved(OWN, NvimExecutable.Kind.PROGRAM), MIN).current(),
                "the dedicated program named in the field is not checked either");
    }

    /**
     * While its path is being typed, a program is not run (a half-typed path may name another
     * user's file): the note names it and says its version shows after Apply.
     */
    @Test
    void aProgramBeingTypedIsNotRunAndTheNoteSaysWhen() {
        Note n = NvimProbe.note(resolved(PROGRAM, NvimExecutable.Kind.PROGRAM), null, false, Optional.empty(),
                DEDICATED, MIN);
        assertEquals(new Note("Program: " + PROGRAM + ". Not the dedicated Neovim: not checked by the plug-in, "
                + "and unsupported. Its version shows after Apply.", false), n);
    }

    @Test
    void aNamedProgramThatIsOldOrNotNeovimIsAProblem() {
        assertEquals(new Note("Found: Neovim " + OLD + " (too old: Neovim for Eclipse needs " + MIN + " or newer), "
                + PROGRAM, true), note(resolved(PROGRAM, NvimExecutable.Kind.PROGRAM), OLD));
        assertEquals(new Note("Found: no Neovim version (it did not answer --version), " + PROGRAM, true),
                note(resolved(PROGRAM, NvimExecutable.Kind.PROGRAM), null));
    }

    @Test
    void aFolderMissingOrRelativeValueSaysWhatToEnter() {
        Path folder = Path.of("/opt/nvim");
        assertEquals(new Note(folder + " is a folder: enter the Neovim program inside it, such as "
                + folder.resolve("bin").resolve(NvimExecutable.defaultCommandName()), true),
                note(resolved(folder, NvimExecutable.Kind.FOLDER), null));
        assertEquals(new Note("No Neovim program at " + PROGRAM + ". Clear the field to use the dedicated Neovim.",
                true), note(resolved(PROGRAM, NvimExecutable.Kind.MISSING), null));
        assertTrue(note(new NvimExecutable.Resolved("nvim", NvimExecutable.Kind.NOT_ABSOLUTE), null).problem());
    }

    @Test
    void unusedEntriesAreListedOneFullPathALine() {
        assertNull(NvimProbe.unusedLines(List.of(), DEDICATED));
        assertEquals("Not used by the plug-in, you can delete:\n  " + DEDICATED.resolve("current") + "\n  "
                + DEDICATED.resolve("nvim-linux-arm64_0.12.6"),
                NvimProbe.unusedLines(List.of("current", "nvim-linux-arm64_0.12.6"), DEDICATED));
    }
}
