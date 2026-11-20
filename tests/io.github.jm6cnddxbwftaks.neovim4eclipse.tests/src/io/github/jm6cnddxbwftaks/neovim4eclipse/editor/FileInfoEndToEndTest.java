// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;

/**
 * Neovim's file info ({@code "a.txt" 3 lines --33%--}) with {@code 'shortmess'} without {@code F},
 * as a user's config may set it. Neovim gives it when it enters a loaded buffer, unless {@code F}
 * is set (its default), so the case without F is the one that could show it. A switch of Eclipse's editors never shows it, as Eclipse shows nothing then
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

    /**
     * Takes F out of 'shortmess', as a user's config may, so that Neovim gives its file info. With F
     * (Neovim's default) Neovim shows nothing anyway, so the tests here are of the case without it.
     */
    private void neovimGivesFileInfo() {
        SessionProbe.lua(session, "vim.opt.shortmess:remove('F')");
    }

    /**
     * Activates another editor and comes back, so that Neovim enters both buffers. Twice before a
     * key: two file info messages would have been shown together, once taking the focus.
     */
    private ITextEditor switchAwayAndBack() throws Exception {
        ITextEditor other = openFile("other.txt", "one\ntwo\n");
        editor.getSite().getPage().activate(editor);
        waitForAttach(editor, "test.txt");
        return other;
    }

    /**
     * No file info in ui2's windows ({@link #shownText}), and the editor keeps the focus. Looked
     * for by its form ({@code "name" 3 lines --33%--}), as ui2's buffers may still hold an older
     * message of another kind.
     */
    private void expectNoMessageAndTheFocus() {
        settle();
        String shown = shownText();
        assertFalse(shown.contains(" lines --"), "no file info after Eclipse's switch: " + shown);
        assertTrue(widget.isFocusControl(), "the editor keeps the keyboard focus");
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
    @Disabled("ui2: Eclipse's own buffer switch shows Neovim's file info; MessageRoute, which left it out, is gone")
    void withoutFEclipsesSwitchStillShowsNothing() throws Exception {
        neovimGivesFileInfo();
        ITextEditor other = switchAwayAndBack();
        try {
            expectNoMessageAndTheFocus();
            undoThenDeleteTheLineBelow();
        } finally {
            page().closeEditor(other, false);
        }
    }

    /**
     * A {@code :b} the user types is Neovim's own: without F, Neovim's file info is shown, in the
     * editor {@code :b} moved to, as Neovim shows it for the plug-in's buffers. Those are
     * {@code acwrite} buffers, whose full name Neovim keeps ({@code core/LuaScripts.CREATE_DOCUMENT_BUFFER});
     * not "[Not edited]".
     */
    @Test
    void withoutFTheUsersOwnBufferCommandShowsNeovimsFileInfo() throws Exception {
        ITextEditor other = switchAwayAndBack();
        try {
            neovimGivesFileInfo();
            runCommandLine(widget, "b other.txt");
            waitUntil("other.txt to become the active editor", TIMEOUT_MS, () -> {
                IEditorPart active = page().getActiveEditor();
                return active != null && "other.txt".equals(active.getTitle());
            });
            waitForAttach(other, "other.txt");
            waitUntil("Neovim's file info for other.txt", TIMEOUT_MS, () -> {
                String shown = shownText();
                return shown.contains("other.txt\" 2 lines --50%--") && !shown.contains("[Not edited]");
            }, () -> "; Neovim shows \"" + shownText() + "\"");
        } finally {
            page().closeEditor(other, false);
        }
    }
}
