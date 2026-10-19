// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

/**
 * {@link MsgpackNeovimApi} without Neovim: a recording {@link RpcRequests} keeps each call as
 * {@code [function, params...]} and returns the answer the test set, as decoded by
 * {@link MsgpackValues} (integers as {@code Long}). So each test reads "this method sends this
 * request, and turns this answer into that value".
 */
class NeovimApiTest {

    private final List<List<Object>> sent = new ArrayList<>();
    /** The next answer: a value, or a Throwable to fail with. */
    private Object answer;
    private final NeovimApi api = new MsgpackNeovimApi((method, params) -> {
        List<Object> call = new ArrayList<>();
        call.add(method);
        call.addAll(Arrays.asList(params));
        sent.add(call);
        return answer instanceof Throwable t ? CompletableFuture.failedFuture(t)
                : CompletableFuture.completedFuture(answer);
    });

    private static <T> T get(CompletableFuture<T> future) throws Exception {
        return future.get();
    }

    private static Throwable failure(CompletableFuture<?> future) {
        return assertThrows(ExecutionException.class, future::get).getCause();
    }

    private void assertSent(Object... call) {
        assertEquals(List.of(Arrays.asList(call)), sent);
    }

    @Test
    void execLuaSendsTheCodeAndItsArguments() throws Exception {
        answer = List.of(1L, "a");
        assertEquals(List.of(1L, "a"), get(api.execLua("return {...}", List.of("x"))));
        assertSent("nvim_exec_lua", "return {...}", List.of("x"));
    }

    @Test
    void voidFunctionsCompleteWithNull() throws Exception {
        assertNull(get(api.command("echo 1")));
        assertNull(get(api.setVar("eclipse", 1)));
        assertNull(get(api.setCurrentBuf(Handle.buffer(2))));
        assertNull(get(api.bufSetName(Handle.buffer(2), "/tmp/a.txt")));
        assertNull(get(api.bufSetLines(Handle.buffer(2), 0, -1, false, List.of("x"))));
        assertEquals(List.of(
                List.of("nvim_command", "echo 1"),
                List.of("nvim_set_var", "eclipse", 1),
                List.of("nvim_set_current_buf", Handle.buffer(2)),
                List.of("nvim_buf_set_name", Handle.buffer(2), "/tmp/a.txt"),
                List.of("nvim_buf_set_lines", Handle.buffer(2), 0, -1, false, List.of("x"))), sent);
    }

    @Test
    void inputAnswersTheNumberOfBytes() throws Exception {
        answer = 2L;
        assertEquals(2, get(api.input("dd")));
        assertSent("nvim_input", "dd");
    }

    @Test
    void getModeReadsTheMap() throws Exception {
        Map<String, Object> mode = new LinkedHashMap<>();
        mode.put("mode", "no");
        mode.put("blocking", true);
        answer = mode;
        assertEquals(new Mode("no", true), get(api.getMode()));
        assertSent("nvim_get_mode");
    }

    @Test
    void getChannelIdAsksForChannelZeroAndReadsItsId() throws Exception {
        answer = Map.of("id", 3L, "mode", "rpc");
        assertEquals(3, get(api.getChannelId()));
        assertSent("nvim_get_chan_info", 0);
    }

    @Test
    void createBufAndGetCurrentWinAnswerHandles() throws Exception {
        answer = Handle.buffer(5);
        assertEquals(Handle.buffer(5), get(api.createBuf(true, false)));
        answer = Handle.window(1000);
        assertEquals(Handle.window(1000), get(api.getCurrentWin()));
        assertEquals(List.of(List.of("nvim_create_buf", true, false), List.of("nvim_get_current_win")), sent);
    }

    @Test
    void bufAttachSendsTheFlagAndNoOptions() throws Exception {
        answer = true;
        assertEquals(true, get(api.bufAttach(Handle.buffer(1), true)));
        assertSent("nvim_buf_attach", Handle.buffer(1), true, Map.of());
    }

    @Test
    void bufDeleteSendsItsOptionsAsAMap() throws Exception {
        get(api.bufDelete(Handle.buffer(4), true, false));
        assertSent("nvim_buf_delete", Handle.buffer(4), Map.of("force", true, "unload", false));
    }

    @Test
    void bufGetLinesAnswersStrings() throws Exception {
        answer = List.of("one", "two");
        assertEquals(List.of("one", "two"), get(api.bufGetLines(Handle.buffer(1), 0, -1, true)));
        assertSent("nvim_buf_get_lines", Handle.buffer(1), 0, -1, true);
    }

    @Test
    void cursorIsRowAndColumn() throws Exception {
        answer = List.of(2L, 3L);
        assertEquals(new WinCursor(2, 3), get(api.winGetCursor(Handle.window(1000))));
        answer = null;
        get(api.winSetCursor(Handle.window(1000), new WinCursor(4, 5)));
        assertEquals(List.of(List.of("nvim_win_get_cursor", Handle.window(1000)),
                List.of("nvim_win_set_cursor", Handle.window(1000), List.of(4, 5))), sent);
    }

    @Test
    void anAnswerOfTheWrongShapeFailsWithUnexpectedAnswer() {
        answer = "not a map";
        UnexpectedAnswerException e = assertInstanceOf(UnexpectedAnswerException.class, failure(api.getMode()));
        assertTrue(e.getMessage().startsWith("nvim_get_mode answered not a map (String)"), e.getMessage());

        answer = Handle.window(1000); // a window where a buffer was promised
        assertInstanceOf(UnexpectedAnswerException.class, failure(api.createBuf(true, true)));
        answer = List.of(1L);
        assertInstanceOf(UnexpectedAnswerException.class, failure(api.winGetCursor(Handle.window(1000))));
        answer = null;
        assertInstanceOf(UnexpectedAnswerException.class, failure(api.input("x")));
        assertInstanceOf(UnexpectedAnswerException.class, failure(api.bufAttach(Handle.buffer(1), false)));
    }

    @Test
    void neovimsErrorPassesThroughUntouched() {
        NeovimErrorException refused = new NeovimErrorException(0, "Vim:E94: No matching buffer for 99");
        answer = refused;
        assertEquals(refused, failure(api.command("b 99")));
    }
}
