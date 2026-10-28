// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ToggleButtonState.Editor;

class ToggleButtonStateTest {

    /** A key the user bound to attach (plugin.xml binds none). */
    @Test
    void aTextEditorWithoutNeovimIsGreyedOutAndNamesTheAttachKey() {
        ToggleButtonState state = ToggleButtonState.of(Editor.TEXT_WITHOUT_NEOVIM, true, "Ctrl+Shift+M", "Ctrl+Shift+N");
        assertFalse(state.enabled());
        assertFalse(state.checked(), "a greyed-out button is never drawn pressed");
        assertEquals(ToggleButtonState.ICON_ON, state.icon(), "Eclipse pales the color logo itself");
        assertEquals("Neovim mode unavailable (Ctrl+Shift+N attaches Neovim).", state.tooltip());
    }

    /** The default: attach has no key, so the tooltip names the command, as Quick Access lists it. */
    @Test
    void withoutAnAttachKeyTheTooltipNamesTheCommand() {
        assertEquals("Neovim mode unavailable (Neovim > Attach Neovim attaches it).",
                ToggleButtonState.of(Editor.TEXT_WITHOUT_NEOVIM, false, "Ctrl+Shift+M", null).tooltip());
    }

    /** The attach key cannot help in an image or a form page, so the tooltip does not offer it. */
    @Test
    void anEditorWithoutTextIsGreyedOutAndSaysSo() {
        ToggleButtonState state = ToggleButtonState.of(Editor.NOT_TEXT, true, "Ctrl+Shift+M", "Ctrl+Shift+N");
        assertFalse(state.enabled());
        assertFalse(state.checked());
        assertEquals("Neovim mode unavailable: not a text editor.", state.tooltip());
    }

    @Test
    void withoutAnyEditorTheButtonIsGreyedOutAndSaysSo() {
        ToggleButtonState state = ToggleButtonState.of(Editor.NONE, false, "Ctrl+Shift+M", "Ctrl+Shift+N");
        assertFalse(state.enabled());
        assertEquals("Neovim mode unavailable: no editor open.", state.tooltip());
    }

    @Test
    void neovimModeIsPressedWithTheColorLogo() {
        ToggleButtonState state = ToggleButtonState.of(Editor.ATTACHED, true, "Ctrl+Shift+M", "Ctrl+Shift+N");
        assertTrue(state.enabled());
        assertTrue(state.checked());
        assertEquals(ToggleButtonState.ICON_ON, state.icon());
        assertEquals("Neovim mode: Neovim gets the keys. Click or (Ctrl+Shift+M) to switch to Eclipse mode.",
                state.tooltip());
    }

    @Test
    void eclipseModeIsRaisedWithTheGreyLogo() {
        ToggleButtonState state = ToggleButtonState.of(Editor.ATTACHED, false, "⇧⌘M", "⇧⌘N");
        assertTrue(state.enabled());
        assertFalse(state.checked());
        assertEquals(ToggleButtonState.ICON_OFF, state.icon());
        assertEquals("Eclipse mode: Eclipse gets the keys. Click or (⇧⌘M) to switch to Neovim mode.",
                state.tooltip());
    }

    @Test
    void aToggleWithoutKeyBindingNamesNone() {
        assertEquals("Neovim mode: Neovim gets the keys. Click to switch to Eclipse mode.",
                ToggleButtonState.of(Editor.ATTACHED, true, null, null).tooltip());
        assertEquals("Eclipse mode: Eclipse gets the keys. Click to switch to Neovim mode.",
                ToggleButtonState.of(Editor.ATTACHED, false, " ", null).tooltip());
    }
}
