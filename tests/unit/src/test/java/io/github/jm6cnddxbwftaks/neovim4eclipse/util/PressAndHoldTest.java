// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PressAndHold.Kind;

/**
 * macOS's accent menu in the {@code :} box. Each sequence is what SWT's macOS port sent with
 * press-and-hold on (logged on macOS, plug-in 0.2.1): key presses with their times, and releases.
 * A held key repeats after about 500 ms, then about every 83 ms, with no release in between.
 */
class PressAndHoldTest {

    private static final int ESC = 27;
    private static final int ENTER = 13;
    private static final int BACKSPACE = 8;
    private static final int ARROW_RIGHT = (1 << 24) + 4;

    private final PressAndHold hold = new PressAndHold();

    private Kind down(int keyCode, char character, long time) {
        return press(keyCode, character, time).kind();
    }

    /** A press; the box passes the key in Neovim's notation, here simply the character. */
    private PressAndHold.Key press(int keyCode, char character, long time) {
        return hold.keyDown(keyCode, character, String.valueOf(character), false, time, true);
    }

    /** E held: the first press is sent, the repeats are not, and the menu may be open. */
    private void holdE(long from) {
        assertEquals(Kind.SEND, down('e', 'e', from));
        for (long t = from + 502; t <= from + 1000; t += 83) {
            assertEquals(Kind.DROP, down('e', 'e', t), "a repeat at " + (t - from) + " ms");
        }
        hold.keyUp('e');
    }

    /**
     * Logged: E held, 2. The 2 is held back; its second press, with the same event time and no
     * release, carries é: the pick. The 2's release follows, and sends nothing.
     */
    @Test
    void aDigitPicksFromTheMenu() {
        holdE(0);
        PressAndHold.Key two = press('2', '2', 3160);
        assertEquals(Kind.HOLD, two.kind());
        assertNull(two.sendFirst());
        assertEquals(Kind.PICK, down('2', 'é', 3160), "Backspace over the e, then é");
        assertNull(hold.keyUp('2'), "the 2 is not sent");
        assertEquals(Kind.SEND, down('\'', '"', 4900), "the menu is closed");
    }

    /** A digit after holding a letter that picks nothing (no menu): sent late, not lost. */
    @Test
    void aDigitThatPicksNothingIsSentBeforeTheNextKey() {
        holdE(0);
        assertEquals(Kind.HOLD, down('2', '2', 3160));
        PressAndHold.Key next = press('3', '3', 3300);
        assertEquals("2", next.sendFirst(), "the 2 first");
        assertEquals(Kind.SEND, next.kind(), "then the 3, as usual: the menu was not there");
    }

    /**
     * Shift+1 held after a held letter: its repeats carry {@code !} too, but come later than the
     * press, so none is a pick. Before, the first repeat was: Backspace over the e, then !.
     */
    @Test
    void aRepeatOfAHeldBackShiftedDigitIsNoPick() {
        holdE(0);
        assertEquals(Kind.HOLD, down('1', '!', 3000));
        PressAndHold.Key repeat = press('1', '!', 3502);
        assertEquals(Kind.SEND, repeat.kind(), "a repeat, sent as usual");
        assertEquals("!", repeat.sendFirst(), "after the ! held back");
    }

    /** A second press of the digit at the same time but carrying no letter is no pick either. */
    @Test
    void onlyALetterIsAPick() {
        holdE(0);
        assertEquals(Kind.HOLD, down('1', '1', 3000));
        assertEquals(Kind.SEND, down('1', '!', 3000));
    }

    /** A held digit let go with no pick (no menu): the box sends it then. */
    @Test
    void aHeldDigitLetGoWithoutAPickIsSent() {
        holdE(0);
        assertEquals(Kind.HOLD, down('2', '2', 3160));
        assertEquals("2", hold.keyUp('2'));
        assertEquals(Kind.SEND, down('3', '3', 3300), "and the menu is over");
    }

    /**
     * A click on an accent in the menu (an edit, no key press) closes it: the next Esc is Neovim's.
     * Before, it was taken as the menu's and the line stayed open.
     */
    @Test
    void aClickClosesTheMenu() {
        holdE(0);
        assertNull(hold.interrupt(), "nothing held back");
        assertEquals(Kind.SEND, down(ESC, '\u001b', 2300));
    }

