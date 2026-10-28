// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Which editor's token holds which Neovim window (docs/design/WINDOWS.md), without Neovim: opening,
 * forgetting (closed by Eclipse, or in Neovim), the travel swap, and a restarted Neovim. The Neovim
 * side is windows_spec.lua; both together NeovimWindowsIntegrationTest.
 */
class EditorWindowsTest {

    private final EditorWindows windows = new EditorWindows();
    private final EditorWindow a = new EditorWindow();
    private final EditorWindow b = new EditorWindow();

    @Test
    void aTokenHoldsNoWindowUntilOneIsAdded() {
        assertFalse(windows.owns(a));
        assertEquals(EditorWindow.UNKNOWN, a.winid());
        windows.add(a, 1001);
        assertTrue(windows.owns(a));
        assertEquals(1001, a.winid());
        assertSame(a, windows.of(1001));
        assertNull(windows.of(1000), "the home window is no editor's");
        assertEquals(1, windows.size());
        assertThrows(IllegalArgumentException.class, () -> windows.add(b, EditorWindow.UNKNOWN));
    }

    @Test
    void forgettingATokenFreesItsWindowAndAShowOpensANewOne() {
        windows.add(a, 1001);
        windows.forget(a);
        assertFalse(windows.owns(a));
        assertEquals(EditorWindow.UNKNOWN, a.winid());
        assertNull(windows.of(1001));
        windows.forget(a); // twice: nothing
        windows.add(a, 1002);
        assertEquals(1002, a.winid());
        assertEquals(1, windows.size());
    }

    /** The safety net: Neovim closed the window by itself (:tabclose), by its id. */
    @Test
    void aWindowClosedInNeovimIsForgottenByItsId() {
        windows.add(a, 1001);
        assertSame(a, windows.forget(1001));
        assertFalse(windows.owns(a));
        assertNull(windows.forget(1001), "unknown now");
        assertNull(windows.forget(1000), "the home window: no token");
    }

    @Test
    void reopeningATokenDropsItsOldWindow() {
        windows.add(a, 1001);
        windows.add(a, 1002);
        assertNull(windows.of(1001));
        assertSame(a, windows.of(1002));
        assertEquals(1, windows.size());
    }

    /** Ctrl-O across files: A's window jumped into B's file, so the two swap windows (E2). */
    @Test
    void theTravelSwapGivesEachEditorTheOthersWindow() {
        windows.add(a, 1001);
        windows.add(b, 1002);
        windows.swap(a, b);
        assertEquals(1002, a.winid());
        assertEquals(1001, b.winid());
        assertSame(b, windows.of(1001));
        assertSame(a, windows.of(1002));
        assertEquals(2, windows.size());
    }

    /** Ctrl-O into the file of an editor that has no window yet: it takes the mover's. */
    @Test
    void anEditorWithoutAWindowTakesTheTravellingOne() {
        windows.add(a, 1001);
        windows.transfer(a, b);
        assertEquals(1001, b.winid());
        assertSame(b, windows.of(1001));
        assertFalse(windows.owns(a), "a new window at its next show");
        assertEquals(1, windows.size());
        assertThrows(IllegalStateException.class, () -> windows.transfer(a, b), "a has none to give");
    }

    @Test
    void aSwapNeedsTwoOpenWindows() {
        windows.add(a, 1001);
        assertThrows(IllegalStateException.class, () -> windows.swap(a, b));
        assertEquals(1001, a.winid(), "nothing changed");
    }

    /** Restart Neovim: the new session has its own registry, so every token opens a new window. */
    @Test
    void aTokenFromAnotherSessionIsNotOwned() {
        windows.add(a, 1001);
        EditorWindows restarted = new EditorWindows();
        assertFalse(restarted.owns(a));
        restarted.add(a, 1001);
        assertTrue(restarted.owns(a));
        assertFalse(windows.owns(a), "the old registry has let go");
    }
}
