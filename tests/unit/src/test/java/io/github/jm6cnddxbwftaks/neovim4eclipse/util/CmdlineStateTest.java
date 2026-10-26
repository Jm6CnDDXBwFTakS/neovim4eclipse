// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CmdlineState.View;

/** What the {@code :} box shows for the events Neovim 0.12.6 sends (experiments X3 and X8). */
class CmdlineStateTest {

    private final CmdlineState state = new CmdlineState();

    private static CmdlineEvent show(String firstc, String content, int pos) {
        return new CmdlineEvent.Show(firstc, content, pos, "", 0, 1);
    }

    private View apply(CmdlineEvent... events) {
        for (CmdlineEvent event : events) {
            state.apply(event);
        }
        return state.view();
    }

    @Test
    void closedUntilALineIsShown() {
        assertEquals(View.CLOSED, state.view());
        assertFalse(state.view().open());
    }

    /** The type character, then the text; the caret after the type character plus Neovim's cursor. */
    @Test
    void aLineAndItsCursor() {
        View view = apply(show(":", "set tabs", 8));
        assertEquals(new View(true, ":", ":set tabs", 9, List.of(), -1), view);
        assertEquals(6, apply(new CmdlineEvent.Pos(5, 1)).caret()); // <Left> three times
    }

    /** Neovim's cursor is a byte offset: {@code "é"} is two bytes but one character. */
    @Test
    void theCursorIsConvertedFromBytes() {
        assertEquals(3, apply(show(":", "éx", 3)).caret()); // after "é" and "x": bytes 3, chars 2, plus ":"
    }

    @Test
    void aPromptIsShownInsteadOfATypeCharacter() {
        View view = apply(new CmdlineEvent.Show("", "def", 3, "Name: ", 0, 1));
        assertEquals("Name: def", view.text());
        assertEquals(9, view.caret());
        assertFalse(view.isSearch());
    }

    @Test
    void theIndentComesBetweenTheTypeAndTheText() {
        assertEquals(":  x", apply(new CmdlineEvent.Show(":", "x", 0, "", 2, 1)).text());
    }

    /** {@code <C-r>} shows {@code "} at the cursor until the register is named. */
    @Test
    void aSpecialCharacterIsShownAtTheCursor() {
        apply(show(":", "ab", 1));
        assertEquals(":a\"b", apply(new CmdlineEvent.SpecialChar("\"", true, 1)).text(), "shifted in");
        assertEquals(":ab", apply(new CmdlineEvent.Pos(1, 1)).text(), "gone with the next report");
        assertEquals(":a^", apply(new CmdlineEvent.SpecialChar("^", false, 1)).text(), "over the character");
    }

    /** {@code <C-r>=} opens a line inside the line: it is shown, then the outer one again. */
    @Test
    void anInnerLineIsShownWhileItIsOpen() {
        apply(show(":", "echo ", 5));
        assertEquals("=1+1", apply(new CmdlineEvent.Show("=", "1+1", 3, "", 0, 2)).text());
        assertEquals(":echo ", apply(new CmdlineEvent.Hide(2, false)).text());
        assertFalse(apply(new CmdlineEvent.Hide(1, false)).open());
    }

    @Test
    void theCompletionMenuOfTheCommandLine() {
        apply(show(":", "e ", 2));
        View view = apply(new CmdlineEvent.MenuShow(List.of("a.txt", "b.txt"), 0, -1), show(":", "e a.txt", 7));
        assertEquals(List.of("a.txt", "b.txt"), view.candidates());
        assertEquals(0, view.selected());
        assertEquals(1, apply(new CmdlineEvent.MenuSelect(1)).selected());
        assertEquals(List.of(), apply(new CmdlineEvent.MenuHide()).candidates());
    }

    /** {@code <C-n>} in a block {@code I} that Neovim drives shows a menu on grid 1: not the box's. */
    @Test
    void anInsertsMenuIsIgnored() {
        assertFalse(state.apply(new CmdlineEvent.MenuShow(List.of("alpha"), 0, 1)));
        assertEquals(List.of(), apply(show(":", "", 0)).candidates());
    }

    @Test
    void closingTheLineDropsTheMenu() {
        apply(show(":", "e ", 2), new CmdlineEvent.MenuShow(List.of("a"), 0, -1), new CmdlineEvent.Hide(1, true));
        assertEquals(List.of(), apply(show(":", "", 0)).candidates());
    }

    @Test
    void aSearchLineIsASearch() {
        assertTrue(apply(show("/", "foo", 3)).isSearch());
        assertTrue(apply(show("?", "foo", 3)).isSearch());
        assertFalse(apply(show(":", "foo", 3)).isSearch());
    }

    /** Events for a line that is not open, and the viewport, change nothing. */
    @Test
    void strayEventsChangeNothing() {
        assertFalse(state.apply(new CmdlineEvent.Pos(1, 1)));
        assertFalse(state.apply(new CmdlineEvent.SpecialChar("\"", true, 1)));
        assertFalse(state.apply(new CmdlineEvent.MenuSelect(1)));
        assertFalse(state.apply(new CmdlineEvent.MenuHide()));
        assertFalse(state.apply(new CmdlineEvent.Viewport(2, 1)));
        assertEquals(View.CLOSED, state.view());
    }
}
