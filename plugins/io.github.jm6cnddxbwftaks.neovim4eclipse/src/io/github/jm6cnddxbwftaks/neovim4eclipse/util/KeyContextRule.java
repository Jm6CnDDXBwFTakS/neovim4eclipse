// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * When the Vim key-binding contexts are active for an editor: the rule {@code editor/EditorKeyContext}
 * applies, kept here so it is unit-tested without Eclipse.
 *
 * <p>Active exactly while Neovim owns the editor's keys and either
 * <ul>
 *   <li>the editor's text widget has the focus and Neovim reads the keys itself: any mode but
 *       insert and replace, or an insert that <em>Neovim</em> drives (after a block-visual {@code I}
 *       or {@code A}, or while recording a macro), where every key must reach Neovim; or</li>
 *   <li>the editor's {@code :} box has the focus (so {@code Ctrl-R}, {@code Ctrl-U} reach Neovim's
 *       command line).</li>
 * </ul>
 * In an insert that Eclipse types, the contexts are off, so Eclipse's own shortcuts (paste, content
 * assist) work as in any editor.
 *
 * <p>Example: in the Java editor, {@code Ctrl+O} is Quick Outline. After {@code Ctrl-V jj I}, Neovim
 * drives the insert, so {@code Ctrl+O} must be Neovim's {@code (insert)} instead: with the contexts
 * on, the plug-in's binding wins over Eclipse's.
 */
public final class KeyContextRule {

    private KeyContextRule() {
    }

    /**
     * Whether the contexts should be active.
     *
     * @param mode                Neovim's mode ({@code n}, {@code i}, {@code no}, ...)
     * @param neovimDrivenInsert  whether the current insert is one Neovim drives
     * @param widgetFocused       whether the editor's text widget has the keyboard focus
     * @param neovimOwnsKeys      whether Neovim owns the editor's keys (attached, Neovim mode on)
     * @param commandLineFocused  whether the editor's {@code :} box has the focus
     */
    public static boolean wanted(String mode, boolean neovimDrivenInsert, boolean widgetFocused,
            boolean neovimOwnsKeys, boolean commandLineFocused) {
        boolean neovimReadsKeys = !VimModes.isTyping(mode) || neovimDrivenInsert;
        return neovimOwnsKeys && ((neovimReadsKeys && widgetFocused) || commandLineFocused);
    }
}
