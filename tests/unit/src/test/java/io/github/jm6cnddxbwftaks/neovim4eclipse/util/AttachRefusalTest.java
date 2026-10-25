// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AttachRefusalTest {

    @Test
    void aTextEditorCanBeAttached() {
        assertNull(AttachRefusal.reason("Notes.txt", true));
    }

    @Test
    void anImageIsRefusedByName() {
        String reason = AttachRefusal.reason("logo.png", false);
        assertTrue(reason.startsWith("Neovim works only in text editors, and \"logo.png\" is not one"), reason);
        assertTrue(reason.contains("source tab"), "multi-page editors: " + reason);
    }

    @Test
    void withoutAnEditorTheUserIsToldToOpenOne() {
        assertEquals("No editor is open. Open a text file first.", AttachRefusal.reason(null, false));
    }
}
