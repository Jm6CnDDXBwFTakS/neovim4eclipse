// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.function.Consumer;

/**
 * What Eclipse shows while the editing Neovim starts on the UI thread: a message in the status bar
 * and the busy cursor, around a start that stays synchronous.
 *
 * <p><b>Why synchronous.</b> The first editor's attach (and the next one after Neovim quit or was
 * restarted) starts Neovim on the UI thread: about 0.2 s on Linux, 0.5 to 0.6 s on macOS and
 * Windows, plus the user's {@code init.lua} (docs/ARCHITECTURE.md, "Threads"). While the UI thread
 * waits, the keys the user types wait in the system's queue, and reach Neovim in order once the
 * editor is attached: {@code dd} right after opening a file deletes a line. A start in the
 * background would leave the editor to Eclipse's own editing meanwhile ({@code dd} typed as text),
 * and a progress dialog would take the keys itself. So the start blocks, and the user is told so.
 *
 * <p><b>Order.</b> The message is set and painted ({@code repaint}, {@code Display.update()}) before
 * the start blocks the UI thread, which paints nothing until it is done; the start runs inside
 * {@code busy} ({@code BusyIndicator.showWhile}, which runs it on the same thread, with the busy
 * cursor); the message is cleared afterward, failed or not, before the caller reports a failure.
 * Nothing is shown when Neovim is already running ({@code starting} false): the start is then a
 * look at a running session. Example, in the editor's attach:
 *
 * <pre>{@code
 * session = StartingNotice.around(starting, statusLine::setMessage, display::update,
 *         work -> BusyIndicator.showWhile(display, work), activator::getOrCreateSession);
 * }</pre>
 */
public final class StartingNotice {

    /** The status bar's message during the start. */
    public static final String MESSAGE = "Neovim is starting, please wait…";

    private StartingNotice() {
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
     * Runs {@code start}, with the message and the busy cursor if {@code starting}.
     *
     * @param starting whether Neovim is not running yet, so the start will take a while
     * @param status   sets the status bar's message; {@code null} clears it
     * @param repaint  paints what is pending now, before the UI thread blocks
     * @param busy     runs the start with the busy cursor, on this thread
     * @param start    the start
     * @return what {@code start} returned
     * @throws Exception what {@code start} threw, after the message is cleared
     */
    public static <T> T around(boolean starting, Consumer<String> status, Runnable repaint, Busy busy, Start<T> start)
            throws Exception {
        if (!starting) {
            return start.run();
        }
        status.accept(MESSAGE);
        repaint.run();
        Object[] result = new Object[1];
        Exception[] failure = new Exception[1];
        try {
            busy.showWhile(() -> {
                try {
                    result[0] = start.run();
                } catch (Exception e) {
                    failure[0] = e;
                }
            });
        } finally {
            status.accept(null);
        }
        if (failure[0] != null) {
            throw failure[0];
        }
        @SuppressWarnings("unchecked")
        T started = (T) result[0];
        return started;
    }
}
