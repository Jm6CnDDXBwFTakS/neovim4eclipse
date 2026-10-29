// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HostCommand;

/**
 * {@link HostCommands}: Vim's file and tab commands carried out by a real Eclipse. Neovim is not
 * involved (auto-attach is off), so the documents are edited directly and only Eclipse's side is
 * tested: each command is given as Neovim hands it over ({@code :Eclipse wall}). That typed lines
 * arrive in that form is tested in Lua ({@code tests/unit/src/test/lua/host_commands_spec.lua}).
 *
 * <p>Which calls each command makes ({@code :wq} saves, then closes; a failed save keeps the file
 * open) is decided by {@code util/HostCommandRunner} and tested in {@code HostCommandRunnerTest}
 * without a workbench. These tests check what the end-to-end tests do not: {@code :e}'s refusal
 * with changes and {@code :e!}'s revert, and the tab commands. Saving ({@code :wal}) and opening
 * ({@code :e}) go the whole way, from the {@code :} box through Neovim, in
 * {@code CommandBoxEndToEndTest}; closing in the end-to-end tests of {@code ZZ} and {@code ZQ}.
 */
class HostCommandsTest {

    private static final long TIMEOUT_MS = TestWait.millis();

    private IProject project;
    private ITextEditor first;
    private boolean autoAttachBefore;

    @BeforeEach
    void openTwoModifiedEditors() throws Exception {
        EditorAttachManager manager = EditorAttachManager.getInstance();
        autoAttachBefore = manager.isAutoAttach();
        manager.setAutoAttach(false);
        manager.detachQuietly();

        project = ResourcesPlugin.getWorkspace().getRoot().getProject("file-commands");
        if (!project.exists()) {
            project.create(null);
        }
        project.open(null);
        first = openModified(createFile("one.txt", "one\n"), "one changed\n");
        // Not kept in a field, but needed: the tab commands move between the two editors' tabs.
        openModified(createFile("two.txt", "two\n"), "two changed\n");
    }

    @AfterEach
    void cleanUp() throws Exception {
        IWorkbenchPage page = page();
        page.closeAllEditors(false);
        if (project != null && project.exists()) {
            project.delete(true, true, null);
        }
        EditorAttachManager.getInstance().setAutoAttach(autoAttachBefore);
    }

    @Test
    void editBangRevertsAndPlainEditRefusesWithChanges() {
        run("edit", first);
        UiTestSupport.spin(100);
        assertTrue(first.isDirty(), ":e must not drop changes (E37)");

        run("edit!", first);
        waitUntil("first editor reverted", TIMEOUT_MS, () -> !first.isDirty());
        assertEquals("one\n", first.getDocumentProvider().getDocument(first.getEditorInput()).get());
    }

    @Test
    void tabCommandsMoveBetweenTheEditorTabs() {
        ITextEditor start = (ITextEditor) page().getActiveEditor();
        run("tabnext", start);
        waitUntil("the other tab to be active", TIMEOUT_MS, () -> page().getActiveEditor() != start);

        ITextEditor other = (ITextEditor) page().getActiveEditor();
        run("tabprevious", other);
        waitUntil("back at the first tab", TIMEOUT_MS, () -> page().getActiveEditor() == start);

        run("tablast", start);
        UiTestSupport.spin(100);
        ITextEditor last = (ITextEditor) page().getActiveEditor();
        run("tabfirst", last);
        waitUntil("a different tab for :tabfirst than :tablast", TIMEOUT_MS, () -> page().getActiveEditor() != last);
    }

    /** Runs {@code "edit! three.txt"} (Neovim's full name, as {@code :Eclipse} hands it over) in {@code editor}. */
    private static void run(String nameAndArgs, ITextEditor editor) {
        List<String> words = List.of(nameAndArgs.split(" "));
        String name = words.get(0);
        boolean bang = name.endsWith("!");
        HostCommand command = HostCommand.of(bang ? name.substring(0, name.length() - 1) : name, bang,
                words.subList(1, words.size()));
        assertNotNull(command, nameAndArgs);
        HostCommands.run(command, editor);
    }

    private IFile createFile(String name, String text) throws Exception {
        IFile file = project.getFile(name);
        file.create(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), true, null);
        return file;
    }

    private static ITextEditor openModified(IFile file, String newText) throws Exception {
        ITextEditor editor = (ITextEditor) IDE.openEditor(page(), file, "org.eclipse.ui.DefaultTextEditor", true);
        editor.getDocumentProvider().getDocument(editor.getEditorInput()).set(newText);
        assertTrue(editor.isDirty(), file.getName() + " should be modified");
        return editor;
    }


    private static IWorkbenchPage page() {
        return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
    }
}
