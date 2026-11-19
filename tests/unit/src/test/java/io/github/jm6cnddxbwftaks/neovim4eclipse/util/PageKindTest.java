// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Which page editor shows what: one per kind, titled by it. */
class PageKindTest {

    @Test
    void eachContentGoesToTheEditorOfItsKind() {
        assertEquals(PageKind.HELP, PageKind.of(new PageBuffer(PageBuffer.HELP, 7, 27)));
        assertEquals(PageKind.MESSAGES, PageKind.of(ViewText.messages(new MessageEvent.History(List.of()))));
        assertEquals(PageKind.CHECKHEALTH, PageKind.of(new PageBuffer(PageBuffer.CHECKHEALTH, 9, 1)));
    }

    @Test
    void theTitlesAreTheViewsOwn() {
        assertEquals("help", PageKind.HELP.title());
        assertEquals("messages", PageKind.MESSAGES.title());
        assertEquals("checkhealth", PageKind.CHECKHEALTH.title());
    }
}
