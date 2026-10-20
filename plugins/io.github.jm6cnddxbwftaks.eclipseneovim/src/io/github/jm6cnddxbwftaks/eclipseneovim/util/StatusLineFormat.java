// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import java.util.Set;

/** Builds the text of the Eclipse status bar item from the parts Neovim reports. Pure Java. */
public final class StatusLineFormat {

    /** Between two parts. */
    static final String SEPARATOR = "  ";

    private StatusLineFormat() {
    }

    /**
     * Joins the enabled, non-empty parts in display order: mode, cmd, statusline.
     * Free text is cleaned first (see {@link #cleanText}).
     *
     * @param items      what the user chose to show
     * @param mode       mode text (already including {@code recording @x} if applicable)
     * @param cmd        pending command text
     * @param statusline text of Neovim's statusline
     */
    public static String compose(Set<StatusItem> items, String mode, String cmd, String statusline) {
        StringBuilder sb = new StringBuilder();
        append(sb, items.contains(StatusItem.MODE), mode);
        append(sb, items.contains(StatusItem.CMD), cmd);
        append(sb, items.contains(StatusItem.STATUSLINE), statusline);
        return sb.toString();
    }

    private static void append(StringBuilder sb, boolean enabled, String text) {
        String clean = enabled ? cleanText(text) : "";
        if (clean.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(SEPARATOR);
        }
        sb.append(clean);
    }

    /**
     * Makes Neovim text fit on one status bar line: line breaks and runs of whitespace (a
     * statusline's {@code %=} expands to padding) become a single space, and the ends are trimmed.
     */
    public static String cleanText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").strip();
    }
}
