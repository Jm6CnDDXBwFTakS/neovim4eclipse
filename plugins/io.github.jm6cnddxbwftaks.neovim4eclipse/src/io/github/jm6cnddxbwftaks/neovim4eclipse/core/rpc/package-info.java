// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

/**
 * The plug-in's own msgpack-rpc client for Neovim, on msgpack-core alone: the connection
 * ({@code RpcChannel}), the encoding ({@code MsgpackValues}), the typed API ({@code NeovimApi}) and
 * its value types. It replaced the neovim-java library; {@code docs/design/RPC_CLIENT.md} tells why and
 * how, step by step.
 *
 * <p>Like the rest of {@code core/}, this package must not import {@code org.eclipse.*}: it is
 * compiled and unit-tested without Eclipse ({@code tests/unit}).
 *
 * <ul>
 *   <li>{@link io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.NeovimApi}: the Neovim functions
 *       the plug-in calls, one method each, named after Neovim's.</li>
 *   <li>{@link io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Incoming}: what Neovim sends on its
 *       own (notifications and requests), implemented by {@code core/NotificationBridge}.</li>
 *   <li>{@link io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.MsgpackNeovimApi}: the same
 *       interface's implementation, over
 *       {@link io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.RpcRequests}.</li>
 *   <li>{@link io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.RpcChannel}: the msgpack-rpc
 *       connection itself: requests, responses, notifications, the reader thread.</li>
 *   <li>{@link io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.MsgpackValues}: Java values to and
 *       from MessagePack, for {@code RpcChannel}.</li>
 *   <li>{@link io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.NeovimErrorException},
 *       {@link io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.NeovimConnectionClosedException}:
 *       how a call fails (Neovim said no; Neovim is gone);
 *       {@link io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.UnexpectedAnswerException}: Neovim
 *       answered with the wrong shape.</li>
 *   <li>{@link io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Handle}: a buffer, window or tab
 *       page.</li>
 *   <li>{@link io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Mode}: the answer to
 *       {@code nvim_get_mode()}.</li>
 *   <li>{@link io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.WinCursor}: a window's cursor,
 *       {@code [row, col]}.</li>
 * </ul>
 */
package io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc;
