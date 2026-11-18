// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Where a page editor goes: its own place if open, beside another, else below the code. */
class PagePlacementTest {

    @Test
    void theKindsEditorStaysWhereItIsWhateverElseIsOpen() {
        assertEquals(PagePlacement.WHERE_IT_IS, PagePlacement.of(true, true, true));
        assertEquals(PagePlacement.WHERE_IT_IS, PagePlacement.of(true, false, false));
    }

    @Test
    void anotherKindsEditorTakesTheNewOneAsATab() {
        assertEquals(PagePlacement.BESIDE_ANOTHER, PagePlacement.of(false, true, true));
        assertEquals(PagePlacement.BESIDE_ANOTHER, PagePlacement.of(false, true, false));
    }

    @Test
    void theFirstSplitsTheCodeBelowOrGoesWhereEclipseOpensEditors() {
        assertEquals(PagePlacement.BELOW, PagePlacement.of(false, false, true));
        assertEquals(PagePlacement.WHERE_ECLIPSE_OPENS, PagePlacement.of(false, false, false));
    }
}
