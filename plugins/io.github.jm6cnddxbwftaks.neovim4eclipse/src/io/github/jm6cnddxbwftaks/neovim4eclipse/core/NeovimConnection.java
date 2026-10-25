// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Incoming;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Mode;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.MsgpackNeovimApi;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.NeovimApi;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.NeovimConnectionClosedException;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.NeovimErrorException;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.RpcChannel;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimVersion;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PlatformUtil;

/**
 * The {@code nvim --embed} process and the msgpack-rpc connection to it: everything needed to call
 * the Neovim API, and nothing about what the calls mean ({@link NeovimSession} does that).
 *
 * <h2>The layers (bottom to top)</h2>
 * <pre>
 *   Process            nvim --embed: msgpack-rpc on its stdin and stdout; stderr is kept apart
 *                      and logged (see {@link #open})
 *   RpcChannel         core/rpc: the messages, matching responses to requests, the reader thread
 *   MsgpackNeovimApi   core/rpc: typed methods over it, api.input("dd"), api.getMode(), ...
 * </pre>
 * All three are the plug-in's own code on msgpack-core (until 2026 the RPC went through the
 * neovim-java library; {@code docs/design/RPC_CLIENT.md} tells why and how it was replaced).
 *
 * <p>After the connection is up, {@link #open} introduces the client to Neovim
 * ({@code nvim_set_client_info}), refuses a Neovim older than {@link NeovimVersion#MINIMUM}
 * ({@link UnsupportedNeovimException}), and attaches as a UI ({@code nvim_ui_attach}), without which
 * Neovim does not process {@code nvim_input} reliably under {@code --embed}.
 *
 * <p>Thread-safe: the API may be called from any thread, but its futures must only be waited on
 * off the SWT UI thread.
 */
