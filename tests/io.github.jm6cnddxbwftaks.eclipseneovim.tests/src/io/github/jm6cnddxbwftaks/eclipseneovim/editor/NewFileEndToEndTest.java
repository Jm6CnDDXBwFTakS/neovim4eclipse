// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import static io.github.jm6cnddxbwftaks.eclipseneovim.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.eclipseneovim.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.Test;

/**
 * {@code :e newfile} opens an empty editor, and {@code :w} creates the file (docs/NEW_FILE.md).
 *
 * <p>Each test watches for dialogs ({@link #closeModalDialogsWhile}): Eclipse's own save of a missing
 * file opens Save As, which would otherwise block the test instead of failing it. No test may let
 * Eclipse save a missing file <em>outside</em> the workspace: its Save As is the system's native
 * dialog, which the watcher cannot close.
 */
class NewFileEndToEndTest extends EndToEndTestBase {

    /** The active editor, once it is titled {@code title} and Neovim is attached to it. */
    private ITextEditor activeEditorNamed(String title) {
        waitUntil(title + " to open", TIMEOUT_MS, () -> {
            IEditorPart active = page().getActiveEditor();
            return active != null && title.equals(active.getTitle());
        }, () -> "; the active editor is " + page().getActiveEditor().getTitle());
        ITextEditor opened = (ITextEditor) page().getActiveEditor();
        waitForAttach(opened, title);
        return opened;
    }

    /** Types {@code text} in insert mode the way a person does: Eclipse types it, then Esc. */
    private void insert(StyledText target, String text) {
        type(target, 'i');
        settle(); // insert mode reached Eclipse, so Eclipse types what follows (not Neovim)
        type(target, text);
        UiTestSupport.escape(target);
        settle();
    }

    private void expectMessage(ITextEditor target, String expected) {
        waitUntil("the message " + expected, TIMEOUT_MS, () -> expected.equals(messageText(target)),
                () -> "; the message line shows " + messageText(target));
    }

    private void closeAll() {
        page().closeAllEditors(false);
        editor = null;
    }

    @Test
    void editOfAMissingFileOpensAnEmptyEditorAndWriteCreatesIt() {
        List<String> dialogs = closeModalDialogsWhile(widget);
        runInBox(widget, "e new.txt");

        ITextEditor opened = activeEditorNamed("new.txt");
        expectMessage(opened, "\"new.txt\" [New]");
        assertEquals("", documentOf(opened).get());
        IFile file = project.getFile("new.txt");
        assertFalse(file.exists(), "nothing on disk before :w");

        StyledText target = widgetOf(opened);
        insert(target, "hello");
        expectText(opened, "hello");
        runInBox(target, "w");

        assertEquals("hello", fileText("new.txt"));
        assertFalse(opened.isDirty());
        expectMessage(opened, "\"new.txt\" [New] [noeol] 1L, 5B written");
        assertEquals(List.of(), dialogs, "no Save As");

        type(target, "0x"); // a later :w is an ordinary save
        expectText(opened, "ello");
        runInBox(target, "w");
        assertEquals("ello", fileText("new.txt"));
        assertEquals(List.of(), dialogs);
        closeAll();
    }

    /**
     * The first key typed in a new workspace file used to be lost: {@code ihello} gave {@code ello}.
     * An existing empty file and a new file outside the workspace did not lose it.
     */
    @Test
    void theFirstKeyTypedInANewWorkspaceFileIsKept() throws Exception {
        IFile missing = project.getFile("first.txt");
        ITextEditor opened = (ITextEditor) IDE.openEditor(page(), missing, TEXT_EDITOR, true);
        waitForAttach(opened, "first.txt");

        insert(widgetOf(opened), "hello");
        expectText(opened, "hello");
        assertFalse(missing.exists(), "typing writes nothing");
        page().closeEditor(opened, false);
    }

