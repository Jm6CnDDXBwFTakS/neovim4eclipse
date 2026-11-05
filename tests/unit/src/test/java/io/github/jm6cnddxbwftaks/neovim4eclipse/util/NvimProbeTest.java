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
        return NvimProbe.note(r, version, Optional.empty(), DEDICATED, MIN);
    }

    /** Every note about a named program says where the name came from: the startup option. */
    private static final String FROM = "from -Dneovim4eclipse.nvim";

    /** The verified dedicated Neovim: "checked", and the page offers only a reinstall. */
    @Test
    void theVerifiedDedicatedNeovimIsCheckedAndCurrent() {
        assertEquals(new Note("Found: dedicated Neovim " + MIN + ", checked, " + OWN, false, true),
                note(resolved(OWN, NvimExecutable.Kind.DEDICATED), null));
    }

    @Test
    void noDedicatedNeovimPointsToGetNeovim() {
        assertEquals(new Note("No dedicated Neovim yet. \"Get Neovim " + MIN + "\" installs it into " + DEDICATED,
                true), note(resolved(OWN, NvimExecutable.Kind.NO_DEDICATED), null));
    }

    @Test
    void anUnverifiedDedicatedNeovimSaysGetNeovimReplacesIt() {
        Note n = NvimProbe.note(resolved(OWN, NvimExecutable.Kind.NO_DEDICATED), null,
                Optional.of(new PluginNeovim.Unverified(OWN)), DEDICATED, MIN);
        assertEquals(new Note("The dedicated Neovim's files differ from Neovim " + MIN + "'s release (an older one, "
                + "or damaged): \"Get Neovim\" replaces it (" + OWN + ")", true), n);
    }

    /** A program the option names runs unchecked, and the note says so; it is not a problem. */
    @Test
    void aNamedProgramIsUncheckedAndUnsupported() {
        Note n = note(resolved(PROGRAM, NvimExecutable.Kind.PROGRAM), NEWER);
        assertEquals(new Note("Found: Neovim " + NEWER + ", " + PROGRAM + ", " + FROM + ". Not the dedicated Neovim: "
                + "not checked by the plug-in, and unsupported. Remove the option to use the dedicated one.", false), n);
        assertFalse(note(resolved(OWN, NvimExecutable.Kind.PROGRAM), MIN).current(),
                "the dedicated program named by the option is not checked either");
    }

    @Test
    void aNamedProgramThatIsOldOrNotNeovimIsAProblem() {
        assertEquals(new Note("Found: Neovim " + OLD + " (too old: Neovim for Eclipse needs " + MIN + " or newer), "
                + PROGRAM + ", " + FROM, true), note(resolved(PROGRAM, NvimExecutable.Kind.PROGRAM), OLD));
        assertEquals(new Note("Not a working Neovim (no answer to --version): " + PROGRAM + ", " + FROM, true),
                note(resolved(PROGRAM, NvimExecutable.Kind.PROGRAM), null));
    }

    @Test
    void aFolderMissingOrRelativeValueSaysWhatToName() {
        Path folder = Path.of("/opt/nvim");
        assertEquals(new Note(folder + " (" + FROM + ") is a folder: name the Neovim program inside it, such as "
                + folder.resolve("bin").resolve(NvimExecutable.defaultCommandName()), true),
                note(resolved(folder, NvimExecutable.Kind.FOLDER), null));
        assertEquals(new Note("No Neovim program at " + PROGRAM + " (" + FROM + "). Remove the option to use the "
                + "dedicated Neovim.", true), note(resolved(PROGRAM, NvimExecutable.Kind.MISSING), null));
        assertEquals(new Note("-Dneovim4eclipse.nvim must name the absolute path of a Neovim program (a leading ~ is "
                + "fine): nvim. Remove the option to use the dedicated Neovim.", true),
                note(new NvimExecutable.Resolved("nvim", NvimExecutable.Kind.NOT_ABSOLUTE), null));
    }

    /**
     * The Get Neovim button follows the notice's rule: for a program the option names, only while the
     * dedicated Neovim does not check out; never beside Reinstall.
     */
    @Test
    void getNeovimIsOfferedForANamedProgramOnlyWithoutAVerifiedDedicatedNeovim() {
        NvimExecutable.Resolved folder = resolved(Path.of("/opt/nvim"), NvimExecutable.Kind.FOLDER);
        Note refused = note(folder, null);
        assertTrue(NvimProbe.offering(refused, folder, false).getNeovim(), "install it, then remove the option");
        assertFalse(NvimProbe.offering(refused, folder, true).getNeovim(), "it is installed: remove the option");
        NvimExecutable.Resolved dedicated = resolved(OWN, NvimExecutable.Kind.DEDICATED);
        assertFalse(NvimProbe.offering(note(dedicated, null), dedicated, true).getNeovim(), "Reinstall instead");
        NvimExecutable.Resolved none = resolved(OWN, NvimExecutable.Kind.NO_DEDICATED);
        assertTrue(note(none, null).getNeovim(), "nothing installed yet");
    }

    /** The unused entries travel apart from the note's text, so the page can show them in the normal color. */
    @Test
    void theUnusedLinesAreKeptApartFromTheNote() {
        Note note = note(resolved(OWN, NvimExecutable.Kind.NO_DEDICATED), null);
        assertNull(note.unused());
        Note with = note.withUnused("lines");
        assertEquals(note.text(), with.text());
        assertEquals("lines", with.unused());
        assertTrue(with.problem(), "the note keeps its color; the lines are shown apart");
    }

    @Test
    void unusedEntriesAreListedOneFullPathALine() {
        assertNull(NvimProbe.unusedLines(List.of(), DEDICATED));
        assertEquals("Not used by the plug-in, you can delete:\n  " + DEDICATED.resolve("current") + "\n  "
                + DEDICATED.resolve("nvim-linux-arm64_0.12.6"),
                NvimProbe.unusedLines(List.of("current", "nvim-linux-arm64_0.12.6"), DEDICATED));
    }
}
