// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

/**
 * What following Eclipse's caret needs from the session: moving Neovim's cursor.
 *
 * <p>Positions are Neovim's: a 1-based line and a 0-based UTF-8 byte column (convert an Eclipse
 * offset with {@code editor/DocumentPositions}). An interface so that the caret glue
 * ({@code editor/CaretSync}) can be tested with a fake that records the positions.
 * {@link NeovimSession} implements it.
 */
public interface CursorPort {

    /** Queues a cursor move; any thread, returns at once (e.g. after a mouse click in Eclipse). */
    void setCursorAsync(int line1Based, int col0Based);

    /** Moves the cursor now. Blocking RPC: call on the session's input thread only. */
    void setCursor(int line1Based, int col0Based) throws Exception;
}
