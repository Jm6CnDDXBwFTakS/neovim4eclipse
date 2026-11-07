// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IStorage;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPersistableElement;
import org.eclipse.ui.IStorageEditorInput;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ISetSelectionTarget;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;

/**
 * Writes to a file other than the editor's: {@code :w name}
 * writes a copy, {@code :2,3w name} some lines, {@code :w >> name} adds them to a file, as in Vim,
 * {@code :saveas name} is Eclipse's Save As without the dialog, and {@code :wq name} writes the copy
 * before closing. The editor's file is {@code test.txt}, {@code "alpha\nbeta\ngamma\n"};
 * each test makes it {@code "lpha\n..."} first ({@code 0x}), so the copy is told from the file.
 *
 * <p>Error checks come last in a test: Neovim shows an error of the write hook in two lines, which
 * opens the output popup, and that takes the keyboard focus.
 */
class OtherFileWriteEndToEndTest extends EndToEndTestBase {

    private static final String CHANGED = "lpha\nbeta\ngamma\n";

    /**
     * Closes the other editors a test opened, before the base class deletes the project: an editor
     * left open on a deleted file asks about it ("File Changed") when next activated, which would
     * block the next test class.
     */
    @AfterEach
    void closeTheOtherEditors() {
        page().closeAllEditors(false);
        editor = null;
    }

    private void change() {
        type(widget, "0x");
        expectText(CHANGED);
    }

    private String errmsg() {
        AtomicReference<String> answer = new AtomicReference<>();
        session.evalAsync("v:errmsg", answer::set);
        waitUntil("v:errmsg", TIMEOUT_MS, () -> answer.get() != null);
        return answer.get();
    }

    private void expectMessage(String expected) {
        waitUntil("the message " + expected, TIMEOUT_MS, () -> expected.equals(messageText(editor)),
                () -> "; the message line shows " + messageText(editor));
    }

    private void backToTheTestFile() {
        page().activate(editor);
        waitForAttach(editor, "test.txt");
    }

    /**
     * A write to a name the file system cannot hold gets Vim's E212 for that name, not "Eclipse could
     * not save" the editor's own file. On Windows {@code :w a<b.txt} is such a name; a NUL byte is one
     * everywhere, so the test asks for the write as Neovim's write hook does (DOCUMENT_BUFFER's
     * {@code eclipse_write} request), with a NUL in the name.
     */
    @Test
    void aWriteToANameTheFileSystemCannotHoldIsE212() throws Exception {
        List<String> before = memberNames();
        AtomicReference<String> answer = new AtomicReference<>();
        session.evalAsync("luaeval('vim.rpcrequest(vim.g.neovim4eclipse_channel, \"eclipse_write\", "
                + "vim.api.nvim_get_current_buf(), \"\", \"copy\", \"a\\0b.txt\", 0, 0, false)[1]')", answer::set);

        waitUntil("Eclipse's answer", TIMEOUT_MS, () -> answer.get() != null);
        assertTrue(answer.get().startsWith("E212: Can't open file for writing"), answer.get());
        assertEquals(before, memberNames(), "no file was written");
    }

    /** The names of the test project's files and folders, sorted. */
    private List<String> memberNames() throws Exception {
        project.refreshLocal(IResource.DEPTH_ONE, null);
        return Arrays.stream(project.members()).map(IResource::getName).sorted().toList();
    }

    @Test
    void aWriteUnderAnotherNameIsACopyAndTheEditorKeepsItsChanges() {
        List<String> dialogs = closeModalDialogsWhile(widget);
        change();
        runInBox(widget, "w copy.txt");

        expectMessage("\"copy.txt\" [New] 3L, 16B written");
        IFile copy = project.getFile("copy.txt");
        assertTrue(copy.exists() && copy.isSynchronized(IResource.DEPTH_ZERO), "a workspace file, known at once");
        assertEquals(CHANGED, fileText("copy.txt"));
        assertEquals("alpha\nbeta\ngamma\n", fileText("test.txt"), "the editor's file is untouched");
        assertTrue(editor.isDirty(), "the editor's changes are still unsaved, as in Vim");
        assertEquals("test.txt", editor.getTitle());

        runInBox(widget, "w"); // still a save of the editor's own file
        // The save goes through Eclipse a moment after Enter: wait for it, as for every outcome
        // (it failed on Windows pinned to one core when read at once).
        waitUntil("test.txt saved", TIMEOUT_MS, () -> !editor.isDirty() && CHANGED.equals(fileText("test.txt")),
                () -> "; it reads " + fileText("test.txt").replace("\n", "\\n") + ", dirty " + editor.isDirty());
        assertEquals(List.of(), dialogs);
    }

