// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * The editing Neovim's start on the UI thread, under the busy cursor: synchronous on purpose.
 *
 * <p><b>Why synchronous.</b> The first editor's attach (and the next one after Neovim quit or was
 * restarted) starts Neovim on the UI thread: about 0.2 s on Linux, 0.5 to 0.6 s on macOS and
 * Windows, plus the user's {@code init.lua} (docs/ARCHITECTURE.md, "Threading rules"). While the
 * UI thread waits, the keys the user types wait in the system's queue, and reach Neovim in order
 * once the editor is attached: {@code dd} typed while Neovim starts deletes a line. A start in the
 * background would leave the editor to Eclipse's own editing meanwhile ({@code dd} typed as
 * text), and a progress dialog would take the keys itself.
 *
 * <p><b>Only the busy cursor.</b> {@code BusyIndicator.showWhile} sets it on Eclipse's windows
 * and runs the start on the same thread, without running the event loop, so no key is delivered
 * meanwhile. A message in the status bar was tried: GTK and Cocoa paint only from the event loop,
 * so it never showed on Linux or macOS (only Windows paints on {@code Display.update()}), and
 * painting it would mean running the loop, which delivers the keys to Eclipse.
 *
 * <p>Nothing is done when Neovim is already running ({@code starting} false): the start is then a
 * look at a running session. Example, in the editor's attach:
 *
 * <pre>{@code
 * session = BusyStart.run(starting, work -> BusyIndicator.showWhile(display, work),
 *         activator::getOrCreateSession);
 * }</pre>
 */
public final class BusyStart {

    private BusyStart() {
    }

    /** Runs work with the busy cursor, on the calling thread ({@code BusyIndicator.showWhile}). */
    @FunctionalInterface
    public interface Busy {
        void showWhile(Runnable work);
    }

    /** The start itself, which may fail. */
    @FunctionalInterface
    public interface Start<T> {
        T run() throws Exception;
    }

    /**
     * Runs {@code start}, under the busy cursor if {@code starting}.
     *
     * @param starting whether Neovim is not running yet, so the start will take a while
     * @param busy     runs the start with the busy cursor, on this thread
     * @param start    the start
     * @return what {@code start} returned
     * @throws Exception what {@code start} threw, once the busy cursor is gone
     */
    public static <T> T run(boolean starting, Busy busy, Start<T> start) throws Exception {
        if (!starting) {
            return start.run();
        }
        Object[] result = new Object[1];
        Exception[] failure = new Exception[1];
        busy.showWhile(() -> {
            try {
                result[0] = start.run();
            } catch (Exception e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        @SuppressWarnings("unchecked")
        T started = (T) result[0];
        return started;
    }
}
