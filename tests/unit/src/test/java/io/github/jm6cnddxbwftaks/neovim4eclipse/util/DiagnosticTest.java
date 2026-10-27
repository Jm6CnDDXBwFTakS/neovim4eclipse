// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Diagnostic.Severity;

class DiagnosticTest {

    @Test
    void severitiesAreNeovimsNumbers() {
        // vim.diagnostic.severity: ERROR 1, WARN 2, INFO 3 (HINT 4 has no Eclipse counterpart).
        assertEquals(1, Severity.ERROR.neovimNumber());
        assertEquals(2, Severity.WARN.neovimNumber());
        assertEquals(3, Severity.INFO.neovimNumber());
    }

    @Test
    void luaArgsAreTheBufferThenSixStringsPerDiagnostic() {
        List<String> args = Diagnostic.toLuaArgs(3, List.of(
                new Diagnostic(0, 4, 0, 7, Severity.ERROR, "x cannot be resolved"),
                new Diagnostic(2, 0, 3, 1, Severity.WARN, "two\nlines \"quoted\"")));
        assertEquals(List.of("3",
                "0", "4", "0", "7", "1", "x cannot be resolved",
                "2", "0", "3", "1", "2", "two\nlines \"quoted\""), args);
        assertEquals(1 + 2 * Diagnostic.LUA_ARGS_PER_DIAGNOSTIC, args.size());
    }

    @Test
    void noDiagnosticsIsJustTheBuffer() {
        // An empty list clears the buffer's diagnostics in Neovim.
        assertEquals(List.of("5"), Diagnostic.toLuaArgs(5, List.of()));
    }

    @Test
    void aNullMessageBecomesEmpty() {
        assertEquals("", new Diagnostic(0, 0, 0, 0, Severity.INFO, null).message());
    }
}
