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

    /** {@code commandId} with every defined id also handled: a normal install. */
    private static Optional<String> chosen(Set<String> defined) {
        return EclipseTerminal.commandId(defined::contains, defined::contains);
    }

    @Test
    void theNewCommandIsTakenWhereBothAreDefined() {
        // Eclipse 2025-09 has both generations of the Terminal.
        assertEquals(Optional.of(EclipseTerminal.COMMAND), chosen(Set.of(EclipseTerminal.COMMAND, EclipseTerminal.OLD_COMMAND)));
        assertEquals(Optional.of(EclipseTerminal.COMMAND), chosen(Set.of(EclipseTerminal.COMMAND)), "2025-12 and later");
        assertEquals(Optional.of(EclipseTerminal.OLD_COMMAND), chosen(Set.of(EclipseTerminal.OLD_COMMAND)), "2024-12 to 2025-06 (2025-09 has both)");
        assertEquals(Optional.empty(), chosen(Set.of()), "no Terminal installed");
    }

    /**
     * Eclipse 2025-06 on a workspace that 2026-09 used: the workspace keeps the new command defined,
     * but nothing handles it there, so the old one, which works, is taken.
     */
    @Test
    void aCommandWithoutAHandlerIsPassedOver() {
        Set<String> defined = Set.of(EclipseTerminal.COMMAND, EclipseTerminal.OLD_COMMAND);
        assertEquals(Optional.of(EclipseTerminal.OLD_COMMAND),
                EclipseTerminal.commandId(defined::contains, EclipseTerminal.OLD_COMMAND::equals));
        assertEquals(Optional.empty(), EclipseTerminal.commandId(defined::contains, id -> false), "defined, but nothing handles either");
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
