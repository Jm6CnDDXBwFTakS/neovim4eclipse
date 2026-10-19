// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.NeovimExceptionBase;

/** {@link NeovimErrorException#fromResponse}: Neovim's {@code [type, message]} and anything else. */
class NeovimErrorExceptionTest {

    @Test
    void neovimsTypeAndMessage() {
        NeovimErrorException e = NeovimErrorException.fromResponse(List.of(1L, "Invalid buffer id: 99"));
        assertEquals(1, e.type());
        assertEquals("Invalid buffer id: 99", e.getMessage());
    }

    @Test
    void anyOtherShapeBecomesTheMessage() {
        NeovimErrorException e = NeovimErrorException.fromResponse("plain text");
        assertEquals(NeovimErrorException.UNKNOWN_TYPE, e.type());
        assertEquals("plain text", e.getMessage());
    }

    @Test
    void isOneOfThePluginsOwnExceptions() {
        assertInstanceOf(NeovimExceptionBase.class, new NeovimErrorException(0, "x"));
        assertInstanceOf(NeovimExceptionBase.class, new NeovimConnectionClosedException("x"));
    }
}
