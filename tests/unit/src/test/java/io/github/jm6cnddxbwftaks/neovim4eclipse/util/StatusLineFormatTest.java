// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** {@link StatusLineFormat}: Neovim's texts made fit for one status bar label each. */
class StatusLineFormatTest {

    @Test
    void whitespaceRunsAndLineBreaksBecomeOneSpace() {
        assertEquals("a b c", StatusLineFormat.cleanText("  a   b\n\tc  "));
        assertEquals("line one line two", StatusLineFormat.cleanText("line one\nline two"));
    }

    @Test
    void cleanTextHandlesNullAndBlank() {
        assertEquals("", StatusLineFormat.cleanText(null));
        assertEquals("", StatusLineFormat.cleanText("   \n "));
    }

    /** Neovim fits the statusline to the label and pads its middle ({@code %=}): the padding stays. */
    @Test
    void theStatuslineKeepsItsSpacingOnOneLine() {
        assertEquals("a.txt [+]        1,1   All", StatusLineFormat.oneLine("a.txt [+]        1,1   All  "));
        assertEquals("one two", StatusLineFormat.oneLine("one\ntwo"));
        assertEquals("a b", StatusLineFormat.oneLine("a\tb"));
        assertEquals("", StatusLineFormat.oneLine(null));
    }
}
