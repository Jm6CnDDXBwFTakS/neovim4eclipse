// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * Whether Neovim's {@code 'mouse'} option enables the mouse in a mode, for a click or wheel on a
 * grid Eclipse draws (a plug-in's floating window, the command-line window, a page).
 *
 * <p><b>Why here.</b> In a terminal, Neovim asks for mouse events only in the modes {@code 'mouse'}
 * names, so a click in another mode never arrives. Eclipse gets every click, and
 * {@code nvim_input_mouse} does not ask, so Eclipse leaves such a click out before sending it. A
 * mapping of {@code <LeftMouse>} then does not run either, as in a terminal.
 *
 * <p>The letters are Neovim's: {@code n}, {@code v}, {@code i}, {@code c}, {@code r} (the hit-enter
 * and more prompts), {@code a} (all but {@code r}), and {@code h} (all but {@code r}, in a help
 * buffer), which {@code LuaScripts.WINDOWS} reports as an added {@code a} while the current buffer
 * is one. Example: with {@code "nv"}, {@code "n"} and {@code "V"} yes, {@code "i"} no.
 */
public final class MouseOption {

    private MouseOption() {
    }

    /**
     * @param option the option as Neovim last reported it; {@code null} before the first report,
     *               when the click goes through, as with Neovim's default
     * @param mode   Neovim's mode ({@code nvim_get_mode})
     */
    public static boolean allows(String option, String mode) {
        if (option == null) {
            return true;
        }
        char letter = VimModes.mouseLetter(mode);
        if (letter == VimModes.NO_MOUSE) {
            return false;
        }
        return option.indexOf(letter) >= 0 || (letter != 'r' && option.indexOf('a') >= 0);
    }
}
