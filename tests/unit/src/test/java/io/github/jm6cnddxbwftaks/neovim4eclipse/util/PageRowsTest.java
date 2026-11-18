// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** A page's command line as one drawn row ({@link PageRows}). */
class PageRowsTest {

    private static final GridEvent.DefaultColors COLORS = new GridEvent.DefaultColors(GridEvent.NO_COLOR, GridEvent.NO_COLOR);

    /** An open line: {@code text} is the whole line as Neovim draws it, {@code caret} a char index into it. */
    private static CmdlineState.View line(String firstc, String text, int caret) {
        return new CmdlineState.View(true, firstc, text, caret, List.of(), -1);
    }

    @Test
    void aClosedLineIsABlankRowWithoutACursor() {
        GridSnapshot row = PageRows.commandRow(CmdlineState.View.CLOSED, 6, 1004, COLORS);
        assertEquals("      ", row.row(0));
        assertEquals(-1, row.cursorCol());
        assertEquals(1004, row.window());
    }

    @Test
    void theLineAndItsCursorAreDrawnAsNeovimSaysThem() {
        GridSnapshot end = PageRows.commandRow(line(":", ":echo", 5), 10, 1004, COLORS);
        assertEquals(":echo     ", end.row(0));
        assertEquals(5, end.cursorCol(), "after the text: the : is the first cell");
        GridSnapshot middle = PageRows.commandRow(line("/", "/foo", 2), 10, 1004, COLORS);
        assertEquals("/foo      ", middle.row(0));
        assertEquals(2, middle.cursorCol(), "on the o, after f");
    }

    @Test
    void aLongLineShowsThePartWithTheCursor() {
        GridSnapshot row = PageRows.commandRow(line(":", ":abcdefghij", 11), 5, 1004, COLORS);
        assertEquals("ghij ", row.row(0));
        assertEquals(4, row.cursorCol(), "the cursor stays on the row, at its end");
    }

    @Test
    void aDoubleWidthCharacterTakesTwoCells() {
        GridSnapshot row = PageRows.commandRow(line(":", ":日x", 3), 6, 1004, COLORS);
        assertEquals(List.of(":", "日", "", "x", " ", " "), row.text());
        assertEquals(4, row.cursorCol(), "after x: two cells for 日");
    }
}
