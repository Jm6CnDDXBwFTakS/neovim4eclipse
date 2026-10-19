// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc;

import java.util.concurrent.CompletableFuture;

/**
 * Sends a msgpack-rpc request and returns a future of the answer: all that
 * {@link MsgpackNeovimApi} needs from the connection.
 *
 * <p>{@link RpcChannel} implements it. A separate one-method interface so that tests can check
 * which function each API method calls with which arguments, without pipes or threads:
 * {@code NeovimApiTest} passes a lambda that records the call and returns a ready answer.
 */
@FunctionalInterface
public interface RpcRequests {

    /**
     * @see RpcChannel#request(String, Object...)
     */
    CompletableFuture<Object> request(String method, Object... params);
}
