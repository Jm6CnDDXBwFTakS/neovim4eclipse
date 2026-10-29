// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.eclipse.core.resources.IFile;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;

/**
 * Neovim moving by itself into a project's file that has no editor ({@code gf}, {@code :b} into a
 * closed file, {@code Ctrl-^}, a quickfix entry): Eclipse opens the file's editor and follows into
 * it, and Neovim never read the file itself, so no filetype plug-in or {@code FileType} autocommand
 * ran (docs/design/BUFFER_NUMBERS.md, 2.3; the Lua side is project_files_spec.lua).
 */
class ProjectFilesEndToEndTest extends EndToEndTestBase {

    /**
     * Counts the {@code FileType} events from now on, as a user's {@code FileType} setup would see
     * them (a language server started from one).
     */
    @BeforeEach
    void countFiletypes() {
        SessionProbe.lua(session, """
                _G.neovim4eclipse_test_filetypes = {}
                vim.api.nvim_create_augroup('neovim4eclipse_test_filetypes', { clear = true })
                vim.api.nvim_create_autocmd('FileType', { group = 'neovim4eclipse_test_filetypes', callback = function(ev)
                  table.insert(_G.neovim4eclipse_test_filetypes, vim.fn.fnamemodify(ev.file, ':t') .. '=' .. ev.match)
                end })
                return 0""");
    }

    @AfterEach
    void stopCounting() {
        SessionProbe.lua(session, "pcall(vim.api.nvim_del_augroup_by_name, 'neovim4eclipse_test_filetypes') return 0");
    }

    private String filetypesSeen() {
        return String.valueOf(SessionProbe.lua(session, "return table.concat(_G.neovim4eclipse_test_filetypes, ',')"));
    }

    private ITextEditor open(IFile file) throws Exception {
        ITextEditor opened = (ITextEditor) IDE.openEditor(page(), file, TEXT_EDITOR, true);
        waitForAttach(opened, file.getName());
        return opened;
    }

    /** The open editor titled {@code title}, or {@code null}. */
    private static IEditorPart editorTitled(String title) {
        return Arrays.stream(page().getEditorReferences()).filter(r -> r.getTitle().equals(title))
                .map(r -> r.getEditor(false)).findFirst().orElse(null);
    }

    /** Waits until Neovim has moved Eclipse into the editor titled {@code title}, attached. */
    private ITextEditor waitForFollowInto(String title) {
        waitUntil(title + "'s editor to be opened and attached", TIMEOUT_MS, () -> {
            NeovimEditorController c = EditorAttachManager.getInstance().getActiveController();
            return c != null && !c.isClosed() && c.getEditor().getTitle().equals(title)
                    && page().getActiveEditor() != null && page().getActiveEditor().getTitle().equals(title);
        }, () -> "; open: " + Arrays.stream(page().getEditorReferences()).map(IEditorReference::getTitle).toList());
        settle();
        return EditorAttachManager.getInstance().getActiveController().getEditor();
    }

    private Object bufferOption(String option) {
        return SessionProbe.lua(session, "return vim.bo." + option);
    }

    private int bufnr() {
        return ((Number) SessionProbe.lua(session, "return vim.api.nvim_get_current_buf()")).intValue();
    }

    private void closeAllBut(ITextEditor keep) {
        for (IEditorReference ref : page().getEditorReferences()) {
            IEditorPart part = ref.getEditor(false);
            if (part != null && part != keep && part != editor) {
                page().closeEditor(part, false);
            }
        }
    }

    /**
     * {@code gf} onto a file no editor shows: its editor opens, with the caret where Neovim put the
     * cursor, Neovim never ran filetype detection for it, and {@code Ctrl-O} goes back, since the
     * window that jumped travelled into the new editor.
     */
    @Test
    void gfOpensTheFilesEditorWithoutAFiletypeAndCtrlOGoesBack() throws Exception {
        createFile("b.txt", "one\ntwo\n");
        ITextEditor a = open(createFile("a.txt", "see b.txt\n"));
        try {
            session.inputAsync("0fb");
            settle();
            session.inputAsync("gf");
            ITextEditor b = waitForFollowInto("b.txt");
            expectText(b, "one\ntwo\n");
            assertEquals("", bufferOption("filetype"), "Neovim did not read it: no filetype");
            assertEquals("acwrite", bufferOption("buftype"), "a document's buffer now");
            assertEquals("", filetypesSeen(), "no FileType event, so no language server either");

            session.inputAsync("<C-o>");
            waitForAttach(a, "a.txt after Ctrl-O");
            assertEquals(a, page().getActiveEditor());
        } finally {
            closeAllBut(null);
        }
    }

