// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import java.util.List;

/**
 * What the Neovim session needs from the system clipboard.
 *
 * <p>An interface (rather than a direct call to the SWT-based bridge) so that {@code core}
 * stays free of UI dependencies, can run headless in tests, and respects the layering
 * {@code editor -> core}, never the reverse.
 */
public interface ClipboardPort {

    /** A port that ignores everything; used by tests and headless callers. */
    ClipboardPort NONE = (lines, linewise) -> { };

    /**
     * Called (from an RPC thread) when text is yanked in Neovim. Implementations must be thread-safe.
     *
     * @param linewise {@code true} for a whole-line yank ({@code yy}, {@code dd}, ...). Other
     *                 applications mark a whole-line copy with a trailing line break, and so must we.
     */
    void copyToSystem(List<String> lines, boolean linewise);
}
