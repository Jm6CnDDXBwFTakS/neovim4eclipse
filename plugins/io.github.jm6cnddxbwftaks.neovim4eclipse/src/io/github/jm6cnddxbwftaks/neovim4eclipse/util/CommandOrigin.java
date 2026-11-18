// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * Whether a command Neovim hands to Eclipse ({@link HostCommand}) belongs to the part that has the
 * focus, judged by the window it ran in, its <em>origin</em>. One rule, in one place
 * ({@code core/FocusRouter} asks it for every command), so that {@code :q} typed in a page never
 * closes the code editor behind it, whatever route the command took to Eclipse.
 *
 * <ul>
 *   <li>No origin ({@link HostCommand#NO_WINDOW}): the part with the focus takes it, as every
 *       command did before commands had an origin.</li>
 *   <li>A page has the focus: only a command from that page's window is its.</li>
 *   <li>A text editor has the focus: any command that did not come from a page's window is its
 *       (its own window, the home window, a floating window over it).</li>
 * </ul>
 *
 * <p>Example: {@code :q} typed in the messages page (window 1004), then a click back into the
 * code before Eclipse read the command. The code has the focus, and the command came from a
 * page's window: it is dropped, and the code editor stays open.
 */
public final class CommandOrigin {

    private CommandOrigin() {
    }

    /**
     * @param origin       the window the command ran in, or {@link HostCommand#NO_WINDOW}
     * @param focusedPage  the window of the page with the focus, or {@link HostCommand#NO_WINDOW}
     *                     while a text editor has it
     * @param originIsPage whether {@code origin} is a page's window
     */
    public static boolean belongsToFocus(int origin, int focusedPage, boolean originIsPage) {
        if (origin == HostCommand.NO_WINDOW) {
            return true;
        }
        if (focusedPage != HostCommand.NO_WINDOW) {
            return origin == focusedPage;
        }
        return !originIsPage;
    }
}
