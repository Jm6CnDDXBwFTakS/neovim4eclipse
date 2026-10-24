// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Logger;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimKeys;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.OutputCapture;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.SearchCommand;

/**
 * Runs what the user submits from the command line box ({@code :} commands, listings, searches) and
 * {@code :normal!} keys, then refreshes Eclipse from Neovim.
 *
 * <p>The tricky part is the output of a typed {@code :} command. It is collected with {@code :redir}
 * around the typed command, and ending that redirection waits until the command has finished, which
 * can take long ({@code :!make}) or need a key (a "press Enter" prompt). See {@link #stopCapture}.
 *
 * <p>Every method blocks and must run on the session's input thread; {@link NeovimSession} queues
 * them there. Neovim is reached only through the small {@link Neovim} interface, so tests use a fake.
 */
final class CommandRunner {

    private static final Logger LOG = Logger.getLogger(CommandRunner.class.getName());

    /** Default for how long a typed {@code :} command may run before its output is given up. */
    static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(60);
    /** Default for how often a running command is checked for a prompt that waits for a key. */
    static final Duration CAPTURE_POLL = Duration.ofMillis(250);

    /** What the runner needs from Neovim. All calls block, on the input thread. */
    interface Neovim {
        /** Types {@code keys} (Neovim notation) as if the user pressed them ({@code nvim_input}). */
        void type(String keys) throws Exception;

        /** Whether Neovim waits for more input, such as a prompt's key ({@code nvim_get_mode}). */
        boolean isBlocked();

        /** Starts the {@code :redir} capture ({@link LuaScripts#START_OUTPUT_CAPTURE}). */
        void startCapture() throws Exception;

        /**
         * Asks Neovim to end the capture ({@link LuaScripts#STOP_OUTPUT_CAPTURE}). Not waited for:
         * the answer comes only once the typed command has finished.
         *
         * @return completes with the collected text (or {@code null})
         */
        CompletableFuture<Object> stopCapture();

        /** Runs {@code cmd} with {@code nvim_exec2} and returns what it printed ({@link LuaScripts#CAPTURE_OUTPUT}). */
        Object captureOutput(String cmd) throws Exception;

        /** Runs {@code keys} with {@code :normal!} ({@link LuaScripts#NORMAL_WITHOUT_MAPPINGS}). */
        void normalWithoutMappings(String keys) throws Exception;

        /** Applies Neovim's buffer to the Eclipse document. */
        void syncBuffer();

        /** Reads mode and cursor and passes them to the listeners. */
        void syncModeAndCursor();
    }

    private final Neovim nvim;
    private final Duration commandTimeout;
    private final Duration capturePoll;

    /**
     * Whether a {@code :redir} typed by the user is collecting output (from {@code :redir @a} until
     * {@code :redir END}); no output capture is done meanwhile, it would take that redirection over.
     */
    private boolean userRedirActive;

    CommandRunner(Neovim nvim) {
        this(nvim, COMMAND_TIMEOUT, CAPTURE_POLL);
    }

    /**
     * @param commandTimeout how long to wait for a typed command's output (tests use milliseconds)
     * @param capturePoll    how often to check for a prompt meanwhile
     */
    CommandRunner(Neovim nvim, Duration commandTimeout, Duration capturePoll) {
        this.nvim = nvim;
        this.commandTimeout = commandTimeout;
        this.capturePoll = capturePoll;
    }

    /**
     * Types {@code :cmd<CR>}, then refreshes buffer, mode and cursor, and passes what the command
     * printed ({@code :echo}, {@code :set ts?}, {@code :!ls}) to {@code onOutput}. Nothing is passed
     * when it printed nothing, or only an error (the status bar shows errors).
     *
     * <p>The command is typed, not run with {@code nvim_command}: Neovim then records it in a macro
     * being recorded and knows it for {@code @:} and the history.
     *
     * <p>Example: {@code run("redir @a", out)} starts the user's redirection; until
     * {@code run("redir END", out)} nothing is captured and {@code out} gets nothing, as in Vim,
     * where the output goes to register a.
     *
     * @param onOutput receives the printed text; {@code null} to ignore it
     */
    void run(String cmd, Consumer<String> onOutput) throws Exception {
        OutputCapture.Redir redir = OutputCapture.redirOf(cmd);
        boolean capture = onOutput != null && redir == OutputCapture.Redir.NONE && !userRedirActive;
        if (redir != OutputCapture.Redir.NONE) {
            userRedirActive = redir == OutputCapture.Redir.START;
        }
        String output = "";
        if (capture) {
            nvim.startCapture();
        }
        try {
            typeCommand(cmd);
        } finally {
            // Also when typing failed: a capture left on would collect everything after it.
            if (capture) {
                output = stopCapture();
            }
        }
        nvim.syncBuffer();
        nvim.syncModeAndCursor();
        if (onOutput != null && !output.isBlank()) {
            onOutput.accept(output);
        }
    }

