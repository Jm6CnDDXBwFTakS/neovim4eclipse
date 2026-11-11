// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.Test;

/**
 * {@code :e newfile} opens an empty editor, and {@code :w} creates the file (docs/design/NEW_FILE.md).
 *
 * <p>Each test watches for dialogs ({@link #closeModalDialogsWhile}): Eclipse's own save of a missing
 * file opens Save As, which would otherwise block the test instead of failing it. No test may let
 * Eclipse save a missing file <em>outside</em> the workspace: its Save As is the system's native
 * dialog, which the watcher cannot close.
 */
class NewFileEndToEndTest extends EndToEndTestBase {

    /**
     * The active editor, once it is titled {@code title}, Neovim is attached to it, and it shows its
     * text and has the keyboard focus.
     *
     * <p>The last two are checked because the keys in these tests are sent straight to the editor's
     * text widget ({@code notifyListeners}), which works even while the widget is hidden. A text
     * editor on a workspace file that does not exist shows a status page ("File ... does not
     * exist.") in front of the widget, so a user could not type at all; the tests passed anyway
     * until this check.
     */
    private ITextEditor activeEditorNamed(String title) {
        waitUntil(title + " to open", TIMEOUT_MS, () -> {
            IEditorPart active = page().getActiveEditor();
            return active != null && title.equals(active.getTitle());
        }, () -> "; the active editor is " + page().getActiveEditor().getTitle());
        ITextEditor opened = (ITextEditor) page().getActiveEditor();
        waitForAttach(opened, title);
        StyledText text = widgetOf(opened);
        assertTrue(text.isVisible(), title + " shows its text, not a status page; the focused control shows: "
                + shownInstead(text));
        UiTestSupport.requireKeyboardFocus(text, TIMEOUT_MS);
        return opened;
    }

