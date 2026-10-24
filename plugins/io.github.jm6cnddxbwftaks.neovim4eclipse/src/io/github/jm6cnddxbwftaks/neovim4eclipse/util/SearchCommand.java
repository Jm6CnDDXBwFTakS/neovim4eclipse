// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/** Builds the key sequence that makes Neovim run a literal-text search. */
public final class SearchCommand {

    private SearchCommand() {
    }

    /**
     * Builds {@code /pattern<CR>} (or {@code ?pattern<CR>}) for {@code nvim_input}.
     *
     * <p>Backslashes and the search delimiter are escaped so the dialog text is treated as the
     * user typed it. {@code <} is sent as {@code <lt>} because {@code nvim_input} would otherwise
     * read text such as {@code <Esc>} inside the pattern as a key press.
     *
     * @param pattern  non-empty pattern text
     * @param backward {@code true} for {@code ?}, {@code false} for {@code /}
     */
    public static String build(String pattern, boolean backward) {
        return (backward ? "?" : "/") + body(pattern, backward) + "<CR>";
    }

    /**
     * The pattern as typed after the {@code /} or {@code ?}, escaped as {@link #build} does, for
     * typing into a search line that is already open.
     */
    public static String body(String pattern, boolean backward) {
        char delimiter = backward ? '?' : '/';
        StringBuilder sb = new StringBuilder(pattern.length() + 4);
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' || c == delimiter) {
                sb.append('\\');
            }
            if (c == '<') {
                sb.append("<lt>");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
