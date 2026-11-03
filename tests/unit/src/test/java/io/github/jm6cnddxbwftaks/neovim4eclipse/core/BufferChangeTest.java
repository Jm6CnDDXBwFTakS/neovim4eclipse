// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

/** {@link BufferChange#toString}: logged at WARNING when a change cannot be applied. */
class BufferChangeTest {

    @Test
    void theTextIsNeverInItsString() {
        BufferChange range = new BufferChange(3, 5, List.of("password=hunter2", "x"));
        assertEquals("BufferChange[lines 3-5 replaced by 2 lines]", range.toString());
        BufferChange whole = new BufferChange(0, -1, List.of("password=hunter2"));
        assertEquals("BufferChange[whole buffer, 1 line]", whole.toString());
        assertFalse(("" + range + whole).contains("hunter2"));
    }
}
