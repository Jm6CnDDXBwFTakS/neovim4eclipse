// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc;

import java.util.Objects;

/**
 * A reference to a Neovim buffer, window or tab page, as the API passes them.
 *
 * <p>On the wire (msgpack-rpc) these are MessagePack <em>extension</em> values: a type code (0
 * buffer, 1 window, 2 tab page, from {@code nvim_get_api_info()}) and the handle's number. The
 * number is what Vim script and Lua see: buffer handle 3 is {@code :buffer 3} and
 * {@code vim.api.nvim_buf_get_lines(3, ...)}. Window handles are not window numbers
 * ({@code winnr()}) but window IDs ({@code win_getid()}, starting at 1000).
 *
 * <p>Example: {@code nvim_create_buf} answers {@code new Handle(Kind.BUFFER, 3)}; passing it to
 * {@code nvim_set_current_buf} shows that buffer.
 *
 * @param kind what the handle refers to
 * @param id   Neovim's number for it
 */
public record Handle(Kind kind, long id) {

    /** What a handle refers to. */
    public enum Kind {
        BUFFER, WINDOW, TABPAGE
    }

    public Handle {
        Objects.requireNonNull(kind, "kind");
    }

    /** A buffer handle; {@code id} is the buffer number ({@code bufnr()}). */
    public static Handle buffer(long id) {
        return new Handle(Kind.BUFFER, id);
    }

    /** A window handle; {@code id} is the window ID ({@code win_getid()}). */
    public static Handle window(long id) {
        return new Handle(Kind.WINDOW, id);
    }

    /**
     * The number as an {@code int}, how the plug-in stores buffer numbers. Neovim's handles are
     * small positive integers, so this never overflows in practice.
     */
    public int intId() {
        return Math.toIntExact(id);
    }
}