    /**
     * An editor Neovim has not been attached to has no Neovim buffer, so Neovim writes to its file
     * and Eclipse decides. (With a buffer, Neovim refuses first: see the next test.)
     */
    @Test
    void aCopyOverAFileOpenWithoutChangesReloadsItsEditor() throws Exception {
        List<String> dialogs = closeModalDialogsWhile(widget);
        ITextEditor other = openInTheBackground(createFile("other.txt", "other\n"));
        change();
        runInBox(widget, "w! other.txt");

        expectMessage("\"other.txt\" 3L, 16B written");
        assertEquals(CHANGED, fileText("other.txt"));
        expectText(other, CHANGED);
        assertFalse(other.isDirty());
        page().activate(other);
        waitForAttach(other, "other.txt");
        assertEquals(List.of(), dialogs, "no File Changed");
    }

    /** As in Vim: Neovim refuses to write a file that another of its buffers has (E139). */
    @Test
    void aCopyToAFileWithANeovimBufferIsNeovimsE139() throws Exception {
        openOtherFile();
        backToTheTestFile();
        change();
        runInBox(widget, "w! other.txt");

        waitUntil("E139", TIMEOUT_MS, () -> errmsg().startsWith("E139"), () -> ": " + errmsg());
        assertEquals("other\n", fileText("other.txt"));
    }

    @Test
    void aCopyNeverGoesOverUnsavedChangesInAnotherEditor() throws Exception {
        ITextEditor other = openInTheBackground(createFile("unsaved.txt", "unsaved\n"));
        documentOf(other).replace(0, 1, ""); // unsaved changes, made without Neovim
        assertTrue(other.isDirty());
        change();
        runInBox(widget, "w! unsaved.txt");

        waitUntil("the refusal", TIMEOUT_MS, () -> errmsg().contains("has unsaved changes"), () -> ": " + errmsg());
        assertEquals("\"unsaved.txt\" has unsaved changes in an Eclipse editor; save or close it first", errmsg());
        assertEquals("unsaved\n", fileText("unsaved.txt"));
        assertEquals("nsaved\n", documentOf(other).get());
        assertTrue(editor.isDirty());
    }

    @Test
    void aCopyOutsideTheWorkspaceGoesToDisk() throws Exception {
        Path folder = Files.createTempDirectory("neovim4eclipse-copy");
        Path out = folder.resolve("out.txt");
        change();
        runInBox(widget, "w " + out);

        expectMessage("\"out.txt\" [New] 3L, 16B written");
        assertEquals(CHANGED, Files.readString(out));
        assertTrue(editor.isDirty());
    }

    @Test
    void aCopyIsInTheEditorsEncoding() throws Exception {
        IFile latin = createFile("latin.txt", "");
        latin.setCharset("ISO-8859-1", null);
        latin.setContents(new ByteArrayInputStream("café\n".getBytes(StandardCharsets.ISO_8859_1)), true, false, null);
        ITextEditor opened = openExisting(latin);
        runInBox(widgetOf(opened), "w latin-copy.txt");

        waitUntil("the copy", TIMEOUT_MS, () -> project.getFile("latin-copy.txt").exists());
        IFile copy = project.getFile("latin-copy.txt");
        assertEquals("ISO-8859-1", copy.getCharset(), "set on the new file, so Eclipse reads it back right");
        try (var in = copy.getContents()) {
            assertArrayEquals("café\n".getBytes(StandardCharsets.ISO_8859_1), in.readAllBytes());
        }
        assertEquals("\"latin-copy.txt\" [New] 1L, 5B written", messageText(opened));
    }

