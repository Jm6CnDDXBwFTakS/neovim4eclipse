// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.regex.Pattern;

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

    /**
     * The errors noice.nvim reports when it starts under a UI that draws the command line, the
     * popup menu and the messages itself, as the plug-in's UI does ({@code ext_cmdline},
     * {@code ext_messages}); noice then turns itself off, and Neovim's own command line and messages
     * (ui2) work as usual. Example, one of the three:
     * {@code You're using a GUI that uses ext_cmdline. Noice can't work when the GUI has ext_cmdline enabled.}
     *
     * <p>Why they are left out: noice is common (LazyVim turns it on), and these lines are no error to
     * fix in Eclipse, yet they opened the warning at every start. Only these exact sentences are left
     * out, the three names and the text as noice 4.x prints them (its {@code health.lua}), so no
     * other error can be hidden by them; should noice change the text, the warning simply shows it
     * again.
     */
    private static final Pattern NOICE_GUI_CHECK = Pattern.compile(
            "You're using a GUI that uses (ext_cmdline|ext_popupmenu|ext_messages)\\."
                    + " Noice can't work when the GUI has \\1 enabled\\.");

    private StartupErrorReport() {
    }

    /**
     * Neovim's start-up messages without the lines that are no error to fix in Eclipse (noice.nvim
     * turning itself off, {@link #NOICE_GUI_CHECK}); "" when nothing else is left, so that no warning
     * opens. Any other line is kept, an error or not, as {@link #message} shows it.
     *
     * <p>Example: noice's three lines alone give ""; with
     * {@code E5112: Lua chunk: init.lua:2: '=' expected near '<'} after them, that line alone is
     * left.
     *
     * @param messages what Neovim's {@code :messages} held after starting
     */
    public static String withoutKnownNotices(String messages) {
        StringBuilder kept = new StringBuilder();
        for (String line : messages.strip().split("\\R")) {
            if (!NOICE_GUI_CHECK.matcher(line.strip()).matches()) {
                kept.append(line).append('\n');
            }
        }
        return kept.toString().strip();
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
