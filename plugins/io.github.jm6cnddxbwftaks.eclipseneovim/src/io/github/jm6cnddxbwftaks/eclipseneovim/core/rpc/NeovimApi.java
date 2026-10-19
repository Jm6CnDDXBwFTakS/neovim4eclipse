// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc;

import java.util.List;
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
 * {@code DocumentBuffers}) then does not depend on the RPC library. Today
 * {@link NeovimJavaApi} implements it with neovim-java; a client of our own replaces it later
 * ({@code docs/RPC_CLIENT.md}). Adding a function is one method here and one in each implementation.
 *
 * <p>Thread rules: any thread may call these methods, but waiting for a future blocks, so it is done
 * on the session's input thread ({@code Rpc.await}), never on the SWT UI thread. A failed call
 * completes the future exceptionally with Neovim's message ({@code E94: ...}) somewhere in the cause
 * chain ({@code Rpc.errorMessage} finds it).
 *
 * <p>Two calls made only while connecting ({@code nvim_set_client_info}, {@code nvim_ui_attach}) are
 * not here yet: {@code NeovimConnection} still makes them with neovim-java directly (step 7 of the
 * plan moves them).
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

    /** {@code nvim_get_current_win()}: the current window. */
    CompletableFuture<Handle> getCurrentWin();

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
