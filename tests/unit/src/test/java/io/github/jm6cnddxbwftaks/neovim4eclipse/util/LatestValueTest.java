// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Values that arrive faster than they are taken replace each other (mouse drags). */
class LatestValueTest {

    private final LatestValue<String> latest = new LatestValue<>();

    @Test
    void theFirstOfferAsksForATakeAndLaterOnesReplaceIt() {
        assertTrue(latest.offer("a"), "nothing was waiting: schedule a take");
        assertFalse(latest.offer("b"), "a take is scheduled already");
        assertEquals("b", latest.take());
        assertNull(latest.take());
        assertTrue(latest.offer("c"), "taken: the next value needs a take of its own");
    }

    @Test
    void nullIsNoValue() {
        assertThrows(IllegalArgumentException.class, () -> latest.offer(null));
    }
}
