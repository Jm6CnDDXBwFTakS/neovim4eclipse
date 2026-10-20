// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc.Handle;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc.Incoming;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc.Mode;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc.MsgpackNeovimApi;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc.NeovimApi;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc.RpcChannel;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc.WinCursor;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.NvimExecutable;

/**
 * The same checks of every {@link NeovimApi} method, run against a real {@code nvim --embed} once
 * through each implementation: {@code NeovimJavaApi} (neovim-java, used today) and
 * {@link MsgpackNeovimApi} (our own client, {@code docs/RPC_CLIENT.md} step 6). A difference
 * between the two shows up here, before the plug-in switches (step 7).
 *
 * <p>The answers are compared by meaning, not by Java type: neovim-java decodes small integers as
 * {@code Integer}, ours always as {@code Long}, so {@link #numbersAsLong} normalizes them first.
 * Each test gets a fresh Neovim ({@code --clean}: no user config), so tests cannot affect each
 * other. Needs Neovim: {@code -Pintegration -Dnvim.path=/usr/bin/nvim}.
 */
@Tag("integration")
class NeovimApiContractTest {

    private static final String NVIM = System.getProperty("nvim.path", "nvim");

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

    /** The checks; each implementation's subclass only says how to connect. */
    abstract class Contract {

        NeovimApi api;

        abstract NeovimApi connect() throws Exception;

        abstract void disconnect();

        @BeforeEach
        void open() throws Exception {
            api = connect();
        }

        @AfterEach
        void close() {
            disconnect();
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
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
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
        void getCurrentWinIsAWindowId() throws Exception {
            Handle window = await(api.getCurrentWin());
            assertEquals(Handle.Kind.WINDOW, window.kind());
            assertEquals(window.id(), lua("return vim.api.nvim_get_current_win()"));
        }

        @Test
        void linesRoundTrip() throws Exception {
            Handle buffer = await(api.createBuf(false, true));
            await(api.bufSetLines(buffer, 0, -1, false, List.of("one", "twö", "")));
            assertEquals(List.of("one", "twö", ""), await(api.bufGetLines(buffer, 0, -1, false)));
            assertEquals(List.of("twö"), await(api.bufGetLines(buffer, 1, 2, true)));
        }

        @Test
        void bufSetNameNamesTheBuffer() throws Exception {
            Handle buffer = await(api.createBuf(true, true));
            await(api.bufSetName(buffer, "/tmp/contract-test.txt"));
            assertEquals("/tmp/contract-test.txt", lua("return vim.api.nvim_buf_get_name(" + buffer.id() + ")"));
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
            Handle window = await(api.getCurrentWin());
            await(api.winSetCursor(window, new WinCursor(2, 3))); // the second l: é is two bytes
            assertEquals(new WinCursor(2, 3), await(api.winGetCursor(window)));
        }
    }

    /** Through neovim-java, as the plug-in works today. */
    @Nested
    class NeovimJava extends Contract {

        private NeovimConnection connection;

        @Override
        NeovimApi connect() throws Exception {
            connection = NeovimConnection.open(NVIM, List.of("--clean"), "0.0.0");
            return connection.api();
        }

        @Override
        void disconnect() {
            connection.requestQuit();
            connection.close();
        }
    }

    /** Through our own client, as the plug-in will work from step 7. */
    @Nested
    class OwnClient extends Contract {

        private Process process;
        private RpcChannel channel;

        @Override
        NeovimApi connect() throws Exception {
            ProcessBuilder pb = new ProcessBuilder(NvimExecutable.embedCommand(NVIM, List.of("--clean")))
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            process = pb.start();
            channel = new RpcChannel(process.getInputStream(), process.getOutputStream(), new Incoming() {
                @Override
                public void notification(String method, List<?> params) {
                }

                @Override
                public Object request(String method, List<?> params) {
                    throw new UnsupportedOperationException(method);
                }
            }, "contract-test-rpc");
            channel.start();
            // As NeovimConnection.open does: without a UI attached, Neovim does not reliably
            // process nvim_input under --embed (the "blocking" test fails without it).
            NeovimApi api = new MsgpackNeovimApi(channel);
            await(api.uiAttach(120, 40, NeovimConnection.TERMINAL_UI));
            return api;
        }

        @Override
        void disconnect() {
            channel.closing();
            try {
                channel.notify("nvim_command", "qa!");
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (RuntimeException | InterruptedException e) {
                // already gone; destroy below
            }
            channel.close();
            process.destroyForcibly();
        }
    }
}