    /**
     * Opens {@code file} with Neovim kept away from it, so it has no Neovim buffer, as an editor not
     * shown since Neovim started. (Any editor Neovim was attached to has one, and Neovim then refuses
     * a write to its file itself, E139.)
     */
    private ITextEditor openInTheBackground(IFile file) throws Exception {
        EditorAttachManager manager = EditorAttachManager.getInstance();
        ITextEditor opened;
        manager.setAutoAttach(false);
        try {
            opened = (ITextEditor) IDE.openEditor(page(), file, TEXT_EDITOR, false);
            settle();
        } finally {
            manager.setAutoAttach(true);
        }
        backToTheTestFile();
        AtomicReference<String> exists = new AtomicReference<>();
        session.evalAsync("bufexists('" + file.getLocation() + "')", exists::set);
        waitUntil("bufexists()", TIMEOUT_MS, () -> exists.get() != null);
        assertEquals("0", exists.get(), "no Neovim buffer for " + file.getName());
        return opened;
    }

    /** Opens {@code file}, already made, and waits until Neovim is attached to it. */
    private ITextEditor openExisting(IFile file) throws Exception {
        ITextEditor opened = (ITextEditor) IDE.openEditor(page(), file, TEXT_EDITOR, true);
        waitForAttach(opened, file.getName());
        return opened;
    }

    @Test
    void aCopyIntoAMissingFolderNeedsPlusPlusP() {
        change();
        runInBox(widget, "w ++p sub/deeper/copy.txt");
        expectMessage("\"copy.txt\" [New] 3L, 16B written");
        assertEquals(CHANGED, fileText("sub/deeper/copy.txt"));

        runInBox(widget, "w other/copy.txt");
        waitUntil("E212", TIMEOUT_MS, () -> errmsg().startsWith("E212"), () -> ": " + errmsg());
        assertFalse(project.getFolder("other").exists());
    }

    @Test
    void someLinesGoToAnotherFile() {
        change();
        runInBox(widget, "2,3w part.txt");
        expectMessage("\"part.txt\" [New] 2L, 11B written");
        assertEquals("beta\ngamma\n", fileText("part.txt"));
        assertTrue(editor.isDirty());
    }

    @Test
    void linesAreAppendedToAFileAndAMissingOneNeedsBang() {
        change();
        runInBox(widget, "1w! >> log.txt");
        expectMessage("\"log.txt\" [New] 1L, 5B appended");
        runInBox(widget, "3w >> log.txt");
        expectMessage("\"log.txt\" 1L, 6B appended");
        assertEquals("lpha\ngamma\n", fileText("log.txt"));
        assertTrue(project.getFile("log.txt").isSynchronized(IResource.DEPTH_ZERO));

        runInBox(widget, "w >> missing.txt");
        waitUntil("E212", TIMEOUT_MS, () -> errmsg().startsWith("E212"), () -> ": " + errmsg());
        assertFalse(project.getFile("missing.txt").exists());
    }

    @Test
    void saveasMovesTheEditorToTheNewFileAndKeepsNeovimsUndo() {
        List<String> dialogs = closeModalDialogsWhile(widget);
        int bufnr = session.getAttachedBufnr();
        change();
        runInBox(widget, "saveas sa.txt");

        waitUntil("the editor on sa.txt", TIMEOUT_MS, () -> "sa.txt".equals(editor.getTitle()),
                () -> "; it is " + editor.getTitle());
        assertEquals(project.getFile("sa.txt"), editor.getEditorInput().getAdapter(IFile.class), "a workspace-file editor");
        assertEquals(CHANGED, fileText("sa.txt"));
        assertEquals("alpha\nbeta\ngamma\n", fileText("test.txt"), "the old file stays as it was");
        assertFalse(editor.isDirty());
        waitForAttach(editor, "sa.txt");
        assertEquals(bufnr, session.getAttachedBufnr(), "the same Neovim buffer");
        expectMessage("\"sa.txt\" [New] 3L, 16B written");

        type(widget, "u");
        expectText("alpha\nbeta\ngamma\n"); // Neovim's undo history came along
        runInBox(widget, "w");
        assertEquals("alpha\nbeta\ngamma\n", fileText("sa.txt"), "a later :w saves the new file");
        assertEquals(List.of(), dialogs);
    }

