// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Handle;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Mode;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.MsgpackNeovimApi;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.NeovimApi;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.RpcChannel;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.WinCursor;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

/**
 * Every {@link NeovimApi} method against a real {@code nvim --embed}, through our own client
 * ({@link MsgpackNeovimApi} over {@link RpcChannel}), connected the way the plug-in connects.
 *
 * <p>Until the switch away from neovim-java ({@code docs/design/RPC_CLIENT.md}, step 7) this was a
 * <em>contract test</em>: the same checks ran through both clients, and had to pass on both. The
 * checks stayed; {@link #numbersAsLong} still compares numbers by value, so a check can say
 * {@code 5L} whatever integer type an answer has. Each test gets a fresh Neovim ({@code --clean}:
 * no user config). Needs Neovim: {@code -Pintegration -Dnvim.path=/usr/bin/nvim}.
 */
@Tag("integration")
class NeovimApiIntegrationTest {

    private static final String NVIM = TestNeovim.PATH;

    /** Waits for an answer, as the plug-in does ({@link Rpc#await}). */
    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return Rpc.await(future);
    }

    /** {@code 3} and {@code 3L}, and lists and maps of them, made comparable. */
    static Object numbersAsLong(Object value) {
        if (value instanceof Number n && !(value instanceof Double) && !(value instanceof Float)) {
            return n.longValue();
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object element : list) {
                out.add(numbersAsLong(element));
            }
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(numbersAsLong(k), numbersAsLong(v)));
            return out;
        }
        return value;
    }

    private NeovimConnection connection;
    private NeovimApi api;

    @BeforeEach
    void open() throws Exception {
        connection = NeovimConnection.open(NVIM, List.of("--clean"), "0.0.0");
        api = connection.api();
    }

    @AfterEach
    void close() {
        connection.requestQuit();
        connection.close();
    }

    private Object lua(String code) throws Exception {
        return numbersAsLong(await(api.execLua(code, List.of())));
    }

    @Test
    void execLuaReturnsPlainValuesAndTakesArguments() throws Exception {
        assertEquals(List.of(1L, "a", true), lua("return {1, 'a', true}"));
        assertEquals(Map.of("x", 2L), lua("return {x = 2}"));
        assertEquals(1.5, lua("return 1.5"));
        assertEquals("ab", await(api.execLua("return select(1, ...) .. select(2, ...)", List.of("a", "b"))));
    }

    @Test
    void commandRunsAnExCommand() throws Exception {
        await(api.command("let g:contract = 5"));
        assertEquals(5L, lua("return vim.g.contract"));
    }

    @Test
    void aFailedCallCarriesNeovimsMessage() {
        Exception e = assertThrows(Exception.class, () -> await(api.command("NoSuchCommand")));
        assertTrue(Rpc.errorMessage(e).startsWith("E492:"), Rpc.errorMessage(e));
    }

    @Test
    void setClientInfoIsWhatNeovimShowsForTheChannel() throws Exception {
        int id = await(api.getChannelId());
        await(api.setClientInfo("contract", Map.of("major", 1, "minor", 2, "patch", 3, "prerelease", "q"),
                "remote", Map.of(), Map.of("website", "https://example.org")));
        Object client = lua("return vim.api.nvim_get_chan_info(" + id + ").client");
        assertEquals(Map.of("name", "contract", "type", "remote", "methods", Map.of(),
                "attributes", Map.of("website", "https://example.org"),
                "version", Map.of("major", 1L, "minor", 2L, "patch", 3L, "prerelease", "q")), client);
    }

    @Test
    void inputAnswersTheBytesQueued() throws Exception {
        assertEquals(2, await(api.input("ix")));
    }

    @Test
    void getModeIsNormalAndNotBlockingAtStart() throws Exception {
        assertEquals(new Mode("n", false), await(api.getMode()));
    }

    /**
     * After {@code f}, Neovim waits inside the command for the character to find: blocked. (Not
     * after {@code d}: an operator returns to the main loop, mode {@code no}, not blocking.)
     * nvim_input only queues the key, so poll until Neovim has read it.
     */
    @Test
    void getModeIsBlockingWhileACommandWaitsForItsArgument() throws Exception {
        await(api.input("f"));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TestWait.millis());
        Mode mode = await(api.getMode());
        while (!mode.blocking() && System.nanoTime() < deadline) {
            Thread.sleep(10);
            mode = await(api.getMode());
        }
        assertEquals(new Mode("n", true), mode);
    }

    @Test
    void setVarSetsAGlobal() throws Exception {
        await(api.setVar("contract", 3));
        assertEquals(3L, lua("return vim.g.contract"));
    }

    @Test
    void getChannelIdIsOurChannel() throws Exception {
        int id = await(api.getChannelId());
        assertTrue(id > 0, "channel " + id);
        // Inside nvim_exec_lua, channel 0 does not name the caller; ask for ours by number.
        assertEquals("stdio", lua("return vim.api.nvim_get_chan_info(" + id + ").stream"));
    }

    @Test
    void createBufAndSetCurrentBuf() throws Exception {
        Handle buffer = await(api.createBuf(true, true));
        assertEquals(Handle.Kind.BUFFER, buffer.kind());
        await(api.setCurrentBuf(buffer));
        assertEquals(buffer.id(), lua("return vim.api.nvim_get_current_buf()"));
    }

    @Test
    void linesRoundTrip() throws Exception {
        Handle buffer = await(api.createBuf(false, true));
        await(api.bufSetLines(buffer, 0, -1, false, List.of("one", "twö", "")));
        assertEquals(List.of("one", "twö", ""), await(api.bufGetLines(buffer, 0, -1, false)));
        assertEquals(List.of("twö"), await(api.bufGetLines(buffer, 1, 2, true)));
    }

    /**
     * The name is in a temporary folder that exists on every system ({@code /tmp} does not on
     * Windows), given with its links already resolved: Neovim resolves links in the directory of a
     * name (on macOS the temporary folder under {@code /var} is really under {@code /private/var}),
     * and Windows can report the folder with a short {@code RUNNER~1} part, which resolving expands.
     */
    @Test
    void bufSetNameNamesTheBuffer(@TempDir Path dir) throws Exception {
        Handle buffer = await(api.createBuf(true, true));
        String name = dir.toRealPath().resolve("contract-test.txt").toString();
        await(api.bufSetName(buffer, name));
        assertEquals(name, lua("return vim.api.nvim_buf_get_name(" + buffer.id() + ")"));
    }

    @Test
    void bufAttachSucceeds() throws Exception {
        Handle buffer = await(api.createBuf(true, true));
        assertEquals(true, await(api.bufAttach(buffer, false)));
    }

    @Test
    void bufDeleteWipesTheBuffer() throws Exception {
        Handle buffer = await(api.createBuf(true, true));
        await(api.bufSetLines(buffer, 0, -1, false, List.of("unsaved")));
        await(api.bufDelete(buffer, true, false));
        assertEquals(false, lua("return vim.api.nvim_buf_is_valid(" + buffer.id() + ")"));
    }

    @Test
    void cursorRoundTripsWithAByteColumn() throws Exception {
        Handle buffer = await(api.createBuf(false, true));
        await(api.setCurrentBuf(buffer));
        await(api.bufSetLines(buffer, 0, -1, false, List.of("first", "héllo")));
        // Window 0 is the current window (Handle.CURRENT_WINDOW).
        await(api.winSetCursor(Handle.CURRENT_WINDOW, new WinCursor(2, 3))); // the second l: é is two bytes
        assertEquals(new WinCursor(2, 3), await(api.winGetCursor(Handle.CURRENT_WINDOW)));
        assertEquals(List.of(2L, 3L), lua("return vim.api.nvim_win_get_cursor(vim.api.nvim_get_current_win())"));
    }
}
