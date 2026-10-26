// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.eclipse.swt.custom.StyledText;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;

/**
 * {@code :bd} with a buffer number or name closes that buffer's editor, not the one it was typed in
 * ({@code HostCommand.Kind.BUFFER_DELETE}). Which calls a command makes is tested without Eclipse
 * in {@code HostCommandRunnerTest}; this checks the real lookup, through Neovim's {@code bufnr()}.
 */
class BufferDeleteEndToEndTest extends EndToEndTestBase {

    /** Opens other.txt, then goes back to test.txt, so that the command is typed there. */
    private ITextEditor openOtherAndReturn() throws Exception {
        ITextEditor other = openOtherFile();
        page().activate(editor);
        waitForAttach(editor, "test.txt again");
        return other;
    }

    /** {@code targetWidget} is taken before the command: once its editor closes there is none. */
    private void expectClosed(StyledText targetWidget, String what) {
        waitUntil(what + " to close", TIMEOUT_MS, targetWidget::isDisposed);
        assertFalse(widget.isDisposed(), "the editor the command was typed in stays open");
        expectText("alpha\nbeta\ngamma\n");
    }

    @Test
    void aNameClosesThatBuffersEditor() throws Exception {
        StyledText other = widgetOf(openOtherAndReturn());
        runInBox(widget, "bd other"); // a unique part of the name is enough, as with :b
        expectClosed(other, "other.txt's editor");
    }

    @Test
    void aNumberClosesThatBuffersEditor() throws Exception {
        StyledText other = widgetOf(openOtherAndReturn());
        int bufnr = ((Number) SessionProbe.lua(session, "return vim.fn.bufnr('other.txt')")).intValue();
        runInBox(widget, "bd " + bufnr);
        expectClosed(other, "other.txt's editor (buffer " + bufnr + ")");
    }

    @Test
    void aBufferNobodyHasGetsVimsErrorAndClosesNothing() throws Exception {
        ITextEditor other = openOtherAndReturn();
        runInBox(widget, "bd 999");
        waitUntil("the E516 message", TIMEOUT_MS,
                () -> messageText(editor).equals("E516: No buffers were deleted"), // Neovim's own: 999 is no listed buffer
                () -> "; the message line shows " + messageText(editor));
        runInBox(widget, "bd nosuchfile");
        waitUntil("the E94 message", TIMEOUT_MS,
                () -> messageText(editor).equals("E94: No matching buffer for nosuchfile"),
                () -> "; the message line shows " + messageText(editor));
        assertFalse(widgetOf(other).isDisposed(), "other.txt stays open");
        assertFalse(widget.isDisposed(), "test.txt stays open");
        page().closeEditor(other, false);
    }
}
