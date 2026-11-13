// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.function.BooleanSupplier;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.IRegion;

import io.github.jm6cnddxbwftaks.neovim4eclipse.sync.FinalNewlines;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InsertReplay;

/**
 * Remembers what the document looked like where an Eclipse-driven insert changed it, so that at Esc
 * the typed text can be re-typed into Neovim (see {@link InsertReplay}) and {@code .} can repeat it.
 * A {@code Ctrl} key Neovim handles in the middle of the insert (a <em>sync point</em>, such as
 * {@code Ctrl-W}) hands over the text typed so far in the same way, and the tracker starts over from
 * there.
 *
 * <h2>A window, not the whole document</h2>
 * The tracker keeps only the whole lines the insert's changes touched: their text from before the
 * first change ({@link #before}) and where they are now ({@link #windowStart}, {@link #windowEnd}).
 * Lines outside the window are untouched, so they read the same now as before; a change outside
 * it widens the window with those untouched lines, read from the document as they still are.
 * Example: typing {@code foo} on line 5000 of a 100k-line file keeps line 5000 alone, where a copy
 * of the whole document was taken before (4 MB, on the UI thread, at every insert).
 *
 * <pre>
 *   before:  line 4999 | line 5000      | line 5001     window = line 5000, firstLine = 4999 (0-based)
 *   after:   line 4999 | line 5000 foo  | line 5001     replay types "foo" at line 5000 (1-based)
 * </pre>
 *
 * <p>The window starts when the <em>first</em> eligible change of an insert is about to happen
 * ({@code documentAboutToBeChanged}), which gives both the text before the typing (identical to
 * Neovim's buffer at that point) and the offset where typing started, even if the caret was moved
 * first. Changes made by the plug-in itself (applying Neovim's buffer events) do not start one (the
 * {@code eligible} check), but once a window is open every change widens it as needed.
 *
 * <p>UI thread only.
 */
final class InsertTracker implements IDocumentListener {

    private final IDocument document;
    private final BooleanSupplier eligible;
    /** The window's lines as they were before the insert's first change; {@code null}: no window. */
    private String before;
    /** Offset of the window's first character in the document now, at a line start. */
    private int windowStart;
    /** Offset just after the window's last character in the document now, at a line's content end. */
    private int windowEnd;
    /** 0-based document line of {@link #windowStart}. */
    private int firstLine;
    /** Offset of the insert's first change in {@link #before}. */
    private int base;
    /** The length change of the edit being made, from {@code documentAboutToBeChanged} to {@code documentChanged}. */
    private int pendingDelta;
    private boolean changePending;

    /**
     * @param eligible whether a change right now is the user typing in a plain Eclipse-driven insert
     *                 (not a Neovim-driven edit, not Eclipse mode, not replace mode)
     */
    InsertTracker(IDocument document, BooleanSupplier eligible) {
        this.document = document;
        this.eligible = eligible;
        document.addDocumentListener(this);
    }

    void dispose() {
        document.removeDocumentListener(this);
        reset();
    }

    /**
     * Where the replay types, when the insert began on the empty line Eclipse shows after a final
     * newline: the end of Neovim's last line ({@code {line1Based, byteCol0Based}}), since Neovim has
     * no such line ({@link InsertReplay.Plan#typedOnANewLineAfter}); {@code null} otherwise. Decided
     * at the first change, while the document still has Neovim's lines.
     */
    private int[] newLineAfter;

    /** Forgets the current window; the next eligible change starts a new one. */
    void reset() {
        before = null;
        changePending = false;
        newLineAfter = null;
    }

    @Override
    public void documentAboutToBeChanged(DocumentEvent event) {
        int offset = event.getOffset();
        int end = offset + event.getLength();
        try {
            if (before == null) {
                if (!eligible.getAsBoolean()) {
                    return;
                }
                windowStart = lineStart(offset);
                windowEnd = lineContentEnd(end);
                firstLine = document.getLineOfOffset(windowStart);
                before = document.get(windowStart, windowEnd - windowStart);
                base = offset - windowStart;
                newLineAfter = endOfNeovimsLastLineIfPast(offset);
            } else {
                cover(offset, end);
            }
        } catch (BadLocationException e) {
            reset(); // cannot happen for an event of this document; nothing to replay then
            return;
        }
        String text = event.getText();
        pendingDelta = (text == null ? 0 : text.length()) - event.getLength();
        changePending = true;
    }

    @Override
    public void documentChanged(DocumentEvent event) {
        if (changePending) {
            windowEnd += pendingDelta; // the change was inside the window
            changePending = false;
        }
    }