    /**
     * {@code :b N} of a file whose editor was closed opens its editor again, with the same buffer
     * number and the caret where it was left (Vim's {@code :b} of a buffer {@code :bd} kept); then
     * {@code Ctrl-^} goes back and forth.
     */
    @Test
    void bOfAClosedFileReopensItsEditorWithItsNumberAndCtrlHatGoesBack() throws Exception {
        IFile cFile = createFile("c.txt", "c1\nc2\nc3\n");
        ITextEditor a = open(createFile("a.txt", "a\n"));
        ITextEditor c = open(cFile);
        int number = bufnr();
        session.inputAsync("3G");
        settle();
        page().closeEditor(c, false);
        waitForAttach(a, "a.txt, c.txt closed");
        try {
            session.inputAsync(":b " + number + "<CR>");
            ITextEditor reopened = waitForFollowInto("c.txt");
            assertEquals(number, bufnr(), "the same buffer number");
            assertEquals(3, cursorLine(), "where it was left");
            assertEquals(2, widgetOf(reopened).getLineAtOffset(widgetOf(reopened).getCaretOffset()), "the caret too");

            session.inputAsync("<C-^>");
            waitForAttach(a, "a.txt after Ctrl-^");
            session.inputAsync("<C-^>");
            waitForFollowInto("c.txt");
        } finally {
            closeAllBut(null);
        }
    }

    /** A quickfix entry in a file without an editor: its editor opens on the entry's line. */
    @Test
    void aQuickfixEntryOpensItsFileOnItsLine() throws Exception {
        IFile d = createFile("d.txt", "d1\nd2\nd3\n");
        open(createFile("a.txt", "a\n"));
        try {
            SessionProbe.lua(session, "vim.fn.setqflist({ { filename = " + lua(d.getLocation().toOSString())
                    + ", lnum = 2, text = 'here' } }) return 0");
            session.inputAsync(":cc 1<CR>");
            ITextEditor opened = waitForFollowInto("d.txt");
            assertEquals(2, cursorLine());
            waitUntil("the caret on line 2", TIMEOUT_MS,
                    () -> widgetOf(opened).getLineAtOffset(widgetOf(opened).getCaretOffset()) == 1);
        } finally {
            closeAllBut(null);
        }
    }

    /**
     * A file made on disk after Eclipse's last refresh (here in a folder Eclipse does not know
     * either) opens with its text, not empty.
     */
    @Test
    void aFileMadeOutsideEclipseOpensWithItsText() throws Exception {
        Path dir = Path.of(project.getLocation().toOSString(), "made");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("e.txt"), "outside\n", StandardCharsets.UTF_8);
        open(createFile("a.txt", "see made/e.txt\n"));
        try {
            session.inputAsync("0fm");
            settle();
            session.inputAsync("gf");
            ITextEditor opened = waitForFollowInto("e.txt");
            expectText(opened, "outside\n");
        } finally {
            closeAllBut(null);
        }
    }

    /**
     * Files Eclipse would open outside the IDE (a zip: the system's program) or not as text are not
     * opened, and Neovim goes back, as before; {@code :help} is still undone too. The zip's buffer is
     * a project file's (Neovim sent its name to Eclipse), so what keeps the system's program from
     * starting is {@link ProjectFileEditors}' check, not the file being unknown.
     */
    @Test
    void aFileEclipseWouldOpenOutsideTheIdeIsNotOpenedAndHelpIsStillUndone() throws Exception {
        createFile("x.zip", "PK\u0003\u0004");
        ITextEditor a = open(createFile("a.txt", "see x.zip\n"));
        try {
            session.inputAsync("0fx");
            settle();
            session.inputAsync("gf");
            settle();
            waitForAttach(a, "a.txt, Neovim sent back");
            settle();
            assertEquals(a, page().getActiveEditor());
            assertEquals(null, editorTitled("x.zip"), "no editor opened");
            assertFalse(String.valueOf(SessionProbe.lua(session, "return require('neovim4eclipse.project_files')"
                    + ".file_of(vim.fn.bufnr('x.zip'))")).isEmpty(), "Eclipse was asked to open it, and refused");
            assertEquals("a.txt", String.valueOf(SessionProbe.lua(session, "return vim.fn.expand('%:t')")));

            session.inputAsync(":help<CR>");
            settle();
            waitUntil("Neovim back in a.txt after :help", TIMEOUT_MS,
                    () -> "a.txt".equals(String.valueOf(SessionProbe.lua(session, "return vim.fn.expand('%:t')"))));
            assertEquals(a, page().getActiveEditor());
        } finally {
            closeAllBut(null);
        }
    }

    /**
     * A script's {@code :edit!} on a document (a plug-in reloading files) reloads Eclipse's text, with
     * its unsaved change, not the file on disk; and Neovim's changes still reach the document after.
     */
    @Test
    void aScriptsEditBangReloadsEclipsesTextAndTheBufferStillSyncs() throws Exception {
        session.inputAsync("ggix<Esc>");
        expectText("xalpha\nbeta\ngamma\n");
        SessionProbe.lua(session, "vim.cmd('edit!') return 0");
        waitUntil("the buffer to hold Eclipse's text again", TIMEOUT_MS,
                () -> "xalpha".equals(String.valueOf(SessionProbe.lua(session, "return vim.api.nvim_get_current_line()"))));
        expectText("xalpha\nbeta\ngamma\n");
        assertFalse(String.valueOf(bufferOption("filetype")).equals("text"), "not read by Neovim");
        session.inputAsync("jiy<Esc>");
        expectText("xalpha\nybeta\ngamma\n");
    }

    private static String lua(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