    @Test
    void saveasOutsideTheWorkspace() throws Exception {
        Path out = Files.createTempDirectory("neovim4eclipse-saveas").resolve("out.txt");
        change();
        runInBox(widget, "saveas " + out);

        waitUntil("the editor on out.txt", TIMEOUT_MS, () -> "out.txt".equals(editor.getTitle()));
        assertEquals(CHANGED, Files.readString(out));
        assertFalse(editor.isDirty());
    }

    @Test
    void saveasToAFileOpenInAnotherEditorIsRefused() throws Exception {
        openInTheBackground(createFile("taken.txt", "taken\n"));
        change();
        runInBox(widget, "saveas! taken.txt");

        waitUntil("the refusal", TIMEOUT_MS, () -> errmsg().startsWith("E139"), () -> ": " + errmsg());
        assertEquals("taken\n", fileText("taken.txt"));
        assertEquals("test.txt", editor.getTitle());
        assertTrue(editor.isDirty());
    }

    private void expectClosed(ITextEditor target) {
        waitUntil("the editor to close", TIMEOUT_MS, () -> page().findEditor(target.getEditorInput()) == null);
    }

    /**
     * From a file in a subfolder, {@code :w name} (Neovim's write) and {@code :wq name} (Eclipse's)
     * put the copy in the same place, the project root, and {@code %:h/name} puts it beside the file.
     * Before, {@code :wq name} wrote beside the file while {@code :w name} wrote at the root.
     */
    @Test
    void aNameFromASubfolderMeansTheSameFileForWAndWq() throws Exception {
        project.getFolder("src").create(true, true, null);
        IFile nested = createFile("src/Nested.txt", "nested\n");
        ITextEditor nestedEditor = (ITextEditor) IDE.openEditor(page(), nested, TEXT_EDITOR, true);
        waitForAttach(nestedEditor, "src/Nested.txt");

        runInBox(widgetOf(nestedEditor), "w w-copy.txt");
        waitUntil("w-copy.txt at the root", TIMEOUT_MS, () -> project.getFile("w-copy.txt").exists());
        runInBox(widgetOf(nestedEditor), "w %:h/beside.txt");
        waitUntil("beside.txt in src", TIMEOUT_MS, () -> project.getFile("src/beside.txt").exists());
        runInBox(widgetOf(nestedEditor), "wq wq-copy.txt");
        expectClosed(nestedEditor);

        assertEquals("nested\n", fileText("wq-copy.txt"), "at the root, as :w puts it");
        assertFalse(project.getFile("src/wq-copy.txt").exists());
    }

    /**
     * An untitled editor has no file of its own, so {@code :w a.txt} is a save as, not a copy: Vim
     * names a buffer without a file after the file it writes ({@code :help cpo-F}). The editor
     * becomes {@code a.txt} (in the folder of the editor before it, the project root), keeps its
     * Neovim buffer, which takes the new name, and a later {@code :w} saves {@code a.txt}.
     */
    @Test
    void writeWithANameSavesAnUntitledEditorAsThatFile() throws Exception {
        List<String> dialogs = closeModalDialogsWhile(widget);
        ITextEditor untitled = openUntitledFile();
        StyledText untitledWidget = widgetOf(untitled);
        int bufnr = session.getAttachedBufnr();
        // Kept in Neovim, not spliced into later Lua as text: on Windows the name has backslashes,
        // which a quoted Lua string would read as escapes (C:\Users: "invalid escape sequence").
        SessionProbe.lua(session, "_G.neovim4eclipse_test_untitled = vim.api.nvim_buf_get_name(0)");
        runInBox(untitledWidget, "call setline(1, 'hello')");
        expectText(untitled, "hello");

        runInBox(untitledWidget, "w a.txt");
        waitUntil("the editor on a.txt", TIMEOUT_MS, () -> "a.txt".equals(untitled.getTitle()),
                () -> "; it is " + untitled.getTitle() + ", the message line shows " + messageText(untitled));
        assertEquals(project.getFile("a.txt"), untitled.getEditorInput().getAdapter(IFile.class), "a workspace-file editor");
        assertEquals("hello", fileText("a.txt"));
        assertFalse(untitled.isDirty());
        waitForAttach(untitled, "a.txt");
        assertEquals(bufnr, session.getAttachedBufnr(), "the same Neovim buffer");
        waitUntil("Neovim's buffer named a.txt, none left of the untitled name", TIMEOUT_MS,
                () -> Boolean.TRUE.equals(SessionProbe.lua(session, "return vim.api.nvim_buf_get_name(0):match('a%.txt$') ~= nil"
                        + " and vim.fn.bufexists(_G.neovim4eclipse_test_untitled) == 0")));

        runInBox(untitledWidget, "call setline(1, 'again')");
        expectText(untitled, "again");
        runInBox(untitledWidget, "w");
        waitUntil("a later :w to save a.txt", TIMEOUT_MS, () -> "again".equals(fileText("a.txt")));
        assertEquals(List.of(), dialogs);
        page().closeEditor(untitled, false);
    }

