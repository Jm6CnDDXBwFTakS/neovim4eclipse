// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;

/**
 * An in-memory {@link EditorCaret}: a caret offset and a selection over a real {@link IDocument}, no
 * widget and no display. Like {@code StyledText}, a caret move made through {@link #moveCaret}
 * notifies the move listeners, so tests see the echo that {@link CaretSync} must suppress.
 *
 * <p>Positions in the helpers are Eclipse's: a 1-based line and a 0-based column in UTF-16 chars.
 * Example:
 *
 * <pre>{@code
 * FakeCaret caret = new FakeCaret(new Document("alpha\nbeta"));
 * caret.userMovesTo(2, 1);          // the user clicks before "eta"
 * caret.assertCaretPosition(2, 1);
 * }</pre>
 */
final class FakeCaret implements EditorCaret {

    private final IDocument document;
    private final List<Runnable> listeners = new ArrayList<>();
    private int caret;
    private int selectionStart;
    private int selectionLength;
    private boolean disposed;
    /** When set, {@link #moveCaret} acts as if the offset were in code the viewer will not unfold. */
    boolean hidden;

    FakeCaret(IDocument document) {
        this.document = document;
    }

    // ---- what the code under test sees ----

    @Override
    public boolean isDisposed() {
        return disposed;
    }

    @Override
    public int caretOffset() {
        return caret;
    }

    @Override
    public int selectionStart() {
        return selectionLength > 0 ? selectionStart : caret;
    }

    @Override
    public int selectionLength() {
        return selectionLength;
    }

    @Override
    public boolean moveCaret(int offset) {
        if (hidden) {
            return false;
        }
        caret = offset;
        selectionLength = 0;
        fireMoved();
        return true;
    }

    @Override
    public void addMoveListener(Runnable onMove) {
        listeners.add(onMove);
    }

    @Override
    public void removeMoveListener(Runnable onMove) {
        listeners.remove(onMove);
    }

    // ---- what the test does as the user ----

    /** The user puts the caret at a line and column (a click, an arrow key). */
    void userMovesTo(int line1Based, int col0Based) {
        caret = offsetOf(line1Based, col0Based);
        selectionLength = 0;
        fireMoved();
    }

    /** The user selects {@code length} characters from {@code start}, the caret at the end (a drag to the right). */
    void userSelects(int start, int length) {
        selectionStart = start;
        selectionLength = length;
        caret = start + length;
        fireMoved();
    }

    /** The editor's widget is disposed (the editor closed). */
    void dispose() {
        disposed = true;
    }

    int listenerCount() {
        return listeners.size();
    }

    // ---- assertions ----

    /** Asserts the caret is at a 1-based line and 0-based UTF-16 column. */
    void assertCaretPosition(int line1Based, int col0Based) {
        assertEquals(line1Based + ":" + col0Based, lineColOf(caret), "caret (line:column)");
    }

    /** Asserts the selection, in document offsets; {@code length} 0 means nothing is selected. */
    void assertSelectionRange(int startOffset, int length) {
        assertEquals(startOffset + "+" + length, selectionStart() + "+" + selectionLength, "selection (start+length)");
    }

    private void fireMoved() {
        List.copyOf(listeners).forEach(Runnable::run);
    }

    private int offsetOf(int line1Based, int col0Based) {
        try {
            return document.getLineOffset(line1Based - 1) + col0Based;
        } catch (BadLocationException e) {
            throw new IllegalArgumentException("no line " + line1Based, e);
        }
    }

    private String lineColOf(int offset) {
        try {
            int line = document.getLineOfOffset(offset);
            return (line + 1) + ":" + (offset - document.getLineOffset(line));
        } catch (BadLocationException e) {
            return "offset " + offset + " outside the document";
        }
    }
}
