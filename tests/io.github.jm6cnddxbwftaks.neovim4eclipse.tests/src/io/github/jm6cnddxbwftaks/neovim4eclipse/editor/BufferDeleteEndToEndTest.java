// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.function.BooleanSupplier;
import java.util.logging.Level;

import org.eclipse.swt.custom.StyledText;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.ExpectedLog;

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

    /**
     * A buffer Neovim deletes by itself (a Lua plug-in, {@code <Cmd>bd}) closes its editor too: the
     * safety net of {@code LuaScripts.DOCUMENT_BUFFER}. The buffer's close that follows finds it
     * unloaded already and must not fail on that: it once logged "Closing buffer 3 (...) failed",
     * with a stack trace, into the Error Log for every such delete.
     */
    @Test
    void aBufferNeovimDeletesByItselfClosesItsEditor() throws Exception {
        StyledText other = widgetOf(openOtherAndReturn());
        int bufnr = ((Number) SessionProbe.lua(session, "return vim.fn.bufnr('other.txt')")).intValue();
        try (ExpectedLog log = ExpectedLog.of(SessionProbe.documentBuffers())) {
            SessionProbe.lua(session, "vim.cmd.bdelete(" + bufnr + ")");
            expectClosed(other, "other.txt's editor (buffer " + bufnr + ")");
            SessionProbe.afterQueuedWork(session, TIMEOUT_MS); // the buffer's close, queued by the editor's
            log.assertNothingAtOrAbove(Level.WARNING);
        }
        assertFalse(widget.isDisposed(), "test.txt stays open");
    }

    /**
     * A plug-in wipes a document's buffer ({@code :bwipeout}, with autocommands, so the safety net's
     * notice is sent) just as its editor is activated, its show queued already: the editor closes,
     * as it does for the same wipe a moment later. The show finds the buffer gone and makes it anew
     * under another number before the UI thread gets to the notice, which must still find the
     * editor ({@code DocumentBuffers.onClosed}).
     *
     * <p>A UI test because three threads take part: the RPC reader reads the notice, the session's
     * input thread runs the show, and the UI thread closes the editor. Their order is made certain
     * here: the wipe is run from the UI thread, which then attaches the editor itself, as its
     * activation does (queuing the show), and holds the event loop until the show has made the
     * buffer anew; only then can the close the notice queued run. A close that looked the editor up
     * by the wiped number would find none, and the editor would stay open.
     * ({@code DocumentBuffersTest} replays the same order without Eclipse.)
     */
    @Test
    void aBufferWipedJustAsItsEditorIsActivatedClosesTheEditor() throws Exception {
        ITextEditor other = openOtherAndReturn();
        StyledText otherWidget = widgetOf(other);
        int bufnr = ((Number) SessionProbe.lua(session, "return vim.fn.bufnr('other.txt')")).intValue();
        // Detaching test.txt's editor queues its document's push, which reads the document on the
        // UI thread. Done first, so that no such work waits in front of the show while the event
        // loop is held: the show needs no UI thread until it has made the buffer anew.
        EditorAttachManager.getInstance().detach();
        settle();
        try (ExpectedLog log = ExpectedLog.of(SessionProbe.documentBuffers())) {
            // The notice is read, and the editor's close queued for the UI thread, before this
            // returns: Neovim sends it during the wipe, before its answer.
            SessionProbe.lua(session, "vim.cmd.bwipeout(" + bufnr + ")");
            EditorAttachManager.getInstance().attach(other, false);
            // The new buffer gets a higher number (Neovim never reuses one), and the show attaches it.
            holdEventLoopUntil("other.txt's buffer to be made anew", () -> session.getAttachedBufnr() > bufnr);
            waitUntil("other.txt's editor to close", TIMEOUT_MS, otherWidget::isDisposed);
            settle(); // the new buffer's close, queued by the editor's
            log.assertNothingAtOrAbove(Level.WARNING);
        } finally {
            if (!otherWidget.isDisposed()) {
                page().closeEditor(other, false); // the next test starts with test.txt alone
            }
        }
        assertFalse(widget.isDisposed(), "test.txt stays open");
    }

    /**
     * Waits for {@code condition} without running the event loop, so that the work other threads
     * queue for the UI thread (with {@code asyncExec}) stays queued until it holds. Only for a
     * condition that no work on the UI thread is needed for. Polls as {@code waitUntil} does, up to
     * the same limit ({@code TestWait}).
     */
    private static void holdEventLoopUntil(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out after " + TIMEOUT_MS + " ms waiting, with the event loop held, for: " + what);
            }
            Thread.sleep(10);
        }
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
