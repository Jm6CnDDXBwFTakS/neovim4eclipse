// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * A help page for the Neovim view: where {@code :help} (or {@code :helpgrep}, or a plug-in's
 * {@code vim.cmd.help}) took the editing Neovim, for a second Neovim to open the same page
 * ({@code core/HelpProfile}). No path crosses: the second Neovim finds the page itself, by its tag,
 * on the same {@code 'runtimepath'}, as Neovim's {@code :help} does.
 *
 * <p>Example: {@code :help help-context} becomes {@code ("help-context", "helphelp.txt", 27, rtp)}.
 * The second Neovim runs {@code :help help-context}, checks that it reached {@code helphelp.txt},
 * and puts the cursor on line 27, where the editing Neovim had it.
 *
 * @param tag         the help tag to open the page by: the nearest one at or above the cursor that
 *                    the help tags know in this file, else the file's own name
 * @param file        the help file's name, without its folder, such as {@code helphelp.txt}
 * @param line        the cursor's line in that file, 1-based
 * @param runtimepath the editing Neovim's {@code 'runtimepath'}, where the help files are
 * @param helpgrep    the pattern of the {@code :helpgrep} whose entry this page is, so the view runs
 *                    it too and {@code ]q} goes on there; {@code ""} for a page reached otherwise
 * @param index       that entry's number in the list, 1-based; 0 without {@code helpgrep}
 */
public record HelpPage(String tag, String file, int line, String runtimepath, String helpgrep, int index)
        implements ViewContent {

    /** A page reached by its tag, not by {@code :helpgrep}. */
    public HelpPage(String tag, String file, int line, String runtimepath) {
        this(tag, file, line, runtimepath, "", 0);
    }

    /** The view's title for a help page. */
    public static final String TITLE = "help";

    @Override
    public String title() {
        return TITLE;
    }
}
