// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * The base of every exception the plug-in defines passes its arguments on to
 * {@link RuntimeException} unchanged. The class is abstract, so the test uses a subclass of its
 * own, as the plug-in's exceptions do.
 */
class NeovimExceptionBaseTest {

    /** Exposes each protected constructor. */
    private static final class Probe extends NeovimExceptionBase {
        private static final long serialVersionUID = 1L;

        Probe(String message) {
            super(message);
        }

        Probe(String message, Throwable cause) {
            super(message, cause);
        }

        Probe(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
            super(message, cause, enableSuppression, writableStackTrace);
        }
    }

    @Test
    void isUnchecked() {
        assertTrue(RuntimeException.class.isAssignableFrom(NeovimExceptionBase.class));
    }

    @Test
    void keepsTheMessage() {
        Probe e = new Probe("Neovim is gone");
        assertEquals("Neovim is gone", e.getMessage());
        assertNull(e.getCause());
    }

    @Test
    void keepsTheMessageAndTheCause() {
        IOException cause = new IOException("broken pipe");
        Probe e = new Probe("Neovim is gone", cause);
        assertEquals("Neovim is gone", e.getMessage());
        assertSame(cause, e.getCause());
    }

    @Test
    void aSignalCarriesNoStackTraceAndNoSuppressedExceptions() {
        Probe e = new Probe("quit", null, false, false);
        e.addSuppressed(new IllegalStateException("ignored")); // dropped: suppression is disabled
        assertEquals("quit", e.getMessage());
        assertEquals(0, e.getStackTrace().length);
        assertEquals(0, e.getSuppressed().length);
    }

    @Test
    void anErrorKeepsItsStackTraceAndSuppressedExceptions() {
        Probe e = new Probe("failed", null, true, true);
        e.addSuppressed(new IllegalStateException("also"));
        assertTrue(e.getStackTrace().length > 0);
        assertEquals(1, e.getSuppressed().length);
    }
}