    /**
     * An untitled editor opened from a project's context menu (<i>New → Untitled Text File</i>, with
     * that project selected in the Project Explorer) works in that project's root, so
     * {@code :w c.txt} writes {@code c.txt} there, not beside the editor that was active before (found
     * 2026-10-31: right-click {@code mtplugin} while {@code mt/a.txt} is open; {@code c.txt} went to
     * {@code mt}). {@link SelectedProject}.
     */
    @Test
    void anUntitledEditorWorksInTheProjectSelectedInTheProjectExplorer() throws Exception {
        List<String> dialogs = closeModalDialogsWhile(widget);
        IProject other = ResourcesPlugin.getWorkspace().getRoot().getProject("e2e-selected");
        other.create(null);
        other.open(null);
        ISetSelectionTarget explorer = (ISetSelectionTarget) page().showView(IPageLayout.ID_PROJECT_EXPLORER);
        try {
            settle(); // the tree shows the new project once its resource change is handled
            explorer.selectReveal(new StructuredSelection(other));
            assertEquals(new StructuredSelection(other), page().getSelection(IPageLayout.ID_PROJECT_EXPLORER),
                    "the project selected, as a right-click selects it");
            ITextEditor untitled = openUntitledFile();
            StyledText untitledWidget = widgetOf(untitled);
            runInBox(untitledWidget, "call setline(1, 'selected')");
            expectText(untitled, "selected");

            runInBox(untitledWidget, "w c.txt");
            waitUntil("the editor on c.txt", TIMEOUT_MS, () -> "c.txt".equals(untitled.getTitle()),
                    () -> "; it is " + untitled.getTitle() + ", the message line shows " + messageText(untitled));
            assertEquals(other.getFile("c.txt"), untitled.getEditorInput().getAdapter(IFile.class),
                    "in the selected project");
            assertFalse(project.getFile("c.txt").exists(), "not beside the editor before it");
            assertEquals(List.of(), dialogs);
        } finally {
            explorer.selectReveal(StructuredSelection.EMPTY); // the next tests' untitled editors: as before
            page().closeAllEditors(false); // before the delete: an editor on a deleted file asks
            other.delete(true, true, null);
        }
    }

    /**
     * {@code :enew} opens an untitled editor, as <i>File → New → Untitled Text File</i> does, which
     * works in the folder of the editor it was typed in, as a Vim window keeps its folder: another
     * project selected in the Project Explorer does not count here (it does for the menu, above).
     */
    @Test
    void enewOpensAnUntitledEditorThatWorksInTheEditorsFolder() throws Exception {
        List<String> dialogs = closeModalDialogsWhile(widget);
        IProject other = ResourcesPlugin.getWorkspace().getRoot().getProject("e2e-enew-selected");
        other.create(null);
        other.open(null);
        ISetSelectionTarget explorer = (ISetSelectionTarget) page().showView(IPageLayout.ID_PROJECT_EXPLORER);
        try {
            settle();
            explorer.selectReveal(new StructuredSelection(other));
            page().activate(editor);
            waitForAttach(editor, "test.txt");

            runInBox(widget, "enew");
            ITextEditor untitled = expectUntitledEditor();
            StyledText untitledWidget = widgetOf(untitled);
            runInBox(untitledWidget, "call setline(1, 'new')");
            expectText(untitled, "new");
            runInBox(untitledWidget, "w e.txt");
            waitUntil("the editor on e.txt", TIMEOUT_MS, () -> "e.txt".equals(untitled.getTitle()),
                    () -> "; it is " + untitled.getTitle() + ", the message line shows " + messageText(untitled));
            assertEquals(project.getFile("e.txt"), untitled.getEditorInput().getAdapter(IFile.class),
                    "in the project of the editor :enew was typed in");
            assertFalse(other.getFile("e.txt").exists(), "not in the selected project");
            assertEquals(List.of(), dialogs);
        } finally {
            explorer.selectReveal(StructuredSelection.EMPTY);
            page().closeAllEditors(false);
            other.delete(true, true, null);
        }
    }

