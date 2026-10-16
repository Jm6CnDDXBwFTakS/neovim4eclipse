// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import java.util.Set;

/** Classification of keys in Neovim notation (the strings produced by {@link KeyMapping}). */
public final class NvimKeys {

    private static final Set<String> ENTER_KEYS = Set.of("<CR>", "CR", "\r", "\n", "<C-CR>");

    /** Single keys that change buffer text in a way the line-event stream may not fully describe. */
    private static final String BUFFER_MUTATING_CHARS = "pPxXs~.u";

    /**
     * Keys that start a command taking one more character: {@code f F t T} (find), {@code r}
     * (replace), {@code @} (play macro), {@code m} (set mark), {@code '} and {@code `} (jump to mark),
     * {@code "} (register) and {@code q} (record). While Neovim waits for that character, a typed
     * {@code :}, {@code /}, {@code ?} or {@code Z} is an <em>argument</em>, not a request to open a
     * prompt ({@code f/}, {@code @:}, {@code qa} ...).
     */
    private static final String ARGUMENT_COMMANDS = "fFtTr@m'`\"q";

    private NvimKeys() {
    }

    /**
     * Whether {@code key} will be followed by an argument character.
     *
     * @param recording whether a macro is being recorded: then {@code q} <em>stops</em> the recording
     *                  and takes no argument
     */
    public static boolean takesArgument(String key, boolean recording) {
        if (key == null || key.length() != 1) {
            return false;
        }
        char c = key.charAt(0);
        if (c == 'q' && recording) {
            return false;
        }
        return ARGUMENT_COMMANDS.indexOf(c) >= 0;
    }

    /** Text with {@code <} escaped as {@code <lt>}, so that {@code nvim_input} types it literally. */
    public static String escapeLiteral(String text) {
        return text.replace("<", "<lt>");
    }

    /**
     * Keys that type an ex command on the command line: {@code :silent <cmd><CR>}.
     *
     * <p>Typing it (instead of calling {@code nvim_command}) makes Neovim record it in a macro,
     * remember it for {@code @:} and in the history. {@code silent} keeps normal messages from
     * raising a "press Enter" prompt; errors are still reported (and still abort a running macro).
     */
    public static String exCommand(String command) {
        return ":silent " + escapeLiteral(command.strip()) + "<CR>";
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