    /**
     * Tab, a Vim Ctrl key or a paste while a digit is held back: the digit goes first, then that,
     * as they were typed, not the 2 after the Tab.
     */
    @Test
    void aDigitHeldBackGoesBeforeAnInterruption() {
        holdE(0);
        assertEquals(Kind.HOLD, down('2', '2', 3160));
        assertEquals("2", hold.interrupt());
        assertNull(hold.keyUp('2'), "sent once");
        assertEquals(Kind.SEND, down(ESC, '\u001b', 3400), "the menu is over");
    }

    /** A digit held back when a composition starts is dropped, so Neovim's line stays as the box shows it. */
    @Test
    void aCompositionDropsADigitHeldBack() {
        holdE(0);
        assertEquals(Kind.HOLD, down('2', '2', 3160));
        hold.compositionStarted();
        assertNull(hold.keyUp('2'));
        assertNull(press('3', '3', 3300).sendFirst());
    }

    /** Logged: E held, Esc. Esc closed Neovim's line; it is the menu's. The next one is Neovim's. */
    @Test
    void theFirstEscAfterAHeldLetterClosesTheMenuOnly() {
        holdE(0);
        assertEquals(Kind.DROP, down(ESC, '\u001b', 2300));
        assertEquals(Kind.SEND, down(ESC, '\u001b', 2900));
    }

    /** Logged: E held, x. The menu closes and x is typed. */
    @Test
    void anotherKeyClosesTheMenuAndIsTyped() {
        holdE(0);
        assertEquals(Kind.SEND, down('x', 'x', 1600));
        assertEquals(Kind.SEND, down(ESC, '\u001b', 2000), "no menu now");
    }

    /** Logged: K held (it has a menu too): its repeats are not sent either. */
    @Test
    void anyHeldLetterDoesNotRepeat() {
        assertEquals(Kind.SEND, down('k', 'k', 0));
        for (long t = 501; t <= 1500; t += 83) {
            assertEquals(Kind.DROP, down('k', 'k', t));
        }
    }

    /** Logged: E held, then → and Enter, which are an input method's composition (MarkedTextInput). */
    @Test
    void theArrowsAreTheCompositionsNotOurs() {
        holdE(0);
        hold.compositionStarted(); // the box: → put è over the e as marked text
        assertEquals(Kind.SEND, down(ESC, '\u001b', 9000), "after the commit, Esc is Neovim's");
    }

    /** Backspace, the arrows and Space are no letters: they repeat as before. */
    @Test
    void keysThatAreNoLetterRepeat() {
        for (int key : new int[] { BACKSPACE, ' ' }) {
            hold.reset();
            assertEquals(Kind.SEND, down(key, (char) key, 0));
            assertEquals(Kind.SEND, down(key, (char) key, 502));
            assertEquals(Kind.SEND, down(key, (char) key, 585));
        }
        hold.reset();
        assertEquals(Kind.SEND, down(ARROW_RIGHT, '\0', 0));
        assertEquals(Kind.SEND, down(ARROW_RIGHT, '\0', 502));
    }

    /** "ee" typed quickly: a release between the presses, so no repeat. */
    @Test
    void aLetterTypedTwiceIsNoRepeat() {
        assertEquals(Kind.SEND, down('e', 'e', 0));
        hold.keyUp('e');
        assertEquals(Kind.SEND, down('e', 'e', 120));
        assertEquals(Kind.SEND, down(ENTER, '\r', 400), "and no menu");
    }

    /** A release that went elsewhere (the focus moved) does not make a later press a repeat. */
    @Test
    void aPressLongAfterTheLastIsNoRepeat() {
        assertEquals(Kind.SEND, down('e', 'e', 0));
        assertEquals(Kind.SEND, down('e', 'e', PressAndHold.LONGEST_REPEAT_DELAY_MS + 1));
    }

    /** With Cmd, Ctrl or Option held, nothing is a menu's. */
    @Test
    void modifiedKeysAreSent() {
        assertEquals(Kind.SEND, hold.keyDown('e', 'e', "e", true, 0, true).kind());
        assertEquals(Kind.SEND, hold.keyDown('e', 'e', "e", true, 502, true).kind());
    }

    /** With press-and-hold off (as many Vim users set it), a held letter repeats, as in a terminal. */
    @Test
    void withPressAndHoldOffHeldLettersRepeat() {
        assertEquals(Kind.SEND, hold.keyDown('e', 'e', "e", false, 0, false).kind());
        assertEquals(Kind.SEND, hold.keyDown('e', 'e', "e", false, 502, false).kind());
        assertEquals(Kind.SEND, hold.keyDown(ESC, '\u001b', "<Esc>", false, 900, false).kind());
    }
}
