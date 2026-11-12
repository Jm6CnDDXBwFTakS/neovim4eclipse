// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.logging.Level;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.ExpectedLog;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;

/**
 * {@link RpcChannel} without Neovim: the test plays Neovim on the other end of two pipes. It reads
 * what the channel writes ({@link #receive()}) and writes MessagePack back ({@link #send}), so each
 * test reads like a conversation, for example "the channel sends request 0; Neovim answers it".
 *
 * <p>{@code java.nio} pipes rather than {@code PipedInputStream}: those check which threads are
 * still alive and fail with "Write end dead" when the writing thread is not the one expected.
 * Messages decode with every integer as a {@code Long}, hence {@code 0L} and {@code 1L} below.
 */
class RpcChannelTest {

    /** What the channel handed to Incoming, as strings: "notification eclipse_mode [i]". */
    private final List<String> heard = new CopyOnWriteArrayList<>();
    /** Answers Neovim's requests in place of NotificationBridge. */
    private volatile BiFunction<String, List<?>, Object> requestHandler = (method, params) -> "answer to " + method;

    private RpcChannel channel;
    /** Neovim's side: what we write the channel reads, and the other way round. */
    private MessagePacker toChannel;
    private OutputStream toChannelStream;
    private MessageUnpacker fromChannel;

    @BeforeEach
    void connect() throws IOException {
        Pipe neovimToChannel = Pipe.open();
        Pipe channelToNeovim = Pipe.open();
        InputStream channelIn = Channels.newInputStream(neovimToChannel.source());
        OutputStream channelOut = Channels.newOutputStream(channelToNeovim.sink());
        toChannelStream = Channels.newOutputStream(neovimToChannel.sink());
        toChannel = MessagePack.newDefaultPacker(toChannelStream);
        fromChannel = MessagePack.newDefaultUnpacker(Channels.newInputStream(channelToNeovim.source()));
        channel = new RpcChannel(channelIn, channelOut, new Incoming() {
            @Override
            public void notification(String method, List<?> params) {
                heard.add("notification " + method + " " + params);
                if ("explode".equals(method)) {
                    throw new IllegalStateException("handler bug");
                }
                if ("linkage".equals(method)) {
                    throw new NoSuchMethodError("a class compiled against an older one");
                }
                if ("close".equals(method)) {
                    channel.close(); // from the reader thread itself, as a handler may
                }
            }

            @Override
            public Object request(String method, List<?> params) {
                heard.add("request " + method + " " + params);
                return requestHandler.apply(method, params);
            }
        }, "test-rpc");
        channel.start();
    }

    @AfterEach
    void disconnect() {
        channel.close();
    }

    /** Neovim sends one message. */
    private void send(Object... message) throws IOException {
        MsgpackValues.pack(toChannel, Arrays.asList(message));
        toChannel.flush();
    }

    /** Neovim sends one value as it is, which need not be a msgpack-rpc message at all. */
    private void sendValue(Object value) throws IOException {
        MsgpackValues.pack(toChannel, value);
        toChannel.flush();
    }

