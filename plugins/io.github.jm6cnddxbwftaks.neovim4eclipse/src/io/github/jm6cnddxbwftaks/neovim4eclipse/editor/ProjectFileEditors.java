// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.MultiPageEditorPart;

/**
 * Opens the editor of a project's file that Neovim moved into by itself ({@code gf}, {@code 'A},
 * {@code :b} into a closed file, a quickfix entry), for {@link EditorAttachManager} to follow into
 * (docs/design/BUFFER_NUMBERS.md, 2.3, point 5). Without an editor Eclipse cannot show the file, and
 * Neovim used to be sent back.
 *
 * <p>What the probes showed (BUFFER_NUMBERS.md, X21, X22), and so what is done here:
 * <ul>
 *   <li><b>Find the file.</b> {@code findFilesForLocationURI} by the name Neovim gave, then by its
 *       real path (a Windows junction or 8.3 short name in Neovim's name is not found otherwise);
 *       the first in an open project ({@link NewFiles#workspaceFileAt}). Eclipse lists a nested
 *       project's file first (a Maven module inside its parent's folder), so the module's editor
 *       opens, with its build path. A file under a linked folder is found once per link; any of
 *       them will do.</li>
 *   <li><b>Refresh it first.</b> A file made on disk since Eclipse's last refresh (by a build tool, a
 *       {@code git checkout}) is found but does not exist for Eclipse, and its editor would open
 *       empty. Its missing folders and the file are refreshed, one level each. A refresh waits for
 *       the workspace while a build runs, so this part runs off the UI thread
 *       ({@link #workspaceFile}).</li>
 *   <li><b>Only Eclipse's own editors.</b> {@code IDE.getEditorDescriptor} names the editor Eclipse
 *       would open; for a {@code .zip}, a {@code .pdf} or an unknown binary that is the system's
 *       program, which would start outside Eclipse. Then nothing opens.</li>
 *   <li><b>Only text.</b> An internal editor that is no text editor (an image in the browser editor)
 *       is closed again. A multi-page editor opened on a form page stays open: the file is shown, and
 *       Neovim attaches when its source tab is chosen.</li>
 * </ul>
 * The editor is opened without being activated: the caller activates it, so that Eclipse's Back
 * history notes the switch as the plug-in's ({@link NavigationHistorySteps#activateForNeovim}).
 */
final class ProjectFileEditors {

    private static final Logger LOG = Logger.getLogger(ProjectFileEditors.class.getName());

    private ProjectFileEditors() {
    }

    /**
     * Opens {@code target}'s editor in {@code page}, not activated. UI thread.
     *
     * @param target the file, from {@link #workspaceFile}
     * @return the text editor or multi-page editor opened, or {@code null} when Eclipse would open it
     *         outside the IDE, or it is no text (each logged)
     */
    static IEditorPart open(IWorkbenchPage page, IFile target) {
        IEditorDescriptor editor;
        try {
            editor = IDE.getEditorDescriptor(target, true, false);
        } catch (PartInitException e) {
            LOG.log(Level.FINE, "No editor for " + target.getFullPath(), e);
            return null;
        }
        if (editor == null || !editor.isInternal() || editor.isOpenExternal() || editor.isOpenInPlace()) {
            LOG.fine(() -> "Eclipse would open " + target.getFullPath() + " outside the IDE ("
                    + (editor == null ? "no editor" : editor.getId()) + "): not opened for Neovim");
            return null;
        }
        IEditorPart opened;
        try {
            opened = IDE.openEditor(page, target, editor.getId(), false);
        } catch (PartInitException e) {
            LOG.log(Level.WARNING, "Could not open " + target.getFullPath() + " for Neovim", e);
            return null;
        }
        if (opened == null) {
            return null;
        }
        if (EditorAttachManager.textEditorOf(opened) == null && !(opened instanceof MultiPageEditorPart)) {
            LOG.fine(() -> target.getFullPath() + " opened in " + opened.getClass().getName()
                    + ", which is no text editor: closed again");
            page.closeEditor(opened, false);
            return null;
        }
        return opened;
    }

    /**
     * The workspace file at Neovim's {@code name}, refreshed, or {@code null} when it is not a file of
     * an open project (or not on disk; logged).
     *
     * <p>Never on the UI thread: a refresh waits for the workspace while another job (a build) holds
     * it. Any other thread.
     *
     * @param name the buffer's name in Neovim (an absolute path, as Neovim wrote it)
     */
    static IFile workspaceFile(String name) {
        Path path;
        try {
            path = Path.of(name);
        } catch (InvalidPathException e) {
            return null;
        }
        IFile found = existing(path); // links resolved by the one lookup, NewFiles.workspaceFileAt
        if (found == null) {
            LOG.fine(() -> "Not a file of an open project: " + name);
        }
        return found;
    }

    /**
     * The file at {@code path} ({@link NewFiles#workspaceFileAt}: in a nested project, the inner
     * project's), refreshed when Eclipse does not know it yet, or {@code null}.
     */
    private static IFile existing(Path path) {
        IFile file = NewFiles.workspaceFileAt(path);
        if (file == null) {
            return null;
        }
        if (!file.exists()) {
            refresh(file);
        }
        return file.isAccessible() ? file : null;
    }

    /** Refreshes {@code file} and the folders above it that Eclipse does not know yet, top down. */
    private static void refresh(IFile file) {
        Deque<IResource> missing = new ArrayDeque<>();
        missing.push(file);
        for (IContainer folder = file.getParent(); folder != null && !folder.exists(); folder = folder.getParent()) {
            missing.push(folder);
        }
        try {
            for (IResource resource : missing) {
                resource.refreshLocal(IResource.DEPTH_ZERO, null);
            }
        } catch (CoreException e) {
            LOG.log(Level.FINE, "Could not refresh " + file.getFullPath(), e);
        }
    }
}
