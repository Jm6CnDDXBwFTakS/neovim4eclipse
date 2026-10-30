// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * Makes the texts Neovim reports fit a status bar label: one line, no stray whitespace. Pure Java.
 * Each part has a label of its own ({@code editor/StatusBarLabels}), so nothing here joins them.
 */
public final class StatusLineFormat {

    private StatusLineFormat() {
    }

    /**
     * For short free text (the pending command): line breaks and runs of whitespace become a single
     * space, and the ends are trimmed. Example: {@code "  2\nd "} gives {@code "2 d"}.
     */
    public static String cleanText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").strip();
    }

    /**
     * For Neovim's evaluated {@code 'statusline'}: one line, its spacing kept. Neovim fits it into the
     * label's width ({@code maxwidth}), and {@code %=} pads the middle so the cursor position ends at
     * the right, as in Vim; collapsing that padding would pull it left again. Line breaks and tabs
     * become spaces and the end is trimmed. Example: {@code "a.txt      1,1   All  "} keeps its
     * inner spaces and loses the last two.
     */
    public static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\r\\n\\t]", " ").stripTrailing();
    }
}
