// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class KeyMappingTest {

    private static final char NONE = 0;

    @Test
    void plainPrintableCharactersPassThrough() {
        assertEquals("j", KeyMapping.toNeovimKey(0, 0, 'j'));
        assertEquals("G", KeyMapping.toNeovimKey(0, KeyMapping.SWT_SHIFT, 'G'));
        assertEquals(".", KeyMapping.toNeovimKey(0, 0, '.'));
        assertEquals("/", KeyMapping.toNeovimKey(0, 0, '/'));
        assertEquals(":", KeyMapping.toNeovimKey(0, 0, ':'));
    }

    @Test
    void lessThanIsEscapedSoItIsNotReadAsKeyNotation() {
        assertEquals("<lt>", KeyMapping.toNeovimKey(0, 0, '<'));
    }

    @Test
    void escapeFromKeyCodeOrCharacter() {
        assertEquals("<Esc>", KeyMapping.toNeovimKey(KeyMapping.SWT_ESC, 0, NONE));
        assertEquals("<Esc>", KeyMapping.toNeovimKey(0, 0, (char) 27));
    }

    @Test
    void enterVariants() {
        assertEquals("<CR>", KeyMapping.toNeovimKey(KeyMapping.SWT_CR, 0, NONE));
        assertEquals("<CR>", KeyMapping.toNeovimKey(0, 0, '\n'));
        assertEquals("<CR>", KeyMapping.toNeovimKey(KeyMapping.SWT_KEYPAD_CR, 0, NONE));
    }

    @Test
    void tabBackspaceDelete() {
        assertEquals("<Tab>", KeyMapping.toNeovimKey(KeyMapping.SWT_TAB, 0, NONE));
        assertEquals("<BS>", KeyMapping.toNeovimKey(KeyMapping.SWT_BS, 0, NONE));
        assertEquals("<Del>", KeyMapping.toNeovimKey(KeyMapping.SWT_DEL, 0, NONE));
    }

    @Test
    void shiftTabStaysPlainTab() {
        // Documented existing behavior: Shift is not encoded for Tab.
        assertEquals("<Tab>", KeyMapping.toNeovimKey(KeyMapping.SWT_TAB, KeyMapping.SWT_SHIFT, NONE));
    }

    @Test
    void ctrlLetterFromCharacter() {
        assertEquals("<C-w>", KeyMapping.toNeovimKey(0, KeyMapping.SWT_CTRL, 'w'));
        assertEquals("<C-w>", KeyMapping.toNeovimKey(0, KeyMapping.SWT_CTRL, 'W'));
    }

    @Test
    void ctrlLetterFromKeyCodeWhenCharacterIsAControlCode() {
        // Real SWT: Ctrl+W delivers character 0x17 and keyCode 'w'.
        assertEquals("<C-w>", KeyMapping.toNeovimKey('w', KeyMapping.SWT_CTRL, (char) 0x17));
        assertEquals("<C-r>", KeyMapping.toNeovimKey('R', KeyMapping.SWT_CTRL, (char) 0x12));
    }

    @Test
    void ctrlVStartsBlockwiseVisual() {
        // Real SWT: Ctrl+V delivers character 0x16 and keyCode 'v'.
        assertEquals("<C-v>", KeyMapping.toNeovimKey('v', KeyMapping.SWT_CTRL, (char) 0x16));
    }

    @Test
    void ctrlLettersThatAreAsciiTabEnterAndBackspaceStayCtrlLetters() {
        // SWT reports Ctrl+I as keyCode 'i' with the Tab character (likewise Ctrl+M = CR, Ctrl+H = BS).
        assertEquals("<C-i>", KeyMapping.toNeovimKey('i', KeyMapping.SWT_CTRL, (char) KeyMapping.SWT_TAB));
        assertEquals("<C-m>", KeyMapping.toNeovimKey('m', KeyMapping.SWT_CTRL, (char) KeyMapping.SWT_CR));
        assertEquals("<C-h>", KeyMapping.toNeovimKey('h', KeyMapping.SWT_CTRL, (char) KeyMapping.SWT_BS));
        // The real keys are unchanged.
        assertEquals("<Tab>", KeyMapping.toNeovimKey(KeyMapping.SWT_TAB, 0, (char) KeyMapping.SWT_TAB));
        assertEquals("<C-Tab>", KeyMapping.toNeovimKey(KeyMapping.SWT_TAB, KeyMapping.SWT_CTRL, (char) KeyMapping.SWT_TAB));
    }

    // ---- Ctrl with punctuation: <C-^>, <C-]>, <C-\>, <C-_>, <C-@> (KeyMapping.CTRL_PUNCTUATION) ----

    private static final int CTRL = KeyMapping.SWT_CTRL;
    private static final int CTRL_SHIFT = KeyMapping.SWT_CTRL | KeyMapping.SWT_SHIFT;

    /** Ctrl-^ (edit the alternate file) in each form a system reports it. */
    @Test
    void ctrlCaretFromItsControlCodeTheCaretOrCtrl6() {
        assertEquals("<C-^>", KeyMapping.toNeovimKey('6', CTRL_SHIFT, (char) 0x1E), "Ctrl+Shift+6, as its control code");
        assertEquals("<C-^>", KeyMapping.toNeovimKey('6', CTRL_SHIFT, '^'), "Ctrl+Shift+6, as the caret");
        assertEquals("<C-^>", KeyMapping.toNeovimKey('^', CTRL, '^'), "Ctrl+^ on a layout with a ^ key");
        assertEquals("<C-^>", KeyMapping.toNeovimKey('6', CTRL, '6'), "Ctrl+6, Vim's other key for it");
        assertEquals("<C-^>", KeyMapping.toNeovimKey(0, 0, (char) 0x1E), "the control code alone");
    }

    @Test
    void ctrlBracketBackslashAndUnderscore() {
        assertEquals("<C-]>", KeyMapping.toNeovimKey(']', CTRL, (char) 0x1D));
        assertEquals("<C-]>", KeyMapping.toNeovimKey(']', CTRL, ']'));
        assertEquals("<C-\\>", KeyMapping.toNeovimKey('\\', CTRL, (char) 0x1C));
        assertEquals("<C-_>", KeyMapping.toNeovimKey('-', CTRL_SHIFT, (char) 0x1F));
        assertEquals("<C-@>", KeyMapping.toNeovimKey('2', CTRL_SHIFT, '@'));
    }

    /**
     * Ctrl-[ is Esc (code 27), as before; Ctrl+2 and Ctrl+- are no aliases of Vim's (Eclipse binds
     * them), and when they do reach Neovim they keep Ctrl: a bare "2" would start a count.
     */
    @Test
    void ctrlOpenBracketIsEscAndOtherDigitsAreNoAliases() {
        assertEquals("<Esc>", KeyMapping.toNeovimKey('[', CTRL, (char) 27));
        assertEquals("<C-2>", KeyMapping.toNeovimKey('2', CTRL, '2'));
        assertEquals("<C-->", KeyMapping.toNeovimKey('-', CTRL, '-'));
    }

    /** Ctrl with '<' or another character keeps Ctrl, in Neovim's spelling (keytrans() gives <C-lt>). */
    @Test
    void ctrlWithPunctuationKeepsCtrl() {
        assertEquals("<C-lt>", KeyMapping.toNeovimKey(',', CTRL_SHIFT, '<'));
        assertEquals("<C->>", KeyMapping.toNeovimKey('.', CTRL_SHIFT, '>'));
        assertEquals("<C-Space>", KeyMapping.toNeovimKey(' ', CTRL, ' '));
    }

    /** Ctrl+Alt is AltGr on Windows: AltGr+] types ] on many layouts, it is no Ctrl-]. */
    @Test
    void altGrPunctuationIsTyped() {
        assertEquals("]", KeyMapping.toNeovimKey(']', CTRL | KeyMapping.SWT_ALT, ']'));
        assertEquals("\\", KeyMapping.toNeovimKey('\\', CTRL | KeyMapping.SWT_ALT, '\\'));
    }

    private static final int ALT = KeyMapping.SWT_ALT;
    private static final int ALT_SHIFT = KeyMapping.SWT_ALT | KeyMapping.SWT_SHIFT;

    @Test
    void altLetter() {
        assertEquals("<A-x>", KeyMapping.toNeovimKey('x', ALT, 'x'));
        assertEquals("<A-x>", KeyMapping.toNeovimKey('x', ALT, 'x', true), "Option+X that types no other character");
    }

    /** Neovim tells <A-A> from <A-a>; it was sent as <A-a>. */
    @Test
    void altShiftLetterKeepsItsCase() {
        assertEquals("<A-A>", KeyMapping.toNeovimKey('a', ALT_SHIFT, 'A', false));
        assertEquals("<A-A>", KeyMapping.toNeovimKey('a', ALT_SHIFT, 'A', true));
    }

    /** Alt with a digit or punctuation keeps Alt (it was dropped: Alt+1 started a count). */
    @Test
    void altWithDigitsAndPunctuationKeepsAlt() {
        assertEquals("<A-1>", KeyMapping.toNeovimKey('1', ALT, '1', false));
        assertEquals("<A-!>", KeyMapping.toNeovimKey('1', ALT_SHIFT, '!', false));
        assertEquals("<A-lt>", KeyMapping.toNeovimKey(',', ALT_SHIFT, '<', false));
        assertEquals("<A-Space>", KeyMapping.toNeovimKey(' ', ALT, ' ', false));
        assertEquals("<A-é>", KeyMapping.toNeovimKey('é', ALT, 'é', false));
        assertEquals("<A-1>", KeyMapping.toNeovimKey('1', ALT, '1', true), "Option+1 as the key itself");
    }

    /**
     * On macOS, Option types characters: Option+A is "å", Option+5 is "[" on a German keyboard, and
     * Option+Shift+A is "Å". They stay text, not Alt keys.
     */
    @Test
    void optionCharactersOnMacOsAreTyped() {
        assertEquals("å", KeyMapping.toNeovimKey('a', ALT, 'å', true));
        assertEquals("[", KeyMapping.toNeovimKey('5', ALT, '[', true));
        assertEquals("Å", KeyMapping.toNeovimKey('a', ALT_SHIFT, 'Å', true));
        assertEquals("<lt>", KeyMapping.toNeovimKey('z', ALT, '<', true), "an Option '<' is still escaped");
    }

    @Test
    void arrowKeysAndNavigation() {
        assertEquals("<Up>", KeyMapping.toNeovimKey(KeyMapping.SWT_ARROW_UP, 0, NONE));
        assertEquals("<Down>", KeyMapping.toNeovimKey(KeyMapping.SWT_ARROW_DOWN, 0, NONE));
        assertEquals("<Left>", KeyMapping.toNeovimKey(KeyMapping.SWT_ARROW_LEFT, 0, NONE));
        assertEquals("<Right>", KeyMapping.toNeovimKey(KeyMapping.SWT_ARROW_RIGHT, 0, NONE));
        assertEquals("<PageUp>", KeyMapping.toNeovimKey(KeyMapping.SWT_PAGE_UP, 0, NONE));
        assertEquals("<PageDown>", KeyMapping.toNeovimKey(KeyMapping.SWT_PAGE_DOWN, 0, NONE));
        assertEquals("<Home>", KeyMapping.toNeovimKey(KeyMapping.SWT_HOME, 0, NONE));
        assertEquals("<End>", KeyMapping.toNeovimKey(KeyMapping.SWT_END, 0, NONE));
        assertEquals("<Insert>", KeyMapping.toNeovimKey(KeyMapping.SWT_INSERT, 0, NONE));
    }

    @Test
    void functionKeysF1ToF12() {
        assertEquals("<F1>", KeyMapping.toNeovimKey(KeyMapping.SWT_F1, 0, NONE));
        assertEquals("<F5>", KeyMapping.toNeovimKey(KeyMapping.SWT_F1 + 4, 0, NONE));
        assertEquals("<F12>", KeyMapping.toNeovimKey(KeyMapping.SWT_F12, 0, NONE));
        assertNull(KeyMapping.toNeovimKey(KeyMapping.SWT_F12 + 1, 0, NONE), "F13 is not mapped");
    }

    @Test
    void modifiersOnSpecialKeys() {
        assertEquals("<S-Up>", KeyMapping.toNeovimKey(KeyMapping.SWT_ARROW_UP, KeyMapping.SWT_SHIFT, NONE));
        assertEquals("<C-Left>", KeyMapping.toNeovimKey(KeyMapping.SWT_ARROW_LEFT, KeyMapping.SWT_CTRL, NONE));
        assertEquals("<A-Right>", KeyMapping.toNeovimKey(KeyMapping.SWT_ARROW_RIGHT, KeyMapping.SWT_ALT, NONE));
        assertEquals("<C-A-Home>",
                KeyMapping.toNeovimKey(KeyMapping.SWT_HOME, KeyMapping.SWT_CTRL | KeyMapping.SWT_ALT, NONE));
        assertEquals("<S-F3>", KeyMapping.toNeovimKey(KeyMapping.SWT_F1 + 2, KeyMapping.SWT_SHIFT, NONE));
    }

    /** An unbound Cmd+U reaching the editor must not go to Neovim as "u" (undo). */
    @Test
    void cmdKeysAreLeftToEclipse() {
        assertNull(KeyMapping.toNeovimKey('u', KeyMapping.SWT_COMMAND, 'u'));
        assertNull(KeyMapping.toNeovimKey('p', KeyMapping.SWT_COMMAND | KeyMapping.SWT_SHIFT, 'P'));
        assertNull(KeyMapping.toNeovimKey(KeyMapping.SWT_ESC, KeyMapping.SWT_COMMAND, (char) KeyMapping.SWT_ESC));
        assertNull(KeyMapping.toNeovimKey(KeyMapping.SWT_ARROW_UP, KeyMapping.SWT_COMMAND, NONE));
        assertNull(KeyMapping.toNeovimKey('x', KeyMapping.SWT_COMMAND | KeyMapping.SWT_CTRL, (char) 24));
    }

    @Test
    void bareModifierAndUnmappedControlCharactersAreIgnored() {
        assertNull(KeyMapping.toNeovimKey(0, 0, (char) 1));
        assertNull(KeyMapping.toNeovimKey(KeyMapping.SWT_SHIFT, 0, NONE));
    }

    @Test
    void swtConstantsMatchPublishedValues() {
        // Guards against accidental edits. These are the documented values of org.eclipse.swt.SWT.
        assertEquals(0x1000001, KeyMapping.SWT_ARROW_UP);
        assertEquals(0x1000009, KeyMapping.SWT_INSERT);
        assertEquals(0x100000A, KeyMapping.SWT_F1);
        assertEquals(0x1000050, KeyMapping.SWT_KEYPAD_CR);
        assertEquals(0x10000, KeyMapping.SWT_ALT);
        assertEquals(0x20000, KeyMapping.SWT_SHIFT);
        assertEquals(0x40000, KeyMapping.SWT_CTRL);
        assertEquals(0x400000, KeyMapping.SWT_COMMAND);
    }
}
