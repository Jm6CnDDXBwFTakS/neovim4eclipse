// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc;

import java.util.List;

/**
 * What Neovim sends on its own, rather than as an answer: msgpack-rpc <em>notifications</em> and
 * <em>requests</em>.
 *
 * <p>On the wire a notification is {@code [2, method, params]} and needs no answer; a request is
 * {@code [0, msgid, method, params]} and Neovim waits until it gets exactly one response
 * {@code [1, msgid, error, result]}. Example: Lua's {@code vim.rpcnotify(chan, "eclipse_mode", "i")}
 * arrives as {@code notification("eclipse_mode", ["i"])}; {@code vim.rpcrequest(chan,
 * "eclipse_write", 3)} arrives as {@code request("eclipse_write", [3])}, and its return value is
 * what the Lua call returns.
 *
 * <p>The RPC layer calls both methods on its reader thread, one message at a time, in the order
 * Neovim sent them. While one runs, nothing else from Neovim is read, so they must not wait for an
 * answer from Neovim themselves (that answer could never be read: a deadlock).
 *
 * <p>Implemented by {@code core/NotificationBridge}. Today neovim-java delivers the messages
 * ({@code NeovimConnection}); later our own client will ({@code docs/RPC_CLIENT.md}).
 */
public interface Incoming {

    /**
     * A notification. Names the implementation does not know must be ignored: Neovim also sends
     * {@code redraw} (to every UI), {@code nvim_buf_changedtick_event} and others.
     *
     * @param params the arguments, decoded into plain Java values; never {@code null}
     */
    void notification(String method, List<?> params);

    /**
     * A request; the return value is the answer.
     *
     * @param params the arguments, as for {@link #notification}
     * @throws Exception to answer with an error instead (its message is the error text)
     */
    Object request(String method, List<?> params) throws Exception;
}
