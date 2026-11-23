// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridEvent.Cell;

/**
 * Reading grid events out of {@code redraw} notifications, shaped as Neovim 0.12.6 sends them with
 * {@code ext_multigrid} and {@code rgb}, and as the RPC layer decodes them (numbers as {@code Long}).
 */
class GridEventTest {

    /** Window handles arrive as a type of {@code core/rpc}; a test stands them in with a string. */
    private static int windowId(Object handle) {
        return handle instanceof String s && s.startsWith("win") ? Integer.parseInt(s.substring(3)) : -1;
    }

    private static List<GridEvent> read(List<?> redraw) {
        return GridEvent.fromRedraw(redraw, GridEventTest::windowId);
    }

    @Test
    void aLinesCellsAreSpelledOut() {
        List<?> redraw = List.of(List.of("grid_line",
                List.of(4L, 0L, 0L, List.of(List.of(":", 0L), List.of("w"), List.of(" ", 7L, 2L)), false)));
        assertEquals(List.of(new GridEvent.Line(4, 0, 0,
                List.of(new Cell(":", 0), new Cell("w", 0), new Cell(" ", 7), new Cell(" ", 7)))), read(redraw),
                "a left-out hl_id is the previous cell's; repeat gives that many cells");
    }

    /** A window's viewport names its window: one redraw brings the editor's and each of ui2's windows'. */
    @Test
    void aViewportNamesItsWindowAndCursor() {
        List<?> redraw = List.of(List.of("win_viewport",
                List.of(2L, "win1000", 0L, 23L, 41L, 4L, 60L, 0L),
                List.of(5L, "win1002", 0L, 1L, 0L, 3L, 1L, 0L)));
        assertEquals(List.of(new GridEvent.Viewport(2, 1000, 41, 4), new GridEvent.Viewport(5, 1002, 0, 3)), read(redraw));
    }

    @Test
    void aDoubleWidthCharacterIsItsTextAndAnEmptyCell() {
        List<?> redraw = List.of(List.of("grid_line", List.of(4L, 1L, 2L, List.of(List.of("日", 3L), List.of("")), false)));
        assertEquals(List.of(new GridEvent.Line(4, 1, 2, List.of(new Cell("日", 3), new Cell("", 3)))), read(redraw));
    }

    @Test
    void eachEventOfABatchInOrderAndTheFlush() {
        List<?> redraw = List.of(
                List.of("grid_resize", List.of(4L, 120L, 7L), List.of(2L, 120L, 30L)),
                List.of("grid_clear", List.of(4L)),
                List.of("grid_cursor_goto", List.of(4L, 6L, 0L)),
                List.of("grid_scroll", List.of(4L, 0L, 7L, 0L, 120L, 1L, 0L)),
                List.of("win_pos", List.of(4L, "win1004", 31L, 0L, 120L, 7L)),
                List.of("win_float_pos", List.of(5L, "win1005", "NW", 2L, 1.0, 2.0, true, 50L)),
                List.of("win_close", List.of(5L)),
                List.of("grid_destroy", List.of(5L)),
                List.of("flush"));
        assertEquals(List.of(new GridEvent.Resize(4, 120, 7), new GridEvent.Resize(2, 120, 30), new GridEvent.Clear(4),
                new GridEvent.CursorGoto(4, 6, 0), new GridEvent.Scroll(4, 0, 7, 0, 120, 1),
                new GridEvent.WinPos(4, 1004, false, 31, 0, 120, 7, 0),
                new GridEvent.WinPos(5, 1005, true, 1, 2, 0, 0, 50), new GridEvent.WinGone(5),
                new GridEvent.Destroy(5), new GridEvent.Flush()), read(redraw));
    }

    /**
     * A floating window's place is the screen cell Neovim worked out (the last two numbers), not
     * its anchor: which-key's panel as 0.12.6 sends it, and a bordered float hung by its bottom
     * left corner one row above the cursor.
     */
    @Test
    void aFloatingWindowsPlaceFocusAndStackingAreRead() {
        List<?> redraw = List.of(List.of("win_float_pos",
                List.of(5L, "win1002", "NW", 1L, 18.0, 0.0, false, 1000L, 1L, 18L, 0L),
                List.of(6L, "win1003", "SW", 2L, 4.0, 4.0, true, 50L, 1L, 0L, 4L)));
        assertEquals(List.of(new GridEvent.WinPos(5, 1002, true, 18, 0, 0, 0, 1000, false),
                new GridEvent.WinPos(6, 1003, true, 0, 4, 0, 0, 50, true)), read(redraw),
                "the panel lets no mouse in, the other does");
    }

    @Test
    void highlightsAndDefaultColorsInRgb() {
        List<?> redraw = List.of(
                List.of("default_colors_set", List.of(0xE0E2EAL, 0x14161BL, 0xFF0000L, 15L, 0L)),
                List.of("hl_attr_define",
                        List.of(7L, Map.of("foreground", 0x8CF8F7L, "bold", true, "undercurl", true), Map.of(), List.of()),
                        List.of(8L, Map.of("reverse", true), Map.of(), List.of()),
                        List.of(9L, Map.of("background", 0L, "blend", 60L), Map.of(), List.of())),
                List.of("default_colors_set", List.of(-1L, -1L, -1L, -1L, -1L)));
        assertEquals(List.of(new GridEvent.DefaultColors(0xE0E2EA, 0x14161B),
                new GridEvent.HlAttr(7, 0x8CF8F7, GridEvent.NO_COLOR, false, true, false, true, false),
                new GridEvent.HlAttr(8, GridEvent.NO_COLOR, GridEvent.NO_COLOR, true, false, false, false, false),
                new GridEvent.HlAttr(9, GridEvent.NO_COLOR, 0x000000, false, false, false, false, false, 60),
                new GridEvent.DefaultColors(GridEvent.NO_COLOR, GridEvent.NO_COLOR)), read(redraw),
                "an undercurl underlines; -1 is no color; blend is how much shows through ('winblend')");
    }

    @Test
    void theLinesOfAGridNotWantedAreLeftOutUnread() {
        List<?> redraw = List.of(List.of("grid_line", List.of(2L, 0L, 0L, List.of(List.of("e", 0L)), false),
                List.of(4L, 0L, 0L, List.of(List.of(":", 0L)), false)), List.of("grid_resize", List.of(2L, 6L, 3L)));
        assertEquals(List.of(new GridEvent.Line(4, 0, 0, List.of(new Cell(":", 0))), new GridEvent.Resize(2, 6, 3)),
                GridEvent.fromRedraw(redraw, GridEventTest::windowId, grid -> grid != 2), "its size still comes");
    }

    @Test
    void otherEventsAndShortOnesAreLeftOut() {
        List<?> redraw = List.of(
                List.of("cmdline_show", List.of(List.of(), 0L, ":", "", 0L, 1L, 0L)),
                List.of("msg_show", List.of("echo", List.of(), false, true, false, 1L, "")),
                List.of("grid_line", List.of(4L, 0L)),
                List.of("grid_resize", List.of(4L)),
                "not a list",
                List.of());
        assertEquals(List.of(), read(redraw));
    }
}
