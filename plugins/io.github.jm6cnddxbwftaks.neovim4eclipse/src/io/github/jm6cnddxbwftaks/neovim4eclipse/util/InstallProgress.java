// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * How <i>Get Neovim</i>'s steps report progress and learn of a cancel, without knowing Eclipse: the
 * install Job adapts its {@code IProgressMonitor} to this (one step at a time, each with its own
 * total), and the tests pass {@link #NONE}.
 */
public interface InstallProgress {

    /** Reports nothing and is never cancelled. */
    InstallProgress NONE = new InstallProgress() {
        @Override
        public void step(String name, long total) {
        }

        @Override
        public void worked(long amount) {
        }

        @Override
        public boolean cancelled() {
            return false;
        }
    };

    /**
     * A new step begins.
     *
     * @param name  what it does, for the progress line: {@code Downloading nvim-linux-arm64.tar.gz}
     * @param total how much work it is (bytes), or {@code -1} if unknown
     */
    void step(String name, long total);

    /** {@code amount} more of the current step is done. */
    void worked(long amount);

    /** Whether the user asked to stop. */
    boolean cancelled();

    /**
     * Stops with {@link NeovimInstallException.Reason#CANCELLED} if the user asked to.
     *
     * @throws NeovimInstallException if cancelled
     */
    default void checkCancelled() {
        if (cancelled()) {
            throw new NeovimInstallException(NeovimInstallException.Reason.CANCELLED, "Cancelled.");
        }
    }
}
