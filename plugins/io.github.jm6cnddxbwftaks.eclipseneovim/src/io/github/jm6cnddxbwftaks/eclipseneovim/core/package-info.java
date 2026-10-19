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
 *         <li>{@code NeovimConnection}: the {@code nvim --embed} process and the RPC layers, and
 *             closing them: {@code :qa!}, then waiting for the process ({@code QuittingConnection}
 *             keeps the expected end of Neovim's output from being logged as an error);</li>
 *         <li>{@code KeyInput}: what happens when a key is sent (pending commands, insert replay,
 *             macros, host {@code :} commands). It does not know the session: it is given
 *             {@code StateSync}, {@code StatusTracker} and its own small {@code KeyInput.Neovim}
 *             interface, so the session depends on it and not the other way round;</li>
 *         <li>{@code DocumentBuffers}: one Neovim buffer per Eclipse document, and which is attached
 *             (the Neovim side; {@code EditorBuffers}, below, is the Eclipse side);</li>
 *         <li>{@code CommandRunner}: submitted {@code :} commands (with their output), listings,
 *             searches and {@code :normal!}; sees Neovim through its small {@code Neovim} interface;</li>
 *         <li>{@code StateSync}: mode, cursor and whether Eclipse may type; reads them from
 *             Neovim and passes them to the listeners, through its small {@code Neovim} and
 *             {@code Listeners} interfaces;</li>
 *         <li>{@code StateSnapshot}: reading the per-key state answer;</li>
 *         <li>{@code SessionEvents}: the mode, buffer change, cursor, selection and IDE action
 *             listeners (the session's {@code add...Listener} methods pass through to it);</li>
 *         <li>{@code StatusTracker}: status bar parts (showcmd, message, statusline) and macro
 *             recording, fired to listeners only when they change;</li>
 *         <li>{@code InputThread}: the single thread for all blocking RPC, which keeps keys in order;</li>
 *         <li>{@code Rpc}: waiting for answers, and Neovim's error text from a failed call.</li>
 *       </ul></li>
 *   <li>Value types passed to listeners: {@code CursorPos}, {@code VisualSelection},
 *       {@code BufferChange}; and {@code DocumentBuffer}, the handle of one document's buffer.</li>
 *   <li>{@code KeyRouter}: whether each key goes to Neovim or stays with Eclipse; the SWT listener
 *       ({@code editor/KeyInterceptor}) only feeds it. {@code NeovimSession} implements its small
 *       {@code KeyRouter.Neovim} interface, so tests use a fake session.</li>
 *   <li>Ports, the small parts of the session other packages depend on, so they can be tested with
 *       fakes: {@code BufferPort} (for {@code sync/BufferSynchronizer}), {@code DocumentBufferPort}
 *       (for {@code sync/UnattachedDocumentSync}), {@code CursorPort} (for
 *       {@code editor/CaretSync}), {@code KeyRouter.Neovim}; and the other direction, what core code
 *       and its callers need from Eclipse: {@code ClipboardPort}, {@code UiExecutor} (the UI thread;
 *       {@code editor/SwtUiExecutor} in the editor, {@code UiExecutor.IMMEDIATE} in tests).</li>
 *   <li>{@code EditorBuffers}: which Eclipse editors show each document's buffer, so when a buffer
 *       is shared, which editor follows a Neovim buffer switch, and when a buffer is wiped. Generic
 *       over the document and editor types, so it holds {@code IDocument} and {@code IEditorPart}
 *       in the plug-in without importing them.</li>
 *   <li>{@code ClipboardRegisters}: Neovim's {@code "+} and {@code "*} registers in the system
 *       clipboard, as Neovim's clipboard provider: which selection, lines to text and back, and the
 *       register type of what Neovim copied itself.</li>
 *   <li>{@code LuaScripts}: every Lua chunk run inside Neovim, with comments.</li>
 *   <li>{@code NotificationBridge}: decodes the msgpack-rpc notifications Neovim sends (buffer line
 *       events and the plug-in's own {@code eclipse_*} events), and answers its two requests, the
 *       clipboard paste and the save of a document Neovim writes.</li>
 * </ul>
 *
 * <p>See {@code docs/LEARNING.md}, part 2, for msgpack-rpc, {@code nvim_input}, blocking, and
 * buffer events.
 */
package io.github.jm6cnddxbwftaks.eclipseneovim.core;
