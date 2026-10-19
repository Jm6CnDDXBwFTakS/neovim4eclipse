// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.ensarsarajcic.neovim.java.api.buffer.BufferStreamApi;
import com.ensarsarajcic.neovim.java.api.buffer.NeovimBufferApi;
import com.ensarsarajcic.neovim.java.api.types.api.DeleteBufferOptions;
import com.ensarsarajcic.neovim.java.api.types.api.VimCoords;
import com.ensarsarajcic.neovim.java.api.types.msgpack.Buffer;
import com.ensarsarajcic.neovim.java.api.types.msgpack.Window;
import com.ensarsarajcic.neovim.java.api.window.WindowStreamApi;
import com.ensarsarajcic.neovim.java.corerpc.reactive.ReactiveRpcStreamer;

/**
 * {@link NeovimApi} implemented with neovim-java: each method calls the matching neovim-java method
 * and converts its types to ours ({@code VimMode} to {@link Mode}, {@code NeovimBufferApi} to
 * {@link Handle}, ...).
 *
 * <p>An <em>adapter</em>, and temporary: it lets the rest of the plug-in use our interface while
 * neovim-java still does the RPC, and goes away when our own client replaces it (step 7 of
 * {@code docs/RPC_CLIENT.md}). Until then, this class and {@code NeovimConnection} are the only ones
 * that see neovim-java's API.
 *
 * <p>neovim-java models a buffer as an object with methods ({@code buffer.setLines(...)}); ours is a
 * plain {@link Handle}. To call a buffer method, {@link #buffer} wraps the handle's number in
 * neovim-java's {@code BufferStreamApi} again, which holds nothing but the number and the stream to
 * send on, so a new one per call costs nothing and needs no cache.
 */
public final class NeovimJavaApi implements NeovimApi {

    private final com.ensarsarajcic.neovim.java.api.NeovimApi api;
    /** The stream {@link #buffer} and {@link #window} send on: the one {@code api} uses. */
    private final ReactiveRpcStreamer streamer;

    /**
     * @param api      neovim-java's API, built on {@code streamer}
     * @param streamer the RPC stream under {@code api}
     */
    public NeovimJavaApi(com.ensarsarajcic.neovim.java.api.NeovimApi api, ReactiveRpcStreamer streamer) {
        this.api = api;
        this.streamer = streamer;
    }

    @Override
    public CompletableFuture<Object> execLua(String code, List<String> args) {
        return api.executeLua(code, args);
    }

    @Override
    public CompletableFuture<Void> command(String command) {
        return api.executeCommand(command);
    }

    @Override
    public CompletableFuture<Integer> input(String keys) {
        return api.input(keys);
    }

    @Override
    public CompletableFuture<Mode> getMode() {
        return api.getMode().thenApply(m -> m == null ? null : new Mode(m.getMode(), m.isBlocking()));
    }

    @Override
    public CompletableFuture<Void> setVar(String name, Object value) {
        return api.setVariable(name, value);
    }

    @Override
    public CompletableFuture<Integer> getChannelId() {
        return api.getChannelInfo(0).thenApply(info -> info.getId());
    }

    @Override
    public CompletableFuture<Handle> createBuf(boolean listed, boolean scratch) {
        return api.createBuffer(listed, scratch).thenApply(b -> Handle.buffer(b.get().getId()));
    }

    @Override
    public CompletableFuture<Void> setCurrentBuf(Handle buffer) {
        return api.setCurrentBuffer(new Buffer(buffer.id()));
    }

    @Override
    public CompletableFuture<Handle> getCurrentWin() {
        return api.getCurrentWindow().thenApply(w -> Handle.window(w.get().getId()));
    }

    @Override
    public CompletableFuture<Boolean> bufAttach(Handle buffer, boolean sendBuffer) {
        return buffer(buffer).attach(sendBuffer, Map.of());
    }

    @Override
    public CompletableFuture<Void> bufSetName(Handle buffer, String name) {
        return buffer(buffer).setName(name);
    }

    @Override
    public CompletableFuture<Void> bufDelete(Handle buffer, boolean force, boolean unload) {
        return buffer(buffer).delete(new DeleteBufferOptions(force, unload));
    }

    @Override
    public CompletableFuture<List<String>> bufGetLines(Handle buffer, int start, int end, boolean strict) {
        return buffer(buffer).getLines(start, end, strict);
    }

    @Override
    public CompletableFuture<Void> bufSetLines(Handle buffer, int start, int end, boolean strict, List<String> lines) {
        return buffer(buffer).setLines(start, end, strict, lines);
    }

    @Override
    public CompletableFuture<WinCursor> winGetCursor(Handle window) {
        return window(window).getCursor().thenApply(c -> c == null ? null : new WinCursor(c.getRow(), c.getCol()));
    }

    @Override
    public CompletableFuture<Void> winSetCursor(Handle window, WinCursor cursor) {
        return window(window).setCursor(new VimCoords(cursor.row(), cursor.col()));
    }

    private NeovimBufferApi buffer(Handle handle) {
        return new BufferStreamApi(streamer, new Buffer(handle.id()));
    }

    private WindowStreamApi window(Handle handle) {
        return new WindowStreamApi(streamer, new Window(handle.id()));
    }
}
