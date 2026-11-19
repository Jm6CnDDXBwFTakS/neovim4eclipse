// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * A buffer of the editing Neovim for a page editor to show: the help page {@code :help} opened, or
 * the report {@code :checkhealth} made. Neovim made the buffer itself; the {@code WinNew} net of
 * {@code LuaScripts.WINDOWS} took it from the window it came in and named it here
 * ({@code eclipse_view_page}), and the page editor shows it in a page window of its own
 * ({@code show_page}). Nothing is copied: the page is Neovim's buffer, so {@code Ctrl-]},
 * {@code :helpgrep}, registers and the user's colors work in it as in a terminal.
 *
 * <p>Example: {@code :help help-context} becomes {@code PageBuffer("help", 7, 27)}: buffer 7 holds
 * {@code helphelp.txt}, and the page's window shows it with line 27 at the top.
 *
 * @param title  what the page editor's title says after "Neovim: ": {@link #HELP} or {@link #CHECKHEALTH}
 * @param buffer the buffer's number in the editing Neovim
 * @param line   the line to show at the top, 1-based (the topic's line; 1 for a report)
 */
public record PageBuffer(String title, int buffer, int line) implements ViewContent {

    /** The title of a help page, and the kind the net names it by. */
    public static final String HELP = "help";
    /** The title of a {@code :checkhealth} report, and the kind the net names it by. */
    public static final String CHECKHEALTH = "checkhealth";
}
