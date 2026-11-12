// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;

import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WelcomeNote;

/**
 * The text check that ends every UI test ({@link TextCheck}, run by {@code EndToEndTestBase}): that
 * it finds a difference and names it, and that a test left in insert mode is compared after its
 * {@code Esc}, when Neovim has the typed text; and that {@link TextMayDiffer} leaves a test out.
 */
class TextCheckEndToEndTest extends EndToEndTestBase {

    /** A test left Eclipse mode on: the next test starts in Neovim mode again. */
    private boolean eclipseModeLeftOn;

    @AfterEach
    void neovimModeBackOn() throws Exception {
        if (eclipseModeLeftOn) {
            runCommand(WelcomeNote.TOGGLE_COMMAND_ID);
            settle();
        }
    }

    /**
     * Ends with the two copies different, which the check would fail: it passes only because the
     * marker leaves it out. Neovim mode comes back on in the clean-up, after the check.
     */
    @Test
    @TextMayDiffer("ends in Eclipse mode, with an edit Neovim has not been sent")
    void aTestMarkedTextMayDifferIsNotCompared() throws Exception {
        runCommand(WelcomeNote.TOGGLE_COMMAND_ID);
        eclipseModeLeftOn = true;
        document.replace(document.getLineOffset(1), 4, "BETA");
        assertNotNull(TextCheck.differences(TIMEOUT_MS), "the copies differ when the test ends");
    }

    /**
     * Eclipse mode: Eclipse's edits are not pushed until Neovim mode is back on, so the two copies
     * differ meanwhile, as after a drift. Neovim mode on again pushes the document: they agree.
     */
    @Test
    void aDifferenceIsFoundAndNamed() throws Exception {
        runCommand(WelcomeNote.TOGGLE_COMMAND_ID);
        document.replace(document.getLineOffset(1), 4, "BETA");
        String differences = TextCheck.differences(TIMEOUT_MS);
        assertNotNull(differences, "Eclipse has BETA, Neovim beta");
        assertTrue(differences.contains("line 2 is \"BETA\" in Eclipse, \"beta\" in Neovim"), differences);

        runCommand(WelcomeNote.TOGGLE_COMMAND_ID);
        settle();
        assertNull(TextCheck.differences(TIMEOUT_MS), "Neovim mode on again pushes the document");
    }

    /**
     * Text Eclipse typed in insert mode reaches Neovim at Esc, which the check presses first.
     *
     * <p>The check finds the test file's buffer by its number, while {@code nvim_get_current_line()}
     * reads the current window's buffer, which can be another one (a buffer left over from an
     * earlier test, say). So the line is read together with where it came from ({@link #CURRENT}),
     * in one call, and a failure message says which window and buffer were current at that read and
     * what the test file's buffer held.
     *
     * <p>It also lists every change to the document from the {@code i} on, each with the thread and
     * the calls that made it ({@link DocumentChanges}). Why: in a rare failure the document read
     * "Xalpha" (the wait below saw it) and later "alpha" again, in Eclipse and in Neovim alike, so
     * something took the X out of the document after it arrived. The list names what did: a change
     * of Neovim's applied by the plug-in ({@code BufferSynchronizer}), Eclipse's file buffer
     * reloading test.txt, an undo.
     */
    @Test
    void aTestLeftInInsertModeIsComparedAfterItsEsc() {
        try (DocumentChanges changes = DocumentChanges.of(document)) {
            type(widget, "iX");
            waitUntilInsert();
            String differences = TextCheck.differences(TIMEOUT_MS);
            assertNull(differences, () -> differences + "; the document's changes from the i on: " + changes);
            assertEquals("n", session.getMode());
            int bufnr = EditorAttachManager.getInstance().bufferNumberOf(document);
            List<?> read = (List<?>) SessionProbe.lua(session, CURRENT.formatted(bufnr));
            assertEquals("Xalpha", read.get(0),
                    () -> read.get(1) + "; the document's changes from the i on: " + changes);
        }
    }

    /**
     * Records each change to a document, with the thread and the calls that made it, for a failure
     * message. Example of one entry: {@code [main] 0+0 "X" at BufferSynchronizer.applyChange <-
     * BufferSynchronizer.lambda$14 <- ... <- Display.runAsyncMessages}: the X Neovim typed, applied
     * to the document by the plug-in. The calls are the first few that are not the document's own
     * or the JDK's: those name the code that changed it.
     */
    private static final class DocumentChanges implements IDocumentListener, AutoCloseable {

        /** How many calls of each change are kept: enough to name its maker, short enough to read. */
        private static final int CALLS = 8;

        private final IDocument document;
        private final List<String> changes = new CopyOnWriteArrayList<>();

        private DocumentChanges(IDocument document) {
            this.document = document;
        }

        static DocumentChanges of(IDocument document) {
            DocumentChanges recorder = new DocumentChanges(document);
            document.addDocumentListener(recorder);
            return recorder;
        }

        @Override
        public void documentAboutToBeChanged(DocumentEvent event) {
        }

