// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * The buffer arguments of {@code :bd} ({@code :bd 3}, {@code :bd other.txt}): how to find the
 * buffer, and what to say when there is none. Vim accepts a buffer number or a buffer name, of
 * which a unique part is enough ({@code :bd oth} for {@code other.txt}), the same rule as
 * {@code :b}. Numbers are used as they are; names are looked up with Neovim's {@code bufnr()},
 * so the matching is exactly Vim's.
 *
 * <p>Example: {@code :bd other} sends {@code echo bufnr('other')} to Neovim, which prints
 * {@code 4} (or {@code -1} when no buffer, or more than one, matches); the editor of buffer 4 is
 * then closed. {@code HostCommand} has the command, {@code EditorAttachManager.closeBuffers}
 * does the closing.
 */
public final class BufferRefs {

    private BufferRefs() {
    }

    /**
     * Whether {@code ref} is a buffer number ({@code 3}) rather than a name. Nine digits at most,
     * so that it fits in an {@code int}; a longer "number" is looked up as a name and not found.
     */
    public static boolean isNumber(String ref) {
        return !ref.isEmpty() && ref.length() <= 9 && ref.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    /**
     * The Vim expression that finds the buffer named {@code ref}: {@code bufnr('other.txt')}. In a
     * single-quoted Vim string only {@code '} is special, written twice: {@code it's} becomes
     * {@code 'it''s'}.
     */
    public static String bufnrExpression(String ref) {
        return "bufnr('" + ref.replace("'", "''") + "')";
    }

    /**
     * The buffer number from {@link #bufnrExpression}'s value, or -1 for anything else (no match,
     * or an error message).
     */
    public static int parseBufnr(String printed) {
        String number = printed == null ? "" : printed.strip();
        return isNumber(number) ? Integer.parseInt(number) : -1;
    }

    /**
     * Vim's message when {@code :bd ref} finds no buffer to delete: E516 for a number (Vim's
     * {@code :bd 99}), E94 for a name. Here "no buffer" also means a buffer that no editor shows,
     * such as a help buffer, which Eclipse cannot close.
     */
    public static String notFoundMessage(String ref) {
        return isNumber(ref) ? "E516: No buffers were deleted: bd " + ref : "E94: No matching buffer for " + ref;
    }
}
