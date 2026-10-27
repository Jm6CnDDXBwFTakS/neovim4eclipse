// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

/**
 * The UI thread, seen as an executor. SWT widgets and JFace documents may only be touched on that
 * thread, while Neovim's events arrive on session threads; code that hands work over says so
 * through this interface instead of calling {@code Display} directly.
 *
 * <p>Why an interface: the editor uses {@code editor/SwtUiExecutor} (the SWT {@code Display}), and
 * tests use {@link #IMMEDIATE} or a fake that holds work back, e.g. to check what happens when a
 * Neovim change is still queued for the UI thread while the editor closes.
 */
public interface UiExecutor {

    /**
     * Runs everything at once on the calling thread, which counts as the UI thread. For tests whose
     * code already runs where the UI thread would be.
     */
    UiExecutor IMMEDIATE = new UiExecutor() {
        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean isUiThread() {
            return true;
        }

        @Override
        public void asyncExec(Runnable work) {
            work.run();
        }

        @Override
        public void syncExec(Runnable work) {
            work.run();
        }
    };

    /** {@code false} once the UI is gone (the workbench is shutting down); work is then dropped. */
    boolean isAvailable();

    /** Whether the calling thread is the UI thread. */
    boolean isUiThread();

    /** Runs {@code work} on the UI thread later; returns at once. */
    void asyncExec(Runnable work);

    /**
     * Runs {@code work} on the UI thread after about {@code delayMs} milliseconds; returns at once.
     * For a debounce: many changes in a row, one piece of work after the last
     * ({@code sync/DiagnosticsSync}). The default runs it as soon as {@link #asyncExec} would, which
     * suits {@link #IMMEDIATE} and the tests' fakes; the editor's executor waits (SWT's
     * {@code Display.timerExec}).
     */
    default void timerExec(int delayMs, Runnable work) {
        asyncExec(work);
    }

    /** Runs {@code work} on the UI thread and waits for it. Never call it while holding a lock the UI thread needs. */
    void syncExec(Runnable work);
}
