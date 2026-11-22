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
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.MsgpackNeovimApi;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.NeovimApi;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.NeovimErrorException;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.RpcChannel;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimVersion;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PlatformUtil;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StartupErrorReport;

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
     * The size reported with {@code nvim_ui_attach}. The plug-in draws only the command-line window
     * from Neovim's screen, but the size matters: it is what {@code &columns} and {@code &lines} say, and what {@code L}
     * and {@code Ctrl-D} count. It holds until an editor attaches, which resizes the screen to its
     * own size ({@code NeovimSession.setEditorSizeAsync}).
     */
    private static final int UI_WIDTH = 120;
    private static final int UI_HEIGHT = 40;
    /**
     * The UI the plug-in attaches as: 24-bit color ({@code rgb}: the highlights of the grids it draws
     * come as {@code 0xRRGGBB}), and:
     * <ul>
     *   <li>{@code ext_multigrid}: each window drawn into a grid of its own, with {@code win_pos}
     *       and {@code win_float_pos} saying which window a grid shows and where
     *       ({@code util/GridEvent}, {@code util/Grids}). The editors' windows' grids are not drawn:
     *       the editors show the documents. The command-line window's is
     *       ({@code editor/NeovimWindowPanel}), and so are the floats: Neovim's command line,
     *       prompts, pager and messages (ui2, {@code LuaScripts.UI2}), its popup menu, and
     *       plug-ins' windows.</li>
     *   <li>{@code ext_linegrid}, which {@code ext_multigrid} needs: the screen as
     *       {@code grid_line} events.</li>
     *   <li>{@code ext_cmdline} and {@code ext_messages}, though the plug-in draws neither from
     *       events: Neovim attaches this UI before it reads the user's {@code init.lua}, and ui2
     *       starts only after it. An error message in between, with no UI taking messages, would
     *       make Neovim wait at a "Press ENTER" prompt nobody sees, and the start would time out.
     *       Once ui2 is on, its Lua UI takes these events and this UI gets none.</li>
     * </ul>
     * {@code ext_popupmenu} stays off: Neovim draws its popup menu (the {@code :} line's Tab list,
     * insert-mode completion) into a grid of its own, drawn as a float. The other {@code ext_*}
     * options stay off too: the tabline and cterm colors are not used.
     */
    static final Map<String, Object> TERMINAL_UI = terminalUi();

    private static Map<String, Object> terminalUi() {
        return uiOptions(List.of("rgb", "ext_cmdline", "ext_messages", "ext_linegrid", "ext_multigrid"));
    }

    /** Every {@code nvim_ui_attach} option the plug-in names, those in {@code on} true, in a fixed order. */
    private static Map<String, Object> uiOptions(List<String> on) {
        Map<String, Object> options = new LinkedHashMap<>();
        for (String name : List.of("rgb", "override", "ext_cmdline", "ext_hlstate", "ext_linegrid",
                "ext_messages", "ext_multigrid", "ext_popupmenu", "ext_tabline", "ext_termcolors")) {
            options.put(name, on.contains(name));
        }
        return Collections.unmodifiableMap(options); // keeps the order: the same bytes every time
    }

    /**
     * What {@code nvim_ui_attach} is called with: the screen's size in cells and the UI options.
     * Example: the editing Neovim's {@code new Ui(120, 40, TERMINAL_UI)}.
     */
    record Ui(int width, int height, Map<String, Object> options) {
    }

    /** Upper bound for the requests made while opening. */
    private static final long OPEN_TIMEOUT_SEC = 5;
    /**
     * How long Neovim may take to run the user's {@code init.lua} after the UI attached
     * ({@link #waitForStartup}). Longer than {@link #OPEN_TIMEOUT_SEC}: a configuration with a
     * plug-in manager can take a few seconds, the first time especially.
     */
    private static final long STARTUP_TIMEOUT_SEC = 10;
    /** How long {@link #requestQuit} waits for Neovim to exit after {@code :qa!} (it takes milliseconds). */
    private static final long QUIT_TIMEOUT_MS = 1500;
    /**
     * How long {@link #requestQuit} waits for {@code :qa!} to be written (it takes microseconds,
     * unless Neovim stopped reading its input).
     */
    private static final long QUIT_SEND_TIMEOUT_MS = 500;

    private final Process process;
    /** The program started, as the command's first word: {@code /home/you/.local/share/.../neovim/la/bin/nvim}. */
    private final String program;
    private final RpcChannel channel;
    private final NeovimApi api;
    /** The channel's receiver: everything goes to the session's once {@link #setIncoming} is called. */
    private final EarlyIncoming early = new EarlyIncoming();
    /** The errors the user's configuration reported while Neovim started, or "" (see {@link #waitForStartup}). */
    private String startupErrors = "";
    /** The running Neovim's version, checked at start ({@link #checkVersion}); {@code null} before. */
    private volatile NeovimVersion version;

    private NeovimConnection(Process process, String program) {
        this.process = process;
        this.program = program;
        this.channel = new RpcChannel(process.getInputStream(), process.getOutputStream(), early,
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
        return open(nvimPath, extraArgs, clientVersion, new Ui(UI_WIDTH, UI_HEIGHT, TERMINAL_UI));
    }

    /** {@link #open(String, List, String)} with the UI {@code ui}. */
    static NeovimConnection open(String nvimPath, List<String> extraArgs, String clientVersion, Ui ui) throws Exception {
        List<String> cmd = NvimExecutable.embedCommand(nvimPath, extraArgs);
        // Inherit the environment: Neovim and the programs it runs (:!, :terminal) need PATH and the rest.
        ProcessBuilder pb = new ProcessBuilder(cmd);
        NvimExecutable.prepareEnvironment(pb.environment(), PlatformUtil.isWindows());
        Process process = pb.start();
        LOG.info(() -> "Started Neovim pid=" + process.pid() + " cmd=" + cmd);
        logStderr(process.getErrorStream());

        NeovimConnection connection = new NeovimConnection(process, cmd.get(0));
        connection.channel.start();
        try {
            await(connection.api.setClientInfo(CLIENT_NAME, clientVersion(clientVersion), "remote", Map.of(), Map.of()));
            // Before attaching: a too old Neovim then never runs the user's init.lua for nothing.
            connection.checkVersion();
            // Without a UI attached, nvim_input is not processed reliably under --embed.
            await(connection.api.uiAttach(ui.width(), ui.height(), ui.options()));
            connection.startupErrors = connection.waitForStartup();
        } catch (Exception e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    /**
     * A connection over a process that is already running, with nothing asked of it: for tests of
     * closing, which need a process that misbehaves (one that never reads its input) rather than
     * Neovim.
     */
    static NeovimConnection over(Process process, String program) {
        NeovimConnection connection = new NeovimConnection(process, program);
        connection.channel.start();
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
        this.version = version;
    }

    /**
     * The running Neovim's version, as {@code 0.12.6}, or {@code ""} before the start checked it. For
     * reports that should say which Neovim they are about ({@code util/RepairReport}).
     */
    String version() {
        NeovimVersion v = version;
        return v == null ? "" : v.toString();
    }

    private static String unknownVersionMessage() {
        return "This Neovim does not report its version (vim.version()), so it is older than "
                + NeovimVersion.MINIMUM + ", which Neovim for Eclipse needs.";
    }

    /**
     * Waits until Neovim has run the user's configuration.
     *
     * <p>Under {@code --embed}, Neovim runs {@code init.lua} (or {@code init.vim}) only once a UI has
     * attached. A request is answered only once Neovim is idle, so the answer to a trivial one
     * means the configuration has run. An error in it (a typo, say) does not stop Neovim: it sends
     * the error as a message event (which arrives before Eclipse listens, so it is read back from
     * {@code :messages} below) and goes on with what the configuration set up before the error, for
     * a Lua syntax error nothing of {@code init.lua}. (Before the plug-in attached with
     * {@code ext_messages}, the error left Neovim at a "Press ENTER" prompt, answering no request,
     * and this method dismissed it with Esc.)
     *
     * @return the configuration's errors, as {@code :messages} lists them, if it reported any
     *         ({@code v:errmsg} is set); "" otherwise
     * @throws TimeoutException if Neovim is still busy after {@link #STARTUP_TIMEOUT_SEC}
     */
    private String waitForStartup() throws Exception {
        try {
            api.execLua("return 0", List.of()).get(STARTUP_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new TimeoutException("Neovim did not finish starting within " + STARTUP_TIMEOUT_SEC
                    + " s (is its configuration, init.lua, waiting for something?)");
        }
        Object errors = await(api.execLua(STARTUP_ERRORS, List.of()));
        String all = errors == null ? "" : errors.toString().strip();
        // noice.nvim's "can't work when the GUI has ext_cmdline enabled" lines are no error to fix:
        // noice turns itself off and Eclipse works. They are logged, not warned about at every start.
        String text = StartupErrorReport.withoutKnownNotices(all);
        if (!text.isEmpty()) {
            LOG.warning(() -> "Neovim's configuration reported errors while starting:\n" + text);
        } else if (!all.isEmpty()) {
            LOG.info(() -> "Neovim's configuration reported only notices that need no warning"
                    + " (noice.nvim turning itself off in Eclipse):\n" + all);
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
     * other message from Neovim is read. The notifications that came before it is set (Neovim
     * sends {@code redraw}s from {@code nvim_ui_attach} on, while the configuration runs) are kept
     * and handed on first; requests before then are answered with an error ({@link EarlyIncoming}).
     */
    void setIncoming(Incoming incoming) {
        early.deliverTo(incoming);
    }

    /** The program started (see {@link #program}). */
    String program() {
        return program;
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
     *
     * <p>The write itself is bounded too, {@value #QUIT_SEND_TIMEOUT_MS} ms ({@link BoundedSend}):
     * a hung Neovim that stopped reading its input, with another thread stuck writing a large
     * request, would otherwise keep this call, on the UI thread, waiting for the channel's write
     * lock for good. Not written in time, the quit is given up, and {@link #close()} ends the
     * process, which also frees the stuck writer.
     */
    void requestQuit() {
        channel.closing();
        if (!isAlive()) {
            return;
        }
        try {
            BoundedSend.start("neovim4eclipse-quit", () -> {
                channel.notify("nvim_command", "qa!");
                return CompletableFuture.completedFuture(null);
            }).get(QUIT_SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOG.warning("Could not send :qa! within " + QUIT_SEND_TIMEOUT_MS
                    + " ms (Neovim does not read its input); ending its process");
            return;
        } catch (ExecutionException e) {
            LOG.log(Level.FINE, "Could not send :qa! (Neovim already gone?); close() ends the process", e.getCause());
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    /**
     * Ends the process (forcibly if it lingers) and closes the connection, failing any request
     * still waiting.
     *
     * <p>The process first, then the streams. Closing Neovim's stdin flushes it, and the flush waits
     * for the stream's lock, which a thread blocked writing into a full pipe holds (see
     * {@link BoundedSend}): with Neovim hung, closing the streams first never returned. Once the
     * process is gone, its pipe is broken and that write fails, so nothing waits any more. The
     * channel is told first that the end of Neovim's output is expected
     * ({@link RpcChannel#closing()}), so the reader does not log the end as a crash.
     */
    @Override
    public void close() {
        channel.closing();
        destroyProcess();
        channel.close();
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
