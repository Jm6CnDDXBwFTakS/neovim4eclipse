// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.List;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Diagnostic;

/**
 * What sending a document's Eclipse problems to its Neovim buffer needs from the session
 * ({@code sync/DiagnosticsSync}). Neovim keeps them as diagnostics ({@code :help diagnostic}), so its
 * own {@code ]d} / {@code [d} move between them.
 *
 * <p>An interface (rather than the whole {@link NeovimSession}) so that the sync class is tested with a
 * small fake in {@code tests/text}. {@link NeovimSession} implements it.
 */
public interface DiagnosticsPort {

    /**
     * Whether Eclipse's problems go to Neovim: {@code true} unless the user's config sets
     * {@code vim.g.neovim4eclipse_problems = 0} (or {@code false}). Read once when the session starts
     * (a change needs a Neovim restart); when false, the editor starts no {@code DiagnosticsSync}, so
     * nothing is read or sent.
     */
    boolean problemsEnabled();

    /**
     * Replaces the diagnostics of {@code buffer} with {@code diagnostics} (an empty list clears them),
     * on the input thread, after every key and command queued before. Returns at once. Nothing happens
     * when the buffer does not exist in this session or has no text yet: the document's next list,
     * sent after its first attach, brings them.
     */
    void setDiagnosticsAsync(DocumentBuffer buffer, List<Diagnostic> diagnostics);
}
