// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.ltk.core.refactoring.RefactoringCore;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FileRename;

/**
 * {@code :file name} typed in an editor ({@code editor/FileRenames}): Eclipse's Rename or Move of
 * the editor's file within its project, the editor and its Neovim buffer following, and Eclipse's
 * refactoring undo taking it back; and each refusal of {@link FileRename}, with nothing changed.
 * The decisions themselves are {@code FileRenameTest}'s. The folders outside the project that a
 * test links to are JUnit's {@code @TempDir}s, deleted after the test (and after the project,
 * whose links {@code EndToEndTestBase} removes with it).
 */
class FileRenameEndToEndTest extends EndToEndTestBase {

    private static final String TEXT = "alpha\nbeta\ngamma\n";

    @AfterEach
    void closeTheOtherEditors() {
        page().closeAllEditors(false);
        editor = null;
    }

    private String errmsg() {
        AtomicReference<String> answer = new AtomicReference<>();
        session.evalAsync("v:errmsg", answer::set);
        waitUntil("v:errmsg", TIMEOUT_MS, () -> answer.get() != null);
        return answer.get();
    }

    /** Clears Neovim's last error, so that the next check sees only what the command said. */
    private void clearErrmsg() {
        session.evalAsync("execute('let v:errmsg = \"\"')", x -> { });
        waitUntil("v:errmsg cleared", TIMEOUT_MS, () -> errmsg().isEmpty());
    }

    private void expectRefusal(String command, String message) {
        clearErrmsg();
        runCommandLine(widget, command);
        waitUntil("the refusal of :" + command, TIMEOUT_MS, () -> errmsg().equals(message), () -> ": " + errmsg());
    }

    @Test
    void fileRenamesTheEditorsFileAndTheBufferFollows() throws Exception {
        int bufnr = session.getAttachedBufnr();
        runCommandLine(widget, "file todo.txt");

        waitUntil("the editor on todo.txt", TIMEOUT_MS, () -> "todo.txt".equals(editor.getTitle()),
                () -> "; it is " + editor.getTitle());
        assertFalse(project.getFile("test.txt").exists(), "renamed, not copied");
        assertEquals(TEXT, fileText("todo.txt"));
        waitForAttach(editor, "todo.txt");
        assertEquals(bufnr, session.getAttachedBufnr(), "the same Neovim buffer, renamed");

        type(widget, "x");
        expectText("lpha\nbeta\ngamma\n");
        type(widget, "u");
        expectText(TEXT); // the editor edits on, with Neovim's undo history

        // On Eclipse's refactoring undo stack, so Edit > Undo in the Project Explorer offers it. Not
        // undone here: Eclipse asks first, in a dialog ("affects files..."), which is Eclipse's own.
        assertTrue(RefactoringCore.getUndoManager().anythingToUndo(), "the rename is on the refactoring undo stack");
    }

    @Test
    void fileWithAFolderMovesItWithinTheProject() throws Exception {
        project.getFolder("docs").create(true, true, null);
        runCommandLine(widget, "file docs/moved.txt");

        waitUntil("the editor on docs/moved.txt", TIMEOUT_MS, () -> project.getFile("docs/moved.txt")
                .equals(editor.getEditorInput().getAdapter(IFile.class)), () -> "; it is " + editor.getTitle());
        assertFalse(project.getFile("test.txt").exists());
        assertEquals(TEXT, fileText("docs/moved.txt"));
        waitForAttach(editor, "moved.txt");
        type(widget, "x");
        expectText("lpha\nbeta\ngamma\n");
    }

    @Test
    void eachRefusalLeavesTheFileAsItWas() throws Exception {
        createFile("other.txt", "other\n");
        expectRefusal("file other.txt", "file: \"other.txt\" exists already");
        expectRefusal("file missing/x.txt", FileRename.NO_FOLDER);
        expectRefusal("file /tmp/neovim4eclipse-outside.txt", FileRename.OTHER_PROJECT);
        type(widget, "x");
        expectText("lpha\nbeta\ngamma\n");
        expectRefusal("file todo.txt", FileRename.UNSAVED);
        assertTrue(project.getFile("test.txt").exists() && !project.getFile("todo.txt").exists(), "nothing renamed");
        assertEquals("test.txt", editor.getTitle());
        type(widget, "u"); // the change undone: the editor is left as it was found
        expectText(TEXT);
    }

