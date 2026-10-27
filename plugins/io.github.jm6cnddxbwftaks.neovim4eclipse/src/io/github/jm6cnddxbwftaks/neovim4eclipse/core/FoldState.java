// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.List;

/**
 * Which of Eclipse's folds Neovim shows closed in a buffer, read after a key (the fold element of
 * {@link LuaScripts#STATE_SNAPSHOT}). {@code closed} holds indexes into the list last sent with
 * {@link FoldsPort#setFoldsAsync}, and only the <em>outermost</em> closed folds: a fold inside a
 * closed one is not listed whatever its state, because Neovim cannot see it (see
 * {@code util/FoldRegions.stateChange}, which turns this into "collapse these, expand those").
 *
 * <p>Example: a class (index 0) with two methods (1 and 2); after {@code zM} Neovim reports
 * {@code FoldState(3, [0])}, after {@code zo} on the class {@code FoldState(3, [1, 2])}. An empty
 * list means no fold is closed, as after {@code zR} or {@code zn}.
 *
 * @param bufnr  the Neovim buffer the folds belong to
 * @param closed indexes of the outermost closed folds, in line order
 */
public record FoldState(int bufnr, List<Integer> closed) {

    public FoldState {
        closed = List.copyOf(closed);
    }
}
