// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.Optional;

/**
 * What the Neovim session needs from the system clipboard: put text on it, and read text from it.
 *
 * <p>An interface (rather than a direct call to the SWT-based bridge) so that {@code core}
 * stays free of UI dependencies, can run headless in tests, and respects the layering
 * {@code editor -> core}, never the reverse. The port moves plain text only; which Neovim register
 * maps to which {@link Selection}, and whether a paste is linewise, is decided in
 * {@link ClipboardRegisters}.
 */
public interface ClipboardPort {

    /**
     * Which system clipboard. Most systems have one; X11 and Wayland on Linux have two, and Vim
     * names them with two registers.
     */
    enum Selection {
        /** The clipboard of Ctrl+C and Ctrl+V, Neovim's {@code "+} register. */
        CLIPBOARD,
        /**
         * Linux's primary selection: whatever was last selected, pasted with the middle mouse
         * button; Neovim's {@code "*} register. Where there is none (Windows, macOS) it is the same
         * as {@link #CLIPBOARD}, as in Vim.
         */
        PRIMARY
    }

    /** A port that ignores copies and has nothing to paste; used by tests and headless callers. */
    ClipboardPort NONE = new ClipboardPort() {
        @Override
        public void copy(Selection selection, String text) {
        }

        @Override
        public Optional<String> paste(Selection selection) {
            return Optional.empty();
        }
    };

    /**
     * Puts {@code text} on the system clipboard. Called from an RPC thread; must not block and must
     * be thread-safe (the SWT implementation queues the work to the UI thread).
     */
    void copy(Selection selection, String text);

    /**
     * The text on the system clipboard, or empty if it holds no text or could not be read in time.
     *
     * <p>Called from the RPC thread while Neovim waits for the answer (Neovim asks for the
     * clipboard when it pastes {@code "+p}), so it may block, but only briefly: implementations give
     * up after a short timeout rather than leave Neovim waiting.
     */
    Optional<String> paste(Selection selection);
}
