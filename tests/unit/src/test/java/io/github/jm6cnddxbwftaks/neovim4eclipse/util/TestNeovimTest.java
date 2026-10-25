// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** How the integration tests read {@code -Dnvim.path} (see {@link TestNeovim}). */
class TestNeovimTest {

    @Test
    void unsetMeansBlankSoTheTestsSearchLikeThePlugIn() {
        assertEquals("", TestNeovim.configured(null));
        assertEquals("", TestNeovim.configured(""));
        assertEquals("", TestNeovim.configured("${nvim.path}"), "Maven's text for an undefined property");
        assertEquals("", TestNeovim.configured("null"));
    }

    @Test
    void aGivenValueIsUsedAsTheTestsPreference() {
        assertEquals("/opt/nvim-0.13.0", TestNeovim.configured(" /opt/nvim-0.13.0 "));
        assertEquals("nvim", TestNeovim.configured("nvim"));
    }
}
