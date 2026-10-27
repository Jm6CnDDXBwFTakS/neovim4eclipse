// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.List;
import java.util.function.Consumer;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FoldRegions;

/**
 * What mirroring an editor's folds into Neovim needs from the session (docs/design/FOLDING.md):
 * whether folding is on, sending Eclipse's fold regions, and hearing which folds Neovim has closed.
 * An interface, rather than the whole {@link NeovimSession}, so that the editor's fold code is tested
 * with a small fake. {@link NeovimSession} implements it.
 *
 * <p>The flow, for {@code zc} on a method: the key reaches Neovim, which closes its fold; the state
 * read after the key lists that fold's index; a {@link #addFoldStateListener listener} collapses the
 * matching annotation in Eclipse. The other way, a fold collapsed with the mouse in Eclipse is sent
 * with {@link #setFoldsAsync} and closed in Neovim.
 */
public interface FoldsPort {

    /**
     * Whether Neovim mirrors Eclipse's folds: {@code true} unless the user's config sets
     * {@code vim.g.neovim4eclipse_folds = 0} (or {@code false}). Read once when the session starts;
     * when false, nothing else in this interface needs to be used, and none of it costs anything.
     */
    boolean foldsEnabled();

    /**
     * Replaces the folds of {@code buffer} in Neovim with {@code folds} ({@link FoldRegions#forNeovim};
     * an empty list means the editor cannot fold, and folding is turned off in Neovim's window), on
     * the input thread, after every key and command queued before. Returns at once. Nothing happens
     * when folding is off, when the buffer does not exist or has no text yet, or when it is not the
     * buffer Neovim shows: folds belong to Neovim's window, so they are sent again when Eclipse shows
     * the editor.
     */
    void setFoldsAsync(DocumentBuffer buffer, List<FoldRegions.Fold> folds);

    /** Hears the closed folds after each key (on a session thread; hop to the UI thread yourself). */
    void addFoldStateListener(Consumer<FoldState> listener);

    void removeFoldStateListener(Consumer<FoldState> listener);
}
