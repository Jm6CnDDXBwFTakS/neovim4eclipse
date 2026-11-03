// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LabelTextTest {

    @Test
    void everyAmpersandIsDoubledAndNothingElseChanges() {
        assertEquals("\"R&&D.txt\" 3L, 12B written", LabelText.literal("\"R&D.txt\" 3L, 12B written"));
        assertEquals("a&&&&b <a>x</a>", LabelText.literal("a&&b <a>x</a>"));
        assertEquals("", LabelText.literal(null));
    }
}
