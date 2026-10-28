// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.function.Consumer;

/**
 * What keeping Neovim's window the size of the editor needs from the session
 * (docs/design/VIEWPORT.md): whether the feature is on, the editor's size, and its word wrap in both
 * directions. An interface, rather than the whole {@link NeovimSession}, so that the editor's side
 * is tested with a small fake. {@link NeovimSession} implements it.
 *
 * <p>The flow, for an editor made taller: the editor measures its text area (40 → 50 lines) and
 * calls {@link #setEditorSizeAsync}; the session asks Neovim what its screen has besides the window
 * (status line, number column) and resizes the screen so that the window is 50 lines tall; {@code L}
 * then goes to the editor's last line.
 */
public interface ViewportPort {

    /**
     * Whether Neovim's window follows the editor: {@code true} unless the user's config sets
     * {@code vim.g.neovim4eclipse_viewport = 0} (or {@code false}). Read once when the session starts;
     * when false, nothing else in this interface needs to be used, and none of it costs anything.
     */
    boolean viewportEnabled();

    /**
     * The attached editor shows {@code columns} × {@code rows} cells of text (its font's average
     * character width and line height). Neovim's screen is resized so that its window shows the
     * same, on the input thread, after every key queued before. Returns at once; sizes that arrive
     * faster than Neovim answers replace each other. The session keeps the last size: with no editor
     * attached Neovim keeps it (decision D8), and a change of the status line or number column in
     * Neovim resizes again with it.
     */
    void setEditorSizeAsync(int columns, int rows);

    /**
     * Sets {@code 'wrap'} in Neovim's window to the editor's word wrap, with {@code 'linebreak'}
     * (decisions D2 and D7), after every key and buffer switch queued before. Nothing happens when
     * {@code buffer} is no longer the one Neovim shows.
     */
    void setWrapAsync(DocumentBuffer buffer, boolean wrap);

    /**
     * Hears {@code :set wrap} and {@code :set nowrap} made in Neovim (on a session thread; hop to the
     * UI thread yourself). What {@link #setWrapAsync} sets is not reported back.
     */
    void addWrapListener(Consumer<WrapChange> listener);

    void removeWrapListener(Consumer<WrapChange> listener);

    /**
     * Neovim's {@code 'wrap'} changed in its window, which shows buffer {@code bufnr}.
     *
     * @param bufnr Neovim's buffer number
     * @param wrap  the new value
     */
    record WrapChange(int bufnr, boolean wrap) {
    }
}
