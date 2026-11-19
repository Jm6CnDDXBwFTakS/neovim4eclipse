// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * Which page editor shows a piece of {@link ViewContent}: one editor per kind
 * ({@code editor/NeovimPageEditor}), each a window of the editing Neovim. Running the same command
 * again shows its content in the same editor; another kind opens another editor. Example:
 * {@code :help} and then {@code :messages} give two editors, "Neovim: help" and "Neovim: messages";
 * a second {@code :help} reuses the first.
 */
public enum PageKind {
    /** {@code :help}, {@code :helpgrep}: a help buffer, a {@link PageBuffer}. */
    HELP(PageBuffer.HELP),
    /** {@code :messages}: the message history, a {@link ViewText}. */
    MESSAGES(ViewText.MESSAGES),
    /** {@code :checkhealth}: the report's buffer, a {@link PageBuffer}. */
    CHECKHEALTH(PageBuffer.CHECKHEALTH);

    private final String title;

    PageKind(String title) {
        this.title = title;
    }

    /** What the editor's title says after "Neovim: ", such as {@code help}; Neovim's name for the kind too. */
    public String title() {
        return title;
    }

    /** The kind of editor that shows {@code content}. */
    public static PageKind of(ViewContent content) {
        return switch (content) {
            case PageBuffer page -> PageBuffer.CHECKHEALTH.equals(page.title()) ? CHECKHEALTH : HELP;
            case ViewText text -> MESSAGES;
        };
    }
}
