// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * What a page editor shows, as the editing Neovim hands it over: text the plug-in puts into a
 * page buffer of its own ({@link ViewText}: {@code :messages}), or a buffer Neovim made itself
 * ({@link PageBuffer}: {@code :help}, {@code :checkhealth}). Either way the page editor shows a
 * window of the editing Neovim ({@code editor/WindowPane}). Sealed, so a {@code switch} over it
 * names every kind.
 *
 * <p>Example: {@code :help help-context} becomes {@code PageBuffer("help", 7, 27)}, titled "help".
 */
public sealed interface ViewContent permits ViewText, PageBuffer {

    /** What the page editor's title says after "Neovim: ", such as {@code messages} or {@code help}. */
    String title();
}
