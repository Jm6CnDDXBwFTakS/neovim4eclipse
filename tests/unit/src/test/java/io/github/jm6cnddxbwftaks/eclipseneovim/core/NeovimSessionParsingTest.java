// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        var v = NeovimConnection.clientVersion("1.2.3.202610170535");
        assertEquals(1, v.getMajor());
        assertEquals(2, v.getMinor());
        assertEquals(3, v.getPatch());
        assertEquals("202610170535", v.getPreRelease());
    }

    @Test
    void clientVersionDefaultsMissingOrBadPartsToZero() {
        var v = NeovimConnection.clientVersion("4.x");
        assertEquals(4, v.getMajor());
        assertEquals(0, v.getMinor());
        assertEquals(0, v.getPatch());
        assertEquals(null, v.getPreRelease());
        assertEquals(0, NeovimConnection.clientVersion(null).getMajor());
    }

}
