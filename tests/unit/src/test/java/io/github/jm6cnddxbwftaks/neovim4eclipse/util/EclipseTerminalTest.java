// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * {@code :terminal} in Eclipse ({@link EclipseTerminal}): which of the Terminal's two command ids to
 * run, and what the messages say. That the command opens the Terminal is a manual check (the test
 * Eclipse has no Terminal; docs/MANUAL_CHECKS.md).
 */
class EclipseTerminalTest {

    @Test
    void theNewCommandIsTakenWhereBothAreDefined() {
        // Eclipse 2025-09 has both generations of the Terminal.
        assertEquals(Optional.of(EclipseTerminal.COMMAND),
                EclipseTerminal.commandId(Set.of(EclipseTerminal.COMMAND, EclipseTerminal.OLD_COMMAND)::contains));
        assertEquals(Optional.of(EclipseTerminal.COMMAND), EclipseTerminal.commandId(Set.of(EclipseTerminal.COMMAND)::contains),
                "2025-12 and later");
        assertEquals(Optional.of(EclipseTerminal.OLD_COMMAND), EclipseTerminal.commandId(Set.of(EclipseTerminal.OLD_COMMAND)::contains),
                "2024-12 to 2025-06 (2025-09 has both)");
        assertEquals(Optional.empty(), EclipseTerminal.commandId(id -> false), "no Terminal installed");
    }

    @Test
    void theCommandIsWhatWasTypedWordForWord() {
        assertEquals("", EclipseTerminal.command(List.of()));
        assertEquals("make -j4", EclipseTerminal.command(List.of("make", "-j4")));
        assertEquals("++close ls", EclipseTerminal.command(List.of("++close", "ls")), "Vim's option is a word of Neovim's shell line");
    }

    @Test
    void aShellAloneNeedsNoMessage() {
        assertNull(EclipseTerminal.notRun("", Path.of("/x"), Path.of("/home/you")));
        assertNull(EclipseTerminal.notRun(null, null, null));
    }

    @Test
    void aCommandNotRunIsNamedWithTheFolder() {
        Path home = Path.of(File.separator + "home", "you");
        assertEquals("terminal: opened Eclipse's Terminal in ~" + File.separator + "src" + File.separator + "app; \"make\" was not run,"
                + " type it there", EclipseTerminal.notRun("make", home.resolve("src").resolve("app"), home));
        assertEquals("terminal: opened Eclipse's Terminal; \"make\" was not run, type it there",
                EclipseTerminal.notRun("make", null, home), "an untitled editor: the Terminal picks the folder");
    }
}
