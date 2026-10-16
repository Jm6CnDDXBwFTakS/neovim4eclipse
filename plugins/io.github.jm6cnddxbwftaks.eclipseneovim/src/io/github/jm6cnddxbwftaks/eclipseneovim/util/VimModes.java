// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import java.util.Locale;

/**
 * Predicates and labels for Neovim mode strings as returned by {@code nvim_get_mode()}.
 *
 * <p>Neovim reports a short code, sometimes with a second letter for a sub-state:
 * {@code n} normal, {@code no} operator-pending (also {@code nov}, {@code noV}, {@code no^V}),
 * {@code v}/{@code V}/{@code ^V} visual (char/line/block), {@code s}/{@code S}/{@code ^S} select,
 * {@code i} insert (also {@code ic}, {@code ix}), {@code R} replace (also {@code Rc}, {@code Rx}, {@code Rv}),
 * {@code c} command-line, {@code t} terminal. See {@code :help mode()}.
 *
 * <p>All mode-string interpretation lives here so the rest of the plug-in never re-implements it.
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

    /** Command-line mode ({@code :}, {@code /}, {@code ?}). */
    public static boolean isCmdline(String mode) {
        return first(mode) == 'c';
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

    /** Text for the status line, in Vim's own {@code -- MODE --} style. */
    public static String statusText(String mode) {
        if (mode == null || mode.isEmpty()) {
            return "-- NORMAL --";
        }
        return switch (first(mode)) {
            case 'n' -> isOperatorPending(mode) ? "-- OPERATOR --" : "-- NORMAL --";
            case 'i' -> "-- INSERT --";
            case 'R' -> "-- REPLACE --";
            case 'v' -> "-- VISUAL --";
            case 'V' -> "-- VISUAL LINE --";
            case CTRL_V -> "-- VISUAL BLOCK --";
            case 's' -> "-- SELECT --";
            case 'S' -> "-- SELECT LINE --";
            case CTRL_S -> "-- SELECT BLOCK --";
            case 'c' -> "-- COMMAND --";
            case 't' -> "-- TERMINAL --";
            default -> "-- " + mode.toUpperCase(Locale.ROOT) + " --";
        };
    }

    /**
     * Status line text including the macro being recorded, as Vim shows it:
     * {@code -- NORMAL --  recording @a}.
     *
     * @param recordingRegister register being recorded into; {@code null} or "" when not recording
     */
    public static String statusText(String mode, String recordingRegister) {
        String text = statusText(mode);
        return recordingRegister == null || recordingRegister.isEmpty()
                ? text : text + "  recording @" + recordingRegister;
    }

    private static char first(String mode) {
        return mode == null || mode.isEmpty() ? '\0' : mode.charAt(0);
    }
}
