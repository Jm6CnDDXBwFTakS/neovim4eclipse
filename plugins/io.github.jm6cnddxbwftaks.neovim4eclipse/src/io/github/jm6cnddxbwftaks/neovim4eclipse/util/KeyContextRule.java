// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * When the Vim key-binding contexts are active for an editor, and which letters they switch on: the
 * rule {@code editor/EditorKeyContext} applies, kept here so it is unit-tested without Eclipse.
 *
 * <p><b>Whether.</b> Active exactly while Neovim owns the editor's keys (attached, Neovim mode on)
 * and the editor's text widget or its {@code :} box has the focus. In every mode: in an insert Eclipse
 * types too, since the letters the user gave to Neovim for insert mode ({@code Ctrl-W},
 * {@code Ctrl-U}, ...) must reach it there (docs/design/INSERT_CTRL_KEYS.md).
 *
 * <p><b>Which letters</b> ({@link #column}): the Ctrl Keys preference page has two choices per
 * letter. In insert and replace mode the <i>Insert</i> choice applies, whether Eclipse types or
 * Neovim drives the insert (after a block-visual {@code I}, while recording): Neovim is in insert
 * mode either way, so the key means what it means there. In any other mode the <i>Normal</i>
 * choice. A letter left to Eclipse has no active binding, so Eclipse's own shortcut works.
 *
 * <p><b>Every letter while Neovim completes</b> ({@link #toNeovim}): from {@code Ctrl-N},
 * {@code Ctrl-P} or {@code Ctrl-X} until the completion ends, the keys steer Neovim's completion,
 * and the page's choice does not apply. Example: {@code Ctrl-Y} is not ticked for insert mode, so
 * it is Eclipse's Redo; with Neovim's list open it accepts the selected entry, as in Vim, and
 * {@code Ctrl-E} takes the completion back instead of opening Quick Switch Editor.
 *
 * <p><b>Every letter in a plug-in's window</b> ({@link #toNeovim}): while Neovim's cursor is in a
 * plug-in's float (a picker's prompt), the keys are that window's, as in a terminal, and the page's
 * choice does not apply. Eclipse's command would act on the document behind the window. Example:
 * {@code Ctrl-V} in Telescope's prompt opens the entry in a vertical split; {@code Ctrl+A} does not
 * select the file behind it, {@code Ctrl+F} does not open Find/Replace.
 *
 * <p>Example: in the Java editor, {@code Ctrl+O} is Quick Outline. In normal mode it is Vim's
 * <i>jump back</i> if its Normal box is ticked; in insert mode Vim's <i>one normal-mode command</i>
 * if its Insert box is (the default on Windows and Linux): with the context on, the plug-in's
 * binding wins over Eclipse's.
 */
public final class KeyContextRule {

    /** Which of the Ctrl Keys page's two choices per letter applies. */
    public enum Column {
        /** The letters chosen for normal, visual and the other modes. */
        NORMAL,
        /** The letters chosen for insert (and replace) mode. */
        INSERT
    }

    private KeyContextRule() {
    }

    /**
     * Whether the contexts should be active.
     *
     * @param widgetFocused       whether the editor's text widget has the keyboard focus
     * @param neovimOwnsKeys      whether Neovim owns the editor's keys (attached, Neovim mode on)
     * @param commandLineFocused  whether the editor's {@code :} box has the focus (so {@code Ctrl-R},
     *                            {@code Ctrl-U} reach Neovim's command line)
     */
    public static boolean wanted(boolean widgetFocused, boolean neovimOwnsKeys, boolean commandLineFocused) {
        return neovimOwnsKeys && (widgetFocused || commandLineFocused);
    }

    /**
     * Which letters the contexts switch on.
     *
     * @param mode               Neovim's mode ({@code n}, {@code i}, {@code no}, ...)
     * @param commandLineFocused whether the editor's {@code :} box has the focus: Neovim's command line
     *                           then reads the keys, whatever mode the editor was in
     */
    public static Column column(String mode, boolean commandLineFocused) {
        return VimModes.isTyping(mode) && !commandLineFocused ? Column.INSERT : Column.NORMAL;
    }

    /**
     * Whether {@code Ctrl+letter} goes to Neovim.
     *
     * @param chosen     the Ctrl Keys page's choice for the letter in the column that applies
     * @param column     which choice that is
     * @param completing whether Neovim completes, or waits after {@code Ctrl-X} for the key that
     *                   says what to complete: then every letter is its own, in insert mode
     * @param inPluginFloat whether Neovim's cursor is in a plug-in's drawn float: then every letter
     *                   is Neovim's, in every mode
     */
    public static boolean toNeovim(boolean chosen, Column column, boolean completing, boolean inPluginFloat) {
        return chosen || inPluginFloat || (completing && column == Column.INSERT);
    }

    /**
     * The context that switches on a chosen letter's binding. Normally the letter's own
     * ({@link VimCtrlKeys#contextId}), whose binding sends the key to the plug-in. With Neovim's
     * completion list turned off, {@code Ctrl-N} and {@code Ctrl-P} in an insert Eclipse types use
     * the Word Completion context instead ({@link VimCtrlKeys#completionContextId}): Eclipse
     * completes there, not Neovim. In an insert Neovim drives they stay Neovim's, since Eclipse
     * does not type there.
     *
     * @param eclipseTypes    whether Eclipse types the current insert (not Neovim)
     * @param neovimCompletes whether {@code Ctrl-N} and {@code Ctrl-P} are Neovim's completion
     *                        ({@code NeovimSession.completesInNeovim})
     */
    public static String contextId(char letter, Column column, boolean eclipseTypes, boolean neovimCompletes) {
        boolean completes = column == Column.INSERT && eclipseTypes && !neovimCompletes
                && VimCtrlKeys.COMPLETION_LETTERS.indexOf(Character.toLowerCase(letter)) >= 0;
        return completes ? VimCtrlKeys.completionContextId(letter) : VimCtrlKeys.contextId(letter);
    }
}
