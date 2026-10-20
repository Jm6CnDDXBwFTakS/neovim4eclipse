// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

/**
 * A msgpack-rpc connection to Neovim over two streams (the stdin and stdout of {@code nvim --embed}):
 * sends our requests and notifications, matches Neovim's responses to our requests, and hands what
 * Neovim sends on its own to {@link Incoming}.
 *
 * <h2>The protocol</h2>
 * Every message is one MessagePack array ({@link MsgpackValues} encodes the values):
 * <pre>
 *   request       [0, msgid, method, params]   expects exactly one response with the same msgid
 *   response      [1, msgid, error, result]    error is nil, or Neovim's [type, message]
 *   notification  [2, method, params]          no answer
 * </pre>
 * Both sides send all three: Neovim answers our {@code nvim_input}, and we answer its
 * {@code vim.rpcrequest}. Messages are not interleaved, but the order of kinds is free: Neovim may
 * send notifications, or a request of its own, before the response we wait for. Example: our
 * {@code nvim_command("w")} makes Neovim send the request {@code eclipse_write} and wait for our
 * answer; only after it does the response to {@code nvim_command} come.
 *
 * <h2>Threads</h2>
 * <ul>
 *   <li><b>Writing:</b> any thread may call {@link #request} and {@link #notify}. Each message is
 *       packed into a buffer first and then written whole, under a lock, so two threads never mix
 *       their bytes and a value that cannot be encoded leaves nothing half-written.</li>
 *   <li><b>Reading:</b> one daemon thread per channel, started by {@link #start}. For each message
 *       it completes the matching future, or calls {@link Incoming} and, for a request, writes the
 *       answer. In arrival order, one at a time: while a handler runs, nothing else is read. So a
 *       handler must never wait for an answer from Neovim (it would wait for the very thread it
 *       blocks), and futures are completed on this thread: continuations ({@code thenApply}) must
 *       not block either. Callers wait for the futures on their own thread
 *       ({@code core/Rpc.await}).</li>
 * </ul>
 *
 * <h2>The end</h2>
 * When the input ends (Neovim exited) or breaks, the reader stops and every request still waiting
 * fails at once with {@link NeovimConnectionClosedException}, as does any later one. After
 * {@link #closing()} (we asked Neovim to exit) that end is expected and logged at FINE only;
 * before it, it is logged once at WARNING ("Neovim exited unexpectedly").
 *
 * <p>Logging: {@code java.util.logging}; at FINEST every message in and out is traced, which is the
 * way to study the traffic ({@code docs/LEARNING.md}).
 */
