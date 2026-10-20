// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import static io.github.jm6cnddxbwftaks.eclipseneovim.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.eclipseneovim.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.INewWizard;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.wizards.IWizardDescriptor;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.eclipseneovim.core.StatusInfo;

/**
 * The {@code :} box ({@link CommandLineBox}) the whole way: {@code :} typed in the editor opens it,
 * the command is typed into it, Enter runs it in Eclipse ({@link HostCommands}) or in Neovim.
 * Review checks Fix 7 ({@code :e!}), Fix 9 ({@code :e} finds files) and Fix 10 (command names).
 *
 * <p>Errors from Eclipse's side ("Can't find file") go to the window's status line, read back by
 * {@link #statusLineTexts}; errors from Neovim's side arrive as the session's status message.
 */
class CommandBoxEndToEndTest extends EndToEndTestBase {

    /** Types {@code :} into {@code target}, waits for the box, and types {@code command} after it. */
    private Text openBox(StyledText target, String command) {
        type(target, ':');
        waitUntil("the : box to open", TIMEOUT_MS, () -> textChildOf(target) != null);
        Text box = textChildOf(target);
        box.insert(command);
        return box;
    }

    /** Runs {@code command} through the box, as typed: {@code :}, the command, Enter. */
    private void runInBox(StyledText target, String command) {
        Text box = openBox(target, command);
        Event enter = new Event();
        enter.keyCode = SWT.CR;
        enter.character = SWT.CR;
        enter.doit = true;
        box.notifyListeners(SWT.KeyDown, enter);
        settle();
    }

    private static boolean activeEditorIs(String title) {
        IEditorPart active = page().getActiveEditor();
        return active != null && title.equals(active.getTitle());
    }

    /**
     * Tab in the {@code :} box, the whole way: Neovim completes {@code :e} names in the edited
     * file's folder (not its own working directory), and Enter opens the completed file.
     */
    @Test
    void tabCompletesAFileNameForEditAndEnterOpensIt() throws Exception {
        createFile("other.txt", "other\n");
        Text box = openBox(widget, "e ot");

        Event tab = new Event();
        tab.detail = SWT.TRAVERSE_TAB_NEXT;
        tab.keyCode = SWT.TAB;
        tab.character = SWT.TAB;
        tab.doit = true;
        box.notifyListeners(SWT.Traverse, tab);
        waitUntil("Tab to complete the name", TIMEOUT_MS, () -> ":e other.txt".equals(box.getText()),
                () -> "; the box reads " + box.getText());

        Event enter = new Event();
        enter.keyCode = SWT.CR;
        enter.character = SWT.CR;
        enter.doit = true;
        box.notifyListeners(SWT.KeyDown, enter);
        waitUntil("other.txt to open", TIMEOUT_MS, () -> activeEditorIs("other.txt"));
        page().closeEditor(page().getActiveEditor(), false);
    }

    // ---- Fix 9: where :e looks ----

    /** From a file in a subfolder, {@code :e} also looks in the project's root folder. */
    @Test
    void editFindsAFileAtTheProjectRoot() throws Exception {
        IFolder folder = project.getFolder("src");
        folder.create(true, true, null);
        IFile nested = createFile("src/Nested.txt", "nested\n");
        createFile("root.txt", "root\n");
        ITextEditor nestedEditor = (ITextEditor) IDE.openEditor(page(), nested, TEXT_EDITOR, true);
        waitForAttach(nestedEditor, "src/Nested.txt");

        runInBox(widgetOf(nestedEditor), "e root.txt");

        waitUntil("root.txt to open", TIMEOUT_MS, () -> activeEditorIs("root.txt"));
        page().closeAllEditors(false);
        editor = null;
    }

