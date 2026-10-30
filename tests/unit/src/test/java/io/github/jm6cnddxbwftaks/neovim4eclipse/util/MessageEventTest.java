// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Reading message events from {@code redraw} notifications, in the shape Neovim 0.12.6 sends them
 * (copied from experiment X1's recording, with the chunks {@code [attr_id, text, hl_id]}).
 */
class MessageEventTest {

    private static List<Object> chunks(String... texts) {
        return Arrays.stream(texts).map(t -> (Object) List.of(0, t, 0)).toList();
    }

    @Test
    void aMessageWithAllItsFields() {
        List<?> redraw = List.of(List.of("msg_show",
                List.of("emsg", chunks("E486: ", "Pattern not found: x"), false, true, false, 7, "typed_cmd")));

        assertEquals(List.of(new MessageEvent.Show("emsg", "E486: Pattern not found: x", false, false, "7", "typed_cmd", true)),
                MessageEvent.fromRedraw(redraw));
    }

    @Test
    void replaceLastAppendAndAStringId() {
        List<?> redraw = List.of(List.of("msg_show",
                List.of("search_count", chunks("/a [1/1]"), true, false, false, 6, ""),
                List.of("progress", chunks("written"), false, true, true, "bufwrite", "typed_cmd")));

        assertEquals(List.of(new MessageEvent.Show("search_count", "/a [1/1]", true, false, "6", "", false),
                new MessageEvent.Show("progress", "written", false, true, "bufwrite", "typed_cmd", true)),
                MessageEvent.fromRedraw(redraw));
    }

    /** {@code :vimgrep}'s progress line is not kept for {@code :messages} (history false); its match is. */
    @Test
    void theHistoryFlagIsRead() {
        List<?> redraw = List.of(List.of("msg_show",
                List.of("", chunks("src/Bar.java"), false, false, false, 1, "typed_cmd"),
                List.of("quickfix", chunks("(1 of 2): class Bar {}"), false, true, false, 2, "typed_cmd")));

        assertEquals(List.of(new MessageEvent.Show("", "src/Bar.java", false, false, "1", "typed_cmd", false),
                new MessageEvent.Show("quickfix", "(1 of 2): class Bar {}", false, false, "2", "typed_cmd", true)),
                MessageEvent.fromRedraw(redraw));
    }

    /** Only the events the plug-in reads, in order; grid and command-line events are skipped. */
    @Test
    void otherEventsAreSkippedAndFlushIsKept() {
        List<?> redraw = List.of(
                List.of("grid_line", List.of(1, 0, 0, List.of(), false)),
                List.of("msg_showcmd", List.of(chunks("2", "f"))),
                List.of("cmdline_show", List.of(chunks("ls"), 2, ":", "", 0, 1)),
                List.of("flush"));

        assertEquals(List.of(new MessageEvent.Showcmd("2f"), new MessageEvent.Flush()),
                MessageEvent.fromRedraw(redraw));
    }

    /** {@code :messages}: one line per entry; an entry that appends continues the line before. */
    @Test
    void theHistoryIsOneLinePerEntry() {
        List<?> entries = List.of(
                List.of("emsg", chunks("E486: Pattern not found: nope"), false),
                List.of("", chunks("3 substitutions"), false),
                List.of("", chunks(" on 3 lines"), true));
        List<?> redraw = List.of(List.of("msg_history_show", List.of(entries, false)));

        assertEquals(List.of(new MessageEvent.History("E486: Pattern not found: nope\n3 substitutions on 3 lines")),
                MessageEvent.fromRedraw(redraw));
    }

    /** The protocol is Neovim's: a surprise is skipped, never thrown. */
    @Test
    void malformedEntriesAreSkipped() {
        List<?> redraw = Arrays.asList(null, List.of(), List.of(42), List.of("msg_show", "not a list"),
                List.of("msg_show", List.of("emsg")), List.of("msg_showcmd", List.of()),
                List.of("msg_history_show", List.of("x")));

        assertEquals(List.of(), MessageEvent.fromRedraw(redraw));
    }

    @Test
    void missingTrailingFieldsAreEmpty() {
        List<?> redraw = List.of(List.of("msg_show", Arrays.asList("echo", chunks("hi"), null)));

        assertEquals(List.of(new MessageEvent.Show("echo", "hi", false, false, "", "", true)),
                MessageEvent.fromRedraw(redraw));
    }
}
