// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * How the Neovim toolbar button (and its menu checkbox, <em>Neovim › Neovim Mode</em>) looks for
 * the active editor: greyed out, pressed, or raised, and what its tooltip says. A pure decision,
 * so it is unit-tested without Eclipse; {@code editor/ToggleNeovimModeHandler} applies it.
 *
 * <pre>
 *   no editor with Neovim     greyed out   "Neovim is not attached to this editor ..."
 *   Neovim mode               pressed      "Neovim mode: Neovim gets the keys. Ctrl+Shift+M: ..."
 *   Eclipse mode              raised       "Eclipse mode: Eclipse gets the keys. Ctrl+Shift+M: ..."
 * </pre>
 *
 * <p>The button is an Eclipse <em>toggle</em> item ({@code style="toggle"} in {@code plugin.xml}):
 * the platform draws it pressed while it is <em>checked</em>, and greyed out while its command is
 * <em>disabled</em>. Being greyed out, it cannot be clicked, and its key binding does nothing; the
 * tooltip says how to attach instead (on most platforms a disabled button still shows its tooltip).
 *
 * @param enabled {@code false} to grey the button out
 * @param checked {@code true} to draw it pressed
 * @param tooltip what hovering over it shows
 */
public record ToggleButtonState(boolean enabled, boolean checked, String tooltip) {

    /**
     * @param attached   the active editor has Neovim attached
     * @param neovimMode Neovim gets the keys (ignored when not attached)
     * @param toggleKey  the toggle's key binding as shown to the user ({@code Ctrl+Shift+M}), or
     *                   {@code null} if it has none
     */
    public static ToggleButtonState of(boolean attached, boolean neovimMode, String toggleKey) {
        if (!attached) {
            return new ToggleButtonState(false, false,
                    "Neovim is not attached to this editor (Neovim: Attach to Active Editor attaches it)");
        }
        String key = toggleKey == null || toggleKey.isBlank() ? "" : " (" + toggleKey + ")";
        return neovimMode
                ? new ToggleButtonState(true, true,
                        "Neovim mode: Neovim gets the keys. Click" + key + " for plain Eclipse editing")
                : new ToggleButtonState(true, false,
                        "Eclipse mode: Eclipse gets the keys. Click" + key + " to give them to Neovim");
    }
}
