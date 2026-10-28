// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.eclipse.core.resources.IFile;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;

/**
 * Two editors on one file (Window › New Editor) share one Neovim buffer ({@code core/EditorBuffers}),
 * and the buffer goes with the last of them; each has its own Neovim window (docs/design/WINDOWS.md).
 */
class TwoEditorsEndToEndTest extends EndToEndTestBase {

    /** Window › New Editor on test.txt, attached. */
    private ITextEditor openSecondEditor() throws Exception {
        page().getActivePart().getSite().getService(IHandlerService.class)
                .executeCommand("org.eclipse.ui.window.newEditor", null);
        IEditorPart second = page().getActiveEditor();
        assertNotSame(editor, second, "a second editor");
        waitForAttach((ITextEditor) second, "the second editor on test.txt");
        return (ITextEditor) second;
    }

    /** How many of Neovim's buffers are test.txt's ({@code :ls!}, wiped buffers are gone). */
    private int testTxtBuffers() {
        Object count = SessionProbe.lua(session, "return #vim.tbl_filter(function(b) "
                + "return vim.api.nvim_buf_get_name(b):match('test%.txt$') ~= nil end, vim.api.nvim_list_bufs())");
        return ((Number) count).intValue();
    }

    private int currentWindow() {
        return ((Number) SessionProbe.lua(session, "return vim.api.nvim_get_current_win()")).intValue();
    }

    /**
     * Each editor has a Neovim window of its own: a window-local option set in one ({@code :setlocal
     * list}) is not set in the other, and is still set when the editor comes back.
     */
    @Test
    void eachEditorOnOneFileHasItsOwnNeovimWindow() throws Exception {
        ITextEditor second = openSecondEditor();
        settle();
        int secondWindow = currentWindow();
        SessionProbe.lua(session, "vim.wo.list = true");

        page().activate(editor);
        waitForAttach(editor, "the first editor");
        settle();
        assertNotEquals(secondWindow, currentWindow(), "another window");
        assertEquals(false, SessionProbe.lua(session, "return vim.wo.list"));

        page().activate(second);
        waitForAttach(second, "the second editor");
        settle();
        assertEquals(secondWindow, currentWindow());
        assertEquals(true, SessionProbe.lua(session, "return vim.wo.list"), "kept in its window");
        page().closeEditor(second, false);
    }

    /** {@code dd} in one editor, {@code u} in the other: the line comes back, one undo history. */
    @Test
    void twoEditorsOnOneFileShareTheUndoHistory() throws Exception {
        ITextEditor second = openSecondEditor();
        page().activate(editor);
        waitForAttach(editor, "the first editor");
        type(widget, "ggdd");
        expectText("beta\ngamma\n");

        page().activate(second);
        waitForAttach(second, "the second editor");
        expectText(second, "beta\ngamma\n");
        type(widgetOf(second), 'u');
        expectText(second, "alpha\nbeta\ngamma\n");
        page().closeEditor(second, false);
    }

    /**
     * Closing both editors wipes the buffer (gone from {@code :ls}); after reopening, {@code u}
     * undoes nothing: the history starts fresh with the saved file.
     */
    @Test
    void closingTheLastEditorWipesTheBufferAndReopeningStartsAFreshHistory() throws Exception {
        type(widget, "ggdd");
        expectText("beta\ngamma\n");
        editor.doSave(null);
        ITextEditor second = openSecondEditor();
        ITextEditor other = openOtherFile(); // stays open, so a buffer and an attached editor remain
        assertEquals(1, testTxtBuffers());

        page().closeEditor(second, false);
        page().closeEditor(editor, false);
        editor = null;
        settle();
        waitUntil("test.txt's buffer to be wiped", TIMEOUT_MS, () -> testTxtBuffers() == 0,
                () -> "; there are " + testTxtBuffers());

        IFile file = project.getFile("test.txt");
        editor = (ITextEditor) IDE.openEditor(page(), file, TEXT_EDITOR, true);
        widget = widgetOf(editor);
        document = documentOf(editor);
        waitForAttach(editor, "the reopened test.txt");
        type(widget, 'u');
        settle();
        expectText("beta\ngamma\n");
        page().closeEditor(other, false);
    }
}