    @Test
    void writeOnAnUntouchedNewEditorCreatesAnEmptyFile() {
        List<String> dialogs = closeModalDialogsWhile(widget);
        runInBox(widget, "e empty.txt");
        ITextEditor opened = activeEditorNamed("empty.txt");

        runInBox(widgetOf(opened), "w");

        assertTrue(project.getFile("empty.txt").exists());
        assertEquals("", fileText("empty.txt"));
        expectMessage(opened, "\"empty.txt\" [New] 0L, 0B written");
        settle();
        page().activate(editor); // and back: no "File Changed" question on activation
        page().activate(opened);
        settle();
        assertEquals(List.of(), dialogs);
        closeAll();
    }

    @Test
    void quittingWithoutWritingLeavesNothing() {
        runInBox(widget, "e gone.txt");
        ITextEditor opened = activeEditorNamed("gone.txt");
        insert(widgetOf(opened), "text");

        StyledText target = widgetOf(opened);
        runInBox(target, "q!");

        waitUntil("the editor to close", TIMEOUT_MS, () -> target.isDisposed());
        assertFalse(project.getFile("gone.txt").exists());
    }

    @Test
    void aMissingFolderNeedsPlusPlusP() {
        List<String> dialogs = closeModalDialogsWhile(widget);
        runInBox(widget, "e sub/new.txt");
        ITextEditor opened = activeEditorNamed("new.txt");
        StyledText target = widgetOf(opened);
        insert(target, "x");

        runInBox(target, "w");
        expectMessage(opened, NewFiles.NO_FOLDER);
        assertTrue(messageLineOf(opened).isError());
        assertFalse(project.getFolder("sub").exists(), "no folder");
        assertTrue(opened.isDirty());

        runInBox(target, "w ++p");
        assertEquals("x", fileText("sub/new.txt"));
        assertFalse(opened.isDirty());
        assertEquals(List.of(), dialogs);
        closeAll();
    }

    @Test
    void aNewFileOutsideTheWorkspaceIsCreatedByWrite() throws Exception {
        Path dir = Files.createTempDirectory("eclipseneovim-e2e").toRealPath();
        Path outside = dir.resolve("outside-new.txt");
        try {
            runInBox(widget, "e " + outside);
            ITextEditor opened = activeEditorNamed("outside-new.txt");
            assertNull(opened.getEditorInput().getAdapter(IFile.class), "not a workspace file");
            insert(widgetOf(opened), "out");
            expectText(opened, "out");

            runInBox(widgetOf(opened), "w");

            assertEquals("out", Files.readString(outside, StandardCharsets.UTF_8));
            assertFalse(opened.isDirty());
            closeAll();
        } finally {
            Files.deleteIfExists(outside);
            Files.deleteIfExists(dir);
        }
    }

    /**
     * Outside the workspace, a missing folder also needs {@code ++p}, and it is created with
     * {@code Files.createDirectories}. (If {@code :w} ever fell back to Eclipse's own save here, this
     * test would hang on the native Save As dialog instead of failing.)
     */
    @Test
    void aMissingFolderOutsideTheWorkspaceNeedsPlusPlusP() throws Exception {
        Path dir = Files.createTempDirectory("eclipseneovim-e2e").toRealPath();
        Path sub = dir.resolve("sub");
        Path outside = sub.resolve("new.txt");
        try {
            runInBox(widget, "e " + outside);
            ITextEditor opened = activeEditorNamed("new.txt");
            StyledText target = widgetOf(opened);
            insert(target, "out");
            expectText(opened, "out");

            runInBox(target, "w");
            expectMessage(opened, NewFiles.NO_FOLDER);
            assertFalse(Files.exists(sub), "no folder");
            assertTrue(opened.isDirty());

            runInBox(target, "w ++p");
            assertEquals("out", Files.readString(outside, StandardCharsets.UTF_8));
            assertFalse(opened.isDirty());
            closeAll();
        } finally {
            Files.deleteIfExists(outside);
            Files.deleteIfExists(sub);
            Files.deleteIfExists(dir);
        }
    }

