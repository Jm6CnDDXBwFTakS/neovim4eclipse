// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which {@link EditorWindow} holds which Neovim window: the bookkeeping of
 * docs/design/WINDOWS.md, without Neovim, so that it is unit-tested on its own
 * ({@code EditorWindowsTest}). {@link DocumentBuffers} does the Neovim side and calls this.
 *
 * <p>The one subtle operation is {@link #swap}. A window <em>travels</em> on {@code Ctrl-O} across
 * files: editor A's window jumped into the file of editor B, so A's window becomes B's, and B's old
 * window (given A's file and cursor by Neovim) becomes A's. Example: A holds window 1001, B holds
 * 1002; after the swap A holds 1002 and B holds 1001. Every per-window thing (jump history, location
 * list, folds, scroll position, options) moves along, which keeps the jump history continuous as it
 * was with one window (WINDOWS.md, E2).
 *
 * <p>Changed on the session's input thread only; read from any thread (the notifications are read on
 * the RPC thread).
 */
final class EditorWindows {

    private final Map<Integer, EditorWindow> byWinid = new ConcurrentHashMap<>();

    /** Records that {@code window} now holds Neovim window {@code winid} (just opened). */
    void add(EditorWindow window, int winid) {
        if (winid == EditorWindow.UNKNOWN) {
            throw new IllegalArgumentException("no window id");
        }
        if (owns(window)) {
            forget(window); // reopened: the old id is gone
        }
        EditorWindow previous = byWinid.put(winid, window);
        if (previous != null && previous != window) {
            clear(previous); // Neovim reused the id of a window closed behind our back
        }
        window.owner = this;
        window.winid = winid;
    }

    /** Whether {@code window} holds a window of this registry. */
    boolean owns(EditorWindow window) {
        return window != null && window.owner == this && window.winid != EditorWindow.UNKNOWN;
    }

    /** The token holding Neovim window {@code winid}, or {@code null} (the home window, a split, none). */
    EditorWindow of(int winid) {
        return byWinid.get(winid);
    }

    /** Forgets {@code window} (closed by Eclipse); a later show opens a new one. */
    void forget(EditorWindow window) {
        if (owns(window)) {
            byWinid.remove(window.winid, window);
            clear(window);
        }
    }

    /**
     * Forgets the token holding Neovim window {@code winid}, if any (the window was closed in Neovim,
     * by {@code :tabclose} say), and returns it.
     */
    EditorWindow forget(int winid) {
        EditorWindow window = byWinid.remove(winid);
        if (window != null) {
            clear(window);
        }
        return window;
    }

    /** Exchanges the windows of {@code a} and {@code b}: the travel on {@code Ctrl-O} (see the class comment). */
    void swap(EditorWindow a, EditorWindow b) {
        if (!owns(a) || !owns(b)) {
            throw new IllegalStateException("swap needs two open windows: " + a + ", " + b);
        }
        int aWin = a.winid;
        int bWin = b.winid;
        a.winid = bWin;
        b.winid = aWin;
        byWinid.put(aWin, b);
        byWinid.put(bWin, a);
    }

    /** How many editor windows are open (the home window is not counted). */
    int size() {
        return byWinid.size();
    }

    private static void clear(EditorWindow window) {
        window.owner = null;
        window.winid = EditorWindow.UNKNOWN;
    }
}
