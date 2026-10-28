// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.function.Consumer;

/**
 * What keeping Neovim's window the size of the editor needs from the session
 * (docs/design/VIEWPORT.md): whether the feature is on, the editor's size, its word wrap in both
 * directions, and the window's top line (Neovim's to the editor, the editor's on attach and Esc). An interface, rather than the whole {@link NeovimSession}, so that the editor's side
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
     * Scrolls Neovim's window so that {@code line1} (1-based) is its top line, after every key queued
     * before: the editor's view is the one to keep (it was just attached, or Eclipse scrolled while
     * it typed in insert mode). Neovim keeps its cursor visible, so it may choose another top line;
     * that one comes back through {@link #addTopLineListener}. Nothing happens when {@code buffer}
     * is no longer the one Neovim shows.
     */
    void setTopLineAsync(DocumentBuffer buffer, int line1);

    /**
     * The user scrolled the editor in normal or visual mode (the scroll bar, the wheel, Eclipse
     * revealing a search hit): Neovim's window is scrolled to {@code line1} too, and its cursor
     * dragged into the new view as {@code Ctrl-E} drags it, so that the editor's view is kept and
     * {@code H}, {@code L} and the next {@code j} work on it. Scrolls that arrive faster than Neovim
     * answers replace each other. Nothing happens when {@code buffer} is no longer the one shown.
     */
    void scrollToAsync(DocumentBuffer buffer, int line1);

    /**
     * The mouse wheel in the editor, in normal or visual mode (decisions D1, D9): Neovim's window
     * scrolls {@code lines} down (negative: up) as {@code Ctrl-E} ({@code Ctrl-Y}) would and drags
     * the cursor along; the editor follows through {@link #addTopLineListener}. Events that arrive
     * faster than Neovim answers are added up. Nothing happens when {@code buffer} is no longer the
     * one shown.
     */
    void scrollLinesAsync(DocumentBuffer buffer, int lines);

    /**
     * Hears Neovim's window scroll ({@code Ctrl-E}, {@code zz}, {@code 50G}, a resize), on a session
     * thread, before the cursor of the same key is reported (docs/design/VIEWPORT.md, 3.2): the
     * editor scrolls to {@link TopLine#line1()}, then places the caret. Only for attached buffers,
     * and only when the viewport is on. The same top line may come twice for one key; the second
     * changes nothing.
     */
    void addTopLineListener(Consumer<TopLine> listener);

    void removeTopLineListener(Consumer<TopLine> listener);

    /**
     * Neovim's {@code 'wrap'} changed in its window, which shows buffer {@code bufnr}.
     *
     * @param bufnr Neovim's buffer number
     * @param wrap  the new value
     */
    record WrapChange(int bufnr, boolean wrap) {
    }

    /**
     * Neovim's window, which shows buffer {@code bufnr}, has {@code line1} at its top.
     *
     * @param bufnr Neovim's buffer number
     * @param line1 the window's top line, 1-based ({@code line('w0')})
     */
    record TopLine(int bufnr, int line1) {
    }
}
