// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * {@link NeovimApi} over our own msgpack-rpc client ({@link RpcChannel}): each method is one
 * request with Neovim's function name and parameters, and a conversion of the answer.
 *
 * <p>Example: {@link #bufGetLines bufGetLines(Handle.buffer(3), 0, -1, false)} sends
 * {@code [0, msgid, "nvim_buf_get_lines", [<buffer 3>, 0, -1, false]]}; Neovim answers
 * {@code [1, msgid, nil, ["first", "second"]]}, and the future completes with that list.
 *
 * <p>Answers arrive as {@link MsgpackValues} decodes them (integers as {@code Long}, maps as
 * {@code Map}); the methods convert them to the types {@code NeovimApi} promises. An answer of a
 * different shape fails the future with {@link UnexpectedAnswerException}; an error answer with
 * {@link NeovimErrorException}, untouched. The conversions run on the channel's reader thread, so
 * they only look at the value and never block.
 *
 * <p>Options that Neovim takes as a dictionary (for example {@code nvim_buf_delete}'s
 * {@code {force = true}}) are sent as a {@code Map}; an empty one as {@code {}}.
 */
public final class MsgpackNeovimApi implements NeovimApi {

    private final RpcRequests rpc;

    /** @param rpc the connection, normally an {@link RpcChannel} */
    public MsgpackNeovimApi(RpcRequests rpc) {
        this.rpc = rpc;
    }

    @Override
    public CompletableFuture<Object> execLua(String code, List<String> args) {
        return rpc.request("nvim_exec_lua", code, args);
    }

    @Override
    public CompletableFuture<Void> command(String command) {
        return call(MsgpackNeovimApi::nothing, "nvim_command", command);
    }

    @Override
    public CompletableFuture<Integer> input(String keys) {
        return call(answer -> integer("nvim_input", answer), "nvim_input", keys);
    }

    @Override
    public CompletableFuture<Void> setClientInfo(String name, Map<String, Object> version, String type,
            Map<String, Object> methods, Map<String, Object> attributes) {
        return call(MsgpackNeovimApi::nothing, "nvim_set_client_info", name, version, type, methods, attributes);
    }

    @Override
    public CompletableFuture<Void> uiAttach(int width, int height, Map<String, Object> options) {
        return call(MsgpackNeovimApi::nothing, "nvim_ui_attach", width, height, options);
    }

    @Override
    public CompletableFuture<Void> uiTryResize(int width, int height) {
        return call(MsgpackNeovimApi::nothing, "nvim_ui_try_resize", width, height);
    }

    @Override
    public CompletableFuture<Mode> getMode() {
        return call(answer -> {
            Map<?, ?> map = map("nvim_get_mode", answer, "a map with mode and blocking");
            Object mode = map.get("mode");
            return new Mode(mode == null ? null : String.valueOf(mode), Boolean.TRUE.equals(map.get("blocking")));
        }, "nvim_get_mode");
    }

    @Override
    public CompletableFuture<Void> setVar(String name, Object value) {
        return call(MsgpackNeovimApi::nothing, "nvim_set_var", name, value);
    }

    @Override
    public CompletableFuture<Integer> getChannelId() {
        return call(answer -> integer("nvim_get_chan_info",
                map("nvim_get_chan_info", answer, "a map with id").get("id")), "nvim_get_chan_info", 0);
    }

    @Override
    public CompletableFuture<Handle> createBuf(boolean listed, boolean scratch) {
        return call(answer -> handle("nvim_create_buf", answer, Handle.Kind.BUFFER), "nvim_create_buf",
                listed, scratch);
    }

    @Override
    public CompletableFuture<Void> setCurrentBuf(Handle buffer) {
        return call(MsgpackNeovimApi::nothing, "nvim_set_current_buf", buffer);
    }

    @Override
    public CompletableFuture<Boolean> bufAttach(Handle buffer, boolean sendBuffer) {
        return call(answer -> {
            if (answer instanceof Boolean attached) {
                return attached;
            }
            throw new UnexpectedAnswerException("nvim_buf_attach", "a boolean", answer);
        }, "nvim_buf_attach", buffer, sendBuffer, Map.of());
    }

    @Override
    public CompletableFuture<Void> bufSetName(Handle buffer, String name) {
        return call(MsgpackNeovimApi::nothing, "nvim_buf_set_name", buffer, name);
    }

    @Override
    public CompletableFuture<Void> bufDelete(Handle buffer, boolean force, boolean unload) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("force", force);
        options.put("unload", unload);
        return call(MsgpackNeovimApi::nothing, "nvim_buf_delete", buffer, options);
    }

    @Override
    public CompletableFuture<List<String>> bufGetLines(Handle buffer, int start, int end, boolean strict) {
        return call(answer -> {
            List<?> lines = list("nvim_buf_get_lines", answer, "a list of lines");
            List<String> out = new ArrayList<>(lines.size());
            for (Object line : lines) {
                out.add(String.valueOf(line));
            }
            return out;
        }, "nvim_buf_get_lines", buffer, start, end, strict);
    }

    @Override
    public CompletableFuture<Void> bufSetLines(Handle buffer, int start, int end, boolean strict, List<String> lines) {
        return call(MsgpackNeovimApi::nothing, "nvim_buf_set_lines", buffer, start, end, strict, lines);
    }

    @Override
    public CompletableFuture<WinCursor> winGetCursor(Handle window) {
        return call(answer -> {
            List<?> pair = list("nvim_win_get_cursor", answer, "[row, col]");
            if (pair.size() != 2) {
                throw new UnexpectedAnswerException("nvim_win_get_cursor", "[row, col]", answer);
            }
            return new WinCursor(integer("nvim_win_get_cursor", pair.get(0)), integer("nvim_win_get_cursor", pair.get(1)));
        }, "nvim_win_get_cursor", window);
    }

    @Override
    public CompletableFuture<Void> winSetCursor(Handle window, WinCursor cursor) {
        return call(MsgpackNeovimApi::nothing, "nvim_win_set_cursor", window, List.of(cursor.row(), cursor.col()));
    }

    // ---- converting answers ----

    /** One request, its answer converted; a conversion that throws fails the future. */
    private <T> CompletableFuture<T> call(Function<Object, T> convert, String function, Object... params) {
        return rpc.request(function, params).thenApply(convert);
    }

    /** For functions that answer nil ({@code void} in {@code :help api}). */
    private static Void nothing(Object answer) {
        return null;
    }

    private static int integer(String function, Object answer) {
        if (answer instanceof Number n) {
            return Math.toIntExact(n.longValue());
        }
        throw new UnexpectedAnswerException(function, "an integer", answer);
    }

    private static Map<?, ?> map(String function, Object answer, String expected) {
        if (answer instanceof Map<?, ?> map) {
            return map;
        }
        throw new UnexpectedAnswerException(function, expected, answer);
    }

    private static List<?> list(String function, Object answer, String expected) {
        if (answer instanceof List<?> list) {
            return list;
        }
        throw new UnexpectedAnswerException(function, expected, answer);
    }

    private static Handle handle(String function, Object answer, Handle.Kind kind) {
        if (answer instanceof Handle handle && handle.kind() == kind) {
            return handle;
        }
        throw new UnexpectedAnswerException(function, "a " + kind.name().toLowerCase() + " handle", answer);
    }
}
