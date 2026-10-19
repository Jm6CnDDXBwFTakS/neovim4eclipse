// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ensarsarajcic.neovim.java.api.NeovimStreamApi;
import com.ensarsarajcic.neovim.java.api.types.api.ClientAttributes;
import com.ensarsarajcic.neovim.java.api.types.api.ClientType;
import com.ensarsarajcic.neovim.java.api.types.api.ClientVersionInfo;
import com.ensarsarajcic.neovim.java.api.types.api.UiOptions;
import com.ensarsarajcic.neovim.java.api.util.ObjectMappers;
import com.ensarsarajcic.neovim.java.corerpc.client.ProcessRpcConnection;
import com.ensarsarajcic.neovim.java.corerpc.message.NotificationMessage;
import com.ensarsarajcic.neovim.java.corerpc.message.ResponseMessage;
import com.ensarsarajcic.neovim.java.corerpc.message.RpcError;
import com.ensarsarajcic.neovim.java.corerpc.client.RpcClient;
import com.ensarsarajcic.neovim.java.corerpc.reactive.ReactiveRpcClient;
import com.ensarsarajcic.neovim.java.corerpc.reactive.ReactiveRpcStreamer;
import com.ensarsarajcic.neovim.java.corerpc.reactive.ReactiveRpcStreamerWrapper;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc.Incoming;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc.NeovimApi;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc.NeovimJavaApi;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.PlatformUtil;

/**
 * The {@code nvim --embed} process and the msgpack-rpc stack on top of it: everything needed to
 * call the Neovim API, and nothing about what the calls mean ({@link NeovimSession} does that).
 *
 * <h2>The layers (bottom to top)</h2>
 * <pre>
 *   Process                 nvim --embed; msgpack-rpc on its stdin/stdout
 *   ProcessRpcConnection    the two streams; wrapped in QuittingConnection, so that Neovim
 *                           exiting at shutdown is not logged as an error
 *   RpcClient               encodes requests, decodes responses and notifications (Jackson + msgpack)
 *   ReactiveRpcStreamer     turns each request into a CompletableFuture
 *   NeovimStreamApi         typed methods: api.input("dd"), api.getMode(), ...
 *   NeovimJavaApi           our own interface over it (core/rpc/NeovimApi), which the rest of the
 *                           plug-in uses, so that it does not depend on neovim-java
 * </pre>
 *
 * <p>After the stack is up, {@link #open} introduces the client to Neovim
 * ({@code nvim_set_client_info}) and attaches as a UI ({@code nvim_ui_attach}), without which
 * Neovim does not process {@code nvim_input} reliably under {@code --embed}.
 *
 * <p>Thread-safe: the API may be called from any thread, but its futures must only be waited on
 * off the SWT UI thread.
 */