    /**
     * Builds the replay plan for the insert that is ending, and resets the tracker.
     *
     * @param caretOffset caret offset at Esc
     * @return the plan, or {@code null} if nothing was typed or it is not a replayable insertion
     */
    InsertReplay.Plan consumePlan(int caretOffset) {
        try {
            if (before == null || !coverCaret(caretOffset)) {
                return null;
            }
            InsertReplay.Plan plan = InsertReplay.plan(before, windowNow(), base, caretOffset - windowStart);
            return plan == null ? null : onNeovimsLines(plan.withLineOffset(firstLine));
        } finally {
            reset();
        }
    }

    /**
     * Builds the sync point for a {@code Ctrl} key Neovim handles in the middle of the insert, and
     * resets the tracker: the insert goes on, and the next change starts a new window from the text
     * Neovim then has. So at Esc only the text typed after the last sync point is replayed.
     *
     * @param caretOffset caret offset now
     */
    InsertReplay.SyncPoint consumeSyncPoint(int caretOffset) {
        try {
            InsertReplay.Plan typed = null;
            if (before != null && coverCaret(caretOffset)) {
                InsertReplay.SyncPoint inWindow = InsertReplay.syncPoint(before, windowNow(), base,
                        caretOffset - windowStart);
                typed = inWindow.typed() == null ? null : onNeovimsLines(inWindow.typed().withLineOffset(firstLine));
            }
            int[] caret = DocumentPositions.offsetToCursor(document, caretOffset);
            return new InsertReplay.SyncPoint(typed, caret[0], caret[1], document.getNumberOfLines());
        } finally {
            reset();
        }
    }

    /**
     * {@code plan} on lines Neovim has, once the document is pushed: typed there
     * ({@link #newLineAfter}), and ending there. The plan counts lines in the document's own text,
     * which includes the empty line Eclipse shows after a final newline; Neovim has no such line.
     * Example: {@code x} typed on line 1, then the caret moved down to that empty line, then Esc:
     * the cursor would end on line 4 of a three-line buffer, and ends at the end of line 3 instead,
     * as {@link DocumentPositions#offsetToCursor} puts any position there.
     */
    private InsertReplay.Plan onNeovimsLines(InsertReplay.Plan plan) {
        InsertReplay.Plan typed = newLineAfter == null ? plan : plan.typedOnANewLineAfter(newLineAfter[0], newLineAfter[1]);
        if (typed.finalLine1Based() <= FinalNewlines.neovimLineCount(document)) {
            return typed;
        }
        int[] end = DocumentPositions.offsetToCursor(document, document.getLength());
        return typed.endingAt(end[0], end[1]);
    }

    /**
     * The end of Neovim's last line, if {@code offset} lies past it, on the empty line Eclipse
     * shows after a final newline; else {@code null}. The cursor rule decides
     * ({@link DocumentPositions#offsetToCursor}): it moves such an offset onto an earlier line.
     */
    private int[] endOfNeovimsLastLineIfPast(int offset) throws BadLocationException {
        int[] cursor = DocumentPositions.offsetToCursor(document, offset);
        return cursor[0] < document.getLineOfOffset(offset) + 1 ? cursor : null;
    }

    /** The window's text in the document now. */
    private String windowNow() {
        try {
            return document.get(windowStart, windowEnd - windowStart);
        } catch (BadLocationException e) {
            throw new IllegalStateException("insert window out of the document", e);
        }
    }

    /** Widens the window to the caret's line (the caret may have moved off the typed lines). */
    private boolean coverCaret(int caretOffset) {
        if (caretOffset < 0 || caretOffset > document.getLength()) {
            return false;
        }
        try {
            cover(caretOffset, caretOffset);
            return true;
        } catch (BadLocationException e) {
            return false;
        }
    }

    /**
     * Widens the window to whole lines covering {@code [from, to]} of the document as it is now. The
     * added text lies outside every change so far, so it is the same now as before the insert, and
     * goes into {@link #before} as it is.
     */
    private void cover(int from, int to) throws BadLocationException {
        if (from < windowStart) {
            int start = lineStart(from);
            String added = document.get(start, windowStart - start);
            before = added + before;
            base += added.length();
            windowStart = start;
            firstLine = document.getLineOfOffset(start);
        }
        if (to > windowEnd) {
            int end = lineContentEnd(to);
            before = before + document.get(windowEnd, end - windowEnd);
            windowEnd = end;
        }
    }

    private int lineStart(int offset) throws BadLocationException {
        return document.getLineOffset(document.getLineOfOffset(offset));
    }

    /** End of the content (before the line delimiter) of the line holding {@code offset}. */
    private int lineContentEnd(int offset) throws BadLocationException {
        IRegion line = document.getLineInformationOfOffset(offset);
        return Math.max(offset, line.getOffset() + line.getLength());
    }
}
