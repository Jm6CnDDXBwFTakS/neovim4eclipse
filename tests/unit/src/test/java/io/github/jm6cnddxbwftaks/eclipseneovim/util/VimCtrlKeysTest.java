// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VimCtrlKeysTest {

    @Test
    void commandIdRoundTripsToNeovimKey() {
        for (char letter : VimCtrlKeys.LETTERS.toCharArray()) {
            assertEquals("<C-" + letter + ">", VimCtrlKeys.keyFor(VimCtrlKeys.commandId(letter)), "letter " + letter);
        }
    }

    @Test
    void blockwiseVisualIsCtrlV() {
        assertEquals("io.github.jm6cnddxbwftaks.eclipseneovim.commands.ctrl.v", VimCtrlKeys.commandId('v'));
        assertEquals("<C-v>", VimCtrlKeys.keyFor("io.github.jm6cnddxbwftaks.eclipseneovim.commands.ctrl.v"));
    }

    @Test
    void uppercaseLettersMapToTheLowercaseCommand() {
        assertEquals(VimCtrlKeys.commandId('v'), VimCtrlKeys.commandId('V'));
    }

    @Test
    void foreignOrMalformedIdsAreRejected() {
        assertNull(VimCtrlKeys.keyFor(null));
        assertNull(VimCtrlKeys.keyFor(""));
        assertNull(VimCtrlKeys.keyFor("org.eclipse.ui.edit.paste"));
        assertNull(VimCtrlKeys.keyFor("io.github.jm6cnddxbwftaks.eclipseneovim.commands.toggleMode"));
        assertNull(VimCtrlKeys.keyFor(VimCtrlKeys.COMMAND_PREFIX));
        assertNull(VimCtrlKeys.keyFor(VimCtrlKeys.COMMAND_PREFIX + "vv"));
        assertNull(VimCtrlKeys.keyFor(VimCtrlKeys.COMMAND_PREFIX + "1"));
    }

    @Test
    void isCtrlKeyCommand() {
        assertTrue(VimCtrlKeys.isCtrlKeyCommand(VimCtrlKeys.commandId('r')));
        assertFalse(VimCtrlKeys.isCtrlKeyCommand("org.eclipse.ui.edit.paste"));
    }

    @Test
    void everyLetterOfTheAlphabetCanBeClaimed() {
        assertEquals(26, VimCtrlKeys.LETTERS.length());
        for (char c = 'a'; c <= 'z'; c++) {
            assertTrue(VimCtrlKeys.LETTERS.indexOf(c) >= 0, "letter " + c);
            assertFalse(VimCtrlKeys.vimMeaning(c).isBlank(), "meaning of " + c);
        }
    }

    @Test
    void ctrlSWAndZStayWithEclipseByDefault() {
        for (char letter : VimCtrlKeys.LETTERS.toCharArray()) {
            boolean eclipse = letter == 's' || letter == 'w' || letter == 'z';
            assertEquals(!eclipse, VimCtrlKeys.toNeovimByDefault(letter), "letter " + letter);
        }
    }

    @Test
    void contextIdsArePerLetter() {
        assertEquals("io.github.jm6cnddxbwftaks.eclipseneovim.context.ctrl.f", VimCtrlKeys.contextId('F'));
    }

    @Test
    void letterOfAcceptsOnlyPlainCtrlLetters() {
        assertEquals('f', VimCtrlKeys.letterOf("<C-f>"));
        assertEquals('f', VimCtrlKeys.letterOf("<C-F>"));
        assertEquals(0, VimCtrlKeys.letterOf("<C-A-f>"));
        assertEquals(0, VimCtrlKeys.letterOf("<C-]>"));
        assertEquals(0, VimCtrlKeys.letterOf("f"));
        assertEquals(0, VimCtrlKeys.letterOf(null));
    }
}
