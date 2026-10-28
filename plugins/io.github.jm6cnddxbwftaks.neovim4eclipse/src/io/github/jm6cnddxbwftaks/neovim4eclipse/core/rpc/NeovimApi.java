// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The Neovim API functions the plug-in calls, and nothing more.
 *
 * <p>Each method sends one msgpack-rpc request and returns a future of the answer; the method names
 * are Neovim's own without the {@code nvim_} prefix, so {@link #bufSetLines} is
 * {@code nvim_buf_set_lines} and {@code :help nvim_buf_set_lines()} documents it. Buffers and
 * windows are passed as {@link Handle}s, so the interface is as flat as Neovim's C API.
 *
 * <p>Why the plug-in has its own interface: the code that uses it ({@code NeovimSession},
 * {@code DocumentBuffers}) then does not depend on how the messages travel, which is how the
 * neovim-java library could be swapped for our own client ({@code docs/design/RPC_CLIENT.md}); tests can
 * also fake it. {@link MsgpackNeovimApi} implements it. Adding a function is one method here and
 * one there.
 *
 * <p>Thread rules: any thread may call these methods, but waiting for a future blocks, so it is done
 * on the session's input thread ({@code Rpc.await}), never on the SWT UI thread. A failed call
 * completes the future exceptionally with Neovim's message ({@code E94: ...}) somewhere in the cause
 * chain ({@code Rpc.errorMessage} finds it).
 *
 * <p>Dictionaries that Neovim takes as options are plain {@code Map}s with Neovim's key names, as
 * {@code :help} documents them; for example {@link #uiAttach}'s {@code {"rgb": false}}.
 */
public interface NeovimApi {

    /**
     * {@code nvim_exec_lua(code, args)}: runs a Lua chunk; inside it, {@code ...} are the arguments.
     * The answer is what the chunk returns, decoded into plain Java values (lists, maps, strings,
     * numbers, booleans, {@code null}).
     */
    CompletableFuture<Object> execLua(String code, List<String> args);

    /** {@code nvim_command(command)}: runs an Ex command, as if typed after {@code :}. */
    CompletableFuture<Void> command(String command);

    /**
     * {@code nvim_input(keys)}: queues keys as if typed, in key notation ({@code "<Esc>dd"}).
     * Answers at once with the number of bytes queued, before Neovim has processed them.
     */
    CompletableFuture<Integer> input(String keys);

    /**
     * {@code nvim_set_client_info(name, version, type, methods, attributes)}: tells Neovim who is on
     * this channel; {@code :lua =vim.api.nvim_get_chan_info(n).client} shows it.
     *
     * @param version    {@code {"major": 1, "minor": 2, "patch": 3, "prerelease": "..."}}
     *                   ({@code prerelease} optional)
     * @param type       {@code "remote"}, {@code "ui"}, {@code "embedder"}, {@code "host"} or
     *                   {@code "plugin"}
     * @param methods    the RPC methods this client offers, by name; empty for us
     * @param attributes optional {@code website}, {@code license}, {@code logo}
     */
    CompletableFuture<Void> setClientInfo(String name, Map<String, Object> version, String type,
            Map<String, Object> methods, Map<String, Object> attributes);

    /**
     * {@code nvim_ui_attach(width, height, options)}: registers this channel as a UI, which makes
     * Neovim send {@code redraw} notifications (ignored here). Needed under {@code --embed}: without
     * a UI, Neovim does not process {@code nvim_input} reliably.
     *
     * @param options for example {@code {"rgb": false, "ext_cmdline": false}}; mind that {@code rgb}
     *                defaults to {@code true}
     */
    CompletableFuture<Void> uiAttach(int width, int height, Map<String, Object> options);

    /**
     * {@code nvim_ui_try_resize(width, height)}: asks Neovim to make this UI's screen
     * {@code width} × {@code height} cells. Neovim resizes its windows to fit (the screen is the
     * smallest of all attached UIs; the plug-in is the only one), so {@code &lines},
     * {@code &columns} and {@code winheight(0)} change, and {@code VimResized} fires. Example:
     * {@code uiTryResize(104, 41)} for an editor of 100 × 40 with a status line and line numbers
     * (docs/design/VIEWPORT.md).
     */
    CompletableFuture<Void> uiTryResize(int width, int height);

    /** {@code nvim_get_mode()}: answered even while Neovim waits for the rest of a command. */
    CompletableFuture<Mode> getMode();

    /** {@code nvim_set_var(name, value)}: sets the global variable {@code g:name}. */
    CompletableFuture<Void> setVar(String name, Object value);

    /**
     * The caller's own channel ID: {@code nvim_get_chan_info(0).id} (channel 0 means "this
     * channel"). Lua code passes it to {@code vim.rpcnotify} and {@code vim.rpcrequest} to reach the
     * plug-in. The only field of {@code nvim_get_chan_info} the plug-in reads.
     */
    CompletableFuture<Integer> getChannelId();

    /** {@code nvim_create_buf(listed, scratch)}: a new, empty buffer. */
    CompletableFuture<Handle> createBuf(boolean listed, boolean scratch);

    /** {@code nvim_set_current_buf(buffer)}: shows {@code buffer} in the current window. */
    CompletableFuture<Void> setCurrentBuf(Handle buffer);

    /**
     * {@code nvim_buf_attach(buffer, send_buffer, {})}: from now on Neovim sends an
     * {@code nvim_buf_lines_event} notification for every change to {@code buffer}; with
     * {@code sendBuffer}, the first one holds the whole buffer.
     */
    CompletableFuture<Boolean> bufAttach(Handle buffer, boolean sendBuffer);

    /** {@code nvim_buf_set_name(buffer, name)}: fails if another buffer has that name already. */
    CompletableFuture<Void> bufSetName(Handle buffer, String name);

    /**
     * {@code nvim_buf_delete(buffer, {force = force, unload = unload})}: wipes the buffer (with
     * {@code unload}, only unloads it); {@code force} discards unsaved changes.
     */
    CompletableFuture<Void> bufDelete(Handle buffer, boolean force, boolean unload);

    /**
     * {@code nvim_buf_get_lines(buffer, start, end, strict)}: lines {@code start} (0-based) up to
     * {@code end} (exclusive; -1 is the end of the buffer), without line endings.
     */
    CompletableFuture<List<String>> bufGetLines(Handle buffer, int start, int end, boolean strict);

    /**
     * {@code nvim_buf_set_lines(buffer, start, end, strict, lines)}: replaces lines {@code start} to
     * {@code end} (as in {@link #bufGetLines}) with {@code lines}. Example: {@code (buf, 1, 1, false,
     * ["x"])} inserts {@code x} as the second line.
     */
    CompletableFuture<Void> bufSetLines(Handle buffer, int start, int end, boolean strict, List<String> lines);

    /** {@code nvim_win_get_cursor(window)}: see {@link WinCursor} for the bases. */
    CompletableFuture<WinCursor> winGetCursor(Handle window);

    /** {@code nvim_win_set_cursor(window, [row, col])}: see {@link WinCursor} for the bases. */
    CompletableFuture<Void> winSetCursor(Handle window, WinCursor cursor);
}
