// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ensarsarajcic.neovim.java.api.NeovimApi;
import com.ensarsarajcic.neovim.java.api.NeovimStreamApi;
import com.ensarsarajcic.neovim.java.api.types.api.ClientAttributes;
import com.ensarsarajcic.neovim.java.api.types.api.ClientType;
import com.ensarsarajcic.neovim.java.api.types.api.ClientVersionInfo;
import com.ensarsarajcic.neovim.java.api.types.api.UiOptions;
import com.ensarsarajcic.neovim.java.api.util.ObjectMappers;
import com.ensarsarajcic.neovim.java.corerpc.client.ProcessRpcConnection;
import com.ensarsarajcic.neovim.java.corerpc.client.RpcClient;
import com.ensarsarajcic.neovim.java.corerpc.client.RpcConnection;
import com.ensarsarajcic.neovim.java.corerpc.reactive.ReactiveRpcClient;
import com.ensarsarajcic.neovim.java.corerpc.reactive.ReactiveRpcStreamer;
import com.ensarsarajcic.neovim.java.corerpc.reactive.ReactiveRpcStreamerWrapper;
import com.ensarsarajcic.neovim.java.handler.NeovimHandlerManager;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.NvimExecutable;

/**
 * The {@code nvim --embed} process and the msgpack-rpc stack on top of it: everything needed to
 * call the Neovim API, and nothing about what the calls mean ({@link NeovimSession} does that).
 *
 * <h2>The layers (bottom to top)</h2>
 * <pre>
 *   Process                 nvim --embed; msgpack-rpc on its stdin/stdout
 *   ProcessRpcConnection    the two streams
 *   RpcClient               encodes requests, decodes responses and notifications (Jackson + msgpack)
 *   ReactiveRpcStreamer     turns each request into a CompletableFuture
 *   NeovimStreamApi         typed methods: api.input("dd"), api.getMode(), ...
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

    private final Process process;
    private final RpcConnection streams;
    private final RpcClient rpcClient;
    /** Per-connection pool for RPC reads and response waits; see {@link #open}. */
    private final ExecutorService rpcExecutor;
    private final NeovimApi api;

    private NeovimConnection(Process process, RpcConnection streams, RpcClient rpcClient,
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
        Process process = pb.start();
        LOG.info(() -> "Started Neovim pid=" + process.pid() + " cmd=" + cmd);

        RpcConnection streams = new ProcessRpcConnection(process, true); // true: kill process on close
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
        NeovimApi api = new NeovimStreamApi(reactive);
        NeovimConnection connection = new NeovimConnection(process, streams, rpcClient, rpcExecutor, api);

        try {
            await(api.setClientInfo(CLIENT_NAME, clientVersion(clientVersion),
                    ClientType.REMOTE, Map.of(), new ClientAttributes(null, null, null)));
            // Without a UI attached, nvim_input is not processed reliably under --embed.
            await(api.attachUi(120, 40, UiOptions.TERMINAL));
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

    /** The typed Neovim API. */
    NeovimApi api() {
        return api;
    }

    /**
     * Routes notifications from Neovim to the {@code @NeovimNotificationHandler} methods of
     * {@code handler} (found by reflection, so they must be public).
     */
    void addNotificationHandler(Object handler) {
        NeovimHandlerManager handlers = new NeovimHandlerManager();
        handlers.registerNeovimHandler(handler);
        handlers.attachToStream(rpcClient);
    }

    boolean isAlive() {
        return process != null && process.isAlive();
    }

    /**
     * Asks Neovim to exit, so that the pipe closes cleanly; otherwise the RPC library logs a
     * {@code JsonEOFException} at ERROR level on the dead pipe. Waits at most 1.5 s.
     */
    void requestQuit() {
        try {
            if (isAlive()) {
                api.executeCommand("qa!").get(1500, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "nvim qa! on shutdown (often normal)", e);
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
