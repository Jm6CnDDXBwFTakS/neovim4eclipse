// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * Predicates and labels for Neovim mode strings as returned by {@code nvim_get_mode()}.
 *
 * <p>Neovim reports a short code, sometimes with a second letter for a sub-state:
 * {@code n} normal, {@code no} operator-pending (also {@code nov}, {@code noV}, {@code no^V}),
 * {@code v}/{@code V}/{@code ^V} visual (char/line/block), {@code s}/{@code S}/{@code ^S} select,
 * {@code i} insert (also {@code ic}, {@code ix}), {@code R} replace (also {@code Rc}, {@code Rx}, {@code Rv}),
 * {@code c} command-line, {@code t} terminal. See {@code :help mode()}.
 *
 * <p>All mode-string interpretation lives here so the rest of the plug-in never re-implements it;
 * the status bar's texts for each mode are {@link ModeLabels}.
 */
public final class VimModes {

    /** Ctrl-V, the first character of the blockwise-visual mode string. */
    public static final char CTRL_V = '\u0016';
    /** Ctrl-S, the first character of the blockwise-select mode string. */
    public static final char CTRL_S = '\u0013';

    private VimModes() {
    }

    /** Visual mode (charwise, linewise or blockwise). Operator-pending {@code no^V} is <em>not</em> visual. */
    public static boolean isVisual(String mode) {
        char c = first(mode);
        return c == 'v' || c == 'V' || c == CTRL_V;
    }

    /** Insert or replace: the modes where Eclipse, not Neovim, handles typed characters. */
    public static boolean isTyping(String mode) {
        char c = first(mode);
        return c == 'i' || c == 'R';
    }

    /**
     * Insert or replace mode while Neovim completes: {@code ic}, {@code Rc} and {@code Rvc} once a
     * completion is under way ({@code Ctrl-N}, {@code Ctrl-P}, a {@code Ctrl-X} mode), {@code ix},
     * {@code Rx} and {@code Rvx} after {@code Ctrl-X} alone, while Neovim waits for the key that
     * names the kind.
     * Neovim is in it with or without a list on the screen: after {@code Ctrl-N} found a single
     * match, or none, there is no list, and the next key still belongs to the completion
     * ({@code Ctrl-N} again, {@code Ctrl-E} to take the match back).
     */
    public static boolean isCompleting(String mode) {
        if (!isTyping(mode) || mode.length() < 2) {
            return false;
        }
        char last = mode.charAt(mode.length() - 1); // after i, R or Rv
        return last == 'c' || last == 'x';
    }

    /** Command-line mode ({@code :}, {@code /}, {@code ?}). */
    public static boolean isCmdline(String mode) {
        return first(mode) == 'c';
    }

    /**
     * Ex mode ({@code gQ}: {@code cv}, {@code cvr}), where Neovim reads one command line after
     * another until {@code :vi}, and ignores {@code <C-c>}.
     */
    public static boolean isExMode(String mode) {
        return mode != null && mode.startsWith("cv");
    }

    /** Modes that show a thin caret: typing modes and the command line. */
    public static boolean usesThinCaret(String mode) {
        return isTyping(mode) || isCmdline(mode);
    }

    /** Operator-pending (after {@code d}, {@code c}, {@code y}, ... waiting for a motion). */
    public static boolean isOperatorPending(String mode) {
        return mode != null && mode.startsWith("no");
    }

    public static boolean isBlockVisual(String mode) {
        return first(mode) == CTRL_V;
    }

    private static char first(String mode) {
        return mode == null || mode.isEmpty() ? '\0' : mode.charAt(0);
    }
}