    /**
     * The next message the channel sent to Neovim. The one read in these tests that could wait
     * forever (a broken channel sends nothing), so it waits at most {@link TestWait}'s limit, as every
     * other wait here does, and then fails. A read cut short stays blocked on its thread until
     * {@link #disconnect} closes the channel, which ends the pipe.
     */
    private Object receive() throws Exception {
        CompletableFuture<Object> next = CompletableFuture.supplyAsync(() -> {
            try {
                return MsgpackValues.unpack(fromChannel);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        try {
            return next.get(TestWait.millis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("the channel sent nothing within " + TestWait.millis() + " ms", e);
        }
    }

    /** Neovim exits: its output ends. */
    private void endOfInput() throws IOException {
        toChannel.close();
        toChannelStream.close();
    }

    private static Object result(CompletableFuture<Object> future) throws Exception {
        return future.get(TestWait.millis(), TimeUnit.MILLISECONDS);
    }

    private static Throwable failure(CompletableFuture<Object> future) {
        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(TestWait.millis(), TimeUnit.MILLISECONDS));
        return e.getCause();
    }

    @Test
    void aRequestIsSentAsZeroMsgidMethodParams() throws Exception {
        CompletableFuture<Object> answer = channel.request("nvim_buf_get_lines", Handle.buffer(3), 0, -1, false);
        assertEquals(List.of(0L, 0L, "nvim_buf_get_lines", List.of(Handle.buffer(3), 0L, -1L, false)), receive());
        send(1, 0, null, List.of("one", "two"));
        assertEquals(List.of("one", "two"), result(answer));
        assertEquals(0, channel.pendingCount());
    }

    @Test
    void responsesAreMatchedByIdInAnyOrder() throws Exception {
        CompletableFuture<Object> first = channel.request("nvim_get_mode");
        CompletableFuture<Object> second = channel.request("nvim_get_current_win");
        receive();
        receive();
        send(1, 1, null, "second's answer"); // Neovim may answer in any order
        send(1, 0, null, "first's answer");
        assertEquals("first's answer", result(first));
        assertEquals("second's answer", result(second));
    }

    @Test
    void anErrorResponseFailsTheFutureWithNeovimsMessage() throws Exception {
        CompletableFuture<Object> answer = channel.request("nvim_command", "b 99");
        receive();
        send(1, 0, List.of(0, "Vim:E94: No matching buffer for 99"), null);
        NeovimErrorException e = assertInstanceOf(NeovimErrorException.class, failure(answer));
        assertEquals("Vim:E94: No matching buffer for 99", e.getMessage());
        assertEquals(0, e.type());
    }

    @Test
    void notificationsAndRequestsReachIncomingInOrderAndRequestsAreAnswered() throws Exception {
        send(2, "eclipse_mode", List.of("i"));
        send(0, 7, "eclipse_clipboard_paste", List.of("+"));
        send(2, "eclipse_cursor", List.of(1, 0));
        assertEquals(Arrays.asList(1L, 7L, null, "answer to eclipse_clipboard_paste"), receive());
        send(2, "sync", List.of()); // a notification sent after the others: when it is heard, so were they
        waitUntil(() -> heard.size() == 4);
        assertEquals(List.of("notification eclipse_mode [i]", "request eclipse_clipboard_paste [+]",
                "notification eclipse_cursor [1, 0]", "notification sync []"), heard);
    }

    @Test
    void aRequestWhoseHandlerThrowsIsAnsweredWithAnError() throws Exception {
        requestHandler = (method, params) -> {
            throw new UnsupportedOperationException("Unknown request: " + method);
        };
        try (ExpectedLog log = ExpectedLog.of(RpcChannel.class)) {
            send(0, 4, "eclipse_unknown", List.of());
            // Neovim waits for every request, so even a failure gets an answer: [type 0, message].
            assertEquals(Arrays.asList(1L, 4L, List.of(0L, "Unknown request: eclipse_unknown"), null), receive());
            log.assertLogged(Level.WARNING, "Request eclipse_unknown from Neovim failed");
        }
    }

    @Test
    void anAnswerThatCannotBeEncodedIsAnsweredWithAnError() throws Exception {
        requestHandler = (method, params) -> new Object();
        try (ExpectedLog log = ExpectedLog.of(RpcChannel.class)) {
            send(0, 5, "eclipse_write", List.of(3));
            List<?> response = (List<?>) receive();
            assertEquals(List.of(1L, 5L), response.subList(0, 2));
            assertTrue(String.valueOf(response.get(2)).contains("No MessagePack form"), response.toString());
        }
    }

    /** Our :w makes Neovim ask Eclipse to save, before it answers the :w itself. */
    @Test
    void neovimMayRequestSomethingWhileOurRequestWaits() throws Exception {
        CompletableFuture<Object> write = channel.request("nvim_command", "w");
        receive();
        send(0, 0, "eclipse_write", List.of(3)); // Neovim's own msgid 0: its ids are its own
        assertEquals(Arrays.asList(1L, 0L, null, "answer to eclipse_write"), receive());
        assertFalse(write.isDone(), "Neovim has not answered our request yet");
        send(1, 0, null, null);
        assertEquals(null, result(write));
    }

    @Test
    void notifySendsTwoMethodParamsAndExpectsNoAnswer() throws Exception {
        channel.notify("nvim_command", "qa!");
        assertEquals(List.of(2L, "nvim_command", List.of("qa!")), receive());
        assertEquals(0, channel.pendingCount());
    }

    @Test
    void aParameterThatCannotBeEncodedSendsNothing() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> channel.request("nvim_set_var", "x", new Object()));
        assertEquals(0, channel.pendingCount());
        // Nothing half-written: the next message is the first thing Neovim reads.
        channel.notify("nvim_command", "echo 1");
        assertEquals(List.of(2L, "nvim_command", List.of("echo 1")), receive());
    }

    @Test
    void aCancelledRequestIsForgottenAndItsLateAnswerDropped() throws Exception {
        CompletableFuture<Object> gaveUp = channel.request("nvim_command", "sleep 10");
        receive();
        gaveUp.cancel(false); // what a caller does when it stops waiting
        assertEquals(0, channel.pendingCount());
        send(1, 0, null, "too late");
        CompletableFuture<Object> next = channel.request("nvim_get_mode");
        receive();
        send(1, 1, null, "on time");
        assertEquals("on time", result(next));
    }

    @Test
    void aThrowingNotificationHandlerIsLoggedAndReadingGoesOn() throws Exception {
        try (ExpectedLog log = ExpectedLog.of(RpcChannel.class)) {
            send(2, "explode", List.of());
            send(2, "after", List.of());
            waitUntil(() -> heard.size() == 2);
            log.assertLogged(Level.WARNING, "Notification explode from Neovim failed");
        }
    }

    /** An Error from a handler (here a stale class's NoSuchMethodError) is one handler's bug too. */
    @Test
    void aNotificationHandlerThatThrowsAnErrorIsLoggedAndReadingGoesOn() throws Exception {
        try (ExpectedLog log = ExpectedLog.of(RpcChannel.class)) {
            send(2, "linkage", List.of());
            send(2, "after", List.of());
            waitUntil(() -> heard.size() == 2);
            log.assertLogged(Level.WARNING, "Notification linkage from Neovim failed");
        }
    }

    /**
     * The same Error at every notification (a stale class in the redraw handler fails at each
     * redraw): logged in full once, then counted, not a WARNING with a stack trace each time.
     */
    @Test
    void aHandlerThatFailsAgainAndAgainIsLoggedInFullOnce() throws Exception {
        try (ExpectedLog log = ExpectedLog.of(RpcChannel.class)) {
            for (int i = 0; i < 12; i++) {
                send(2, "linkage", List.of());
            }
            send(2, "after", List.of());
            waitUntil(() -> heard.size() == 13);
            log.assertLogged(Level.WARNING, "Notification linkage from Neovim failed");
            log.assertLogged(Level.WARNING, "Notification linkage from Neovim failed: 10 times so far");
        }
    }

    /**
     * A handler that overflows the stack: Neovim, which waits for the answer, still gets one, and
     * then the connection ends as if Neovim had exited: a waiting request fails at once rather
     * than after its timeout, and so does every later one. The error itself is thrown again on the
     * reader thread ({@link #readerDeath}).
     */
    @Test
    void aRequestHandlerThatOverflowsTheStackIsAnsweredAndEndsTheConnection() throws Exception {
        AtomicReference<Throwable> died = readerDeath();
        requestHandler = (method, params) -> {
            throw new StackOverflowError("deep");
        };
        try (ExpectedLog log = ExpectedLog.of(RpcChannel.class)) {
            CompletableFuture<Object> waiting = channel.request("nvim_get_mode");
            receive();
            send(0, 4, "eclipse_deep", List.of());
            assertEquals(Arrays.asList(1L, 4L, List.of(0L, "deep"), null), receive());
            assertInstanceOf(NeovimConnectionClosedException.class, failure(waiting));
            assertInstanceOf(NeovimConnectionClosedException.class, failure(channel.request("nvim_get_mode")));
            log.assertLogged(Level.SEVERE, "Reading from Neovim failed; the connection is closed");
            waitUntil(() -> died.get() instanceof StackOverflowError);
        }
    }

    /**
     * Input nested deeper than the reader's stack: {@code 91} (an array of one element) a million
     * times. The StackOverflowError ends the reader, and the waiting request fails at once. The
     * bytes are written on a thread of their own: once the reader is gone, nobody drains the pipe.
     */
    @Test
    void inputNestedTooDeepEndsTheConnectionAndFailsWaitingRequests() throws Exception {
        AtomicReference<Throwable> died = readerDeath();
        try (ExpectedLog log = ExpectedLog.of(RpcChannel.class)) {
            CompletableFuture<Object> waiting = channel.request("nvim_get_mode");
            receive();
            byte[] nested = new byte[1_000_000];
            Arrays.fill(nested, (byte) 0x91);
            Thread writer = new Thread(() -> {
                try {
                    toChannelStream.write(nested);
                } catch (IOException expected) {
                    // the pipe closes when the test ends
                }
            }, "test-nested-writer");
            writer.setDaemon(true);
            writer.start();
            assertInstanceOf(NeovimConnectionClosedException.class, failure(waiting));
            log.assertLogged(Level.SEVERE, "Reading from Neovim failed; the connection is closed");
            waitUntil(() -> died.get() instanceof StackOverflowError);
        }
    }

    /**
     * Catches the error that ends the reader in a test that ends it on purpose. The reader throws
     * a {@link VirtualMachineError} again after its cleanup, so that it is not lost; without this,
     * the JVM's default handler would print it, and a stack overflow's trace is a thousand
     * identical lines in every build's output. The test then checks that the error did arrive.
     */
    private AtomicReference<Throwable> readerDeath() {
        AtomicReference<Throwable> died = new AtomicReference<>();
        channel.whenReaderDies((thread, error) -> died.set(error));
        return died;
    }

    @Test
    void aMalformedMessageIsIgnoredAndReadingGoesOn() throws Exception {
        try (ExpectedLog log = ExpectedLog.of(RpcChannel.class)) {
            send(9, "what");
            send(2, "after", List.of());
            waitUntil(() -> heard.size() == 1);
            log.assertLogged(Level.WARNING, "malformed");
        }
    }

    @Test
    void theExpectedEndAfterClosingLogsNoWarningAndFailsWaitingRequests() throws Exception {
        try (ExpectedLog log = ExpectedLog.of(RpcChannel.class)) {
            CompletableFuture<Object> waiting = channel.request("nvim_command", "qa!");
            receive();
            channel.closing();
            endOfInput();
            assertInstanceOf(NeovimConnectionClosedException.class, failure(waiting));
            log.assertNothingAtOrAbove(Level.WARNING);
        }
    }

    @Test
    void anUnexpectedEndIsLoggedAsAWarningAndFailsWaitingRequests() throws Exception {
        try (ExpectedLog log = ExpectedLog.of(RpcChannel.class)) {
            CompletableFuture<Object> waiting = channel.request("nvim_get_mode");
            receive();
            endOfInput(); // Neovim crashed
            assertInstanceOf(NeovimConnectionClosedException.class, failure(waiting));
            log.assertLogged(Level.WARNING, "Neovim exited unexpectedly");
        }
    }

    @Test
    void anEndInTheMiddleOfAMessageCountsAsTheEnd() throws Exception {
        try (ExpectedLog log = ExpectedLog.of(RpcChannel.class)) {
            CompletableFuture<Object> waiting = channel.request("nvim_get_mode");
            receive();
            toChannelStream.write(new byte[] { (byte) 0x94, 0x01 }); // [1, ... and then nothing
            channel.closing();
            endOfInput();
            assertInstanceOf(NeovimConnectionClosedException.class, failure(waiting));
            log.assertNothingAtOrAbove(Level.WARNING);
        }
    }

    @Test
    void afterCloseNothingIsSent() throws Exception {
        CompletableFuture<Object> waiting = channel.request("nvim_get_mode");
        receive();
        channel.close();
        assertInstanceOf(NeovimConnectionClosedException.class, failure(waiting));
        assertInstanceOf(NeovimConnectionClosedException.class, failure(channel.request("nvim_get_mode")));
        assertThrows(NeovimConnectionClosedException.class, () -> channel.notify("nvim_command", "qa!"));
        channel.close(); // twice is fine
    }

    // ---- messages that are not what msgpack-rpc allows ----

    @Test
    void aValueThatIsNotAMessageIsIgnoredAndReadingGoesOn() throws Exception {
        try (ExpectedLog log = ExpectedLog.of(RpcChannel.class)) {
            sendValue("hello"); // not an array
            sendValue(List.of()); // an empty array
            sendValue(List.of("two", "x", List.of())); // the type is not a number
            send(2, "after", List.of());
            waitUntil(() -> heard.size() == 1);
            log.assertLogged(Level.WARNING, "Ignored a message that is not msgpack-rpc: hello");
            log.assertLogged(Level.WARNING, "Ignored a message that is not msgpack-rpc: []");
            log.assertLogged(Level.WARNING, "Ignored a message that is not msgpack-rpc: [two, x, []]");
        }
    }

    /** The right type, the wrong shape: one malformed message of each type, then one that is fine. */
    @Test
    void eachTypeWithTheWrongShapeIsMalformed() throws Exception {
        try (ExpectedLog log = ExpectedLog.of(RpcChannel.class)) {
            send(1, 0, null); // a response needs 4 elements
            send(1, "zero", null, "x"); // ... and a number as its msgid
            send(2, 5, List.of()); // a notification needs a method name
            send(2, "a", List.of(), "extra"); // ... and exactly 3 elements
            send(0, "seven", "m", List.of()); // a request needs a number as its msgid
            send(0, 7, 8, List.of()); // ... and a method name
            send(0, 7, "m"); // ... and 4 elements
            send(2, "after", List.of());
            waitUntil(() -> heard.size() == 1);
            assertEquals(List.of("notification after []"), heard);
            log.assertLogged(Level.WARNING, "Ignored a malformed msgpack-rpc message: [1, 0, null]");
            log.assertLogged(Level.WARNING, "Ignored a malformed msgpack-rpc message: [0, 7, m]");
        }
    }

    @Test
    void paramsThatAreNotAnArrayReachIncomingAsAList() throws Exception {
        send(2, "one", "single"); // a lone value: a list of that value
        send(2, "none", null); // nil: no params
        waitUntil(() -> heard.size() == 2);
        assertEquals(List.of("notification one [single]", "notification none []"), heard);
    }

    @Test
    void aResponseNobodyWaitsForIsDroppedQuietly() throws Exception {
        try (ExpectedLog log = ExpectedLog.of(RpcChannel.class, Level.FINE)) {
            send(1, 42, null, "for whom?");
            CompletableFuture<Object> next = channel.request("nvim_get_mode");
            receive();
            send(1, 0, null, "on time");
            assertEquals("on time", result(next));
            log.assertLogged(Level.FINE, "Response 42 came after its caller stopped waiting; dropped");
            log.assertNothingAtOrAbove(Level.WARNING);
        }
    }

    // ---- a connection that breaks while writing or closing ----

    /** An output stream whose every write and close fails, as a pipe does once Neovim is gone. */
    private static final class BrokenOutput extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            throw new IOException("Broken pipe");
        }

