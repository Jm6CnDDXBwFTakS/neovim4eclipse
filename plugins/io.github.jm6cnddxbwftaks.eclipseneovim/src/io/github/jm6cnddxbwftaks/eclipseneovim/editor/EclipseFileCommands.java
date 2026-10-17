// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.EditPath;

/**
 * Vim's file commands carried out by Eclipse: save, close, open and revert. Which command means
 * what is decided by {@link io.github.jm6cnddxbwftaks.eclipseneovim.util.HostExCommand}; the
 * dispatching is done by {@link HostCommands}.
 *
 * <p>Why Eclipse: Neovim's buffers are copies the plug-in keeps in step with Eclipse's documents;
 * the IDE owns the files. A Neovim {@code :w} would write the copy, behind Eclipse's back.
 *
 * <p>UI thread only.
 */
public final class EclipseFileCommands {

    private static final Logger LOG = Logger.getLogger(EclipseFileCommands.class.getName());

    private EclipseFileCommands() {
    }

    /** {@code ZZ}: write if needed, then quit. */
    public static void zz(ITextEditor editor) {
        HostCommands.runOnUi(() -> {
            save(editor);
            close(editor, true);
        });
    }

    /** {@code ZQ}: quit without writing. */
    public static void zq(ITextEditor editor) {
        HostCommands.runOnUi(() -> close(editor, true));
    }

    /** {@code :w}. @return {@code true} if the editor is clean afterward */
    static boolean save(ITextEditor editor) {
        try {
            IEditorPart part = EditorAttachManager.getInstance().workbenchPartOf(editor);
            if (!part.isDirty()) {
                return true;
            }
            part.doSave(new NullProgressMonitor());
            return !part.isDirty();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Eclipse save failed", e);
            return false;
        }
    }

    /**
     * {@code :wa}: saves every modified editor in every workbench window, without asking, as Vim
     * writes every changed buffer. This is Eclipse's own <em>File &gt; Save All</em>.
     *
     * @return {@code true} if nothing is left unsaved (an editor can refuse, e.g. on a write error)
     */
    static boolean saveAll() {
        try {
            IWorkbench workbench = PlatformUI.getWorkbench();
            return workbench.saveAllEditors(false); // false: no "Save resources?" confirmation
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Eclipse save all failed", e);
            return false;
        }
    }

    /**
     * {@code :q}.
     *
     * @param force {@code true} closes without prompting to save (discarding changes);
     *              {@code false} lets Eclipse ask
     */
    static void close(ITextEditor editor, boolean force) {
        try {
            IWorkbenchPage page = editor.getEditorSite().getPage();
            if (page != null) {
                page.closeEditor(EditorAttachManager.getInstance().workbenchPartOf(editor), !force);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Eclipse close failed", e);
        }
    }

    /**
     * {@code :qa}: closes every editor of the editor's window.
     *
     * @param force {@code true} closes without prompting (discarding changes); {@code false} lets
     *              Eclipse ask about each modified one, as Vim refuses to quit with changes
     */
    static void closeAll(ITextEditor editor, boolean force) {
        try {
            IWorkbenchPage page = editor.getEditorSite().getPage();
            if (page != null) {
                page.closeAllEditors(!force);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Eclipse close all failed", e);
        }
    }

    /**
     * {@code :e[!] [file]}.
     * <ul>
     *   <li>With a file name: opens it (see {@link EditPath} for where it is looked for).</li>
     *   <li>{@code :e!}: reverts the editor to the saved file, discarding changes.</li>
     *   <li>{@code :e}: reloads the saved file, refused (as in Vim, E37) while there are changes.</li>
     * </ul>
     * A revert reaches Neovim like any other change Eclipse makes (see
     * {@code BufferSynchronizer.pushLocalChanges}).
     */
    static void edit(ITextEditor editor, String argument, boolean force) {
        if (argument.isEmpty()) {
            IEditorPart part = EditorAttachManager.getInstance().workbenchPartOf(editor);
            if (part.isDirty() && !force) {
                HostCommands.showError(editor, "E37: No write since last change (add ! to override)");
                return;
            }
            editor.doRevertToSaved();
            return;
        }
        Path file = findFile(editor, argument);
        if (file == null) {
            HostCommands.showError(editor, "Can't find file \"" + argument + "\"");
            return;
        }
        if (Files.isDirectory(file)) {
            HostCommands.showError(editor, "\"" + argument + "\" is a directory");
            return;
        }
        open(editor.getEditorSite().getPage(), file);
    }

    /** The first existing {@link EditPath#candidates} entry, or {@code null}. */
    private static Path findFile(ITextEditor editor, String argument) {
        Path current = EditorAttachManager.fileOf(editor.getEditorInput());
        List<Path> bases = new ArrayList<>();
        bases.add(current == null ? null : current.getParent());
        bases.add(projectRootOf(editor.getEditorInput()));
        for (Path candidate : EditPath.candidates(argument, Path.of(System.getProperty("user.home")), bases)) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Opens {@code file} in its default editor: through the workspace if it belongs to a project
     * (so that the editor knows its project, builders, markers), else as an outside file.
     */
    private static void open(IWorkbenchPage page, Path file) {
        try {
            IFile[] inWorkspace = ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(file.toUri());
            for (IFile candidate : inWorkspace) {
                if (candidate.isAccessible()) {
                    IDE.openEditor(page, candidate, true);
                    return;
                }
            }
            IDE.openEditorOnFileStore(page, EFS.getLocalFileSystem().getStore(file.toUri()));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not open " + file, e);
        }
    }

    /** The root folder of the workspace project the input's file is in, or {@code null}. */
    private static Path projectRootOf(IEditorInput input) {
        IFile file = input == null ? null : input.getAdapter(IFile.class);
        IPath location = file == null || file.getProject() == null ? null : file.getProject().getLocation();
        return location == null ? null : Path.of(location.toOSString());
    }
}
