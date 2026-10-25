// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * The text of the warning shown when Neovim started but the user's configuration ({@code init.lua}
 * or {@code init.vim}) reported errors: the part of {@code editor/StartupErrorNotice} that needs no
 * Eclipse, so that it is unit-tested.
 *
 * <p>Neovim keeps going after such an error, with whatever the configuration set up before it (for
 * a Lua syntax error, nothing of {@code init.lua}), as it does in a terminal after Enter. The
 * warning says so, shows the first lines of what Neovim reported, and how to try again.
 */
public final class StartupErrorReport {

    /** The dialog's title. */
    public static final String TITLE = "Neovim configuration error";

    /** At most this many lines of Neovim's messages; {@code :messages} has them all. */
    static final int MAX_LINES = 12;

    private StartupErrorReport() {
    }

    /**
     * The warning, for example:
     *
     * <pre>
     * Neovim started, but its configuration reported errors, so it may be only partly loaded
     * (after a syntax error in init.lua, not at all):
     *
     * Error in /home/me/.config/nvim/init.lua:
     * E5112: Lua chunk: .../init.lua:2: '=' expected near '&lt;'
     *
     * Fix the configuration, then restart Eclipse to load it. ...
     * </pre>
     *
     * @param messages what Neovim's {@code :messages} held after starting
     */
    public static String message(String messages) {
        String[] lines = messages.strip().split("\\R");
        StringBuilder shown = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, MAX_LINES); i++) {
            shown.append(lines[i]).append('\n');
        }
        if (lines.length > MAX_LINES) {
            shown.append("... (").append(lines.length - MAX_LINES).append(" more lines: see :messages)\n");
        }
        return "Neovim started, but its configuration reported errors, so it may be only partly loaded"
                + " (after a syntax error in init.lua, not at all):\n\n"
                + shown
                + "\nFix the configuration, then restart Eclipse to load it. Mappings in init.lua are"
                + " written in Lua: vim.keymap.set('n', ';', ':') rather than nnoremap ; :.";
    }
}
