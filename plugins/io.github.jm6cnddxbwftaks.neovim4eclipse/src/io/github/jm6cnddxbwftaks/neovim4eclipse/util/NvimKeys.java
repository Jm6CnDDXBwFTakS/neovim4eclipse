// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.Set;

/** Classification of keys in Neovim notation (the strings produced by {@link KeyMapping}). */
public final class NvimKeys {

    private static final Set<String> ENTER_KEYS = Set.of("<CR>", "CR", "\r", "\n", "<C-CR>");

    /** Single keys that change buffer text in a way the line-event stream may not fully describe. */
    private static final String BUFFER_MUTATING_CHARS = "pPxXs~.u";

    private NvimKeys() {
    }

    /**
     * Whether {@code key}, typed after {@code q}, starts a recording: a register Vim records into
     * ({@code a}-{@code z}, {@code A}-{@code Z} to append, {@code 0}-{@code 9}, {@code "}). Any other key
     * starts none: {@code q:} opens the command-line window, {@code q<Esc>} does nothing.
     */
    public static boolean isRecordingRegister(String key) {
        if (key == null || key.length() != 1) {
            return false;
        }
        char c = key.charAt(0);
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '"';
    }

    /** Text with {@code <} escaped as {@code <lt>}, so that {@code nvim_input} types it literally. */
    public static String escapeLiteral(String text) {
        return text.replace("<", "<lt>");
    }

    /**
     * Keys that type an ex command on the command line: {@code :<cmd><CR>}.
     *
     * <p>Typing it (instead of calling {@code nvim_command}) makes Neovim record it in a macro,
     * remember it for {@code @:} and in the history, exactly as typed. (Until the plug-in attached
     * with {@code ext_messages} it typed {@code :silent <cmd>}, to keep messages from raising a
     * "Press ENTER" prompt; with {@code ext_messages} there is no such prompt, and {@code :silent}
     * would now hide the messages Eclipse shows.)
     */
    public static String exCommand(String command) {
        return ":" + escapeLiteral(command.strip()) + "<CR>";
    }

    /** Whether the key is Enter (which may execute a pending {@code :} command). */
    public static boolean isEnter(String keys) {
        return keys != null && ENTER_KEYS.contains(keys);
    }

    /**
     * Whether the key is one of the simple edits after which the session does a full buffer
     * re-sync instead of trusting incremental line events alone: {@code p P x X s ~ .},
     * undo ({@code u}) and redo ({@code <C-r>}). (Undo re-inserts whole lines, the case
     * incremental sync is most likely to get wrong.)
     */
    public static boolean isBufferMutating(String keys) {
        if (keys == null || keys.isEmpty()) {
            return false;
        }
        if (keys.length() == 1) {
            return BUFFER_MUTATING_CHARS.indexOf(keys.charAt(0)) >= 0;
        }
        return "<C-r>".equalsIgnoreCase(keys);
    }
}