final class NeovimConnection implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(NeovimConnection.class.getName());

    /**
     * neovim-java logs through SLF4J, which the bundled slf4j-simple prints to the console. At its
     * default level, INFO, that is one line per RPC message ("Sending message: RequestMessage{...}"),
     * several per key, flooding the console of a runtime Eclipse and of the tests. So unless a level
     * was chosen with {@code -Dorg.slf4j.simpleLogger.defaultLogLevel=...} (for example {@code debug}
     * to study the RPC traffic), only warnings and errors are printed.
     *
     * <p>A system property and not a {@code simplelogger.properties} file: slf4j-simple looks the
     * file up through the thread's context class loader, which inside OSGi is usually not this
     * bundle's. It reads the property once, when the first logger is created, which is why this runs
     * here, before any neovim-java class is used.
     */
    private static final String SLF4J_LEVEL_PROPERTY = "org.slf4j.simpleLogger.defaultLogLevel";

    static {
        if (System.getProperty(SLF4J_LEVEL_PROPERTY) == null) {
            System.setProperty(SLF4J_LEVEL_PROPERTY, "warn");
        }
    }

    /** Name this plug-in reports to Neovim through {@code nvim_set_client_info}. */
    static final String CLIENT_NAME = "Eclipse-Neovim";

    /** Upper bound for the requests made while opening. */
    private static final long OPEN_TIMEOUT_SEC = 5;
    /** How long {@link #requestQuit} waits for Neovim to exit after {@code :qa!} (it takes milliseconds). */
    private static final long QUIT_TIMEOUT_MS = 1500;

    private final Process process;
    private final QuittingConnection streams;
    private final RpcClient rpcClient;
    /** Per-connection pool for RPC reads and response waits; see {@link #open}. */
    private final ExecutorService rpcExecutor;
    private final NeovimApi api;

    private NeovimConnection(Process process, QuittingConnection streams, RpcClient rpcClient,
            ExecutorService rpcExecutor, NeovimApi api) {
        this.process = process;
        this.streams = streams;
        this.rpcClient = rpcClient;
        this.rpcExecutor = rpcExecutor;
        this.api = api;
    }

    /**
     * Launches Neovim and connects to it as a UI.
     *
     * @param nvimPath      preference value: command name or absolute path (see {@link NvimExecutable})
     * @param extraArgs     extra command-line arguments, e.g. {@code --clean} for tests
     * @param clientVersion this plug-in's version (OSGi {@code major.minor.micro.qualifier}), reported to Neovim
     * @throws Exception if the process cannot start or does not answer in time
     */
    static NeovimConnection open(String nvimPath, List<String> extraArgs, String clientVersion) throws Exception {
        List<String> cmd = NvimExecutable.embedCommand(nvimPath, extraArgs);
        // Inherit the environment so "nvim"/"nvim.exe" resolve through PATH/PATHEXT.
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        NvimExecutable.prepareEnvironment(pb.environment(), PlatformUtil.isWindows());
        Process process = pb.start();
        LOG.info(() -> "Started Neovim pid=" + process.pid() + " cmd=" + cmd);

        // true: kill the process on close. Wrapped so that Neovim exiting at shutdown is not logged as
        // an error by neovim-java (see QuittingConnection).
        QuittingConnection streams = new QuittingConnection(new ProcessRpcConnection(process, true));
        // Library defaults are JVM-wide: RpcClient uses a static pool that rpcClient.stop() shuts
        // down (every later open() then fails), and the reactive wrapper blocks response waits on
        // ForkJoinPool.commonPool() (shared with the whole IDE). Give each connection its own pool.
        ExecutorService rpcExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "eclipse-neovim-rpc");
            t.setDaemon(true);
            return t;
        });
        // One shared RpcClient so API calls and notification handlers use the same channel.
        RpcClient rpcClient = new RpcClient.Builder()
                .withObjectMapper(ObjectMappers.defaultNeovimMapper())
                .withExecutorService(rpcExecutor)
                .build();
        ReactiveRpcStreamer reactive = ReactiveRpcClient.createInstanceWithCustomReactiveStreamer(
                new ReactiveRpcStreamerWrapper(rpcClient, rpcExecutor));
        reactive.attach(streams);
        NeovimStreamApi streamApi = new NeovimStreamApi(reactive);
        NeovimConnection connection = new NeovimConnection(process, streams, rpcClient, rpcExecutor,
                new NeovimJavaApi(streamApi, reactive));

        try {
            // These two still use neovim-java's API directly (its option types); they move to
            // core/rpc/NeovimApi with our own client (docs/RPC_CLIENT.md, step 7).
            await(streamApi.setClientInfo(CLIENT_NAME, clientVersion(clientVersion),
                    ClientType.REMOTE, Map.of(), new ClientAttributes(null, null, null)));
            // Without a UI attached, nvim_input is not processed reliably under --embed.
            await(streamApi.attachUi(120, 40, UiOptions.TERMINAL));
        } catch (Exception e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    /**
     * Converts an OSGi version ({@code 1.2.3.qualifier}) to Neovim's client version: the qualifier
     * becomes the pre-release tag; missing or non-numeric parts become 0.
     */
    static ClientVersionInfo clientVersion(String version) {
        String[] parts = version == null ? new String[0] : version.split("\\.", 4);
        return new ClientVersionInfo(versionPart(parts, 0), versionPart(parts, 1), versionPart(parts, 2),
                parts.length > 3 && !parts[3].isEmpty() ? parts[3] : null);
    }

    private static int versionPart(String[] parts, int index) {
        try {
            return index < parts.length ? Integer.parseInt(parts[index]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The typed Neovim API (our interface, implemented with neovim-java for now). */
    NeovimApi api() {
        return api;
    }

    /**
     * Delivers everything Neovim sends on its own to {@code incoming}: notifications and requests
     * ({@code vim.rpcnotify}, {@code vim.rpcrequest}, buffer line events). Call once, before Neovim
     * has reason to send any.
     *
     * <p>Notifications: one raw callback on the {@code RpcClient}, which receives all of them, and
     * {@link Incoming#notification} picks by name. A handler that throws is logged here and the message
     * dropped, so that one bad message is reported once, by us, and does not reach the library.
     *
     * <p>Requests: each gets exactly one answer, the return value or, if
     * {@link Incoming#request} throws, an error response; Neovim waits for every request until it is
     * answered. Not neovim-java's {@code @NeovimRequestHandler}: in 0.5.0 its proxy stores those
     * handlers in one map and looks them up in another, so every request was answered "No handler
     * found".
     *
     * <p>neovim-java calls both on the thread that reads Neovim's output, in arrival order (the same
     * thread for notifications and requests); while one runs, no other message from Neovim is read.
     */
    void setIncoming(Incoming incoming) {
        rpcClient.addNotificationCallback(notification -> {
            try {
                incoming.notification(notification.getName(), arguments(notification.getArguments()));
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Notification " + notification.getName() + " from Neovim failed", e);
            }
        });
        rpcClient.addRequestCallback(request -> {
            ResponseMessage response;
            try {
                response = new ResponseMessage(request.getId(), null,
                        incoming.request(request.getMethod(), arguments(request.getArguments())));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Request " + request.getMethod() + " from Neovim failed", e);
                response = new ResponseMessage(request.getId(), RpcError.exception(String.valueOf(e.getMessage())), null);
            }
            try {
                rpcClient.send(response);
            } catch (IOException e) {
                LOG.log(Level.FINE, "Answering " + request.getMethod() + " failed (Neovim gone?)", e);
            }
        });
    }

    /** A message's arguments; never {@code null}, as {@link Incoming} promises. */
    private static List<?> arguments(List<?> raw) {
        return raw == null ? List.of() : raw;
    }

    boolean isAlive() {
        return process != null && process.isAlive();
    }

    /**
     * Asks Neovim to exit ({@code :qa!}) and waits until it has, at most {@value #QUIT_TIMEOUT_MS} ms.
     *
     * <p>It waits for the <em>process</em>, not for an answer: Neovim exits while running
     * {@code :qa!} and never answers. (Waiting for an answer used to cost the full timeout on every
     * close, 1.5 s per session.) So the command is sent as a msgpack-rpc <em>notification</em>, a
     * message that expects no answer. Sent as a request, a thread would wait for the answer until
     * {@link #close()} interrupts it, and neovim-java would print that interruption as an error.
     * The connection is also told first that the end of Neovim's output is expected now, so
     * neovim-java does not log that as an error either.
     */
    void requestQuit() {
        streams.quitting();
        if (!isAlive()) {
            return;
        }
        try {
            rpcClient.send(new NotificationMessage.Builder("nvim_command").addArgument("qa!").build());
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not send :qa! (Neovim already gone?); close() ends the process", e);
            return;
        }
        try {
            if (!process.waitFor(QUIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                LOG.fine("Neovim did not exit after :qa!; close() ends the process");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stops the RPC threads, closes the streams and ends the process (forcibly if it lingers). */
    @Override
    public void close() {
        // Stop RPC listener threads before closing the streams they read.
        try {
            rpcClient.stop();
        } catch (Exception e) {
            LOG.log(Level.FINE, "Error stopping RpcClient", e);
        }
        rpcExecutor.shutdownNow(); // releases response waits that will never be answered
        try {
            streams.close();
        } catch (Exception e) {
            LOG.log(Level.FINE, "Error closing RPC connection", e);
        }
        destroyProcess();
    }

    private void destroyProcess() {
        if (!isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(OPEN_TIMEOUT_SEC, TimeUnit.SECONDS);
    }
}