    /**
     * A {@code :lcd} typed in the editor goes with {@code :enew}: the new editor's window copies the
     * folder of the one it was typed in, as a Vim window keeps its local folder when {@code :enew}
     * empties it, rather than the project's root worked out again.
     */
    @Test
    void enewKeepsTheLcdOfTheEditorItWasTypedIn() throws Exception {
        List<String> dialogs = closeModalDialogsWhile(widget);
        IFolder sub = project.getFolder("enew-lcd");
        sub.create(true, true, null);
        try {
            runInBox(widget, "lcd enew-lcd"); // relative to the window's folder, the project's root
            runInBox(widget, "enew");
            ITextEditor untitled = expectUntitledEditor();
            StyledText untitledWidget = widgetOf(untitled);
            runInBox(untitledWidget, "call setline(1, 'local')");
            expectText(untitled, "local");
            runInBox(untitledWidget, "w f.txt");
            waitUntil("the editor on f.txt", TIMEOUT_MS, () -> "f.txt".equals(untitled.getTitle()),
                    () -> "; it is " + untitled.getTitle() + ", the message line shows " + messageText(untitled));
            assertEquals(sub.getFile("f.txt"), untitled.getEditorInput().getAdapter(IFile.class),
                    "in the folder :lcd chose");
            assertFalse(project.getFile("f.txt").exists(), "not in the project's root");
            assertEquals(List.of(), dialogs);
        } finally {
            page().closeAllEditors(false);
            // Windows deletes no folder a process works in, and Neovim's window still has
            // enew-lcd as its :lcd until the closes reach Neovim (on one core, not yet). Eclipse
            // sends no event before a folder delete, only before a project's (ProjectFolderExit),
            // so the test moves Neovim out itself, as leaveAndDelete does for folders outside.
            settle();
            session.leaveFolder(sub.getLocation().toOSString(), TestWait.duration());
            sub.delete(true, null);
        }
    }

    /** The untitled editor's alternate file is the editor {@code :enew} came from: {@code Ctrl-^} goes back. */
    @Test
    void ctrlCaretInAnEnewEditorGoesBack() throws Exception {
        runInBox(widget, "enew");
        expectUntitledEditor();
        session.inputAsync("<C-^>");
        waitForAttach(editor, "test.txt, after Ctrl-^");
        assertEquals(editor, page().getActiveEditor());
    }

    /**
     * {@code :enew!} with unsaved changes and nothing selected in the Project Explorer (the manual
     * check en-bang, automated): an untitled editor opens as for {@code :enew}, and the changed
     * editor stays open with its changes, since an Eclipse editor is not a Vim window that
     * {@code !} would empty. No error (E492, E37) and no dialog.
     */
    @Test
    void enewBangWithUnsavedChangesKeepsThemAndOpensAnUntitledEditor() throws Exception {
        List<String> dialogs = closeModalDialogsWhile(widget);
        ISetSelectionTarget explorer = (ISetSelectionTarget) page().showView(IPageLayout.ID_PROJECT_EXPLORER);
        try {
            explorer.selectReveal(StructuredSelection.EMPTY);
            page().activate(editor);
            waitForAttach(editor, "test.txt");
            type(widget, "ggdd");
            expectText("beta\ngamma\n");
            assertTrue(editor.isDirty());

            runInBox(widget, "enew!");
            ITextEditor untitled = expectUntitledEditor();
            settle(); // an error would reach the message line after the new editor shows
            for (ITextEditor shown : List.of(untitled, editor)) {
                assertFalse(Pattern.compile("E\\d+:").matcher(messageText(shown)).find(),
                        "no error: " + messageText(shown));
            }
            assertTrue(List.of(page().getEditors()).contains(editor), "the changed editor stays open");
            assertTrue(editor.isDirty(), "with its changes");
            assertEquals("beta\ngamma\n", document.get());
            assertEquals(List.of(), dialogs);
        } finally {
            page().closeAllEditors(false);
        }
    }