    /** The written message counts bytes in the file's encoding: é is 1 byte in ISO-8859-1, 2 in UTF-8. */
    @Test
    void theWrittenMessageCountsBytesInTheFilesEncoding() throws Exception {
        project.setDefaultCharset("ISO-8859-1", null);
        runInBox(widget, "e enc.txt");
        ITextEditor opened = activeEditorNamed("enc.txt");
        StyledText target = widgetOf(opened);
        insert(target, "é");
        expectText(opened, "é");

        runInBox(target, "w");

        expectMessage(opened, "\"enc.txt\" [New] [noeol] 1L, 1B written");
        try (var in = project.getFile("enc.txt").getContents(true)) {
            assertArrayEquals(new byte[] { (byte) 0xE9 }, in.readAllBytes(), "written in ISO-8859-1");
        }
        closeAll();
    }

    /** No editor is registered for this extension: the Text Editor opens, never an outside program. */
    @Test
    void anUnknownExtensionOpensInTheTextEditor() {
        runInBox(widget, "e notes.unknownext");
        ITextEditor opened = activeEditorNamed("notes.unknownext");
        assertEquals(NewFiles.TEXT_EDITOR, opened.getSite().getId());
        closeAll();
    }

    @Test
    void wqCreatesTheFileAndClosesTheEditor() {
        List<String> dialogs = closeModalDialogsWhile(widget);
        runInBox(widget, "e wq.txt");
        ITextEditor opened = activeEditorNamed("wq.txt");

        StyledText target = widgetOf(opened);
        runInBox(target, "wq"); // even untouched, as in Vim

        waitUntil("the editor to close", TIMEOUT_MS, () -> target.isDisposed());
        assertTrue(project.getFile("wq.txt").exists());
        assertEquals(List.of(), dialogs);
    }

    @Test
    void zzCreatesAChangedFileAndCloses() {
        List<String> dialogs = closeModalDialogsWhile(widget);
        runInBox(widget, "e zz.txt");
        ITextEditor opened = activeEditorNamed("zz.txt");
        StyledText target = widgetOf(opened);
        insert(target, "z");

        type(target, "ZZ");

        waitUntil("the editor to close", TIMEOUT_MS, () -> target.isDisposed());
        assertEquals("z", fileText("zz.txt"));
        assertEquals(List.of(), dialogs);
    }

    @Test
    void xOnAnUntouchedNewEditorClosesWithoutCreatingTheFile() {
        runInBox(widget, "e x.txt");
        ITextEditor opened = activeEditorNamed("x.txt");

        StyledText target = widgetOf(opened);
        runInBox(target, "x");

        waitUntil("the editor to close", TIMEOUT_MS, () -> target.isDisposed());
        assertFalse(project.getFile("x.txt").exists());
    }

    @Test
    void writeAllCreatesNewFilesAndSavesTheOthers() throws Exception {
        List<String> dialogs = closeModalDialogsWhile(widget);
        document.replace(0, 0, "1");
        settle();
        runInBox(widget, "e all.txt");
        ITextEditor opened = activeEditorNamed("all.txt");
        insert(widgetOf(opened), "a");

        runInBox(widgetOf(opened), "wa");

        assertEquals("a", fileText("all.txt"));
        assertEquals("1alpha\nbeta\ngamma\n", fileText("test.txt"));
        assertFalse(editor.isDirty());
        assertEquals(List.of(), dialogs);
        closeAll();
    }

    /** {@code ./} means beside the current file, even when the project root has a file of that name. */
    @Test
    void dotSlashCreatesBesideTheCurrentFileNotAtTheRoot() throws Exception {
        project.getFolder("src").create(true, true, null);
        IFile nested = createFile("src/Nested.txt", "nested\n");
        createFile("Foo.txt", "root\n");
        ITextEditor nestedEditor = (ITextEditor) IDE.openEditor(page(), nested, TEXT_EDITOR, true);
        waitForAttach(nestedEditor, "src/Nested.txt");

        runInBox(widgetOf(nestedEditor), "e ./Foo.txt");

        ITextEditor opened = activeEditorNamed("Foo.txt");
        assertEquals(project.getFile("src/Foo.txt"), opened.getEditorInput().getAdapter(IFile.class));
        assertEquals("", documentOf(opened).get(), "a new file, not the root's Foo.txt");
        closeAll();
    }
}