public final class RpcChannel implements RpcRequests, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RpcChannel.class.getName());

    private static final int REQUEST = 0;
    private static final int RESPONSE = 1;
    private static final int NOTIFICATION = 2;
    /** Neovim's error type 0 ("Exception"), used for the errors we answer its requests with. */
    private static final long EXCEPTION_ERROR = 0;

    private final InputStream in;
    private final OutputStream out;
    private final Incoming incoming;
    private final Thread reader;
    /** Guards {@link #out}: one whole message at a time. */
    private final Object writeLock = new Object();
    /**
     * Our requests still waiting for a response, by msgid. An entry is added before the request is
     * written (so even an instant answer finds it) and removed when its future completes in any way:
     * answered, failed, or cancelled by a caller who stopped waiting.
     */
    private final Map<Long, CompletableFuture<Object>> pending = new ConcurrentHashMap<>();
    /** msgids are unsigned 32-bit numbers in msgpack-rpc; they wrap around after 2^32 requests. */
    private final AtomicLong nextId = new AtomicLong();
    /** Set by {@link #closing()}: the end of the input is expected from now on. */
    private volatile boolean closing;
    /** Set when the connection has ended; no message is sent after it. */
    private volatile boolean closed;

    /**
     * @param in         Neovim's output (the process's stdout)
     * @param out        Neovim's input (the process's stdin)
     * @param incoming   receives Neovim's notifications and requests, on the reader thread
     * @param threadName the reader thread's name, as it shows in a thread dump
     */
    public RpcChannel(InputStream in, OutputStream out, Incoming incoming, String threadName) {
        this.in = in;
        this.out = out;
        this.incoming = incoming;
        this.reader = new Thread(this::readLoop, threadName);
        this.reader.setDaemon(true); // never keeps the IDE (or a test JVM) from exiting
    }

    /** Starts reading. Separate from the constructor, so no other thread sees a half-built channel. */
    public void start() {
        reader.start();
    }

    /**
     * Sends a request and returns a future of the result. Example:
     * {@code request("nvim_buf_get_lines", Handle.buffer(3), 0, -1, false)} completes with
     * {@code ["first line", "second line"]}.
     *
     * <p>The future fails with {@link NeovimErrorException} when Neovim refuses the call and with
     * {@link NeovimConnectionClosedException} when the connection ends first. Cancelling it (a
     * caller that gives up) forgets the request; a late answer is then dropped.
     *
     * @param params the arguments, of the types {@link MsgpackValues} accepts
     * @throws IllegalArgumentException if a parameter cannot be encoded (nothing is sent)
     */
    @Override
    public CompletableFuture<Object> request(String method, Object... params) {
        long id = nextId.getAndIncrement() & 0xFFFF_FFFFL;
        CompletableFuture<Object> answer = new CompletableFuture<>();
        if (closed) {
            answer.completeExceptionally(closedException(method));
            return answer;
        }
        // Not the future whenComplete returns: callers get and may cancel the original one.
        pending.put(id, answer);
        answer.whenComplete((result, failure) -> pending.remove(id, answer));
        byte[] message;
        try {
            message = encode(List.of(REQUEST, id, method, Arrays.asList(params)));
        } catch (IllegalArgumentException | IOException e) {
            answer.cancel(false);
            throw e instanceof IllegalArgumentException iae ? iae : new IllegalArgumentException(e);
        }
        try {
            write(message, method);
        } catch (NeovimConnectionClosedException e) {
            answer.completeExceptionally(e);
        }
        return answer;
    }

    /**
     * Sends a notification, which Neovim does not answer. Used where an answer would never come:
     * {@code nvim_command("qa!")} makes Neovim exit before it could reply.
     *
     * @throws IllegalArgumentException if a parameter cannot be encoded (nothing is sent)
     * @throws NeovimConnectionClosedException if the connection has ended or the write fails
     */
    public void notify(String method, Object... params) {
        byte[] message;
        try {
            message = encode(List.of(NOTIFICATION, method, Arrays.asList(params)));
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
        write(message, method);
    }

    /**
     * From now on the end of Neovim's output is expected: call it just before asking Neovim to
     * exit, so that the end is not reported as a crash.
     */
    public void closing() {
        closing = true;
    }

    /**
     * Ends the connection: closes both streams, which stops the reader, and fails every waiting
     * request. Does not end the Neovim process; its owner does. Safe to call more than once.
     */
    @Override
    public void close() {
        closing = true;
        closed = true;
        closeQuietly(out);
        closeQuietly(in);
        if (Thread.currentThread() != reader) {
            reader.interrupt(); // in case the stream ignores close() while a read blocks
        }
        failPending();
    }

    /** How many requests wait for an answer; for tests. */
    int pendingCount() {
        return pending.size();
    }

    // ---- writing ----

    private static byte[] encode(List<Object> message) throws IOException {
        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            MsgpackValues.pack(packer, message);
            return packer.toByteArray();
        }
    }

    private void write(byte[] message, String what) {
        synchronized (writeLock) {
            if (closed) {
                throw closedException(what);
            }
            try {
                out.write(message);
                out.flush();
            } catch (IOException e) {
                throw new NeovimConnectionClosedException("Could not send " + what + " to Neovim", e);
            }
        }
        LOG.finest(() -> "-> " + what + " (" + message.length + " bytes)");
    }

    // ---- reading (reader thread) ----

    private void readLoop() {
        Throwable failure = null;
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(in)) {
            while (unpacker.hasNext()) { // false at a clean end of the input
                dispatch(MsgpackValues.unpack(unpacker));
            }
        } catch (IOException | RuntimeException e) {
            // RuntimeException: above all msgpack-core's unchecked MessagePackException, also
            // thrown for input that ends in the middle of a message (see MsgpackValues.unpack);
            // and any bug in dispatching, so that waiting requests still fail instead of hanging.
            failure = e;
        }
        closed = true;
        if (closing) {
            LOG.log(Level.FINE, "Neovim connection ended", failure);
        } else {
            LOG.log(Level.WARNING, "Neovim exited unexpectedly", failure);
        }
        failPending();
    }

    private void dispatch(Object raw) {
        if (!(raw instanceof List<?> message) || message.isEmpty() || !(message.get(0) instanceof Long kind)) {
            LOG.warning(() -> "Ignored a message that is not msgpack-rpc: " + raw);
            return;
        }
        LOG.finest(() -> "<- " + raw);
        if (kind == RESPONSE && message.size() == 4 && message.get(1) instanceof Long id) {
            onResponse(id, message.get(2), message.get(3));
        } else if (kind == NOTIFICATION && message.size() == 3 && message.get(1) instanceof String method) {
            onNotification(method, params(message.get(2)));
        } else if (kind == REQUEST && message.size() == 4 && message.get(1) instanceof Long id
                && message.get(2) instanceof String method) {
            onRequest(id, method, params(message.get(3)));
        } else {
            LOG.warning(() -> "Ignored a malformed msgpack-rpc message: " + raw);
        }
    }

    private void onResponse(long id, Object error, Object result) {
        CompletableFuture<Object> answer = pending.remove(id);
        if (answer == null) {
            LOG.fine(() -> "Response " + id + " came after its caller stopped waiting; dropped");
        } else if (error != null) {
            answer.completeExceptionally(NeovimErrorException.fromResponse(error));
        } else {
            answer.complete(result);
        }
    }

    private void onNotification(String method, List<?> params) {
        try {
            incoming.notification(method, params);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Notification " + method + " from Neovim failed", e);
        }
    }

    /** Answers a request from Neovim, exactly once: with the result, or with an error. */
    private void onRequest(long id, String method, List<?> params) {
        byte[] response;
        try {
            response = encode(Arrays.asList(RESPONSE, id, null, incoming.request(method, params)));
        } catch (Exception e) {
            // Also an answer that cannot be encoded (IllegalArgumentException): Neovim still waits.
            LOG.log(Level.WARNING, "Request " + method + " from Neovim failed", e);
            try {
                response = encode(Arrays.asList(RESPONSE, id,
                        List.of(EXCEPTION_ERROR, String.valueOf(e.getMessage())), null));
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        try {
            write(response, "the answer to " + method);
        } catch (NeovimConnectionClosedException e) {
            LOG.log(Level.FINE, "Answering " + method + " failed (Neovim gone?)", e);
        }
    }

    private static List<?> params(Object raw) {
        return raw instanceof List<?> list ? list : raw == null ? List.of() : List.of(raw);
    }

    // ---- the end ----

    private void failPending() {
        for (CompletableFuture<Object> answer : new ArrayList<>(pending.values())) {
            answer.completeExceptionally(new NeovimConnectionClosedException("Neovim connection closed"));
        }
    }

    private static NeovimConnectionClosedException closedException(String what) {
        return new NeovimConnectionClosedException("Neovim connection closed; " + what + " not sent");
    }

    private static void closeQuietly(AutoCloseable stream) {
        try {
            stream.close();
        } catch (Exception e) {
            LOG.log(Level.FINE, "Closing a Neovim stream failed", e);
        }
    }
}