    /** Waits for the untitled editor {@code :enew} opens to be active, with Neovim attached to it. */
    private ITextEditor expectUntitledEditor() {
        waitUntil("an untitled editor", TIMEOUT_MS, () -> page().getActiveEditor() instanceof ITextEditor active
                && active != editor && active.getEditorInput().getAdapter(IFile.class) == null,
                () -> "; the active editor is " + page().getActiveEditor() + ", the message line shows " + messageText(editor));
        ITextEditor untitled = (ITextEditor) page().getActiveEditor();
        waitForAttach(untitled, "the untitled editor");
        return untitled;
    }

    /** {@code :wq b.txt} in an untitled editor saves it as {@code b.txt}, then closes it without asking. */
    @Test
    void wqWithANameSavesAnUntitledEditorAsThatFileAndClosesWithoutAsking() throws Exception {
        List<String> dialogs = closeModalDialogsWhile(widget);
        ITextEditor untitled = openUntitledFile();
        StyledText untitledWidget = widgetOf(untitled);
        runInBox(untitledWidget, "call setline(1, 'hello')");
        expectText(untitled, "hello");

        runInBox(untitledWidget, "wq b.txt");
        expectClosed(untitled);
        assertEquals("hello", fileText("b.txt"));
        assertEquals(List.of(), dialogs, "no question about the untitled document's changes");
    }

    /**
     * An untitled document shown in two editors (Window › Editor › Clone) gets a copy: a save as could
     * move only one editor. Before, the file was written and then an error said the editor could not
     * move.
     */
    @Test
    void writeWithANameCopiesAClonedUntitledDocument() throws Exception {
        List<String> dialogs = closeModalDialogsWhile(widget);
        ITextEditor untitled = openUntitledFile();
        runInBox(widgetOf(untitled), "call setline(1, 'shared')");
        expectText(untitled, "shared");
        runCommand("org.eclipse.ui.window.newEditor"); // Window › Editor › Clone
        ITextEditor clone = (ITextEditor) page().getActiveEditor();
        waitForAttach(clone, "the clone");

        runInBox(widgetOf(clone), "w shared.txt");
        waitUntil("the copy written", TIMEOUT_MS, () -> project.getFile("shared.txt").exists());
        assertEquals("shared", fileText("shared.txt"));
        waitUntil("Vim's written message, no error", TIMEOUT_MS,
                () -> messageText(clone).startsWith("\"shared.txt\" [New]"), () -> ": " + messageText(clone));
        assertTrue(EditorAttachManager.hasNoFile(clone.getEditorInput()), "the clone stays untitled");
        assertTrue(EditorAttachManager.hasNoFile(untitled.getEditorInput()), "the first editor too");
        assertEquals(List.of(), dialogs);
        page().closeEditor(clone, false);
        page().closeEditor(untitled, false);
    }

    /**
     * Only an untitled editor has no file: read-only contents from elsewhere (a revision in Git's
     * history, an entry in a jar: {@link IStorageEditorInput}) have a name, and {@code :w name} there
     * writes a copy; so do workspace files.
     */
    @Test
    void onlyAnUntitledEditorCountsAsHavingNoFile() throws Exception {
        ITextEditor untitled = openUntitledFile();
        assertTrue(EditorAttachManager.hasNoFile(untitled.getEditorInput()), "untitled");
        page().closeEditor(untitled, false);
        assertFalse(EditorAttachManager.hasNoFile(editor.getEditorInput()), "a workspace file");
        IStorageEditorInput revision = new IStorageEditorInput() {
            @Override public IStorage getStorage() { return null; }
            @Override public boolean exists() { return false; }
            @Override public ImageDescriptor getImageDescriptor() { return null; }
            @Override public String getName() { return "test.txt 1a2b3c4"; }
            @Override public IPersistableElement getPersistable() { return null; }
            @Override public String getToolTipText() { return getName(); }
            @Override public <T> T getAdapter(Class<T> adapter) { return null; }
        };
        assertFalse(EditorAttachManager.hasNoFile(revision), "a revision from Git's history");
    }

