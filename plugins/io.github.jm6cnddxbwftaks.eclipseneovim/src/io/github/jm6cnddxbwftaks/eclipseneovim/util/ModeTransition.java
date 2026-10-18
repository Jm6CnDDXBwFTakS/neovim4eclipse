// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

/**
 * What a key's mode change means for keeping Eclipse in step with Neovim, decided after Neovim has
 * processed the key (and is not waiting for more of a command).
 *
 * <p>Most keys need nothing: Neovim's line events bring the text to Eclipse. A few changes need a
 * full re-sync of the buffer, and two of them also change who types the insert, Eclipse or Neovim.
 * Example: {@code o} in normal mode gives {@link #ENTER_INSERT} (the new line must reach Eclipse
 * before Eclipse types on it); {@code I} in blockwise visual gives {@link #ENTER_NEOVIM_INSERT}.
 *
 * <p>Pure: the session's {@code KeyInput} acts on the result. Mode strings are those of
 * {@code nvim_get_mode()} ({@link VimModes}).
 */
public enum ModeTransition {

    /** Insert mode began and Eclipse types it: sync first, so Eclipse types on Neovim's text. */
    ENTER_INSERT,
    /**
     * Insert mode began and Neovim must type it, so every key goes to Neovim: after a blockwise
     * selection (at Esc Vim repeats the text on every line of the block), or while recording a
     * macro (the macro must contain every typed key). Sync first, as for {@link #ENTER_INSERT}.
     */
    ENTER_NEOVIM_INSERT,
    /** An insert typed by Neovim ended: sync, since Esc may have repeated the text on every line of a block. */
    LEAVE_NEOVIM_INSERT,
    /**
     * Outside insert mode after a key that changes text in a way worth a cheap full re-sync
     * ({@link NvimKeys#isBufferMutating}: {@code p}, {@code x}, {@code u} ...).
     */
    TEXT_CHANGED,
    /** Nothing to do: line events carry any change. */
    NONE;

    /**
     * @param modeBefore         the mode before the key
     * @param modeAfter          the mode after it
     * @param neovimDrivenInsert whether the insert that was going on (if any) is typed by Neovim
     * @param recording          whether a macro is being recorded
     * @param key                the key, in Neovim notation
     */
    public static ModeTransition after(String modeBefore, String modeAfter, boolean neovimDrivenInsert,
            boolean recording, String key) {
        boolean wasTyping = VimModes.isTyping(modeBefore);
        boolean nowTyping = VimModes.isTyping(modeAfter);
        if (!wasTyping && nowTyping) {
            return VimModes.isBlockVisual(modeBefore) || recording ? ENTER_NEOVIM_INSERT : ENTER_INSERT;
        }
        if (wasTyping && !nowTyping && neovimDrivenInsert) {
            return LEAVE_NEOVIM_INSERT;
        }
        if (!nowTyping && NvimKeys.isBufferMutating(key)) {
            return TEXT_CHANGED;
        }
        return NONE;
    }

    /** Whether Eclipse's document must be re-synced from Neovim's buffer. */
    public boolean syncsBuffer() {
        return this != NONE;
    }
}
