// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToggleButtonStateTest {

    @Test
    void withoutNeovimTheButtonIsGreyedOutAndNamesTheAttachKey() {
        ToggleButtonState state = ToggleButtonState.of(false, true, "Ctrl+Shift+M", "Ctrl+Shift+N");
        assertFalse(state.enabled());
        assertFalse(state.checked(), "a greyed-out button is never drawn pressed");
        assertEquals(ToggleButtonState.ICON_ON, state.icon(), "Eclipse pales the color logo itself");
        assertEquals("Neovim mode unavailable (Ctrl+Shift+N attaches Neovim).", state.tooltip());
    }

    @Test
    void withoutAnAttachKeyTheTooltipNamesTheCommand() {
        assertEquals("Neovim mode unavailable (Neovim: Attach to Active Editor attaches Neovim).",
                ToggleButtonState.of(false, false, "Ctrl+Shift+M", null).tooltip());
    }

    @Test
    void neovimModeIsPressedWithTheColorLogo() {
        ToggleButtonState state = ToggleButtonState.of(true, true, "Ctrl+Shift+M", "Ctrl+Shift+N");
        assertTrue(state.enabled());
        assertTrue(state.checked());
        assertEquals(ToggleButtonState.ICON_ON, state.icon());
        assertEquals("Neovim mode: Neovim gets the keys. Click or (Ctrl+Shift+M) to switch to Eclipse mode.",
                state.tooltip());
    }

    @Test
    void eclipseModeIsRaisedWithTheGreyLogo() {
        ToggleButtonState state = ToggleButtonState.of(true, false, "⇧⌘M", "⇧⌘N");
        assertTrue(state.enabled());
        assertFalse(state.checked());
        assertEquals(ToggleButtonState.ICON_OFF, state.icon());
        assertEquals("Eclipse mode: Eclipse gets the keys. Click or (⇧⌘M) to switch to Neovim mode.",
                state.tooltip());
    }

    @Test
    void aToggleWithoutKeyBindingNamesNone() {
        assertEquals("Neovim mode: Neovim gets the keys. Click to switch to Eclipse mode.",
                ToggleButtonState.of(true, true, null, null).tooltip());
        assertEquals("Eclipse mode: Eclipse gets the keys. Click to switch to Neovim mode.",
                ToggleButtonState.of(true, false, " ", null).tooltip());
    }
}