    @Test
    void wqWithANameWritesTheCopyThenCloses() {
        ITextEditor target = editor;
        runInBox(widget, "wq copy.txt");
        expectClosed(target);
        assertEquals("alpha\nbeta\ngamma\n", fileText("copy.txt"));
    }

    @Test
    void wqBangWithANameDiscardsTheEditorsOwnChanges() {
        ITextEditor target = editor;
        change();
        runInBox(widget, "wq! copy.txt");
        expectClosed(target);
        assertEquals(CHANGED, fileText("copy.txt"), "the copy has the changes");
        assertEquals("alpha\nbeta\ngamma\n", fileText("test.txt"), "the file has not");
    }

    @Test
    void xWithANameWritesNothingWithoutChanges() {
        ITextEditor target = editor;
        runInBox(widget, "x copy.txt");
        expectClosed(target);
        assertFalse(project.getFile("copy.txt").exists());
    }

    @Test
    void wqWithAnExistingNameNeedsBangAndStaysOpen() throws Exception {
        createFile("taken.txt", "taken\n");
        runInBox(widget, "wq taken.txt");
        waitUntil("E13", TIMEOUT_MS, () -> messageText(editor).startsWith("E13"), () -> ": " + messageText(editor));
        assertEquals("taken\n", fileText("taken.txt"));
        assertTrue(page().findEditor(editor.getEditorInput()) != null, "still open");
    }

    /**
     * Neovim keeps an empty buffer named after a file written under another name (Vim's alternate
     * file); left there, it kept an editor opened on that file later from naming its own buffer.
     */
    @Test
    void anEditorOpenedOnACopyGetsABufferOfThatName() throws Exception {
        runInBox(widget, "w copy.txt");
        expectMessage("\"copy.txt\" [New] 3L, 17B written");
        ITextEditor copy = openExisting(project.getFile("copy.txt"));
        AtomicReference<String> name = new AtomicReference<>();
        session.evalAsync("bufname('%')", name::set);
        waitUntil("bufname()", TIMEOUT_MS, () -> name.get() != null);
        assertEquals(project.getFile("copy.txt").getLocation().toOSString(), name.get());
        assertEquals("alpha\nbeta\ngamma\n", documentOf(copy).get());
    }

    /**
     * Neovim's own {@code :w other} (a copy) onto a symbolic link to a missing file: Neovim's E13
     * check follows the link and lets it pass as a missing file, so Eclipse refuses the write
     * without {@code !} ({@link DanglingLinks}); {@code :w!} writes through it. Skipped where links
     * cannot be made.
     */
    @Test
    void aCopyThroughALinkToAMissingFileNeedsBang() throws Exception {
        Path dir = Files.createTempDirectory("neovim4eclipse-e2e").toRealPath();
        Path gone = dir.resolve("gone.txt");
        Path link = Path.of(project.getLocation().toOSString()).resolve("copy.local");
        try {
            try {
                Files.createSymbolicLink(link, gone);
            } catch (IOException | UnsupportedOperationException e) {
                assumeTrue(false, "cannot make a symbolic link here: " + e);
            }
            runInBox(widget, "w copy.local");
            waitUntil("the refusal", TIMEOUT_MS, () -> errmsg().contains("is a link to"), () -> ": " + errmsg());
            assertEquals(DanglingLinks.refusal("copy.local", link), errmsg());
            assertFalse(Files.exists(gone), "nothing written through the link");

            runInBox(widget, "w! copy.local");
            waitUntil("the link's target written", TIMEOUT_MS, () -> Files.exists(gone));
            assertEquals("alpha\nbeta\ngamma\n", Files.readString(gone, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(link);
            leaveAndDelete(dir, gone);
        }
    }
}

