// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * Which page editor shows a piece of {@link ViewContent}: one editor per kind, each with a second
 * Neovim of its own ({@code editor/NeovimPageEditor}). Running the same command again shows its
 * content in the same editor; another kind opens another editor. Example: {@code :help} and then
 * {@code :messages} give two editors, "Neovim: help" and "Neovim: messages"; a second
 * {@code :help} reuses the first.
 */
public enum PageKind {
    /** {@code :help}, {@code :helpgrep}: a {@link HelpPage}. */
    HELP(HelpPage.TITLE),
    /** {@code :messages}: the message history, a {@link ViewText}. */
    MESSAGES(ViewText.MESSAGES),
    /** {@code :checkhealth}: a report, a {@link ViewText}. */
    CHECKHEALTH(ViewText.CHECKHEALTH);

    private final String title;

    PageKind(String title) {
        this.title = title;
    }

    /** What the editor's title says after "Neovim: ", such as {@code help}. */
    public String title() {
        return title;
    }

    /** The kind of editor that shows {@code content}. */
    public static PageKind of(ViewContent content) {
        return switch (content) {
            case HelpPage page -> HELP;
            case ViewText text -> ViewText.CHECKHEALTH.equals(text.title()) ? CHECKHEALTH : MESSAGES;
        };
    }
}