    /**
     * Runs a command that only displays information (see {@code ListingCommand}) and passes what it
     * printed, or its error message, to {@code onOutput}. Not typed, so a macro being recorded does
     * not include it (it changes nothing anyway).
     *
     * <p>While the user's own {@code :redir} runs, the command is typed instead and nothing is
     * passed: {@code nvim_exec2} would keep the output from that redirection, and in Vim the
     * redirection gets it ({@code :redir @a}, {@code :echo 'x'}, {@code :redir END} puts "x" in a).
     */
    void capture(String cmd, Consumer<String> onOutput) throws Exception {
        if (userRedirActive) {
            typeCommand(cmd);
            nvim.syncModeAndCursor();
            return;
        }
        String output;
        try {
            Object raw = nvim.captureOutput(cmd);
            output = raw == null ? "" : String.valueOf(raw);
        } catch (Exception e) {
            output = Rpc.errorMessage(e);
        }
        onOutput.accept(output.startsWith("\n") ? output.substring(1) : output);
    }

    /**
     * Runs normal-mode {@code keys} without mappings ({@code :normal!}), then refreshes Eclipse.
     * Used for Neovim's own meaning of a key the plug-in maps to an IDE action, for example
     * {@code gd} in a file that has no Open Declaration.
     */
    void normal(String keys) throws Exception {
        nvim.normalWithoutMappings(keys);
        nvim.syncBuffer();
        nvim.syncModeAndCursor();
    }

    /**
     * Answers a search line the user opened in Neovim ({@code d/} through a mapping, say), which
     * Neovim keeps open while the editor's box edits the pattern: replaces what Neovim's line holds
     * with {@code pattern} and runs it ({@code <C-u>pattern<CR>}), then refreshes Eclipse. Answering
     * the open line, rather than typing a new search, keeps a pending operator.
     */
    void answerSearch(String pattern, boolean backward) throws Exception {
        nvim.type("<C-u>" + SearchCommand.body(pattern, backward) + "<CR>");
        dismissPrompt();
        nvim.syncBuffer();
        nvim.syncModeAndCursor();
    }

    /** Types a search ({@code /pattern<CR>} or {@code ?pattern<CR>}), then refreshes Eclipse. */
    void search(String pattern, boolean backward) throws Exception {
        nvim.type(SearchCommand.build(pattern, backward));
        dismissPrompt();
        nvim.syncBuffer();
        nvim.syncModeAndCursor();
    }

    /** Types {@code :cmd<CR>} and dismisses a "press Enter" prompt it leaves. */
    private void typeCommand(String cmd) throws Exception {
        nvim.type(NvimKeys.exCommand(cmd));
        dismissPrompt();
    }

    /**
     * Dismisses a "press Enter" prompt the typed command or search left, which Eclipse cannot show.
     * A search that finds nothing leaves one since the command line is external
     * ({@code ext_cmdline}); its message still reaches the message line through {@code v:errmsg}.
     */
    private void dismissPrompt() throws Exception {
        if (nvim.isBlocked()) {
            nvim.type("<Esc>");
        }
    }

    /**
     * Ends the {@code :redir} capture and returns what it collected.
     *
     * <p>Neovim answers this request only once the typed command has finished. While waiting, the
     * mode is checked every {@code capturePoll} (Neovim answers that at once): if Neovim is stuck at
     * a prompt, an Esc dismisses it.
     *
     * <p>A command still running after {@code commandTimeout} is not waited for any longer: its
     * output is dropped and the editor is refreshed. The request stays queued in Neovim, which
     * handles requests in order, so {@code redir END} still runs when the command finishes, before
     * any later capture starts; the redirection is never left on.
     *
     * @return the collected text, or "" when the command did not finish in time
     */
    private String stopCapture() throws Exception {
        CompletableFuture<Object> stop = nvim.stopCapture();
        long deadline = System.nanoTime() + commandTimeout.toNanos();
        while (true) {
            try {
                Object raw = stop.get(capturePoll.toMillis(), TimeUnit.MILLISECONDS);
                return raw == null ? "" : String.valueOf(raw);
            } catch (TimeoutException e) {
                if (System.nanoTime() > deadline) {
                    LOG.warning("':' command still running after " + commandTimeout.toSeconds()
                            + " s; its output is not shown");
                    return "";
                }
                if (nvim.isBlocked()) {
                    LOG.fine("command left Neovim at a prompt; sending Esc");
                    nvim.type("<Esc>");
                }
            }
        }
    }
}