    /**
     * Only the case changes, on a system that ignores it (macOS, Windows): the one way to change a
     * name's spelling there, and Eclipse's move must accept the case variant of the same file.
     * Linux tells the two names apart, where it is an ordinary rename.
     */
    @Test
    @EnabledOnOs({ OS.MAC, OS.WINDOWS })
    void onlyTheCaseChangesWhereCaseIsIgnored() {
        runCommandLine(widget, "file Test.txt");
        waitUntil("the editor on Test.txt", TIMEOUT_MS, () -> "Test.txt".equals(editor.getTitle()),
                () -> "; it is " + editor.getTitle() + ", v:errmsg " + errmsg());
        assertEquals("Test.txt", editor.getEditorInput().getAdapter(IFile.class).getName(), "the new spelling in the workspace");
        assertEquals(TEXT, fileText("Test.txt"));
    }

    /**
     * Where the path ends decides: a folder of the project that is a symbolic link to a place
     * outside it (as a cloned repository may hold, {@code docs -> /elsewhere}) is refused, though the
     * path is written inside the project. Not on Windows, where a symbolic link needs a privilege.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aFolderLinkedOutsideTheProjectIsRefused(@TempDir Path outside) throws Exception {
        Files.createSymbolicLink(project.getLocation().toPath().resolve("out"), outside);
        project.refreshLocal(IResource.DEPTH_INFINITE, null);
        expectRefusal("file out/moved.txt", FileRename.OTHER_PROJECT);
        assertTrue(project.getFile("test.txt").exists());
        assertFalse(Files.exists(outside.resolve("moved.txt")), "nothing moved out of the project");
    }

    /**
     * An Eclipse linked folder ({@code IFolder.createLink}) is a folder of the project whose files
     * live elsewhere: Eclipse would write the moved file there. The rule checks where Eclipse
     * writes, so the move is refused, though the path is written inside the project.
     */
    @Test
    void aLinkedFolderIsRefused(@TempDir Path outside) throws Exception {
        project.getFolder("ext").createLink(outside.toUri(), 0, null);
        expectRefusal("file ext/moved.txt", FileRename.OTHER_PROJECT);
        assertTrue(project.getFile("test.txt").exists());
        assertFalse(Files.exists(outside.resolve("moved.txt")), "nothing moved into the linked folder");
    }

    /** A file in a linked folder: Eclipse would rename it where the link points; refused with its own message. */
    @Test
    void aFileInALinkedFolderIsRefused(@TempDir Path outside) throws Exception {
        project.getFolder("ext").createLink(outside.toUri(), 0, null);
        ITextEditor linked = openFile("ext/a.txt", "a\n");
        clearErrmsg();
        runCommandLine(widgetOf(linked), "file b.txt");
        waitUntil("the refusal", TIMEOUT_MS, () -> errmsg().equals(FileRename.LINKED), () -> ": " + errmsg());
        assertTrue(Files.exists(outside.resolve("a.txt")) && !Files.exists(outside.resolve("b.txt")));
    }

    /** A Java file is left to Eclipse's Java Rename ({@code grn}), which renames the class and its references too. */
    @Test
    void aJavaFileIsRefused() throws Exception {
        ITextEditor java = openFile("Foo.java", "class Foo {}\n");
        StyledText javaText = widgetOf(java);
        clearErrmsg();
        runCommandLine(javaText, "file Bar.java");
        waitUntil("the refusal", TIMEOUT_MS, () -> errmsg().equals(FileRename.JAVA), () -> ": " + errmsg());
        assertTrue(project.getFile("Foo.java").exists() && !project.getFile("Bar.java").exists());
    }

    /** {@code :file} alone shows the file's info, as in Vim, and renames nothing. */
    @Test
    void fileAloneIsNeovims() {
        runCommandLine(widget, "file");
        assertTrue(project.getFile("test.txt").exists());
        assertEquals("test.txt", editor.getTitle());
    }
}
