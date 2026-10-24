// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Reading command-line events out of {@code redraw} notifications, shaped as the RPC layer decodes them. */
class CmdlineEventTest {

    /** {@code cmdline_show} arguments: content chunks, pos, firstc, prompt, indent, level. */
    private static List<Object> showArgs(String firstc, int level, String... chunks) {
        List<Object> content = new java.util.ArrayList<>();
        for (String chunk : chunks) {
            content.add(List.of(0L, chunk)); // [attr, text]; newer Neovim adds a highlight id
        }
        return List.of(content, 0L, firstc, "", 0L, (long) level);
    }

    @Test
    void showsAndHidesAreReadInOrder() {
        List<?> redraw = List.of(
                List.of("grid_line", List.of(1L, 0L, 0L, List.of())),
                List.of("cmdline_show", showArgs(":", 1, "%s", "/")),
                List.of("flush", List.of()),
                List.of("cmdline_hide", List.of(1L, false)));
        assertEquals(List.of(new CmdlineEvent(true, ":", "%s/", 1), new CmdlineEvent(false, "", "", 1)),
                CmdlineEvent.fromRedraw(redraw));
    }

    @Test
    void oneBatchCanHoldSeveralEvents() {
        List<?> redraw = List.of(List.of("cmdline_show", showArgs("/", 1, "a"), showArgs("/", 1, "ab")));
        assertEquals(List.of(new CmdlineEvent(true, "/", "a", 1), new CmdlineEvent(true, "/", "ab", 1)),
                CmdlineEvent.fromRedraw(redraw));
    }

    @Test
    void chunksWithAHighlightIdAndAnOldHideWithoutAbortAreRead() {
        List<Object> args = List.of(List.of(List.of(0L, "w", 7L)), 1L, ":", "", 0L, 2L, 0L);
        List<?> redraw = List.of(List.of("cmdline_show", args), List.of("cmdline_hide", List.of(2L)));
        assertEquals(List.of(new CmdlineEvent(true, ":", "w", 2), new CmdlineEvent(false, "", "", 2)),
                CmdlineEvent.fromRedraw(redraw));
    }

    @Test
    void malformedEntriesAreSkipped() {
        List<?> redraw = List.of("not a list", List.of(), List.of("cmdline_show", List.of("too short")),
                List.of("cmdline_show", "not a list either"));
        assertEquals(List.of(), CmdlineEvent.fromRedraw(redraw));
    }
}