        @Override
        public void documentChanged(DocumentEvent event) {
            String calls = StackWalker.getInstance().walk(frames -> frames
                    .filter(f -> !f.getClassName().startsWith("java.") && !f.getClassName().startsWith("jdk.")
                            && !f.getClassName().equals("org.eclipse.jface.text.AbstractDocument")
                            && !f.getClassName().equals("org.eclipse.jface.text.Document")
                            && !f.getClassName().endsWith(".SynchronizableDocument") // a file buffer's document
                            && !f.getClassName().equals(DocumentChanges.class.getName()))
                    .limit(CALLS)
                    .map(f -> f.getClassName().replaceAll(".*\\.", "") + "." + f.getMethodName())
                    .collect(Collectors.joining(" <- ")));
            String text = event.getText() == null ? "" : event.getText().replace("\n", "\\n");
            changes.add("[" + Thread.currentThread().getName() + "] " + event.getOffset() + "+" + event.getLength()
                    + " \"" + text + "\" at " + calls);
        }

        @Override
        public void close() {
            document.removeDocumentListener(this);
        }

        @Override
        public String toString() {
            return changes.isEmpty() ? "none" : String.join("; ", changes);
        }
    }

    /**
     * A drift Eclipse never sees: Neovim's line 3 changes with its line events dropped as an echo of
     * a push (between two {@code eclipse_echo} notifications, as {@code LuaScripts.SET_LINES_MARKED}
     * sends them). Eclipse's next edit in normal mode (a format, a refactoring; here a replace) is
     * pushed whole and finds it: Neovim gets Eclipse's line back, the Error Log one warning that says
     * when and holds no text, and the status bar tells the user.
     */
    @Test
    void aDriftIsRepairedByThePushOfAnEclipseEditAndReported() throws Exception {
        int bufnr = EditorAttachManager.getInstance().bufferNumberOf(document);
        SessionProbe.lua(session, """
                local chan, buf = vim.g.neovim4eclipse_channel, %d
                vim.rpcnotify(chan, 'eclipse_echo', buf, true)
                vim.api.nvim_buf_set_lines(buf, 2, 3, true, { 'GAMMA' })
                vim.rpcnotify(chan, 'eclipse_echo', buf, false)
                """.formatted(bufnr));
        assertEquals("gamma", document.get().split("\\n")[2], "Eclipse never saw the drift");

        document.replace(0, 0, "X"); // Eclipse's own edit, in normal mode
        // The status bar's message comes after the report, which comes after the push: wait for it.
        waitUntil("the status bar's message", TIMEOUT_MS,
                () -> statusLineTexts(editor).stream().anyMatch(t -> t.contains("drifted apart")));
        assertEquals("gamma", SessionProbe.lua(session, "return vim.api.nvim_buf_get_lines(" + bufnr + ", 2, 3, true)[1]"),
                "Neovim's line 3 put right");
        List<LogRecord> repairs = takeRepairs();
        assertEquals(1, repairs.size(), () -> "one report: " + repairs);
        assertEquals(Level.WARNING, repairs.get(0).getLevel());
        String message = repairs.get(0).getMessage();
        assertTrue(message.contains("a .txt file in the") && message.contains("Eclipse's lines were written into Neovim's")
                && message.contains("Found after an Eclipse edit in normal mode."), message);
        assertTrue(!message.contains("amma") && !message.contains("test.txt"), "no text, no name: " + message);
    }

    /**
     * Lua that returns {@code { current line, where it came from }}: the current window and buffer
     * (number, name, first line), the test file's buffer ({@code %d}, the number the plug-in keeps
     * for its document, 0 for none): whether it is loaded, its windows and lines, and every buffer's
     * number and name. Example of the second: {@code "window 1000, buffer 3 (/tmp/ws/p/test.txt,
     * first line "Xalpha"); test.txt is buffer 3, loaded, in windows { 1000 }: { "Xalpha",
     * "beta", "gamma" }; buffers 2: 3:/tmp/ws/p/test.txt"}.
     */
    private static final String CURRENT = """
            local win = vim.api.nvim_get_current_win()
            local current = vim.api.nvim_get_current_buf()
            local line = vim.api.nvim_get_current_line()
            local test = %d
            local valid = test > 0 and vim.api.nvim_buf_is_valid(test)
            local all = {}
            for _, b in ipairs(vim.api.nvim_list_bufs()) do
              all[#all + 1] = b .. ':' .. vim.api.nvim_buf_get_name(b)
            end
            local first = vim.api.nvim_buf_get_lines(current, 0, 1, false)[1] or ''
            local where = 'window ' .. win .. ', buffer ' .. current
                .. ' (' .. vim.api.nvim_buf_get_name(current) .. ', first line ' .. vim.inspect(first) .. '); '
                .. 'test.txt is buffer ' .. test
            if valid then
              where = where .. ', ' .. (vim.api.nvim_buf_is_loaded(test) and 'loaded' or 'not loaded')
                  .. ', in windows ' .. vim.inspect(vim.fn.win_findbuf(test))
                  .. ': ' .. vim.inspect(vim.api.nvim_buf_get_lines(test, 0, -1, false))
            else
              where = where .. ': none'
            end
            return { line, where .. '; buffers ' .. table.concat(all, ' ') }
            """;

    private void waitUntilInsert() {
        UiTestSupport.waitUntil("insert mode", TIMEOUT_MS, () -> "i".equals(session.getMode()));
        expectText("Xalpha\nbeta\ngamma\n");
    }
}
