// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

/**
 * Headless Neovim integration: the embedded process, msgpack-rpc session, and event dispatch.
 *
 * <p>Primary type: {@link io.github.jm6cnddxbwftaks.eclipseneovim.core.NeovimSession}. This package must not
 * depend on SWT, JFace or the Eclipse workbench (use {@link io.github.jm6cnddxbwftaks.eclipseneovim.core.ClipboardPort}
 * style interfaces instead) so it can be compiled and tested without Eclipse. All blocking RPC runs
 * on the session's input thread, never on the SWT UI thread.
 *
 * <h2>The pieces</h2>
 * <ul>
 *   <li>{@link io.github.jm6cnddxbwftaks.eclipseneovim.core.NeovimSession}: the facade the editor uses.
 *       Sends keys and requests, and publishes mode, cursor, selection and text changes to listeners.
 *       It delegates to:
 *       <ul>
 *         <li>{@code NeovimConnection}: the {@code nvim --embed} process and the RPC layers;</li>
 *         <li>{@code KeyInput}: what happens when a key is sent (pending commands, insert replay,
 *             macros, host {@code :} commands);</li>
 *         <li>{@code DocumentBuffers}: one Neovim buffer per Eclipse document, and which is attached;</li>
 *         <li>{@code StateSnapshot}: reading the per-key state answer;</li>
 *         <li>{@code StatusTracker}: status bar parts (showcmd, message, statusline) and macro
 *             recording, fired to listeners only when they change;</li>
 *         <li>{@code InputThread}: the single thread for all blocking RPC, which keeps keys in order;</li>
 *         <li>{@code Rpc}: waiting for answers, and Neovim's error text from a failed call.</li>
 *       </ul></li>
 *   <li>Value types passed to listeners: {@code CursorPos}, {@code VisualSelection},
 *       {@code BufferChange}; and {@code DocumentBuffer}, the handle of one document's buffer.</li>
 *   <li>{@code LuaScripts}: every Lua chunk run inside Neovim, with comments.</li>
 *   <li>{@code NotificationBridge}: decodes the msgpack-rpc notifications Neovim sends (buffer line
 *       events and the plug-in's own {@code eclipse_*} events).</li>
 * </ul>
 *
 * <p>See {@code docs/LEARNING.md}, part 2, for msgpack-rpc, {@code nvim_input}, blocking, and
 * buffer events.
 */
package io.github.jm6cnddxbwftaks.eclipseneovim.core;
