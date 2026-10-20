// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link Handle}: what the factories build, equality, and the {@code int} view of the number. */
class HandleTest {

    @Test
    void factoriesSetTheKind() {
        assertEquals(new Handle(Handle.Kind.BUFFER, 3), Handle.buffer(3));
        assertEquals(new Handle(Handle.Kind.WINDOW, 1000), Handle.window(1000));
    }

    @Test
    void aBufferAndAWindowWithTheSameNumberDiffer() {
        // Buffer 1 and window 1 are different things; mixing them up must not compare equal.
        assertNotEquals(Handle.buffer(1), Handle.window(1));
    }

    @Test
    void intIdIsTheNumber() {
        assertEquals(42, Handle.buffer(42).intId());
    }

    @Test
    void intIdRefusesANumberThatDoesNotFit() {
        // Never seen from Neovim, but a silent wrap-around would address the wrong buffer.
        assertThrows(ArithmeticException.class, () -> Handle.buffer(1L << 40).intId());
    }

    @Test
    void theKindIsRequired() {
        assertThrows(NullPointerException.class, () -> new Handle(null, 1));
    }
}
