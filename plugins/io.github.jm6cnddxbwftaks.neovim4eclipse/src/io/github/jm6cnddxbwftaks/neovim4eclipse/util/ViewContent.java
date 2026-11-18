// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * What a page editor shows, as the editing Neovim hands it over: text ({@link ViewText}:
 * {@code :messages}, {@code :checkhealth}) or a help page ({@link HelpPage}: {@code :help}). Each
 * kind has its own page editor and second Neovim ({@code editor/NeovimPageEditor}): text is
 * shown by one that loads none of the user's plug-ins, help by one with the user's
 * {@code 'runtimepath'}, so that the user's own help pages and help plug-ins work as in their
 * editor. Sealed, so a {@code switch} over it names
 * every kind.
 *
 * <p>Example: {@code :help help-context} becomes {@code HelpPage("help-context", "helphelp.txt", 27,
 * "/home/you/.config/nvim,...")}, titled "help".
 */
public sealed interface ViewContent permits ViewText, HelpPage {

    /** What the view's title says after "Neovim: ", such as {@code messages} or {@code help}. */
    String title();
}
