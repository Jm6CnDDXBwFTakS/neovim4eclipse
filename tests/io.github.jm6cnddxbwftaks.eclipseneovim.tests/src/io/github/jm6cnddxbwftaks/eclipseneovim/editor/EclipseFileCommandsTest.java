// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import static io.github.jm6cnddxbwftaks.eclipseneovim.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

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

/**
 * {@link EclipseFileCommands}: Vim's write commands carried out by Eclipse. Neovim is not involved
 * (auto-attach is off), so the documents are edited directly and only the saving is tested.
 */
class EclipseFileCommandsTest {

    private static final long TIMEOUT_MS = 10_000;

    private IProject project;
    private ITextEditor first;
    private ITextEditor second;
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
        second = openModified(createFile("two.txt", "two\n"), "two changed\n");
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
    void writeAllSavesEveryModifiedEditor() throws Exception {
        assertTrue(EclipseFileCommands.tryHandleEx("wa", first));

        waitUntil("both editors saved", TIMEOUT_MS, () -> !first.isDirty() && !second.isDirty());
        assertEquals("one changed\n", contentsOf(project.getFile("one.txt")));
        assertEquals("two changed\n", contentsOf(project.getFile("two.txt")));
    }

    @Test
    void writeSavesOnlyTheCurrentEditor() throws Exception {
        assertTrue(EclipseFileCommands.tryHandleEx("w", first));

        waitUntil("first editor saved", TIMEOUT_MS, () -> !first.isDirty());
        assertTrue(second.isDirty());
        assertEquals("two\n", contentsOf(project.getFile("two.txt")));
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

    private static String contentsOf(IFile file) throws Exception {
        try (InputStream in = file.getContents(true)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static IWorkbenchPage page() {
        return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
    }
}
