// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Writes to a file other than the editor's (the plan "writes under another name"): {@code :w name}
 * writes a copy, {@code :2,3w name} some lines, {@code :w >> name} adds them to a file, as in Vim,
 * and {@code :saveas name} is Eclipse's Save As without the dialog. The editor's file is {@code test.txt}, {@code "alpha\nbeta\ngamma\n"};
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
        assertEquals(CHANGED, fileText("test.txt"));
        assertFalse(editor.isDirty());
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
}
