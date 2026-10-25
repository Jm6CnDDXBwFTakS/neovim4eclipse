// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToggleButtonStateTest {

    @Test
    void withoutNeovimTheButtonIsGreyedOutAndSaysHowToAttach() {
        ToggleButtonState state = ToggleButtonState.of(false, true, "Ctrl+Shift+M");
        assertFalse(state.enabled());
        assertFalse(state.checked(), "a greyed-out button is never drawn pressed");
        assertTrue(state.tooltip().contains("Attach to Active Editor"), state.tooltip());
    }

    @Test
    void neovimModeIsPressed() {
        ToggleButtonState state = ToggleButtonState.of(true, true, "Ctrl+Shift+M");
        assertTrue(state.enabled());
        assertTrue(state.checked());
        assertEquals("Neovim mode: Neovim gets the keys. Click (Ctrl+Shift+M) for plain Eclipse editing",
                state.tooltip());
    }

    @Test
    void eclipseModeIsRaised() {
        ToggleButtonState state = ToggleButtonState.of(true, false, "⇧⌘M");
        assertTrue(state.enabled());
        assertFalse(state.checked());
        assertEquals("Eclipse mode: Eclipse gets the keys. Click (⇧⌘M) to give them to Neovim", state.tooltip());
    }

    @Test
    void aToggleWithoutKeyBindingNamesNone() {
        assertEquals("Neovim mode: Neovim gets the keys. Click for plain Eclipse editing",
                ToggleButtonState.of(true, true, null).tooltip());
        assertEquals("Eclipse mode: Eclipse gets the keys. Click to give them to Neovim",
                ToggleButtonState.of(true, false, " ").tooltip());
    }
}
