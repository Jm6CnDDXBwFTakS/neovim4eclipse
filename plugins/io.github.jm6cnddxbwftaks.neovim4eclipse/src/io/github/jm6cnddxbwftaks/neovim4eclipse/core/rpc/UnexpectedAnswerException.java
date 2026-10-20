// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimExceptionBase;

/**
 * Neovim answered a call, but not with the shape its documentation promises: for example
 * {@code nvim_get_mode} with something other than a map, or {@code nvim_create_buf} with a window
 * handle. Not expected from a real Neovim; seeing it means a Neovim version changed an answer, or
 * a bug in {@link MsgpackNeovimApi}.
 *
 * <p>Compare {@link NeovimErrorException}, where Neovim reports that the call failed.
 */
public final class UnexpectedAnswerException extends NeovimExceptionBase {

    private static final long serialVersionUID = 1L;

    /**
     * @param function the Neovim function, such as {@code nvim_get_mode}
     * @param expected what the answer should have been, such as {@code "a map with mode"}
     * @param answer   what came back
     */
    public UnexpectedAnswerException(String function, String expected, Object answer) {
        super(function + " answered " + describe(answer) + ", expected " + expected);
    }

    private static String describe(Object answer) {
        return answer == null ? "nil" : answer + " (" + answer.getClass().getSimpleName() + ")";
    }
}