        @Override
        public void close() throws IOException {
            throw new IOException("already closed");
        }
    }

    /** A second channel that reads from a pipe the test writes, and writes to {@link BrokenOutput}. */
    private RpcChannel brokenChannel(Pipe input) {
        RpcChannel broken = new RpcChannel(Channels.newInputStream(input.source()), new BrokenOutput(),
                new Incoming() {
                    @Override
                    public void notification(String method, List<?> params) {
                        heard.add("notification " + method + " " + params);
                    }

                    @Override
                    public Object request(String method, List<?> params) {
                        heard.add("request " + method + " " + params);
                        return "answer";
                    }
                }, "test-rpc-broken");
        broken.start();
        return broken;
    }

    @Test
    void aRequestThatCannotBeWrittenFailsAsClosed() throws Exception {
        RpcChannel broken = brokenChannel(Pipe.open());
        try {
            CompletableFuture<Object> answer = broken.request("nvim_get_mode");
            NeovimConnectionClosedException e =
                    assertInstanceOf(NeovimConnectionClosedException.class, failure(answer));
            assertEquals("Could not send nvim_get_mode to Neovim", e.getMessage());
            assertEquals("Broken pipe", e.getCause().getMessage());
            assertEquals(0, broken.pendingCount());
        } finally {
            broken.close();
        }
    }

    @Test
    void aNotificationThatCannotBeWrittenThrows() throws Exception {
        RpcChannel broken = brokenChannel(Pipe.open());
        try {
            NeovimConnectionClosedException e = assertThrows(NeovimConnectionClosedException.class,
                    () -> broken.notify("nvim_command", "qa!"));
            assertEquals("Could not send nvim_command to Neovim", e.getMessage());
        } finally {
            broken.close();
        }
    }

    /** Neovim asked, then went away before the answer: nothing to do but note it. */
    @Test
    void anAnswerThatCannotBeWrittenIsOnlyNoted() throws Exception {
        Pipe input = Pipe.open();
        RpcChannel broken = brokenChannel(input);
        MessagePacker neovim = MessagePack.newDefaultPacker(Channels.newOutputStream(input.sink()));
        try (ExpectedLog log = ExpectedLog.of(RpcChannel.class, Level.FINE)) {
            MsgpackValues.pack(neovim, List.of(0, 3, "eclipse_write", List.of(1)));
            MsgpackValues.pack(neovim, List.of(2, "after", List.of()));
            neovim.flush();
            waitUntil(() -> heard.size() == 2); // the reader went on to the next message
            log.assertLogged(Level.FINE, "Answering eclipse_write failed (Neovim gone?)");
            log.assertNothingAtOrAbove(Level.WARNING);
        } finally {
            broken.close();
        }
    }

    @Test
    void streamsThatFailToCloseAreNotedAndCloseStillFinishes() throws Exception {
        Pipe input = Pipe.open();
        RpcChannel broken = brokenChannel(input);
        try (ExpectedLog log = ExpectedLog.of(RpcChannel.class, Level.FINE)) {
            broken.close();
            log.assertLogged(Level.FINE, "Closing a Neovim stream failed");
            assertThrows(NeovimConnectionClosedException.class, () -> broken.notify("nvim_command", "qa!"));
        }
    }

    @Test
    void aHandlerMayCloseTheChannelFromTheReaderThread() throws Exception {
        CompletableFuture<Object> waiting = channel.request("nvim_get_mode");
        receive();
        send(2, "close", List.of()); // the handler closes the channel while the reader runs it
        assertInstanceOf(NeovimConnectionClosedException.class, failure(waiting));
        assertThrows(NeovimConnectionClosedException.class, () -> channel.notify("nvim_command", "qa!"));
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TestWait.millis());
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + TestWait.millis() + " ms");
            }
            Thread.sleep(5);
        }
    }
}