    /**
     * An absolute path outside the workspace opens as an outside file (no project). (A temporary
     * folder made by hand: JUnit's {@code @TempDir} is in a package the fragment does not import.)
     */
    @Test
    void editOpensAnAbsolutePathOutsideTheWorkspace() throws Exception {
        Path dir = Files.createTempDirectory("eclipseneovim-e2e").toRealPath();
        Path outside = dir.resolve("outside.txt");
        try {
            Files.writeString(outside, "outside\n", StandardCharsets.UTF_8);

            runInBox(widget, "e " + outside);

            waitUntil("outside.txt to open", TIMEOUT_MS, () -> activeEditorIs("outside.txt"));
            IEditorPart opened = page().getActiveEditor();
            assertTrue(opened.getEditorInput() instanceof IURIEditorInput, "opened from its location");
            assertNull(opened.getEditorInput().getAdapter(IFile.class), "not a workspace file");
            page().closeEditor(opened, false);
        } finally {
            Files.deleteIfExists(outside);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void editOfAMissingFileSaysSoInTheStatusLine() {
        runInBox(widget, "e nosuchfile.txt");

        waitUntil("the error in the status line", TIMEOUT_MS,
                () -> statusLineTexts(editor).contains("Can't find file \"nosuchfile.txt\""),
                () -> "; the status line shows " + statusLineTexts(editor));
        assertSame(editor, page().getActiveEditor(), "nothing else opened");
    }

    /**
     * An untitled file (File › New › Untitled Text File) has no folder to look in, so a relative
     * name is not found, even one at the root of a project; an absolute path still opens.
     */
    @Test
    void anUntitledFileFindsOnlyAbsolutePaths() throws Exception {
        createFile("root.txt", "root\n");
        ITextEditor untitled = openUntitledFile();
        StyledText untitledWidget = widgetOf(untitled);

        runInBox(untitledWidget, "e root.txt");
        waitUntil("the error in the status line", TIMEOUT_MS,
                () -> statusLineTexts(untitled).contains("Can't find file \"root.txt\""),
                () -> "; the status line shows " + statusLineTexts(untitled));
        assertSame(untitled, page().getActiveEditor());

        runInBox(untitledWidget, "e " + project.getFile("root.txt").getLocation().toOSString());
        waitUntil("root.txt to open", TIMEOUT_MS, () -> activeEditorIs("root.txt"));
        page().closeAllEditors(false);
        editor = null;
    }

    /** File › New › Untitled Text File, by running its wizard as the menu does. */
    private ITextEditor openUntitledFile() throws Exception {
        IWizardDescriptor descriptor = PlatformUI.getWorkbench().getNewWizardRegistry()
                .findWizard("org.eclipse.ui.editors.wizards.UntitledTextFileWizard");
        INewWizard wizard = (INewWizard) descriptor.createWizard();
        wizard.init(PlatformUI.getWorkbench(), StructuredSelection.EMPTY);
        assertTrue(wizard.performFinish());
        ITextEditor untitled = (ITextEditor) page().getActiveEditor();
        assertNull(untitled.getEditorInput().getAdapter(IFile.class), "an untitled file");
        waitForAttach(untitled, "the untitled file");
        return untitled;
    }

    // ---- Fix 10: command names; Fix 7: :e! ----

    /** {@code :wal} is {@code :wall}, abbreviated: Eclipse saves every changed file. */
    @Test
    void walSavesEveryChangedFile() throws Exception {
        ITextEditor second = openFile("second.txt", "second\n");
        documentOf(second).replace(0, 0, "2");
        page().activate(editor);
        waitForAttach(editor, "test.txt");
        document.replace(0, 0, "1");
        settle();

        runInBox(widget, "wal");

        waitUntil("both files saved", TIMEOUT_MS, () -> !editor.isDirty() && !second.isDirty());
        assertEquals("1alpha\nbeta\ngamma\n", fileText("test.txt"));
        assertEquals("2second\n", fileText("second.txt"));
        page().closeEditor(second, false);
    }

    /**
     * {@code :q} takes no argument, so {@code :q foo} goes to Neovim, which reports E488; nothing
     * closes. The error reaches the plug-in's status bar as the session's status message (read from
     * {@code v:errmsg}, which the session then clears).
     */
    @Test
    void quitWithAnArgumentIsAnErrorAndKeepsTheEditorOpen() {
        List<String> messages = new CopyOnWriteArrayList<>();
        Consumer<StatusInfo> listener = status -> messages.add(status.message());
        session.addStatusListener(listener);
        try {
            runInBox(widget, "q foo");

            waitUntil("Neovim's error", TIMEOUT_MS, () -> messages.stream().anyMatch(m -> m.startsWith("E488")),
                    () -> "; the messages were " + messages);
            assertSame(editor, page().getActiveEditor());
            assertFalse(widget.isDisposed());
        } finally {
            session.removeStatusListener(listener);
        }
    }

    /**
     * {@code :e!} reverts the editor to the saved file; the revert must reach Neovim, or
     * {@code G dd} would delete a line of the text from before.
     */
    @Test
    void editBangRevertsAndNeovimHasTheSavedText() {
        type(widget, "Gdd");
        expectText("alpha\nbeta\n");
        type(widget, "ggdd");
        expectText("beta\n");

        runInBox(widget, "e!");
        expectText("alpha\nbeta\ngamma\n");
        assertFalse(editor.isDirty());

        type(widget, "Gdd");
        expectText("alpha\nbeta\n");
    }
}
