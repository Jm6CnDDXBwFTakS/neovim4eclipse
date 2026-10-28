// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

/**
 * The Neovim window of one Eclipse editor, so that each editor keeps its own cursor, column memory,
 * scroll position, folds, jump history and window-local options (docs/design/WINDOWS.md).
 *
 * <p>A <em>window</em> in Neovim is a view on a buffer: two windows on one buffer share the text,
 * undo and marks, and each has its own cursor. Each editor's window sits alone in its own hidden
 * <em>tabpage</em> ({@code 'showtabline'} 0), so it fills Neovim's screen, which is the attached
 * editor's size (docs/design/VIEWPORT.md).
 *
 * <p>Like {@link DocumentBuffer}, the editor holds this token for its lifetime and the session fills
 * it in: the window is opened at the editor's first {@link NeovimSession#showWindow}, belongs to that
 * session only (a restarted Neovim opens a new one at the next attach), and is closed with
 * {@link NeovimSession#closeWindowAsync}. A window can also <em>travel</em> to another editor
 * ({@link NeovimSession#travelWindow}): then the two tokens swap windows.
 */
public final class EditorWindow {

    /** Window id used when a notification does not say which window it is about, or before first use. */
    public static final int UNKNOWN = -1;

    /** The registry that opened the window; {@code null} before first use and after closing. */
    volatile EditorWindows owner;
    /** Neovim's window id ({@code nvim_get_current_win()}, 1000 and up), or {@link #UNKNOWN}. */
    volatile int winid = UNKNOWN;

    /** Neovim's window id, or {@link #UNKNOWN} while the window is not open. */
    public int winid() {
        return winid;
    }

    /** Whether the window exists in {@code session} (not yet opened, closed, or from a restarted Neovim, if not). */
    public boolean isOpenIn(NeovimSession session) {
        return session.buffers().windows().owns(this);
    }

    @Override
    public String toString() {
        return "EditorWindow[" + winid + "]";
    }
}
