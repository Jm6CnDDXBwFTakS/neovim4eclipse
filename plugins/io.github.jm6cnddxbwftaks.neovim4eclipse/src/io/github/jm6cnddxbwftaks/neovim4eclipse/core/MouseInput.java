// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

/**
 * One mouse event in the editor, for Neovim's own mouse handling ({@link LuaScripts#MOUSE}): which
 * button did what, with which modifiers, on which character of the document.
 *
 * <p>Positions are the document's, in Neovim's units (line 1-based, column in UTF-8 bytes,
 * 0-based); the script finds the character on Neovim's screen. Neovim decides what the events mean:
 * a press moves the cursor, presses in quick succession select a word, the line ({@code V}) or a
 * block, a drag selects, Shift+press extends the selection.
 *
 * <p>Example: a triple click on line 3 is {@code press} and {@code release} three times, each
 * {@code new MouseInput(Action.PRESS, "", 3, 4)} and the matching release.
 *
 * @param action    what the left button did
 * @param modifiers Neovim's modifier letters: {@code "S"} (Shift), {@code "A"} (Alt), {@code "SA"},
 *                  or {@code ""}
 * @param line1     document line, 1-based
 * @param col0      byte column in that line, 0-based; may be the line's length (past its end)
 */
public record MouseInput(Action action, String modifiers, int line1, int col0) {

    /** What the left button did; {@link #luaName} is {@code nvim_input_mouse}'s name for it. */
    public enum Action {
        PRESS("press"), DRAG("drag"), RELEASE("release");

        private final String luaName;

        Action(String luaName) {
            this.luaName = luaName;
        }

        public String luaName() {
            return luaName;
        }
    }

    public MouseInput {
        if (action == null) {
            throw new IllegalArgumentException("action is required");
        }
        modifiers = modifiers == null ? "" : modifiers;
        if (line1 < 1 || col0 < 0) {
            throw new IllegalArgumentException("not a document position: line " + line1 + ", column " + col0);
        }
    }

    /** Neovim's modifier letters for Shift and Alt ({@code "S"}, {@code "A"}, {@code "SA"}, {@code ""}). */
    public static String modifiers(boolean shift, boolean alt) {
        return (shift ? "S" : "") + (alt ? "A" : "");
    }
}
