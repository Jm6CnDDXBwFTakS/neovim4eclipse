// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

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
 * After {@link #pass}, whatever the keys before it caused has reached the test's listeners: what has
 * not arrived will not.
 *
 * <pre>
 * keys(s, "\"", "+", "y", "y");
 * waitFor(() -&gt; !system.copies.isEmpty(), "the copy");
 * NeovimBarrier.pass(s);
 * assertEquals(List.of("CLIPBOARD:one" + NL), system.copies); // exactly one
 * </pre>
 *
 * <p>Limits: it covers what Neovim does in response to the keys, not work Neovim deferred on
 * purpose ({@code vim.schedule}, {@code vim.defer_fn}, a timer). And it fails after the session's
 * RPC limit ({@code Rpc.TIMEOUT_SEC}) if Neovim is in the middle of a command (after {@code f},
 * waiting for the character), because Neovim holds non-fast requests back then. Same idea as
 * {@code CommandRunner.Neovim.awaitIdle} in the plug-in.
 */
final class NeovimBarrier {

    private NeovimBarrier() {
    }

    /** Returns once Neovim has sent, and the session has handled, everything the keys before caused. */
    static void pass(NeovimSession session) throws Exception {
        session.lua("return 0");
    }
}