final class NeovimConnection implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(NeovimConnection.class.getName());

    /** Name this plug-in reports to Neovim through {@code nvim_set_client_info}. */
    static final String CLIENT_NAME = "neovim4eclipse";

    /**
     * The size reported with {@code nvim_ui_attach}. The plug-in draws nothing from Neovim's screen,
     * but the size still matters a little: it is what {@code &columns} and {@code &lines} say.
     */
    private static final int UI_WIDTH = 120;
    private static final int UI_HEIGHT = 40;
    /**
     * A plain terminal-like UI: no 24-bit color ({@code rgb} defaults to {@code true}, so it is sent
     * explicitly) and none of the {@code ext_*} features, which would make Neovim send their events
     * instead of drawing messages, the popup menu ... into the grid; except {@code ext_cmdline}.
     * With it Neovim reports every command line it opens ({@code cmdline_show}, read by
     * {@link NotificationBridge}), which lets the editor's box take over one the user opened through
     * a mapping (docs/design/KEY_ROUTING.md). It works without {@code ext_linegrid}.
     */
    static final Map<String, Object> TERMINAL_UI = terminalUi();

    private static Map<String, Object> terminalUi() {
        Map<String, Object> options = new LinkedHashMap<>();
        for (String name : List.of("rgb", "override", "ext_cmdline", "ext_hlstate", "ext_linegrid",
                "ext_messages", "ext_multigrid", "ext_popupmenu", "ext_tabline", "ext_termcolors")) {
            options.put(name, "ext_cmdline".equals(name));
        }
        return Collections.unmodifiableMap(options); // keeps the order: the same bytes every time
    }

    /** Upper bound for the requests made while opening. */
    private static final long OPEN_TIMEOUT_SEC = 5;
    /**
     * How long Neovim may take to run the user's {@code init.lua} after the UI attached
     * ({@link #waitForStartup}). Longer than {@link #OPEN_TIMEOUT_SEC}: a configuration with a
     * plug-in manager can take a few seconds, the first time especially.
     */
    private static final long STARTUP_TIMEOUT_SEC = 10;
    /** How often {@link #waitForStartup} looks for a prompt while Neovim starts. */
    private static final long STARTUP_POLL_MS = 50;
    /** How long {@link #requestQuit} waits for Neovim to exit after {@code :qa!} (it takes milliseconds). */
    private static final long QUIT_TIMEOUT_MS = 1500;

    private final Process process;
    private final RpcChannel channel;
    private final NeovimApi api;
    /** Where Neovim's notifications and requests go; see {@link #setIncoming}. */
    private volatile Incoming incoming;
    /** The errors the user's configuration reported while Neovim started, or "" (see {@link #waitForStartup}). */
    private String startupErrors = "";

    private NeovimConnection(Process process) {
        this.process = process;
        this.channel = new RpcChannel(process.getInputStream(), process.getOutputStream(), new Forwarder(),
                "neovim4eclipse-rpc");
        this.api = new MsgpackNeovimApi(channel);
    }

    /**
     * Launches Neovim and connects to it as a UI.
     *
     * <p>Neovim's stderr is a separate stream, not merged into stdout: stdout carries MessagePack,
     * and a stray line of text in it would be read as a broken message and end the session. Neovim
     * normally writes nothing there under {@code --embed}; whatever it does write (a crash report,
     * say) is logged at WARNING, one line at a time, by a small daemon thread.
     *
     * @param nvimPath      preference value: blank, a folder or an absolute path (see {@link NvimExecutable})
     * @param extraArgs     extra command-line arguments, e.g. {@code --clean} for tests
     * @param clientVersion this plug-in's version (OSGi {@code major.minor.micro.qualifier}), reported to Neovim
     * @throws Exception if the process cannot start or does not answer in time
     */
    static NeovimConnection open(String nvimPath, List<String> extraArgs, String clientVersion) throws Exception {
        List<String> cmd = NvimExecutable.embedCommand(nvimPath, extraArgs);
        // Inherit the environment: Neovim and the programs it runs (:!, :terminal) need PATH and the rest.
        ProcessBuilder pb = new ProcessBuilder(cmd);
        NvimExecutable.prepareEnvironment(pb.environment(), PlatformUtil.isWindows());
        Process process = pb.start();
        LOG.info(() -> "Started Neovim pid=" + process.pid() + " cmd=" + cmd);
        logStderr(process.getErrorStream());

        NeovimConnection connection = new NeovimConnection(process);
        connection.channel.start();
        try {
            await(connection.api.setClientInfo(CLIENT_NAME, clientVersion(clientVersion), "remote", Map.of(), Map.of()));
            // Before attaching: a too old Neovim then never runs the user's init.lua for nothing.
            connection.checkVersion();
            // Without a UI attached, nvim_input is not processed reliably under --embed.
            await(connection.api.uiAttach(UI_WIDTH, UI_HEIGHT, TERMINAL_UI));
            connection.startupErrors = connection.waitForStartup();
        } catch (Exception e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    /**
     * Refuses a Neovim older than {@link NeovimVersion#MINIMUM}. Neovim answers requests before a UI
     * attaches (only {@code init.lua} waits for that), so this runs first. A Neovim too old to have
     * {@code vim.version()} at all (before 0.8) fails the request and is refused the same way.
     *
     * @throws UnsupportedNeovimException with a message such as
     *         {@code Neovim 0.12.4 is too old: Neovim for Eclipse needs 0.12.6 or newer.}
     */
    private void checkVersion() throws Exception {
        NeovimVersion version;
        try {
            version = NeovimVersion.fromLua(await(api.execLua(NeovimVersion.LUA_QUERY, List.of())));
        } catch (ExecutionException e) {
            // Neovim answered with an error (no vim.version()); anything else (the pipe broke) is not
            // about the version, so it is passed on as it is.
            if (!(e.getCause() instanceof NeovimErrorException)) {
                throw e;
            }
            throw new UnsupportedNeovimException(unknownVersionMessage(), e.getCause());
        } catch (IllegalArgumentException e) {
            throw new UnsupportedNeovimException(unknownVersionMessage(), e);
        }
        LOG.info(() -> "Neovim version " + version);
        if (!version.isSupported()) {
            throw new UnsupportedNeovimException(version.tooOldMessage());
        }
    }

    private static String unknownVersionMessage() {
        return "This Neovim does not report its version (vim.version()), so it is older than "
                + NeovimVersion.MINIMUM + ", which Neovim for Eclipse needs.";
    }

    /**
     * Waits until Neovim has run the user's configuration, and dismisses the "Press ENTER" prompt
     * an error in it leaves.
     *
     * <p>Under {@code --embed}, Neovim runs {@code init.lua} (or {@code init.vim}) only once a UI has
     * attached. An error there, a typo say, makes Neovim show the error and wait at a hit-enter
     * prompt, which this plug-in does not draw; while it waits, Neovim answers no request but
     * {@code nvim_get_mode}, so every later request of the start-up would time out
     * ("Neovim not started: TimeoutException"). So this sends a request that is answered only once
     * Neovim is idle, and while it waits, asks for the mode (answered at once): at a prompt
     * ({@code r}, {@code rm}), an Esc dismisses it, as a user in a terminal would press Enter. Neovim
     * then carries on with what its configuration set up before the error: for a Lua syntax error,
     * nothing of {@code init.lua}.
     *
     * @return the configuration's errors, as {@code :messages} lists them, if it reported any
     *         ({@code v:errmsg} is set); "" otherwise
     * @throws TimeoutException if Neovim is still busy after {@link #STARTUP_TIMEOUT_SEC}
     */
    private String waitForStartup() throws Exception {
        CompletableFuture<Object> idle = api.execLua("return 0", List.of());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STARTUP_TIMEOUT_SEC);
        while (true) {
            try {
                idle.get(STARTUP_POLL_MS, TimeUnit.MILLISECONDS);
                break;
            } catch (TimeoutException stillStarting) {
                Mode mode = await(api.getMode());
                if (mode != null && mode.blocking() && mode.mode() != null && mode.mode().startsWith("r")) {
                    LOG.info("Neovim waits at a prompt while starting (an error in its configuration?); dismissing it");
                    await(api.input("<Esc>"));
                }
                if (System.nanoTime() > deadline) {
                    throw new TimeoutException("Neovim did not finish starting within " + STARTUP_TIMEOUT_SEC
                            + " s (is its configuration, init.lua, waiting for something?)");
                }
            }
        }
        Object errors = await(api.execLua(STARTUP_ERRORS, List.of()));
        String text = errors == null ? "" : errors.toString().strip();
        if (!text.isEmpty()) {
            LOG.warning(() -> "Neovim's configuration reported errors while starting:\n" + text);
        }
        return text;
    }

    /** The start-up messages if an error was reported ({@code v:errmsg}), else "". */
    private static final String STARTUP_ERRORS = """
            if vim.v.errmsg == '' then
              return ''
            end
            return vim.api.nvim_exec2('messages', { output = true }).output
            """;

    /**
     * The errors the user's configuration ({@code init.lua}) reported while Neovim started, as
     * {@code :messages} listed them then, or "" if it reported none. Neovim started anyway.
     */
    String startupErrors() {
        return startupErrors;
    }

    /** Logs each line Neovim writes to stderr, until the stream ends with the process. */
    private static void logStderr(InputStream stderr) {
        Thread thread = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(stderr, StandardCharsets.UTF_8))) {
                for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                    String text = line;
                    LOG.warning(() -> "Neovim stderr: " + text);
                }
            } catch (IOException e) {
                LOG.log(Level.FINE, "Reading Neovim's stderr ended", e);
            }
        }, "neovim4eclipse-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Converts an OSGi version ({@code 1.2.3.qualifier}) to Neovim's client version: the qualifier
     * becomes the pre-release tag; missing or non-numeric parts become 0.
     */
    static Map<String, Object> clientVersion(String version) {
        String[] parts = version == null ? new String[0] : version.split("\\.", 4);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("major", versionPart(parts, 0));
        info.put("minor", versionPart(parts, 1));
        info.put("patch", versionPart(parts, 2));
        if (parts.length > 3 && !parts[3].isEmpty()) {
            info.put("prerelease", parts[3]); // optional: left out, not sent as nil
        }
        return info;
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
     * Delivers everything Neovim sends on its own to {@code incoming}: notifications and requests
     * ({@code vim.rpcnotify}, {@code vim.rpcrequest}, buffer line events). Call once, right after
     * {@link #open}, before asking Neovim for anything that would make it send one.
     *
     * <p>{@link RpcChannel} calls it on its reader thread, in arrival order; while it runs, no
     * other message from Neovim is read. Until it is set, notifications are dropped (Neovim sends
     * {@code redraw}s right after {@code nvim_ui_attach}) and requests are answered with an error.
     */
    void setIncoming(Incoming incoming) {
        this.incoming = incoming;
    }

    /** Hands messages to {@link #incoming} once it is set (the channel needs a receiver from the start). */
    private final class Forwarder implements Incoming {
        @Override
        public void notification(String method, List<?> params) {
            Incoming target = incoming;
            if (target != null) {
                target.notification(method, params);
            }
        }

        @Override
        public Object request(String method, List<?> params) throws Exception {
            Incoming target = incoming;
            if (target == null) {
                throw new IllegalStateException("Eclipse is not ready for " + method + " yet");
            }
            return target.request(method, params);
        }
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
     * message that expects no answer. The channel is told first that the end of Neovim's output is
     * expected now ({@link RpcChannel#closing()}), so that it is not reported as a crash.
     */
    void requestQuit() {
        channel.closing();
        if (!isAlive()) {
            return;
        }
        try {
            channel.notify("nvim_command", "qa!");
        } catch (NeovimConnectionClosedException e) {
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

    /** Closes the connection (failing any request still waiting) and ends the process (forcibly if it lingers). */
    @Override
    public void close() {
        channel.close();
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
