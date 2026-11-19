// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.concurrent.TimeUnit;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;

/**
 * Lets an integration test check that something did <em>not</em> happen, without sleeping.
 *
 * <p>The problem: a test such as "a yank is copied once, not twice" waits for the first copy, then
 * must make sure no second one follows. It used to sleep 200 ms and look. On a fast machine that
 * wastes time; on a loaded one the stray copy can come later than 200 ms, so the test passes
 * although the bug is there.
 *
 * <p>The barrier: one request that Neovim answers only after everything before it.
 * <ul>
 *   <li>First it waits for the session's input thread to run what is queued there
 *       ({@code NeovimSession.queuedWorkDone}): an {@code *Async} call only queues its work, and the
 *       request below, sent from the test thread, would otherwise overtake it. Example:
 *       {@code setBackgroundAsync(true)}, then {@code pass}, then reading {@code 'background'}:
 *       without this step the read can come before the change (a queued register write once did,
 *       on a Windows machine, where the input thread was not yet started).</li>
 *   <li>Neovim runs a non-fast API call such as {@code nvim_exec_lua} from its main loop, only once
 *       the keys queued before it have been handled (unlike {@code nvim_get_mode}, which is "fast"
 *       and answered at once). So by then every autocmd those keys fired has run, and every
 *       {@code rpcnotify} and {@code nvim_buf_lines_event} they caused has been written.</li>
 *   <li>Neovim writes its messages to the channel in order, so those notifications come before the
 *       answer.</li>
 *   <li>The plug-in's reader thread handles messages one at a time, in arrival order, and calls the
 *       listeners itself (see {@code RpcChannel}, "Threads"). So when the answer completes the
 *       request, every listener for those notifications has already returned.</li>
 * </ul>
 * After {@link #pass}, whatever the keys and {@code *Async} calls before it caused has reached the
 * test's listeners: what has not arrived will not.
 *
 * <pre>
 * keys(s, "\"", "+", "y", "y");
 * waitFor(() -&gt; !system.copies.isEmpty(), "the copy");
 * NeovimBarrier.pass(s);
 * assertEquals(List.of("CLIPBOARD:one" + NL), system.copies); // exactly one
 * </pre>
 *
 * <p>Limits: it covers what Neovim does in response to the keys and calls, not work Neovim deferred on
 * purpose ({@code vim.schedule}, {@code vim.defer_fn}, a timer). And it fails after the session's
 * RPC limit ({@code Rpc.TIMEOUT_SEC}) if Neovim is in the middle of a command (after {@code f},
 * waiting for the character), because Neovim holds non-fast requests back then. Same idea as
 * {@code CommandRunner.Neovim.awaitIdle} in the plug-in.
 */
final class NeovimBarrier {

    private NeovimBarrier() {
    }

    /**
     * Returns once Neovim has sent, and the session has handled, everything the keys and queued
     * calls before caused. Waits at most {@link TestWait#millis} for the queue.
     */
    static void pass(NeovimSession session) throws Exception {
        session.queuedWorkDone().get(TestWait.millis(), TimeUnit.MILLISECONDS);
        session.lua("return 0");
    }
}
