// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** When the Vim key contexts are on ({@link KeyContextRule}). */
class KeyContextRuleTest {

    @Test
    void normalModeWithFocus() {
        assertTrue(KeyContextRule.wanted("n", false, true, true, false));
        assertFalse(KeyContextRule.wanted("n", false, false, true, false), "no focus");
        assertFalse(KeyContextRule.wanted("n", false, true, false, false), "Eclipse mode");
    }

    @Test
    void anInsertEclipseTypesLeavesTheKeysToEclipse() {
        assertFalse(KeyContextRule.wanted("i", false, true, true, false));
        assertFalse(KeyContextRule.wanted("R", false, true, true, false));
    }

    /** After Ctrl-V jj I, or while recording: Ctrl+O must be Neovim's even in the Java editor. */
    @Test
    void anInsertNeovimDrivesKeepsTheContexts() {
        assertTrue(KeyContextRule.wanted("i", true, true, true, false));
        assertFalse(KeyContextRule.wanted("i", true, false, true, false), "no focus");
    }

    @Test
    void theCommandLineBoxKeepsThemInAnyMode() {
        assertTrue(KeyContextRule.wanted("c", false, false, true, true));
        assertTrue(KeyContextRule.wanted("i", false, false, true, true));
        assertFalse(KeyContextRule.wanted("c", false, false, false, true), "Eclipse mode");
    }
}
