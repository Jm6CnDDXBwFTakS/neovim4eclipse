// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

/**
 * The plug-in's own types for talking to Neovim over msgpack-rpc.
 *
 * <p>Today the RPC itself still goes through neovim-java. This package holds the interface and the
 * value types that the rest of {@code core/} uses instead of neovim-java's, so that the library can
 * later be replaced by a small client of our own without touching that code. The plan, step by
 * step, is in {@code docs/RPC_CLIENT.md}.
 *
 * <p>Like the rest of {@code core/}, this package must not import {@code org.eclipse.*}: it is
 * compiled and unit-tested without Eclipse ({@code tests/unit}).
 *
 * <ul>
 *   <li>{@link io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc.NeovimApi}: the Neovim functions
 *       the plug-in calls, one method each, named after Neovim's.</li>
 *   <li>{@link io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc.NeovimJavaApi}: that interface
 *       implemented with neovim-java (temporary, an adapter).</li>
 *   <li>{@link io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc.Handle}: a buffer, window or tab
 *       page.</li>
 *   <li>{@link io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc.Mode}: the answer to
 *       {@code nvim_get_mode()}.</li>
 *   <li>{@link io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc.WinCursor}: a window's cursor,
 *       {@code [row, col]}.</li>
 * </ul>
 */
package io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc;
