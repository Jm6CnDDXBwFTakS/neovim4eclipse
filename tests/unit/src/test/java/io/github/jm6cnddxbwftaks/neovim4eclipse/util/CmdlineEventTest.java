// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Reading command-line, completion-menu and view events out of {@code redraw} notifications, shaped
 * as Neovim 0.12.6 sends them (experiments X3 and X8) and as the RPC layer decodes them.
 */
class CmdlineEventTest {

    /** {@code cmdline_show} arguments: content chunks, pos, firstc, prompt, indent, level, hl_id. */
    private static List<Object> showArgs(String firstc, int pos, String prompt, int level, String... chunks) {
        List<Object> content = new java.util.ArrayList<>();
        for (String chunk : chunks) {
            content.add(List.of(0L, chunk, 0L));
        }
        return List.of(content, (long) pos, firstc, prompt, 0L, (long) level, 0L);
    }

    @Test
    void aLineIsShownMovedAndHiddenInOrder() {
        List<?> redraw = List.of(
                List.of("grid_line", List.of(1L, 0L, 0L, List.of())),
                List.of("cmdline_show", showArgs(":", 3, "", 1, "%s", "/")),
                List.of("cmdline_pos", List.of(1L, 1L)),
                List.of("flush"),
                List.of("cmdline_hide", List.of(1L, true)));
        assertEquals(List.of(new CmdlineEvent.Show(":", "%s/", 3, "", 0, 1), new CmdlineEvent.Pos(1, 1),
                new CmdlineEvent.Hide(1, true)), CmdlineEvent.fromRedraw(redraw));
    }

    /** {@code input("Name: ", "def")}: no type character, a prompt and its default text. */
    @Test
    void aPromptHasItsTextAndNoTypeCharacter() {
        List<?> redraw = List.of(List.of("cmdline_show", showArgs("", 3, "Name: ", 1, "def")));
        assertEquals(List.of(new CmdlineEvent.Show("", "def", 3, "Name: ", 0, 1)), CmdlineEvent.fromRedraw(redraw));
    }

    /** {@code <C-r>} shows {@code "} at the cursor while Neovim waits for the register name. */
    @Test
    void aSpecialCharacter() {
        List<?> redraw = List.of(List.of("cmdline_special_char", List.of("\"", true, 1L)));
        assertEquals(List.of(new CmdlineEvent.SpecialChar("\"", true, 1)), CmdlineEvent.fromRedraw(redraw));
    }

    /** {@code :e <Tab>}: the candidates are the items' words; grid -1 is the command line. */
    @Test
    void theCompletionMenu() {
        List<Object> items = List.of(List.of("Find.java", "", "", ""), List.of("cls/", "", "", ""));
        List<?> redraw = List.of(
                List.of("popupmenu_show", List.of(items, 0L, 0L, 2L, -1L)),
                List.of("popupmenu_select", List.of(1L)),
                List.of("popupmenu_hide"));
        List<CmdlineEvent> events = CmdlineEvent.fromRedraw(redraw);
        assertEquals(List.of(new CmdlineEvent.MenuShow(List.of("Find.java", "cls/"), 0, -1), new CmdlineEvent.MenuSelect(1),
                new CmdlineEvent.MenuHide()), events);
        assertTrue(((CmdlineEvent.MenuShow) events.get(0)).forCmdline());
    }

    /** While {@code /foo} is typed: the window's cursor is the match (line 2, column 1, 0-based). */
    @Test
    void theViewport() {
        List<?> redraw = List.of(List.of("win_viewport", List.of(2L, "win", 0L, 6L, 2L, 1L, 5L, 0L)));
        assertEquals(List.of(new CmdlineEvent.Viewport(2, 1)), CmdlineEvent.fromRedraw(redraw));
    }

    @Test
    void oneBatchCanHoldSeveralEvents() {
        List<?> redraw = List.of(List.of("cmdline_show", showArgs("/", 1, "", 1, "a"), showArgs("/", 2, "", 1, "ab")));
        assertEquals(List.of(new CmdlineEvent.Show("/", "a", 1, "", 0, 1), new CmdlineEvent.Show("/", "ab", 2, "", 0, 1)),
                CmdlineEvent.fromRedraw(redraw));
    }

    @Test
    void malformedEntriesAreSkipped() {
        List<?> redraw = Arrays.asList("not a list", List.of(), List.of(42), List.of("cmdline_show", List.of("too short")),
                List.of("cmdline_show", "not a list either"), List.of("cmdline_pos", List.of(1L)),
                List.of("popupmenu_show", List.of("x")), List.of("win_viewport", List.of(2L)));
        assertEquals(List.of(), CmdlineEvent.fromRedraw(redraw));
    }
}
