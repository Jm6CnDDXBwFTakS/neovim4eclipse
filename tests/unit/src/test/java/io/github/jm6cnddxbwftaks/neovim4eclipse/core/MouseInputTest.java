// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MouseInputTest {

    @Test
    void modifiersAreNeovimsLetters() {
        assertEquals("", MouseInput.modifiers(false, false));
        assertEquals("S", MouseInput.modifiers(true, false));
        assertEquals("A", MouseInput.modifiers(false, true));
        assertEquals("SA", MouseInput.modifiers(true, true));
    }

    @Test
    void actionsHaveNeovimsNames() {
        assertEquals("press", MouseInput.Action.PRESS.luaName());
        assertEquals("drag", MouseInput.Action.DRAG.luaName());
        assertEquals("release", MouseInput.Action.RELEASE.luaName());
    }

    @Test
    void aPositionOutsideTheDocumentIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new MouseInput(MouseInput.Action.PRESS, "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new MouseInput(MouseInput.Action.PRESS, "", 1, -1));
        assertEquals("", new MouseInput(MouseInput.Action.PRESS, null, 1, 0).modifiers());
    }
}
