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

    /** Ctrl-[ is Esc (code 27), as before; Ctrl+2 and Ctrl+- are Eclipse's, not Vim's aliases. */
    @Test
    void ctrlOpenBracketIsEscAndOtherDigitsAreNoAliases() {
        assertEquals("<Esc>", KeyMapping.toNeovimKey('[', CTRL, (char) 27));
        assertEquals("2", KeyMapping.toNeovimKey('2', CTRL, '2'));
        assertEquals("-", KeyMapping.toNeovimKey('-', CTRL, '-'));
    }

    /** Ctrl+Alt is AltGr on Windows: AltGr+] types ] on many layouts, it is no Ctrl-]. */
    @Test
    void altGrPunctuationIsTyped() {
        assertEquals("]", KeyMapping.toNeovimKey(']', CTRL | KeyMapping.SWT_ALT, ']'));
        assertEquals("\\", KeyMapping.toNeovimKey('\\', CTRL | KeyMapping.SWT_ALT, '\\'));
    }

    @Test
    void altLetter() {
        assertEquals("<A-x>", KeyMapping.toNeovimKey(0, KeyMapping.SWT_ALT, 'x'));
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
    }
}
