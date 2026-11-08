// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

/**
 * Lets the UI tests ask Neovim directly, for checks the editor cannot show: an error message
 * ({@code v:errmsg}), the buffer list. {@link NeovimSession#lua} is package-private; this class is
 * in the same package, and the test fragment shares the plug-in's class loader, so it may call it.
 *
 * <p>Blocks until Neovim answers. Fine on the UI thread for these small reads; not for anything
 * that makes Neovim ask Eclipse something back (such as the clipboard).
 */
public final class SessionProbe {

    private SessionProbe() {
    }

    /** Runs {@code code} in Neovim and returns its result as Neovim sends it. */
    public static Object lua(NeovimSession session, String code) {
        try {
            return session.lua(code);
        } catch (Exception e) {
            throw new AssertionError("Neovim did not run: " + code, e);
        }
    }

    /**
     * The class that opens and closes documents' buffers, for {@code ExpectedLog.of(...)}: it is
     * package-private, and a test in another package needs it to check what a close logs.
     */
    public static Class<?> documentBuffers() {
        return DocumentBuffers.class;
    }

    /**
     * Waits until the session's input thread has done the work queued before this call. Example: an
     * editor's close queues its buffer's close ({@code closeBufferAsync}); a test that checks what
     * that close logged waits here first. Any queued request is a barrier, since the input thread
     * runs its work in order; a read of the quickfix list changes nothing.
     */
    public static void afterQueuedWork(NeovimSession session, long timeoutMs) {
        try {
            session.currentQuickfixAsync().get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new AssertionError("The session's queued work did not finish", e);
        }
    }
}
