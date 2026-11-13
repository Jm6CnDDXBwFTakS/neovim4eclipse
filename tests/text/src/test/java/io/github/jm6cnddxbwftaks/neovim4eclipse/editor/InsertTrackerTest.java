// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InsertReplay;

/**
 * {@link InsertTracker} keeps only a window of the lines an insert touched. Each case checks that the
 * plan equals the one made from the whole document before and after ({@link InsertReplay#plan}),
 * which is what the tracker did before it kept a window.
 */
class InsertTrackerTest {

    private IDocument document = new Document("alpha\nbeta\ngamma\n");
    private boolean eligible = true;
    private InsertTracker tracker = new InsertTracker(document, () -> eligible);
    /** The whole document before the insert's first change, and that change's offset, for the reference plan. */
    private String wholeBefore;
    private int firstOffset;

    private void edit(int offset, int length, String text) throws BadLocationException {
        if (wholeBefore == null && eligible) {
            wholeBefore = document.get();
            firstOffset = offset;
        }
        document.replace(offset, length, text);
    }

    /** The tracker's plan, checked against the whole-document plan. */
    private InsertReplay.Plan planAt(int caret) {
        InsertReplay.Plan expected = wholeBefore == null ? null
                : InsertReplay.plan(wholeBefore, document.get(), firstOffset, caret);
        InsertReplay.Plan plan = tracker.consumePlan(caret);
        assertEquals(expected, plan);
        return plan;
    }

    @Test
    void textTypedAtALineEnd() throws Exception {
        edit(10, 0, "s"); // "betas"
        edit(11, 0, "!");
        InsertReplay.Plan plan = planAt(12);
        assertEquals(List.of("s!"), plan.textChunks());
        assertEquals(2, plan.baseLine1Based());
        assertEquals(4, plan.baseCol0Based());
    }

    /**
     * Text typed on the empty line Eclipse shows after the final newline: Neovim has no such line,
     * so the replay types it as a new line after the end of {@code gamma}, the last line Neovim has
     * (an Enter first). The final cursor is on the {@code z}, as the plain plan has it.
     */
    @Test
    void textTypedOnTheEmptyLineAfterTheFinalNewlineIsANewLineForNeovim() throws Exception {
        edit(17, 0, "z"); // "alpha\nbeta\ngamma\nz"
        InsertReplay.Plan plain = InsertReplay.plan(wholeBefore, document.get(), firstOffset, 18);
        InsertReplay.Plan plan = tracker.consumePlan(18);
        assertEquals(List.of("\r", "z"), plan.textChunks());
        assertEquals(3, plan.baseLine1Based());
        assertEquals(5, plan.baseCol0Based(), "the end of gamma");
        assertEquals(plain.finalLine1Based(), plan.finalLine1Based());
        assertEquals(plain.finalCol0Based(), plan.finalCol0Based());
        assertEquals(4, plan.finalLine1Based(), "on the z");
    }

    /**
     * {@code z} and Enter on the empty line after the final newline: Neovim gets one line {@code z},
     * and the cursor ends at its end, as the caret is on the empty line Eclipse shows after it.
     */
    @Test
    void aLineTypedOnTheEmptyLineAfterTheFinalNewlineIsOneLineForNeovim() throws Exception {
        edit(17, 0, "z\n"); // "alpha\nbeta\ngamma\nz\n"
        InsertReplay.Plan plan = tracker.consumePlan(document.getLength());
        assertEquals(List.of("\r", "z"), plan.textChunks());
        assertEquals(4, plan.finalLine1Based(), "on Neovim's last line, z");
        assertEquals(1, plan.finalCol0Based());
    }

    /**
     * Esc with the caret moved down to the empty line after the final newline, after typing on line
     * 1: the cursor ends at the end of {@code gamma}, the last line Neovim has, not on a line 4 it
     * does not have.
     */
    @Test
    void escOnTheEmptyLineAfterTheFinalNewlineEndsOnNeovimsLastLine() throws Exception {
        edit(0, 0, "x"); // "xalpha\nbeta\ngamma\n"
        InsertReplay.Plan plan = tracker.consumePlan(document.getLength());
        assertEquals(List.of("x"), plan.textChunks());
        assertEquals(1, plan.baseLine1Based());
        assertEquals(3, plan.finalLine1Based());
        assertEquals(5, plan.finalCol0Based(), "the end of gamma");
    }

