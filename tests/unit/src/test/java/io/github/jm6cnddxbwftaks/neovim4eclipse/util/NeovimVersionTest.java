// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The version check that refuses a too old Neovim at start (see {@link NeovimVersion}). */
class NeovimVersionTest {

    @Test
    void readsTheThreeNumbersLuaReturns() {
        // msgpack integers arrive as Long or Integer, depending on their size.
        assertEquals(new NeovimVersion(0, 12, 6), NeovimVersion.fromLua(List.of(0L, 12L, 6L)));
        assertEquals(new NeovimVersion(0, 13, 0), NeovimVersion.fromLua(List.of(0, 13, 0)));
    }

    @Test
    void rejectsAnAnswerThatIsNotThreeNumbers() {
        assertThrows(IllegalArgumentException.class, () -> NeovimVersion.fromLua(null));
        assertThrows(IllegalArgumentException.class, () -> NeovimVersion.fromLua(List.of(0L, 12L)));
        assertThrows(IllegalArgumentException.class, () -> NeovimVersion.fromLua(List.of(0L, "12", 6L)));
        assertThrows(IllegalArgumentException.class, () -> NeovimVersion.fromLua("0.12.6"));
    }

    @Test
    void theMinimumAndNewerAreSupportedOlderIsNot() {
        NeovimVersion min = NeovimVersion.MINIMUM;
        assertTrue(min.isSupported());
        assertTrue(new NeovimVersion(min.major(), min.minor(), min.patch() + 1).isSupported());
        assertTrue(new NeovimVersion(min.major(), min.minor() + 1, 0).isSupported());
        assertTrue(new NeovimVersion(min.major() + 1, 0, 0).isSupported());
        assertFalse(new NeovimVersion(0, 9, 5).isSupported());
        if (min.patch() > 0) {
            assertFalse(new NeovimVersion(min.major(), min.minor(), min.patch() - 1).isSupported());
        }
    }

    @Test
    void comparesNumbersNotText() {
        // As text, "0.9.0" sorts after "0.12.6"; as numbers it is older.
        assertTrue(new NeovimVersion(0, 9, 0).compareTo(new NeovimVersion(0, 12, 6)) < 0);
        assertTrue(new NeovimVersion(0, 12, 10).compareTo(new NeovimVersion(0, 12, 9)) > 0);
        assertEquals(0, new NeovimVersion(0, 12, 6).compareTo(new NeovimVersion(0, 12, 6)));
    }

    @Test
    void theRefusalNamesBothVersions() {
        assertEquals("Neovim 0.9.5 is too old: Neovim for Eclipse needs " + NeovimVersion.MINIMUM + " or newer.",
                new NeovimVersion(0, 9, 5).tooOldMessage());
        assertEquals("0.12.6", new NeovimVersion(0, 12, 6).toString());
    }

    @Test
    void readsTheFirstLineOfNvimDashDashVersion() {
        assertEquals(new NeovimVersion(0, 12, 6), NeovimVersion.fromVersionOutput("NVIM v0.12.6\nBuild type: Release\n"));
        assertEquals(new NeovimVersion(0, 13, 0),
                NeovimVersion.fromVersionOutput("NVIM v0.13.0-dev-1234+gabcdef0\n"), "a nightly counts as its numbers");
        assertEquals(null, NeovimVersion.fromVersionOutput("VIM - Vi IMproved 9.1\n"));
        assertEquals(null, NeovimVersion.fromVersionOutput(null));
    }
}
