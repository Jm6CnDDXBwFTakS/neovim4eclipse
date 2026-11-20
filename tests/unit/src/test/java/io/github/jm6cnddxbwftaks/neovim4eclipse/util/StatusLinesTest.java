// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link StatusLines}: Neovim's answer for a window's status line ({@code LuaScripts.STATUS_LINE},
 * whose Lua side is {@code status_line_spec.lua}) read into a one-row {@link GridSnapshot}.
 */
class StatusLinesTest {

    /** {@code {fg, bg, reverse, bold, italic, underline, strikethrough}}, as the script sends one. */
    private static final List<Object> WHITE_ON_GRAY_BOLD = List.of(0xFFFFFF, 0x333333, false, true, false, false, false);
    private static final List<Object> DEFAULT_COLORS = List.of(-1, -1, true, false, true, true, true);

    @Test
    void aLineIsOneRowOfItsCellsWithTheirHighlights() {
        GridSnapshot line = StatusLines.of(1000, List.of(true, List.of("a", " ", "b"), List.of(1, 2, 2),
                List.of(WHITE_ON_GRAY_BOLD, DEFAULT_COLORS)));
        assertEquals(1000, line.window());
        assertEquals(3, line.width());
        assertEquals(1, line.height());
        assertEquals("a b", line.row(0));
        assertEquals(1, line.hlAt(0, 0));
        assertEquals(2, line.hlAt(0, 2));
        assertEquals(new GridEvent.HlAttr(1, 0xFFFFFF, 0x333333, false, true, false, false, false), line.attr(1));
        assertEquals(new GridEvent.HlAttr(2, GridEvent.NO_COLOR, GridEvent.NO_COLOR, true, false, true, true, true),
                line.attr(2), "-1 is the default color");
        assertEquals(-1, line.cursorRow(), "no cursor on a status line");
    }

    /** A wide character fills two cells; the second is "", as on Neovim's own grids. */
    @Test
    void aWideCharactersSecondCellIsEmpty() {
        GridSnapshot line = StatusLines.of(1, List.of(true, List.of("a", "あ", "", "b"), List.of(1, 1, 1, 1),
                List.of(WHITE_ON_GRAY_BOLD)));
        assertEquals(4, line.width());
        assertEquals("あ", line.textAt(0, 1));
        assertEquals("", line.textAt(0, 2));
        assertEquals("aあb", line.row(0));
    }

    /** A cell that is not a string draws as a blank, a highlight that is not a number as the default. */
    @Test
    void oddCellsDrawAsDefaultBlanks() {
        GridSnapshot line = StatusLines.of(1, List.of(true, Arrays.asList("a", null), Arrays.asList(1, "x"),
                List.of(WHITE_ON_GRAY_BOLD, List.of(1, 2))));
        assertEquals("a ", line.row(0));
        assertEquals(0, line.hlAt(0, 1));
        assertEquals(GridEvent.NO_COLOR, line.attr(2).foreground(), "a highlight of too few fields is left out");
    }

    @Test
    void noLineAndMalformedAnswersGiveNoRows() {
        List<Object> cells = List.of("a");
        List<Object> hl = List.of(1);
        List<Object> attrs = List.of(WHITE_ON_GRAY_BOLD);
        List<Object> notShown = List.of(false);
        List<Object> withNull = new ArrayList<>(List.of(true, cells, hl, attrs));
        withNull.set(1, null);
        for (Object answer : Arrays.asList(null, "junk", List.of(), notShown, List.of(true, cells, hl),
                List.of(true, List.of(), List.of(), attrs), // no cells
                List.of(true, cells, List.of(1, 1), attrs), // cells and highlights disagree
                List.of("true", cells, hl, attrs), withNull)) {
            GridSnapshot line = StatusLines.of(7, answer);
            assertEquals(0, line.height(), "for " + answer);
            assertEquals(7, line.window(), "still the window's, so its row is hidden: " + answer);
        }
        assertEquals(StatusLines.none(7), StatusLines.of(7, notShown));
    }

    /** {@code eclipse_status_lines}' argument: {@code {{win, line}, ...}}; odd entries are left out. */
    @Test
    void allReadsEveryWindowsLine() {
        List<Object> a = List.of(true, List.of("a"), List.of(1), List.of(WHITE_ON_GRAY_BOLD));
        List<GridSnapshot> lines = StatusLines.all(List.of(List.of(1000, a), List.of(1001, List.of(false)),
                "junk", List.of("1002", a), List.of(1003)));
        assertEquals(2, lines.size());
        assertEquals(1000, lines.get(0).window());
        assertEquals("a", lines.get(0).row(0));
        assertEquals(1001, lines.get(1).window());
        assertEquals(0, lines.get(1).height());
        assertTrue(StatusLines.all(null).isEmpty());
        assertTrue(StatusLines.all("junk").isEmpty());
        assertFalse(StatusLines.all(List.of(List.of(5, a))).isEmpty());
    }
}
