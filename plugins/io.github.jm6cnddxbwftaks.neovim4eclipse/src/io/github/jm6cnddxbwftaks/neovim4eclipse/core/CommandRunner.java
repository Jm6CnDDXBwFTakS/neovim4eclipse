// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimKeys;

/**
 * Runs {@code :} commands for the plug-in and its tests ({@link NeovimSession#commandAsync}) and
 * {@code :normal!} keys, then refreshes Eclipse from Neovim. (What the user types on the {@code :}
 * line goes to Neovim's own command line key by key.)
 *
 * <p>Commands are <em>typed</em> ({@code :ls<CR>}), not run with
 * {@code nvim_command}: Neovim then records them in a macro being recorded, keeps them in its
 * history and knows the last one for {@code @:}, as when typed in Vim. What they print does not pass
 * through here: Neovim shows it itself, in ui2's windows ({@link LuaScripts#UI2}), which the
 * editor draws.
 *
 * <p>Every method blocks and must run on the session's input thread; {@link NeovimSession} queues
 * them there. Neovim is reached only through the small {@link Neovim} interface, so tests use a fake.
 */
final class CommandRunner {

    private static final Logger LOG = Logger.getLogger(CommandRunner.class.getName());

    /** Default for how long a typed command ({@code :!make}) may run before Eclipse stops waiting. */
    static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(60);

    /** What the runner needs from Neovim. All calls block, on the input thread. */
    interface Neovim {
        /** Types {@code keys} (Neovim notation) as if the user pressed them ({@code nvim_input}). */
        void type(String keys) throws Exception;

        /**
         * Returns once Neovim has finished with what was typed: any request is answered only when
         * Neovim is idle, so this one waits while {@code :!make} runs.
         *
         * @throws TimeoutException if Neovim is still busy after {@code timeout}
         */
        void awaitIdle(Duration timeout) throws Exception;

        /** Runs {@code keys} with {@code :normal!} ({@link LuaScripts#NORMAL_WITHOUT_MAPPINGS}). */
        void normalWithoutMappings(String keys) throws Exception;

        /** Applies Neovim's buffer to the Eclipse document. */
        void syncBuffer();

        /** Reads mode and cursor and passes them to the listeners. */
        void syncModeAndCursor();
    }

    private final Neovim nvim;
    private final Duration commandTimeout;

    CommandRunner(Neovim nvim) {
        this(nvim, COMMAND_TIMEOUT);
    }

    /** @param commandTimeout how long to wait for a typed command to finish (tests use milliseconds) */
    CommandRunner(Neovim nvim, Duration commandTimeout) {
        this.nvim = nvim;
        this.commandTimeout = commandTimeout;
    }

    /**
     * Types {@code :cmd<CR>}, waits until it has run, then refreshes buffer, mode and cursor.
     * Example: {@code run("ls")} types {@code :ls<CR>}; Neovim shows the listing itself (ui2).
     */
    void run(String cmd) throws Exception {
        typeAndRefresh(NvimKeys.exCommand(cmd));
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
     * Types {@code keys}, waits until Neovim has run them and
     * refreshes Eclipse. A command still running after {@code commandTimeout} is not waited for
     * any longer; the refresh then waits its own (short) time and may fail, and the next key or
     * Neovim's own events bring Eclipse up to date.
     */
    private void typeAndRefresh(String keys) throws Exception {
        nvim.type(keys);
        try {
            nvim.awaitIdle(commandTimeout);
        } catch (TimeoutException e) {
            // The command's text only at FINE: a WARNING reaches the Error Log and .metadata/.log,
            // and a command line may hold a secret (a token in :!curl -H ...), as for
            // NeovimSession.commandAsync's label.
            LOG.warning("Command still running after " + commandTimeout.toSeconds() + " s");
            LOG.fine(() -> "The command still running: " + keys);
        }
        nvim.syncBuffer();
        nvim.syncModeAndCursor();
    }
}