    /** What the focused text control shows, such as Eclipse's "File ... does not exist." page. */
    private static String shownInstead(StyledText editorText) {
        return editorText.getDisplay().getFocusControl() instanceof StyledText shown && shown != editorText
                ? "\"" + shown.getText() + "\"" : "(nothing)";
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

    /** The text of the output popup over {@code parent}, a {@link StyledText} below it, or "none". */
    private static String popupText(org.eclipse.swt.widgets.Composite parent) {
        for (org.eclipse.swt.widgets.Control child : parent.getChildren()) {
            if (child instanceof StyledText text && !text.isDisposed()) {
                return "\"" + text.getText() + "\"";
            }
            if (child instanceof org.eclipse.swt.widgets.Composite composite) {
                String found = popupText(composite);
                if (!found.equals("none")) {
                    return found;
                }
            }
        }
        return "none";
    }

    /** Neovim's {@code v:errmsg}: the last error it reported. */
    private String errmsg() {
        AtomicReference<String> answer = new AtomicReference<>();
        session.evalAsync("v:errmsg", answer::set);
        waitUntil("v:errmsg", TIMEOUT_MS, () -> answer.get() != null);
        return answer.get();
    }

    /**
     * The error a {@code :w} that Neovim asked Eclipse for ends with: Neovim's own report of
     * Eclipse's answer ({@code NewFiles.write}), shown as Neovim shows an error in an autocommand,
     * in the output popup under its {@code Error in BufWriteCmd Autocommands} header. Checked as
     * what is left, not as a message seen on the way: an error Eclipse showed itself, then
     * replaced by Neovim's report of a vaguer one ("Eclipse could not save"), would pass a check of
     * the message line. {@code v:errmsg} is the last error Neovim reported.
     */
    private void expectFinalError(ITextEditor target, String expected) {
        waitUntil("v:errmsg " + expected, TIMEOUT_MS, () -> expected.equals(errmsg()), () -> ": " + errmsg());
        waitUntil("the popup with " + expected, TIMEOUT_MS, () -> popupText(widgetOf(target)).contains(expected),
                () -> "; the popup shows " + popupText(widgetOf(target)));
        settle();
        assertEquals(expected, errmsg(), "the error Neovim reported last, once it is done");
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
        assertTrue(file.isSynchronized(IResource.DEPTH_ZERO), "the workspace knows the saved file (no F5 needed)");
        assertEquals(List.of(), dialogs);
        closeAll();
    }

    /**
     * After the first {@code :w} the editor is an ordinary workspace-file editor (so later saves go
     * through the workspace and project features work), and Neovim still has the same buffer: the
     * second {@code u} undoes the text typed before the write, as in Vim.
     */
    @Test
    void theFirstWriteSwitchesToTheWorkspaceFileAndKeepsNeovimsBuffer() {
        List<String> dialogs = closeModalDialogsWhile(widget);
        runInBox(widget, "e kept.txt");
        ITextEditor opened = activeEditorNamed("kept.txt");
        StyledText target = widgetOf(opened);
        insert(target, "hello");
        expectText(opened, "hello");
        int bufnr = session.getAttachedBufnr();

        runInBox(target, "w");

        IFile file = project.getFile("kept.txt");
        assertEquals(file, opened.getEditorInput().getAdapter(IFile.class), "a workspace-file editor now");
        assertEquals(bufnr, session.getAttachedBufnr(), "the same Neovim buffer");
        expectMessage(opened, "\"kept.txt\" [New] [noeol] 1L, 5B written");
        type(target, 'x');
        expectText(opened, "hell");
        type(target, 'u');
        expectText(opened, "hello");
        type(target, 'u');
        expectText(opened, "");
        assertEquals(List.of(), dialogs);
        closeAll();
    }

    /**
     * A reported bug: write a new file, delete it in Eclipse, then {@code :e} the same
     * name again. The new editor must be empty, in Eclipse and in Neovim. It showed the old text,
     * from a file buffer Eclipse had failed to release (see {@code NewFiles.releaseLeftOverBuffer}).
     */
    @Test
    void aNewFileWrittenDeletedAndOpenedAgainIsEmpty() throws Exception {
        List<String> dialogs = closeModalDialogsWhile(widget);
        runInBox(widget, "e again.txt");
        ITextEditor first = activeEditorNamed("again.txt");
        insert(widgetOf(first), "hello");
        expectText(first, "hello");
        runInBox(widgetOf(first), "w");
        IFile file = project.getFile("again.txt");
        assertEquals("hello", fileText("again.txt"));

        file.delete(true, null); // as the Project Explorer's Delete does
        waitUntil("Eclipse to close the deleted file's editor", TIMEOUT_MS,
                () -> page().findEditor(first.getEditorInput()) == null);
        var buffers = FileBuffers.getTextFileBufferManager();
        assertNull(buffers.getTextFileBuffer(file.getLocation(), LocationKind.LOCATION),
                "no file buffer left over from the editor opened by location");
        page().activate(editor);
        waitForAttach(editor, "test.txt");
        runInBox(widget, "e again.txt");
        ITextEditor second = activeEditorNamed("again.txt");
        assertEquals("", documentOf(second).get(), "Eclipse's document");
        assertEquals(List.of(""), session.getBufferLines(), "Neovim's buffer");
        assertEquals(List.of(), dialogs);
        closeAll();
    }

    /**
     * The first key typed in a new workspace file used to be lost: {@code ihello} gave {@code ello}.
     * That happened while the editor was opened as a workspace file; opened by location, as
     * {@code :e} does now, an existing empty file and a new file outside the workspace never lost it.
     */
    @Test
    void theFirstKeyTypedInANewWorkspaceFileIsKept() throws Exception {
        IFile missing = project.getFile("first.txt");
        runInBox(widget, "e first.txt");
        ITextEditor opened = activeEditorNamed("first.txt");

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

    /**
     * Where file names ignore case (macOS, Windows), a window folder spelled in another case still
     * gives short names in messages: after {@code :lcd} to the project's folder in upper case,
     * {@code :e new.txt} says {@code "new.txt" [New]}, not the full path. Neovim shortens a name
     * only when it starts with the window's folder, so the folder and the name must agree by the
     * file system's rule. Skipped where names keep case (Linux), as the file system tells. The
     * manual test "Short file names in messages".
     */
    @Test
    void aNewFilesMessageNamesItShortAfterLcdInAnotherCase() {
        Path folder = Path.of(project.getLocation().toOSString());
        Path upper = folder.resolveSibling(folder.getFileName().toString().toUpperCase(Locale.ROOT));
        assumeTrue(!upper.toString().equals(folder.toString()) && Files.isDirectory(upper),
                "file names keep their case here");
        runInBox(widget, "lcd " + upper.toString().replace(" ", "\\ "));

        runInBox(widget, "e new.txt");

        ITextEditor opened = activeEditorNamed("new.txt");
        expectMessage(opened, "\"new.txt\" [New]");
        closeAll();
    }

    @Test
    void aMissingFolderNeedsPlusPlusP() {
        List<String> dialogs = closeModalDialogsWhile(widget);
        runInBox(widget, "e sub/new.txt");
        ITextEditor opened = activeEditorNamed("new.txt");
        StyledText target = widgetOf(opened);
        insert(target, "x");

        runInBox(target, "w");
        expectFinalError(opened, NewFiles.NO_FOLDER);
        assertFalse(project.getFolder("sub").exists(), "no folder");
        assertTrue(opened.isDirty());

        runInBox(target, "w ++p");
        assertEquals("x", fileText("sub/new.txt"));
        assertFalse(opened.isDirty());
        assertEquals(List.of(), dialogs);
        closeAll();
    }

    /**
     * A symbolic link to a file that does not exist opens as a new file, as in Vim, but {@code :w}
     * does not write through it: that would create the link's target, wherever a cloned repository
     * made it point ({@link DanglingLinks}). {@code :w!} does. Skipped where links cannot be made.
     */
    @Test
    void aWriteThroughALinkToAMissingFileNeedsBang() throws Exception {
        Path dir = Files.createTempDirectory("neovim4eclipse-e2e").toRealPath();
        Path gone = dir.resolve("gone.txt");
        Path link = Path.of(project.getLocation().toOSString()).resolve("notes.local");
        try {
            try {
                Files.createSymbolicLink(link, gone);
            } catch (IOException | UnsupportedOperationException e) {
                assumeTrue(false, "cannot make a symbolic link here: " + e);
            }
            List<String> dialogs = closeModalDialogsWhile(widget);
            runInBox(widget, "e notes.local");
            ITextEditor opened = activeEditorNamed("notes.local");
            expectMessage(opened, "\"notes.local\" [New] " + DanglingLinks.newFileNote(link));
            StyledText target = widgetOf(opened);
            insert(target, "x");

            runInBox(target, "w");
            expectFinalError(opened, DanglingLinks.refusal("notes.local", link));
            assertFalse(Files.exists(gone), "nothing written through the link");
            assertTrue(opened.isDirty());

            runInBox(target, "w!");
            waitUntil("the link's target written", TIMEOUT_MS, () -> Files.exists(gone));
            assertEquals("x", Files.readString(gone, StandardCharsets.UTF_8));
            assertEquals(List.of(), dialogs);
            closeAll();
        } finally {
            // Closed first, unsaved: an editor left open on a deleted link (a failed assertion
            // above) makes Eclipse ask about the missing file in a dialog that blocks every later test.
            closeAll();
            Files.deleteIfExists(link);
            leaveAndDelete(dir, gone);
        }
    }

    /**
     * A link to a missing file that the workspace lists as an existing file still opens as a new
     * file, and {@code :w!} writes its target, which the workspace file then shows.
     *
     * <p>Why: on Windows Eclipse's file system finds the link itself ({@code FindFirstFile} does not
     * follow it), so a refresh puts the link in the workspace as an existing {@code IFile}, and a
     * text editor reading it, by the workspace file or by its location, shows "File not found"
     * instead of its text. Linux's file system follows the link and never lists it, so the test makes
     * the same state another way: the workspace file is created as a plain file, which is then
     * replaced by the link behind Eclipse's back, with no refresh. Skipped where links cannot be made.
     */
    @Test
    void aLinkToAMissingFileThatTheWorkspaceListsStillOpensAsANewFile() throws Exception {
        Path dir = Files.createTempDirectory("neovim4eclipse-e2e").toRealPath();
        Path gone = dir.resolve("gone.txt");
        IFile listed = project.getFile("listed.local");
        Path link = Path.of(listed.getLocation().toOSString());
        try {
            listed.create(new java.io.ByteArrayInputStream(new byte[0]), true, null);
            Files.delete(link);
            try {
                Files.createSymbolicLink(link, gone);
            } catch (IOException | UnsupportedOperationException e) {
                assumeTrue(false, "cannot make a symbolic link here: " + e);
            }
            assertTrue(listed.exists(), "the workspace lists the link as an existing file, as on Windows");
            List<String> dialogs = closeModalDialogsWhile(widget);
            runInBox(widget, "e listed.local");
            ITextEditor opened = activeEditorNamed("listed.local");
            expectMessage(opened, "\"listed.local\" [New] " + DanglingLinks.newFileNote(link));
            StyledText target = widgetOf(opened);
            insert(target, "x");

            runInBox(target, "w");
            expectFinalError(opened, DanglingLinks.refusal("listed.local", link));
            assertFalse(Files.exists(gone), "nothing written through the link");

            runInBox(target, "w!");
            waitUntil("the link's target written", TIMEOUT_MS, () -> Files.exists(gone));
            assertEquals("x", Files.readString(gone, StandardCharsets.UTF_8));
            waitUntil("the editor clean", TIMEOUT_MS, () -> !opened.isDirty());
            assertEquals(listed, opened.getEditorInput().getAdapter(IFile.class),
                    "the editor switched to the workspace file");
            assertEquals("x", fileText("listed.local"), "the workspace file shows the written text");
            assertEquals(List.of(), dialogs);
            closeAll();
        } finally {
            closeAll(); // first, unsaved: see aWriteThroughALinkToAMissingFileNeedsBang
            Files.deleteIfExists(link);
            listed.refreshLocal(IResource.DEPTH_ZERO, null);
            leaveAndDelete(dir, gone);
        }
    }

    /**
     * {@code :wa} leaves a new file that is a link to a missing file unsaved, as {@code :w} does;
     * {@code :wa!} passes its {@code !} on (util/HostCommandRunner) and writes through the link, as
     * {@code :w!} does. Skipped where links cannot be made.
     */
    @Test
    void writeAllThroughALinkToAMissingFileNeedsBang() throws Exception {
        Path dir = Files.createTempDirectory("neovim4eclipse-e2e").toRealPath();
        Path gone = dir.resolve("gone.txt");
        Path link = Path.of(project.getLocation().toOSString()).resolve("all.local");
        try {
            try {
                Files.createSymbolicLink(link, gone);
            } catch (IOException | UnsupportedOperationException e) {
                assumeTrue(false, "cannot make a symbolic link here: " + e);
            }
            List<String> dialogs = closeModalDialogsWhile(widget);
            runInBox(widget, "e all.local");
            ITextEditor opened = activeEditorNamed("all.local");
            StyledText target = widgetOf(opened);
            insert(target, "a");

            runInBox(target, "wa");
            expectMessage(opened, DanglingLinks.refusal("all.local", link));
            assertFalse(Files.exists(gone), "nothing written through the link");
            assertTrue(opened.isDirty());

            runInBox(target, "wa!");
            waitUntil("the link's target written", TIMEOUT_MS, () -> Files.exists(gone));
            assertEquals("a", Files.readString(gone, StandardCharsets.UTF_8));
            assertEquals(List.of(), dialogs);
            closeAll();
        } finally {
            // Closed first, unsaved: an editor left open on a deleted link (a failed assertion
            // above) makes Eclipse ask about the missing file in a dialog that blocks every later test.
            closeAll();
            Files.deleteIfExists(link);
            leaveAndDelete(dir, gone);
        }
    }

    /**
     * {@code :wq} on a new file that is a link to a missing file refuses the write and leaves the
     * editor open, as Vim does after E13 or E212; {@code :wq!} passes its {@code !} on to the save
     * (util/HostCommandRunner), writes through the link and closes. Skipped where links cannot be made.
     */
    @Test
    void wqThroughALinkToAMissingFileNeedsBangAndOtherwiseKeepsTheEditor() throws Exception {
        Path dir = Files.createTempDirectory("neovim4eclipse-e2e").toRealPath();
        Path gone = dir.resolve("gone.txt");
        Path link = Path.of(project.getLocation().toOSString()).resolve("wq.local");
        try {
            try {
                Files.createSymbolicLink(link, gone);
            } catch (IOException | UnsupportedOperationException e) {
                assumeTrue(false, "cannot make a symbolic link here: " + e);
            }
            List<String> dialogs = closeModalDialogsWhile(widget);
            runInBox(widget, "e wq.local");
            ITextEditor opened = activeEditorNamed("wq.local");
            StyledText target = widgetOf(opened);
            insert(target, "q");

            runInBox(target, "wq");
            expectMessage(opened, DanglingLinks.refusal("wq.local", link));
            assertFalse(target.isDisposed(), "the editor stays open");
            assertFalse(Files.exists(gone), "nothing written through the link");

            runInBox(target, "wq!");
            waitUntil("the editor to close", TIMEOUT_MS, target::isDisposed);
            assertEquals("q", Files.readString(gone, StandardCharsets.UTF_8));
            assertEquals(List.of(), dialogs);
        } finally {
            // Closed first, unsaved: an editor left open on a deleted link (a failed assertion
            // above) makes Eclipse ask about the missing file in a dialog that blocks every later test.
            closeAll();
            Files.deleteIfExists(link);
            leaveAndDelete(dir, gone);
        }
    }

    @Test
    void aNewFileOutsideTheWorkspaceIsCreatedByWrite() throws Exception {
        Path dir = Files.createTempDirectory("neovim4eclipse-e2e").toRealPath();
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
            leaveAndDelete(dir, outside);
        }
    }

    /**
     * Outside the workspace, a missing folder also needs {@code ++p}, and it is created with
     * {@code Files.createDirectories}. (If {@code :w} ever fell back to Eclipse's own save here, this
     * test would hang on the native Save As dialog instead of failing.)
     */
    @Test
    void aMissingFolderOutsideTheWorkspaceNeedsPlusPlusP() throws Exception {
        Path dir = Files.createTempDirectory("neovim4eclipse-e2e").toRealPath();
        Path sub = dir.resolve("sub");
        Path outside = sub.resolve("new.txt");
        try {
            runInBox(widget, "e " + outside);
            ITextEditor opened = activeEditorNamed("new.txt");
            StyledText target = widgetOf(opened);
            insert(target, "out");
            expectText(opened, "out");

            runInBox(target, "w");
            expectFinalError(opened, NewFiles.NO_FOLDER);
            assertFalse(Files.exists(sub), "no folder");
            assertTrue(opened.isDirty());

            runInBox(target, "w ++p");
            assertEquals("out", Files.readString(outside, StandardCharsets.UTF_8));
            assertFalse(opened.isDirty());
            closeAll();
        } finally {
            leaveAndDelete(dir, outside, sub);
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

    /**
     * {@code :wa} creates a new file whose editor is in the background and switches that editor to
     * the workspace file, keeping its Neovim buffer. The document's sync must go with it: before,
     * only an attach made it again, so the {@code :bufdo} below changed Neovim's buffer but not the
     * document, and its {@code update} saved Eclipse's old text.
     */
    @Test
    void aNewFileThatWriteAllCreatedInTheBackgroundStillFollowsNeovim() throws Exception {
        List<String> dialogs = closeModalDialogsWhile(widget);
        runInBox(widget, "e new1.txt");
        ITextEditor first = activeEditorNamed("new1.txt");
        insert(widgetOf(first), "x");
        runInBox(widgetOf(first), "e new2.txt");
        ITextEditor second = activeEditorNamed("new2.txt");

        runInBox(widgetOf(second), "wa");
        assertEquals("x", fileText("new1.txt"));
        assertEquals(project.getFile("new1.txt"), first.getEditorInput().getAdapter(IFile.class),
                "new1.txt's editor shows the workspace file now");

        session.commandAsync("bufdo %s/x/y/e | update");

        waitUntil("new1.txt saved with Neovim's change", TIMEOUT_MS, () -> "y".equals(fileText("new1.txt")),
                () -> "; it reads " + fileText("new1.txt"));
        expectText(first, "y");
        assertEquals(List.of(), dialogs);
        closeAll();
    }

    /**
     * As in Neovim, {@code ./} is the window's folder (the project root), like a plain name; the
     * file's own folder is {@code %:h}, which Neovim expands. From {@code src/Nested.txt}. And
     * {@code :w name} and {@code :wq name} put their copies at the root alike ({@code :wq name} once
     * wrote beside the file), while {@code :w %:h/name} writes beside it.
     */
    @Test
    void dotSlashIsTheProjectRootAndPercentHIsTheFilesFolder() throws Exception {
        project.getFolder("src").create(true, true, null);
        IFile nested = createFile("src/Nested.txt", "nested\n");
        createFile("Foo.txt", "root\n");
        ITextEditor nestedEditor = (ITextEditor) IDE.openEditor(page(), nested, TEXT_EDITOR, true);
        waitForAttach(nestedEditor, "src/Nested.txt");

        runInBox(widgetOf(nestedEditor), "e ./Foo.txt");
        ITextEditor root = activeEditorNamed("Foo.txt");
        assertEquals(project.getFile("Foo.txt"), NewFiles.workspaceFileAt(EditorAttachManager.fileOf(root.getEditorInput())));
        assertEquals("root\n", documentOf(root).get(), "the root's Foo.txt");
        page().activate(nestedEditor);
        waitForAttach(nestedEditor, "src/Nested.txt");

        runInBox(widgetOf(nestedEditor), "e %:h/New.txt");
        ITextEditor created = activeEditorNamed("New.txt");
        assertEquals(project.getFile("src/New.txt"), NewFiles.workspaceFileAt(EditorAttachManager.fileOf(created.getEditorInput())));
        assertEquals("", documentOf(created).get(), "a new file beside Nested.txt");

        // :w name (Neovim's write) and :wq name (Eclipse's) put the copy in the same place, the root;
        // %:h/name puts it beside the file
        page().activate(nestedEditor);
        waitForAttach(nestedEditor, "src/Nested.txt");
        runInBox(widgetOf(nestedEditor), "w w-copy.txt");
        waitUntil("w-copy.txt at the root", TIMEOUT_MS, () -> project.getFile("w-copy.txt").exists());
        runInBox(widgetOf(nestedEditor), "w %:h/beside.txt");
        waitUntil("beside.txt in src", TIMEOUT_MS, () -> project.getFile("src/beside.txt").exists());
        runInBox(widgetOf(nestedEditor), "wq wq-copy.txt");
        waitUntil("src/Nested.txt to close", TIMEOUT_MS, () -> page().findEditor(nestedEditor.getEditorInput()) == null);
        assertEquals("nested\n", fileText("wq-copy.txt"), "at the root, as :w puts it");
        assertFalse(project.getFile("src/wq-copy.txt").exists());
        closeAll();
    }
}
