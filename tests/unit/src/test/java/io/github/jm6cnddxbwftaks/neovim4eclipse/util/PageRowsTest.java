// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** A page's command line, and its Tab candidates, as drawn rows ({@link PageRows}). */
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

    @Test
    void noCandidatesNoRow() {
        assertNull(PageRows.candidateRow(line(":", ":e x", 4), 20, 1004, COLORS));
        assertNull(PageRows.candidateRow(CmdlineState.View.CLOSED, 20, 1004, COLORS));
    }

    /** {@code :e Rea<Tab>}: Neovim's list, the selected one in reverse video, as the code's : box shows it. */
    @Test
    void theCandidatesAreOneRowWithTheSelectedOneMarked() {
        CmdlineState.View view = new CmdlineState.View(true, ":", ":e Readme.md", 12, List.of("Readme.md", "README.txt"), 1);
        GridSnapshot row = PageRows.candidateRow(view, 24, 1004, COLORS);
        assertEquals("Readme.md  README.txt   ", row.row(0));
        assertEquals(1004, row.window());
        assertEquals(0, row.hlAt(0, 0), "not selected");
        assertEquals(PageRows.SELECTED, row.hlAt(0, 11), "README.txt starts at cell 11");
        assertEquals(PageRows.SELECTED, row.hlAt(0, 20));
        assertEquals(0, row.hlAt(0, 21));
        assertTrue(row.attr(PageRows.SELECTED).reverse());
        assertEquals(-1, row.cursorCol(), "no cursor: the line's is on the command row");
    }

    @Test
    void manyCandidatesShowTheSelectedOnesNeighbors() {
        CmdlineState.View view = new CmdlineState.View(true, ":", ":e d", 4, List.of("a", "b", "c", "d", "e", "f"), 3);
        GridSnapshot row = PageRows.candidateRow(view, 11, 1004, COLORS);
        assertTrue(row.row(0).contains("d"), row.row(0));
        assertTrue(row.row(0).startsWith("\u2026"), "cut off at the start: " + row.row(0));
        assertEquals(11, row.width());
    }
}
