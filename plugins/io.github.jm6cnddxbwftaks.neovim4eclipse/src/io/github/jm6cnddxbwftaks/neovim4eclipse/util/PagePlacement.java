// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * Where a page editor ({@code :help}, {@code :messages}, {@code :checkhealth}) goes when its
 * content arrives, as {@code editor/NeovimPageOpener} applies it. The first one splits the code's
 * editor below, as Vim's {@code :help} splits the window; the others join it as tabs, so the code
 * area is split once; the same kind again stays where it is, wherever the user moved it.
 *
 * <p>Example: {@code :help} in a lone editor: {@link #BELOW}. Then {@code :messages}:
 * {@link #BESIDE_ANOTHER}, a tab in the help editor's stack. Then {@code :help} again:
 * {@link #WHERE_IT_IS}.
 */
public enum PagePlacement {
    /** The kind's editor is open: it stays where it is and comes to the front. */
    WHERE_IT_IS,
    /** Another kind's editor is open: a tab in its stack. */
    BESIDE_ANOTHER,
    /** None is open: a new stack below the editor the content came from. */
    BELOW,
    /** None is open and no editor to split: where Eclipse opens editors. */
    WHERE_ECLIPSE_OPENS;

    /**
     * @param open        whether an editor of this content's kind is open
     * @param anotherOpen whether a page editor of another kind is open
     * @param from        whether there is an editor to split, the one the content came from
     */
    public static PagePlacement of(boolean open, boolean anotherOpen, boolean from) {
        if (open) {
            return WHERE_IT_IS;
        }
        if (anotherOpen) {
            return BESIDE_ANOTHER;
        }
        return from ? BELOW : WHERE_ECLIPSE_OPENS;
    }
}
