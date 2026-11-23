// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** {@code 'mouse'} decides each mode by Neovim's letters ({@code :help 'mouse'}). */
class MouseOptionTest {

    private static final String CTRL_V = String.valueOf(VimModes.CTRL_V);
    private static final String CTRL_S = String.valueOf(VimModes.CTRL_S);

    private static void only(String letter, String without, List<String> modes) {
        for (String mode : modes) {
            assertTrue(MouseOption.allows(letter, mode), mode + " with " + letter);
            assertTrue(MouseOption.allows("a", mode), mode + " with a");
            assertFalse(MouseOption.allows(without, mode), mode + " with " + without);
            assertFalse(MouseOption.allows("", mode), mode + " with 'mouse' empty");
        }
    }

    @Test
    void eachModeHasItsLetter() {
        only("n", "vic", List.of("n", "no", "nov", "niI", "t"));
        only("v", "nic", List.of("v", "V", CTRL_V, "s", "S", CTRL_S));
        only("i", "nvc", List.of("i", "ic", "R", "Rv"));
        only("c", "nvi", List.of("c", "cv"));
    }

    @Test
    void thePromptsTakeOnlyR() {
        assertTrue(MouseOption.allows("r", "r"));
        assertTrue(MouseOption.allows("r", "rm"));
        assertFalse(MouseOption.allows("a", "r"), "a is all but the prompts");
    }

    @Test
    void aConfirmPromptAndAShellCommandTakeNone() {
        assertFalse(MouseOption.allows("anvicr", "r?"));
        assertFalse(MouseOption.allows("anvicr", "!"));
    }

    @Test
    void beforeTheFirstReportTheClickGoesThrough() {
        assertTrue(MouseOption.allows(null, "n"));
    }
}
