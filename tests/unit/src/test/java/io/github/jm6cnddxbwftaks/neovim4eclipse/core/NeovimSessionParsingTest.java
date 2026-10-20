// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Pure helpers of {@link NeovimSession} and {@link NeovimConnection}; no Neovim process involved. */
class NeovimSessionParsingTest {

    // ---- managesSystemClipboard ----

    @Test
    void neovimManagesTheClipboardWhenUnnamedRegisterIsLinkedToIt() {
        assertTrue(NeovimSession.managesSystemClipboard("unnamedplus"));
        assertTrue(NeovimSession.managesSystemClipboard("unnamed"));
        assertTrue(NeovimSession.managesSystemClipboard("unnamed,unnamedplus"));
        assertTrue(NeovimSession.managesSystemClipboard("autoselect,unnamedplus"));
    }

    @Test
    void neovimDoesNotManageTheClipboardByDefault() {
        assertFalse(NeovimSession.managesSystemClipboard(""));
        assertFalse(NeovimSession.managesSystemClipboard(null));
        assertFalse(NeovimSession.managesSystemClipboard("autoselect"));
    }

    // ---- clientVersion ----

    @Test
    void clientVersionTakesMajorMinorMicroAndQualifier() {
        // The map nvim_set_client_info takes as its version.
        assertEquals(Map.of("major", 1, "minor", 2, "patch", 3, "prerelease", "202610170535"),
                NeovimConnection.clientVersion("1.2.3.202610170535"));
    }

    @Test
    void clientVersionDefaultsMissingOrBadPartsToZero() {
        // No qualifier: no prerelease key at all (Neovim would show a nil one).
        assertEquals(Map.of("major", 4, "minor", 0, "patch", 0), NeovimConnection.clientVersion("4.x"));
        assertEquals(Map.of("major", 0, "minor", 0, "patch", 0), NeovimConnection.clientVersion(null));
    }

}
