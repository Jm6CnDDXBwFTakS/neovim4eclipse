// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

/**
 * A normal-mode key that Neovim hands back to the IDE because only the IDE can do it: {@code gd}
 * (go to definition), {@code K} (show documentation), Neovim's language keys {@code grn},
 * {@code gra}, {@code grr}, {@code gri}, {@code grt} and {@code gO}, {@code gt} / {@code gT} (next or previous
 * editor tab), {@code ZZ} / {@code ZQ} (save and close, or close, the editor), and {@code Ctrl-O} /
 * {@code Ctrl-I} once Neovim's jump history runs out (Eclipse's Back and Forward).
 *
 * <p>How it works: {@link LuaScripts#IDE_KEYS} maps those keys ({@link IdeKeys}, one table for
 * both sides; {@code ZZ}, {@code ZQ} and the history keys in {@link LuaScripts#SETUP}) in Neovim to a Lua function that sends
 * an {@code eclipse_action} notification with the action's name and Vim's count; the
 * {@link NotificationBridge} turns it into this record. Mapping in Neovim (rather than catching the
 * keys in Eclipse) lets Neovim do what it is good at: counts ({@code 3gt}), the {@code g} prefix
 * and {@code timeoutlen}. A user who wants Neovim's own meaning of these keys sets
 * {@code vim.g.neovim4eclipse_ide_keys = 0} in {@code init.lua}; that switch leaves {@code ZZ} and
 * {@code ZQ} mapped, because Neovim's own would quit the embedded Neovim, which every editor uses,
 * and the {@code Ctrl-O} / {@code Ctrl-I} fallback, which acts only where Neovim would do nothing
 * (a mapping of the key in {@code init.lua} turns it off).
 *
 * @param name  one of the {@code *} constants below; others are ignored by the editor
 * @param count Vim's count ({@code v:count}): 0 when none was typed
 * @param step  for {@link #HISTORY_BACK}, the id of its first Back step (the others follow:
 *              {@code 3 Ctrl-O} is steps {@code step}, {@code step + 1}, {@code step + 2}); for
 *              {@link #HISTORY_FORWARD}, the id of the oldest Back step to undo, or 0 for plain
 *              Forward. The ids are Neovim's (the trail in {@link LuaScripts#SETUP}); 0 otherwise
 */
public record IdeAction(String name, int count, long step) {

    /** An action without a Back step id. */
    public IdeAction(String name, int count) {
        this(name, count, 0);
    }

    /** {@code gd}: the IDE's Open Declaration. */
    public static final String DEFINITION = "definition";
    /** {@code K}: the IDE's Show Tooltip Description (hover information). */
    public static final String HOVER = "hover";
    /** {@code grn}: the IDE's Rename (Alt+Shift+R: the refactoring in Java). */
    public static final String RENAME = "rename";
    /** {@code gra}: the IDE's Quick Fix (Ctrl+1), on the selection in visual mode. */
    public static final String QUICK_FIX = "quick_fix";
    /** {@code grr}: the IDE's References (Ctrl+Shift+G: references in the workspace). */
    public static final String REFERENCES = "references";
    /** {@code gri}: the IDE's Open Implementation. */
    public static final String IMPLEMENTATION = "implementation";
    /** {@code grt}: the IDE's Open Declared Type (the type of the variable or expression under the cursor). */
    public static final String TYPE_DEFINITION = "type_definition";
    /** {@code gO}: the IDE's Quick Outline (Ctrl+O). */
    public static final String OUTLINE = "outline";
    /** {@code gt}, {@code {N}gt}: next editor tab, or tab N. */
    public static final String NEXT_TAB = "next_tab";
    /** {@code gT}, {@code {N}gT}: previous editor tab, or N tabs back. */
    public static final String PREVIOUS_TAB = "previous_tab";
    /**
     * {@code Ctrl-O} when Neovim's jump history has no older entry: Eclipse's Back (Alt+Left),
     * {@code count} times. Each editor is a Neovim window with its own jump history, and switching
     * editors in Eclipse (a tab click) is no Neovim jump, so Eclipse's history takes over there.
     */
    public static final String HISTORY_BACK = "history_back";
    /**
     * {@code Ctrl-I} when its newest step to undo is a Back, or when Neovim's jump history has no
     * newer entry: Eclipse's Forward (Alt+Right).
     */
    public static final String HISTORY_FORWARD = "history_forward";
    /** {@code ZZ}: save the editor if it is dirty, then close it (Vim's {@code :x}). */
    public static final String SAVE_AND_CLOSE = "save_and_close";
    /** {@code ZQ}: close the editor without saving (Vim's {@code :q!}). */
    public static final String CLOSE = "close";
}
