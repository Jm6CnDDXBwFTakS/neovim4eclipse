// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SearchCommandTest {

    @Test
    void forwardSearch() {
        assertEquals("/foo<CR>", SearchCommand.build("foo", false));
    }

    @Test
    void backwardSearch() {
        assertEquals("?foo<CR>", SearchCommand.build("foo", true));
    }

    @Test
    void backslashIsEscaped() {
        assertEquals("/a\\\\b<CR>", SearchCommand.build("a\\b", false));
    }

    @Test
    void onlyTheActiveDelimiterIsEscaped() {
        assertEquals("/a\\/b?c<CR>", SearchCommand.build("a/b?c", false));
        assertEquals("?a/b\\?c<CR>", SearchCommand.build("a/b?c", true));
    }

    @Test
    void lessThanIsSentAsLiteralSoPatternTextIsNotReadAsAKey() {
        assertEquals("/x<lt>Esc>y<CR>", SearchCommand.build("x<Esc>y", false));
    }
}
