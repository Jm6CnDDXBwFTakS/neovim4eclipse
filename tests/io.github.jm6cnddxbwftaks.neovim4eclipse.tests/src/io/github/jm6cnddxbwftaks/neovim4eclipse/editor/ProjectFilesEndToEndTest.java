// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
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
     * The same while another job holds the workspace, as a build does for as long as it runs: the
     * file's refresh waits for it, in the background, and the UI goes on meanwhile. The editor opens
     * when the job is done. (Refreshed on the UI thread, the {@code settle()} below would wait for the
     * job, which then has ended.)
     */
    @Test
    void aFileMadeOutsideEclipseDuringABuildOpensAfterItWithoutHoldingUpTheUi() throws Exception {
        Path dir = Path.of(project.getLocation().toOSString(), "built");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("f.txt"), "from the build\n", StandardCharsets.UTF_8);
        open(createFile("a.txt", "see built/f.txt\n"));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Job build = holdTheWorkspace(holding, release);
        try {
            build.schedule();
            holding.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            session.inputAsync("0fb");
            settle();
            session.inputAsync("gf");
            settle();
            assertEquals(Job.RUNNING, build.getState(), "the UI went on while the workspace was held");
            assertEquals(null, editorTitled("f.txt"), "not opened before the refresh");
            release.countDown();
            ITextEditor opened = waitForFollowInto("f.txt");
            expectText(opened, "from the build\n");
        } finally {
            release.countDown();
            build.join();
            closeAllBut(null);
        }
    }

    /**
     * The same, but Neovim moves on before the job is done: {@code gf}, then {@code Ctrl-O} back
     * while the file's lookup still waits. When the lookup ends, Neovim is no longer in the file, so
     * its editor does not open ({@code EditorAttachManager.followNeovimIntoFile}: only the buffer
     * Neovim is in now is followed). A later {@code gf} opens it as usual.
     */
    @Test
    void aFileNeovimLeftDuringABuildIsNotOpenedAfterIt() throws Exception {
        Path dir = Path.of(project.getLocation().toOSString(), "built");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("g.txt"), "from the build\n", StandardCharsets.UTF_8);
        ITextEditor a = open(createFile("a.txt", "see built/g.txt\n"));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Job build = holdTheWorkspace(holding, release);
        try {
            build.schedule();
            holding.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            session.inputAsync("0fb");
            settle();
            session.inputAsync("gf");
            settle();
            assertFalse(session.getEnteredBufnr() == session.getAttachedBufnr(), "gf entered g.txt's buffer");
            session.inputAsync("<C-o>");
            settle();
            assertEquals(session.getAttachedBufnr(), session.getEnteredBufnr(), "Ctrl-O went back to a.txt");
            release.countDown();
            build.join();
            waitUntil("g.txt's lookup to end", TIMEOUT_MS, () -> !lookupRunning("g.txt"));
            settle(); // the lookup's last step runs on the UI thread, queued as it ended
            assertEquals(null, editorTitled("g.txt"), "Neovim had moved on: not opened");
            assertEquals(a, page().getActiveEditor(), "a.txt stays the editor");

            session.inputAsync("gf");
            expectText(waitForFollowInto("g.txt"), "from the build\n");
        } finally {
            release.countDown();
            build.join();
            closeAllBut(null);
        }
    }

    /**
     * A job that holds the workspace as a build does ({@code buildRule()}: the root), from
     * {@code holding} until {@code release}.
     */
    private static Job holdTheWorkspace(CountDownLatch holding, CountDownLatch release) {
        Job build = Job.create("test: hold the workspace as a build does", monitor -> {
            holding.countDown();
            try {
                release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Status.OK_STATUS;
        });
        build.setRule(ResourcesPlugin.getWorkspace().getRoot());
        return build;
    }

    /** Whether the job that looks up {@code name}'s workspace file is still to run or running. */
    private static boolean lookupRunning(String name) {
        return Arrays.stream(Job.getJobManager().find(null))
                .anyMatch(job -> job.getName().startsWith("Neovim: find ") && job.getName().endsWith(name));
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
        settle(); // the attach's whole buffer is applied on the UI thread: let it run before asking Neovim
        waitUntil("the buffer to hold Eclipse's text again", TIMEOUT_MS,
                () -> "xalpha".equals(String.valueOf(SessionProbe.lua(session, "return vim.api.nvim_get_current_line()"))));
        expectText("xalpha\nbeta\ngamma\n");
        assertFalse(String.valueOf(bufferOption("filetype")).equals("text"), "not read by Neovim");
        session.inputAsync("jiy<Esc>");
        expectText("xalpha\nybeta\ngamma\n");
    }

    /**
     * The buffer is never empty during a script's {@code :edit!}: the cursor keeps its line, and a
     * change Neovim makes before Eclipse has attached the buffer again reaches the document (the
     * attach sends the whole buffer first: NeovimSession, "re-attach after a reload"). Here the
     * script changes line 2 itself, right after its {@code :edit!}, and keys follow at once.
     */
    @Test
    void aScriptsEditBangKeepsTheCursorAndChangesRightAfterItReachTheDocument() {
        session.inputAsync("ggix<Esc>3G");
        expectText("xalpha\nbeta\ngamma\n");
        settle();
        SessionProbe.lua(session, "vim.cmd('edit!') vim.api.nvim_buf_set_lines(0, 1, 2, false, { 'BETA' }) return 0");
        settle(); // the attach's whole buffer is applied on the UI thread: let it run before asking Neovim
        assertEquals(3, cursorLine(), "the cursor keeps its line");
        session.inputAsync("ggiR<Esc>");
        expectText("Rxalpha\nBETA\ngamma\n");
        session.inputAsync("Gix<Esc>");
        expectText("Rxalpha\nBETA\nxgamma\n");
    }

    /**
     * A script's {@code :edit!} while Eclipse types in insert mode: the text Eclipse typed is in the
     * document only until Esc replays it into Neovim, so the reload must neither drop it from the
     * document nor have it inserted twice.
     */
    @Test
    void aScriptsEditBangWhileEclipseTypesKeepsTheTypedTextOnce() {
        UiTestSupport.type(widget, "gg");
        UiTestSupport.type(widget, 'I');
        waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
        UiTestSupport.type(widget, "ab"); // typed by Eclipse, not Neovim
        expectText("abalpha\nbeta\ngamma\n");
        SessionProbe.lua(session, "vim.cmd('edit!') return 0");
        settle();
        expectText("abalpha\nbeta\ngamma\n");
        UiTestSupport.escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        expectText("abalpha\nbeta\ngamma\n");
        assertEquals("abalpha", String.valueOf(SessionProbe.lua(session, "return vim.api.nvim_buf_get_lines(0, 0, 1, false)[1]")));

        // The insert is still one insert for Neovim: u takes it back in one step, and . repeats it
        // (I, then "ab") on the next line, as after any insert.
        session.inputAsync("u");
        expectText("alpha\nbeta\ngamma\n");
        session.inputAsync("j.");
        expectText("alpha\nabbeta\ngamma\n");
    }

    /**
     * The same for a file outside every project (<i>File, Open File...</i>), which Neovim's project
     * folders do not cover: its buffer has the reload hook all the same (LuaScripts.DOCUMENT_BUFFER).
     */
    @Test
    void aScriptsEditBangOnAFileOutsideTheWorkspaceReloadsEclipsesText() throws Exception {
        Path dir = Files.createTempDirectory("outside");
        Path file = dir.resolve("outside.txt");
        Files.writeString(file, "disk\n", StandardCharsets.UTF_8);
        ITextEditor outside = (ITextEditor) IDE.openEditorOnFileStore(page(), EFS.getLocalFileSystem().getStore(file.toUri()));
        try {
            waitForAttach(outside, "outside.txt");
            IDocument doc = documentOf(outside);
            doc.replace(0, doc.getLength(), "unsaved\n");
            settle();
            SessionProbe.lua(session, "vim.cmd('edit!') return 0");
            settle(); // the attach's whole buffer is applied on the UI thread: let it run before asking Neovim
            waitUntil("the buffer to hold Eclipse's text again", TIMEOUT_MS,
                    () -> "unsaved".equals(String.valueOf(SessionProbe.lua(session, "return vim.api.nvim_get_current_line()"))));
            session.inputAsync("ggiZ<Esc>");
            expectText(outside, "Zunsaved\n");
        } finally {
            page().closeEditor(outside, false);
        }
    }

    /**
     * A project opened or closed while Neovim runs reaches it (ProjectFolders' listener schedules
     * its job, which walks the workspace off the thread that made the change: here the UI thread).
     */
    @Test
    void aProjectOpenedOrClosedLaterIsSeenByNeovim() throws Exception {
        IProject other = ResourcesPlugin.getWorkspace().getRoot().getProject("e2e-other");
        try {
            other.create(null);
            other.open(null);
            String file = other.getLocation().append("o.txt").toOSString();
            String inside = "return require('neovim4eclipse.project_files').inside(" + lua(file) + ")";
            waitUntil("Neovim to know the opened project", TIMEOUT_MS,
                    () -> Boolean.TRUE.equals(SessionProbe.lua(session, inside)));
            other.close(null);
            waitUntil("Neovim to forget the closed project", TIMEOUT_MS,
                    () -> Boolean.FALSE.equals(SessionProbe.lua(session, inside)));
        } finally {
            other.delete(true, true, null);
        }
    }

    /**
     * A linked folder made while Neovim runs (<i>New → Folder → Link to alternate location</i>):
     * its files are the project's from then on, so {@code gf} onto one by its real path opens its
     * editor and Neovim never reads it. Once the link is removed, the file is outside every project
     * again: Neovim reads it itself (a {@code FileType} event) and no editor opens. The manual test
     * "A linked folder, added while running" does the same by hand.
     */
    @Test
    void aLinkedFolderMadeAndRemovedWhileRunningIsSeenByNeovim() throws Exception {
        Path outside = Files.createTempDirectory("linked");
        Path ext = outside.resolve("ext.txt");
        Files.writeString(ext, "outside text\n", StandardCharsets.UTF_8);
        String inside = "return require('neovim4eclipse.project_files').inside(" + lua(ext.toString()) + ")";
        IFolder link = project.getFolder("ext");
        ITextEditor a = open(createFile("a.txt", ext + "\n"));
        try {
            link.createLink(IPath.fromOSString(outside.toString()), IResource.NONE, null);
            waitUntil("Neovim to know the linked folder", TIMEOUT_MS,
                    () -> Boolean.TRUE.equals(SessionProbe.lua(session, inside)));
            session.inputAsync("gg0gf");
            ITextEditor opened = waitForFollowInto("ext.txt");
            expectText(opened, "outside text\n");
            assertEquals("", bufferOption("filetype"), "Neovim did not read it: no filetype");
            assertEquals("acwrite", bufferOption("buftype"), "a document's buffer now");
            assertEquals("", filetypesSeen(), "no FileType event while linked");

            page().closeEditor(opened, false);
            link.delete(true, null);
            waitUntil("Neovim to forget the removed link", TIMEOUT_MS,
                    () -> Boolean.FALSE.equals(SessionProbe.lua(session, inside)));
            page().activate(a);
            waitForAttach(a, "a.txt again");
            session.inputAsync("gg0gf");
            waitUntil("Neovim to read ext.txt itself", TIMEOUT_MS, () -> filetypesSeen().contains("ext.txt="),
                    () -> "; seen: " + filetypesSeen());
            settle();
            assertEquals(null, editorTitled("ext.txt"), "no editor for a file outside the projects");
        } finally {
            closeAllBut(null);
            if (link.exists()) {
                link.delete(true, null);
            }
            // Neovim read ext.txt itself in step 7: a buffer of its own, which would stay listed and,
            // once the file is deleted, modified (E211), stopping a later test's :bufdo in it.
            wipeBuffersUnder(outside);
            Files.deleteIfExists(ext);
            Files.deleteIfExists(outside);
        }
    }

    /**
     * Eclipse's own Back (Alt+Left, Cmd+[ on macOS) after {@code gf} goes back to the file {@code gf}
     * left, as {@code Ctrl-O} does: the plug-in activates the opened editor in a way Eclipse's
     * navigation history records ({@code NavigationHistorySteps}). The manual test "gf onto a text
     * file, then back".
     */
    @Test
    void gfThenEclipsesBackGoesToTheFileGfLeft() throws Exception {
        createFile("b.txt", "one\ntwo\n");
        ITextEditor a = open(createFile("a.txt", "see b.txt\n"));
        try {
            session.inputAsync("0fb");
            settle();
            session.inputAsync("gf");
            waitForFollowInto("b.txt");

            runCommand("org.eclipse.ui.navigate.backwardHistory"); // Alt+Left, Cmd+[

            waitForAttach(a, "a.txt after Eclipse's Back");
            assertEquals(a, page().getActiveEditor());
        } finally {
            closeAllBut(null);
        }
    }

    /**
     * A global mark into a closed file: {@code 'A} from another editor reopens the file's editor with
     * the caret on the mark's line. A closed file keeps its buffer, and the buffer keeps its marks
     * (docs/design/BUFFER_NUMBERS.md). The manual test "A global mark into a closed file".
     */
    @Test
    void aGlobalMarkIntoAClosedFileReopensItOnTheMarksLine() throws Exception {
        ITextEditor a = open(createFile("a.txt", "a\n"));
        ITextEditor b = open(createFile("b.txt", "b1\nb2\nb3\n"));
        try {
            session.inputAsync("2GmA");
            settle();
            page().closeEditor(b, false);
            waitForAttach(a, "a.txt, b.txt closed");

            session.inputAsync("'A");

            ITextEditor reopened = waitForFollowInto("b.txt");
            assertEquals(2, cursorLine(), "on the mark's line");
            waitUntil("the caret on line 2", TIMEOUT_MS,
                    () -> widgetOf(reopened).getLineAtOffset(widgetOf(reopened).getCaretOffset()) == 1);
        } finally {
            // Global marks outlive the test: Neovim keeps them in its ShaDa file.
            SessionProbe.lua(session, "vim.cmd('delmarks A') return 0");
            closeAllBut(null);
        }
    }

    /**
     * A script's {@code :edit!} on a file with an unsaved change keeps the change (Eclipse's text, not
     * the file on disk), leaves the editor dirty, and keeps a mark on its line: the plug-in reads the
     * document back into the buffer instead of the file. The manual test "A plug-in's :edit! on an
     * open file". Example: line 1 changed to {@code xalpha} and {@code 'a} set on line 2; after
     * {@code :edit!} line 1 still reads {@code xalpha} and {@code 'a} is still on line 2.
     */
    @Test
    void aScriptsEditBangKeepsTheUnsavedChangeAndTheMarks() {
        session.inputAsync("ggix<Esc>2Gma3G");
        expectText("xalpha\nbeta\ngamma\n");
        settle();
        assertTrue(editor.isDirty(), "an unsaved change");

        SessionProbe.lua(session, "vim.cmd('edit!') return 0");
        settle();

        assertEquals("xalpha", SessionProbe.lua(session, "return vim.fn.getline(1)"), "Eclipse's text, not the file's");
        assertTrue(editor.isDirty(), "still unsaved");
        assertEquals(2, ((Number) SessionProbe.lua(session, "return vim.fn.line(\"'a\")")).intValue(), "'a on its line");
        assertEquals(3, cursorLine(), "the cursor on its line");
    }

    /**
     * Several existing projects imported at once (<i>File → Import → Existing Projects into
     * Workspace</i> makes them in one workspace operation): Neovim learns every one's folder, and
     * {@code gf} onto a file of the last opens its editor. One walk, or a few, sends them all
     * ({@code ProjectFolders}, "Bursts"). The manual test "Closing, opening and importing projects";
     * closing and opening are {@link #aProjectOpenedOrClosedLaterIsSeenByNeovim}.
     * Example: {@code e2e-imp1} to {@code e2e-imp3} at {@code /tmp/n4e-importsN/imp1} and so on;
     * {@code gf} onto {@code /tmp/n4e-importsN/imp3/i.txt}.
     */
    @Test
    void severalProjectsImportedAtOnceAreAllSeenByNeovim() throws Exception {
        Path root = Files.createTempDirectory("n4e-imports");
        List<String> names = List.of("imp1", "imp2", "imp3");
        List<IProject> imported = new ArrayList<>();
        Path target = root.resolve("imp3").resolve("i.txt");
        try {
            for (String name : names) {
                Files.createDirectories(root.resolve(name));
                Files.writeString(root.resolve(name).resolve("i.txt"), "imported " + name + "\n", StandardCharsets.UTF_8);
            }
            open(createFile("a.txt", target + "\n"));
            ResourcesPlugin.getWorkspace().run(monitor -> {
                for (String name : names) {
                    IProject made = ResourcesPlugin.getWorkspace().getRoot().getProject("e2e-" + name);
                    IProjectDescription description = ResourcesPlugin.getWorkspace().newProjectDescription(made.getName());
                    description.setLocation(IPath.fromOSString(root.resolve(name).toString()));
                    made.create(description, monitor);
                    made.open(monitor);
                    imported.add(made);
                }
            }, null);
            for (String name : names) {
                String inside = "return require('neovim4eclipse.project_files').inside("
                        + lua(root.resolve(name).resolve("i.txt").toString()) + ")";
                waitUntil("Neovim to know " + name, TIMEOUT_MS, () -> Boolean.TRUE.equals(SessionProbe.lua(session, inside)));
            }

            session.inputAsync("gg0gf");

            ITextEditor opened = waitForFollowInto("i.txt");
            expectText(opened, "imported imp3\n");
            assertEquals(imported.get(2).getFile("i.txt"), ((IFileEditorInput) opened.getEditorInput()).getFile());
            assertEquals("", filetypesSeen(), "Neovim did not read it itself");
        } finally {
            closeAllBut(null);
            wipeBuffersUnder(root);
            for (IProject made : imported) {
                made.delete(false, true, null); // keeps the files; deleted below
            }
            for (String name : names) {
                leaveAndDelete(root.resolve(name), root.resolve(name).resolve("i.txt"), root.resolve(name).resolve(".project"));
            }
            leaveAndDelete(root);
        }
    }

    /**
     * Wipes Neovim's buffers of the files under {@code folder}, a folder outside the test project:
     * the base class wipes only its own project's, and one Neovim serves every test. A buffer left
     * listed would be entered by a later test's {@code :bufdo}.
     */
    private void wipeBuffersUnder(Path folder) {
        SessionProbe.lua(session, """
                local paths = require('neovim4eclipse.paths')
                local rules = paths.rules(paths.current())
                for _, buf in ipairs(vim.api.nvim_list_bufs()) do
                  local name = vim.api.nvim_buf_get_name(buf)
                  if name ~= '' and rules.inside(name, FOLDER) then
                    pcall(vim.api.nvim_buf_delete, buf, { force = true })
                  end
                end
                return 0""".replace("FOLDER", lua(folder.toString())));
    }

    private static String lua(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
