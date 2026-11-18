// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

/**
 * What a page editor ({@link NeovimPageEditor}) shows its page in: a pane with a grid canvas, keys
 * and mouse. Two kinds, by where the page's Neovim window is:
 * <ul>
 *   <li>{@link ScreenPane}: a second Neovim of the page's own, whose whole screen is drawn (help
 *       and checkhealth);</li>
 *   <li>{@link WindowPane}: a window of the editing Neovim, the one behind the text editors, drawn
 *       from its grid (the messages page): one Neovim, so registers, search history and the
 *       user's colors and mappings are the code's.</li>
 * </ul>
 * UI thread.
 */
interface PagePane {

    /** The canvas the page is drawn on: for the editor's focus and key context, and for tests. */
    GridCanvas canvas();

    /** Gives the page the keyboard. */
    void setFocus();

    /** A Vim key Eclipse would have taken for a shortcut ({@code <C-w>}, {@link SendKeyHandler}), typed in the page. */
    void sendKey(String key);

    /** Ends the page's Neovim or closes its window. The editor calls it when it is disposed. */
    void dispose();
}
