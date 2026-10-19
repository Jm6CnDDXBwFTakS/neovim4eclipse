// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc;

import java.util.List;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.NeovimExceptionBase;

/**
 * Neovim refused an API call: the {@code error} of its response {@code [1, msgid, error, result]}
 * was not nil.
 *
 * <p>Neovim's error is {@code [type, message]}. The type is {@code 0} (Exception: the call ran and
 * failed, for example {@code nvim_command("b 99")}) or {@code 1} (Validation: the arguments were
 * wrong, for example a buffer that does not exist). The message is Neovim's own text, such as
 * {@code "Vim:E94: No matching buffer for 99"}, and is this exception's {@link #getMessage()}, so
 * {@code core/Rpc.errorMessage} finds the {@code E94: ...} part in it.
 */
public final class NeovimErrorException extends NeovimExceptionBase {

    private static final long serialVersionUID = 1L;

    /** Neovim's error type for an error that does not say. */
    public static final long UNKNOWN_TYPE = -1;

    private final long type;

    public NeovimErrorException(long type, String message) {
        super(message);
        this.type = type;
    }

    /**
     * From the {@code error} field of a response: {@code [type, message]} as Neovim sends it, or
     * anything else (another client may send a plain string), which then becomes the message.
     */
    static NeovimErrorException fromResponse(Object error) {
        if (error instanceof List<?> list && list.size() >= 2 && list.get(0) instanceof Number type) {
            return new NeovimErrorException(type.longValue(), String.valueOf(list.get(1)));
        }
        return new NeovimErrorException(UNKNOWN_TYPE, String.valueOf(error));
    }

    /** {@code 0} Exception, {@code 1} Validation, or {@link #UNKNOWN_TYPE}. */
    public long type() {
        return type;
    }
}
