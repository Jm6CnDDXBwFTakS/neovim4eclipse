// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.ctrlKey;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.escape;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IRegion;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ChangedLines;

/**
 * Edits deep in a file of {@value #LINES} lines, opened from disk as a user opens one: the edit, its dot repeat and undo, a
 * {@code :w}, and insert-mode Ctrl-W, each in Eclipse's document and Neovim's buffer alike.
 *
 * <p>Why a test of its own: at {@code Esc} and at a sync point (Ctrl-W) the plug-in compares the
 * whole document with Neovim's buffer only up to {@code ChangedLines.COMPARE_WHOLE_UP_TO_LINES}
 * (20,000) lines, and pushes just the changed lines in a longer one. The other tests' three-line
 * file never takes that path; here every change must still land on the same line in both, and
 * nothing else may differ ({@link #expectNeovimHasTheDocument}). Speed is not
 * measured here (that would be a {@code timeoutReliant} test): each step only has to finish within
 * the usual {@code TestWait}.
 */
class LargeFileEndToEndTest extends EndToEndTestBase {

    /** More than {@code ChangedLines.COMPARE_WHOLE_UP_TO_LINES}, so that {@code Esc} pushes only the changed lines. */
    static final int LINES = ChangedLines.COMPARE_WHOLE_UP_TO_LINES + 2_000;

    /** Closes test.txt and opens big.txt in its place: the base class closes {@link #editor} afterwards. */
    @BeforeEach
    void openTheBigFile() throws Exception {
        String text = IntStream.rangeClosed(1, LINES).mapToObj(LargeFileEndToEndTest::original)
                .collect(Collectors.joining("\n", "", "\n"));
        IFile big = project.getFile("big.txt");
        big.create(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), true, null);
        page().closeEditor(editor, false);
        editor = (ITextEditor) IDE.openEditor(page(), big, TEXT_EDITOR, true);
        document = documentOf(editor);
        widget = widgetOf(editor);
        waitForAttach(editor, "big.txt");
        waitUntil("Neovim's buffer to have " + LINES + " lines", TIMEOUT_MS,
                () -> ((Number) SessionProbe.lua(session, "return vim.fn.line('$')")).intValue() == LINES);
    }

    /** Line {@code n} (1-based) as the file has it: "line 06000 of the big file, some words to edit". */
    private static String original(int n) {
        return "line %05d of the big file, some words to edit".formatted(n);
    }

    @Test
    void anEditAtLine6000ItsDotAndUndoAndWriteStayInSync() {
        runInBox(widget, "6000");
        type(widget, "cw");
        waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
        type(widget, "LINE");
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        expectLine(6000, "LINE" + original(6000).substring("line".length()));

        type(widget, "j0."); // 0: after Esc the cursor is on the E of LINE, and . changes from the cursor
        expectLine(6001, "LINE" + original(6001).substring("line".length()));
        type(widget, 'u');
        expectLine(6001, original(6001));
        expectLine(6000, "LINE" + original(6000).substring("line".length()));
        expectNeovimHasTheDocument();

        runInBox(widget, "w");
        waitUntil("the editor saved", TIMEOUT_MS, () -> !editor.isDirty());
        assertEquals(document.get(), fileText("big.txt"), "the file on disk is the document");
    }

    @Test
    void ctrlWTwiceAtLine9000DeletesBothTypedWords() {
        runInBox(widget, "9000");
        type(widget, 'A');
        waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
        type(widget, " extra words");
        expectLine(9000, original(9000) + " extra words");
        ctrlKey(widget, 'w');
        expectLine(9000, original(9000) + " extra ");
        waitUntil("Eclipse types again", TIMEOUT_MS, session::isEclipseTypingReady);
        ctrlKey(widget, 'w');
        expectLine(9000, original(9000) + " ");
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        expectNeovimHasTheDocument();
    }

    /** Waits until the document's line {@code n} (1-based) reads {@code expected}. */
    private void expectLine(int n, String expected) {
        waitUntil("line " + n + " to read " + expected, TIMEOUT_MS, () -> expected.equals(documentLine(n)),
                () -> "; it reads " + documentLine(n) + " (mode " + session.getMode() + ")");
    }

    private String documentLine(int n) {
        try {
            IRegion line = document.getLineInformation(n - 1);
            return document.get(line.getOffset(), line.getLength());
        } catch (BadLocationException e) {
            return "(no line " + n + ": " + document.getNumberOfLines() + " lines)";
        }
    }

    /** Waits until Neovim's buffer holds the document's text, all {@value #LINES} lines. */
    private void expectNeovimHasTheDocument() {
        waitUntil("Neovim's buffer to be the document", TIMEOUT_MS, () -> document.get().equals(neovimText()));
    }

    /** The attached buffer's text, as the document spells it: its lines, each ended by a line break. */
    private String neovimText() {
        try {
            return String.join("\n", session.getBufferLines()) + "\n";
        } catch (Exception e) {
            return "(Neovim's lines not read: " + e + ")";
        }
    }
}
