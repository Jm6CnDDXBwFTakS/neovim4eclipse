// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.MessageEvent.History;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewText.LineHighlight;

/** The text the Neovim view shows for {@code :messages} and {@code :checkhealth}. */
class ViewTextTest {

    @Test
    void messagesKeepTheirOrderAndMarkErrorsAndWarnings() {
        ViewText text = ViewText.messages(new History(List.of(
                new History.Entry("", "3 lines yanked"),
                new History.Entry("emsg", "E486: Pattern not found: x"),
                new History.Entry("wmsg", "W10: Warning: Changing a readonly file"),
                new History.Entry("lua_error", "E5108: Lua: boom\nstack traceback:"))));
        assertEquals(ViewText.MESSAGES, text.title());
        assertEquals("", text.filetype(), "no filetype: the colors are the highlights");
        assertEquals(List.of("3 lines yanked", "E486: Pattern not found: x", "W10: Warning: Changing a readonly file",
                "E5108: Lua: boom", "stack traceback:"), text.lines());
        assertEquals(List.of(new LineHighlight(1, "ErrorMsg"), new LineHighlight(2, "WarningMsg"),
                new LineHighlight(3, "ErrorMsg"), new LineHighlight(4, "ErrorMsg")), text.highlights(),
                "every line of a message of several lines takes its color");
    }

    @Test
    void anEmptyHistoryIsNoText() {
        ViewText text = ViewText.messages(new History(List.of()));
        assertEquals(List.of(), text.lines());
        assertEquals(List.of(), text.highlights());
    }

    @Test
    void aCarriageReturnInAMessageIsNoLineOfItsOwn() {
        assertEquals(List.of("a", "b"), ViewText.messages(new History(List.of(new History.Entry("", "a\r\nb")))).lines());
    }

    @Test
    void aCheckhealthReportIsColoredByItsFiletype() {
        ViewText text = ViewText.checkhealth(List.of("vim.health: OK", "- OK fine"));
        assertEquals(ViewText.CHECKHEALTH, text.title());
        assertEquals(ViewText.CHECKHEALTH, text.filetype());
        assertEquals(List.of("vim.health: OK", "- OK fine"), text.lines());
        assertEquals(List.of(), text.highlights());
    }

    @Test
    void theErrorKindsAreTheMessageLines() {
        for (String kind : MessageRoute.ERROR_KINDS) {
            assertEquals("ErrorMsg", ViewText.groupOf(kind), kind);
        }
        assertEquals("", ViewText.groupOf("echo"));
        assertEquals("", ViewText.groupOf(""));
    }
}
