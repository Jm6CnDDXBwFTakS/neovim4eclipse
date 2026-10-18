// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

/**
 * The editor's caret and selection, in <em>document</em> offsets.
 *
 * <p>Why not the {@code StyledText} directly: the widget counts offsets in what it shows, which
 * leaves out folded code ({@link WidgetOffsets}), and it can only be created with a display. This
 * interface hides both. The editor uses {@link StyledTextCaret}; UI-free tests use a fake that
 * keeps two numbers, so that a test of {@link CaretSync} reads as "the user clicked at line 2,
 * column 3; Neovim's cursor went to line 2, byte 4".
 *
 * <p>UI thread only.
 */
interface EditorCaret {

    /** {@code true} once the editor's widget is gone; nothing else may be called then. */
    boolean isDisposed();

    /** The caret as a document offset. */
    int caretOffset();

    /** Start of the selection as a document offset; the caret when nothing is selected. */
    int selectionStart();

    /** Number of selected characters; 0 when nothing is selected. */
    int selectionLength();

    /**
     * Moves the caret to a document offset, clearing any selection, unfolds code that hides it, and
     * scrolls it into view. Like a user's move, this notifies the move listeners.
     *
     * @return {@code false} if the offset stays hidden in folded code and the caret did not move
     */
    boolean moveCaret(int offset);

    /** {@code onMove} runs whenever the caret moves or the user clicks in the text. */
    void addMoveListener(Runnable onMove);

    void removeMoveListener(Runnable onMove);
}
