// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

/**
 * A normal-mode key that Neovim hands back to the IDE because only the IDE can do it: {@code gd}
 * (go to definition), {@code K} (show documentation), {@code gt} / {@code gT} (next or previous
 * editor tab).
 *
 * <p>How it works: {@link LuaScripts#SETUP} maps those keys in Neovim to a Lua function that sends
 * an {@code eclipse_action} notification with the action's name and Vim's count; the
 * {@link NotificationBridge} turns it into this record. Mapping in Neovim (rather than catching the
 * keys in Eclipse) lets Neovim do what it is good at: counts ({@code 3gt}), the {@code g} prefix
 * and {@code timeoutlen}. A user who wants Neovim's own meaning of these keys sets
 * {@code vim.g.eclipse_ide_keys = 0} in {@code init.lua}.
 *
 * @param name  one of the {@code *} constants below; others are ignored by the editor
 * @param count Vim's count ({@code v:count}): 0 when none was typed
 */
public record IdeAction(String name, int count) {

    /** {@code gd}: the IDE's Open Declaration. */
    public static final String DEFINITION = "definition";
    /** {@code K}: the IDE's Show Tooltip Description (hover information). */
    public static final String HOVER = "hover";
    /** {@code gt}, {@code {N}gt}: next editor tab, or tab N. */
    public static final String NEXT_TAB = "next_tab";
    /** {@code gT}, {@code {N}gT}: previous editor tab, or N tabs back. */
    public static final String PREVIOUS_TAB = "previous_tab";
}