    /** The same at a sync point ({@code Ctrl+W} after the text): typed as a new line too. */
    @Test
    void aSyncPointOnTheEmptyLineAfterTheFinalNewlineTypesANewLine() throws Exception {
        edit(17, 0, "zz");
        InsertReplay.SyncPoint sync = tracker.consumeSyncPoint(19);
        assertEquals(List.of("\r", "zz"), sync.typed().textChunks());
        assertEquals(3, sync.typed().baseLine1Based());
        assertEquals(4, sync.caretLine1Based());
        assertEquals(2, sync.caretCol0Based());
    }

    @Test
    void enterTypedMakesTheWindowGrow() throws Exception {
        edit(8, 0, "\n"); // "be" / "ta"
        edit(9, 0, "x");
        planAt(10);
    }

    /** Typing on one line of a long document: the plan's lines are the document's. */
    @Test
    void deepInALongDocument() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            text.append("line ").append(i).append('\n');
        }
        setDocument(text.toString());
        int offset = document.getLineOffset(4000) + 4; // after "line"
        edit(offset, 0, "-");
        InsertReplay.Plan plan = planAt(offset + 1);
        assertEquals(4001, plan.baseLine1Based());
    }

    /** Backspace over text typed before: only the net insertion matters. */
    @Test
    void typedThenPartlyDeleted() throws Exception {
        edit(5, 0, "xyz");
        edit(7, 1, "");
        planAt(7);
    }

    /** A change on a line before the window widens it; two places edited is not a plain insertion. */
    @Test
    void aChangeBeforeTheWindow() throws Exception {
        edit(10, 0, "s");
        edit(0, 0, "A");
        assertNull(planAt(12));
    }

    /** A change after the window widens it too. */
    @Test
    void aChangeAfterTheWindow() throws Exception {
        edit(0, 0, "A");
        edit(document.getLineOffset(2), 0, "G");
        assertNull(planAt(1));
    }

    /** The caret moved to another line before Esc: Neovim's cursor goes there, as with the whole document. */
    @Test
    void theCaretOnAnotherLine() throws Exception {
        edit(5, 0, "!");
        planAt(document.getLineOffset(2) + 2);
    }

    @Test
    void windowsLineBreaks() throws Exception {
        setDocument("alpha\r\nbeta\r\ngamma\r\n");
        edit(11, 0, "s");
        edit(12, 0, "\r\n");
        planAt(14);
    }

    /** Neovim's own change applied to the document starts no window. */
    @Test
    void aChangeThatIsNotTypingStartsNothing() throws Exception {
        eligible = false;
        edit(0, 0, "X");
        assertNull(planAt(1));
    }

    @Test
    void aSyncPointHandsOverTheTypedTextAndStartsOver() throws Exception {
        edit(10, 0, " foo");
        InsertReplay.SyncPoint sync = tracker.consumeSyncPoint(14);
        assertEquals(InsertReplay.syncPoint(wholeBefore, document.get(), firstOffset, 14).typed(), sync.typed());
        assertEquals(2, sync.caretLine1Based());
        assertEquals(8, sync.caretCol0Based());
        assertEquals(document.getNumberOfLines(), sync.documentLines());
        assertNull(tracker.consumePlan(14), "started over: nothing typed since");
    }

    @Test
    void aSyncPointWithNothingTypedHasOnlyTheCaret() {
        InsertReplay.SyncPoint sync = tracker.consumeSyncPoint(7);
        assertNull(sync.typed());
        assertEquals(2, sync.caretLine1Based());
        assertEquals(1, sync.caretCol0Based());
    }

    private void setDocument(String text) {
        tracker.dispose();
        document = new Document(text);
        tracker = new InsertTracker(document, () -> eligible);
    }
}
