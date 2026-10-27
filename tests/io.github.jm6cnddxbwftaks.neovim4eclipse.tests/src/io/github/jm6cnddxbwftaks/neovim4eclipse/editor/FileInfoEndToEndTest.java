// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;

/**
 * Neovim's file info ({@code "a.txt" 3 lines --33%--}) with {@code 'shortmess'} with and without
 * {@code F}, as a user's config may set it. Neovim gives it when it enters a loaded buffer, unless
 * {@code F} is set. A switch of Eclipse's editors never shows it, as Eclipse shows nothing then
 * ({@code core/LuaScripts.SHOW_BUFFER}, a deliberate exception to leaving the user's settings
 * alone).
 *
 * <p>One Neovim serves every test, so each test here puts the value from before it back.
 */
class FileInfoEndToEndTest extends EndToEndTestBase {

    private String shortmessBefore;

    @BeforeEach
    void rememberShortmess() {
        shortmessBefore = (String) SessionProbe.lua(session, "return vim.o.shortmess");
    }

    @AfterEach
    void restoreShortmess() {
        if (shortmessBefore != null) {
            SessionProbe.lua(session, "vim.o.shortmess = '" + shortmessBefore.replace("'", "\\'") + "'");
        }
    }

    /** Sets 'shortmess' as a user's config would: with F (Neovim's default) or without it. */
    private void neovimGivesFileInfo(boolean gives) {
        SessionProbe.lua(session, gives ? "vim.opt.shortmess:remove('F')" : "vim.opt.shortmess:append('F')");
    }

    /**
     * Activates another editor and comes back, so that Neovim enters both buffers. Twice before a
     * key: two file info messages would have gone to the output popup together, taking the focus.
     */
    private ITextEditor switchAwayAndBack() throws Exception {
        ITextEditor other = openFile("other.txt", "one\ntwo\n");
        editor.getSite().getPage().activate(editor);
        waitForAttach(editor, "test.txt");
        return other;
    }

    private void expectNoMessageAndTheFocus() {
        assertEquals("", messageText(editor), "no message after Eclipse's switch");
        assertTrue(widget.isFocusControl(), "the editor keeps the keyboard focus (no output popup)");
    }

    /**
     * As {@code EditorEndToEndTest.afterEclipsesUndoNeovimsCursorIsOnTheRestoredLine}: Eclipse's Undo
     * selects the line it brings back only in the focused widget, and Neovim's cursor follows that.
     */
    private void undoThenDeleteTheLineBelow() {
        type(widget, "ggdd");
        expectText("beta\ngamma\n");
        editor.getAdapter(ITextOperationTarget.class).doOperation(ITextOperationTarget.UNDO);
        expectText("alpha\nbeta\ngamma\n");
        settle();
        type(widget, "jdd");
        expectText("alpha\ngamma\n");
    }

    @Test
    void withFDefaultEclipsesSwitchShowsNothing() throws Exception {
        neovimGivesFileInfo(false);
        ITextEditor other = switchAwayAndBack();
        try {
            expectNoMessageAndTheFocus();
            undoThenDeleteTheLineBelow();
        } finally {
            page().closeEditor(other, false);
        }
    }

    @Test
    void withoutFEclipsesSwitchStillShowsNothing() throws Exception {
        neovimGivesFileInfo(true);
        ITextEditor other = switchAwayAndBack();
        try {
            expectNoMessageAndTheFocus();
            undoThenDeleteTheLineBelow();
        } finally {
            page().closeEditor(other, false);
        }
    }
}
