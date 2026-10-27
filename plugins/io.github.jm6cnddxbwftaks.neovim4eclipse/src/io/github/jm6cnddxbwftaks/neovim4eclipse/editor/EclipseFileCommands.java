// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.editors.text.IEncodingSupport;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.DocumentWrite;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.EditPath;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WrittenMessage;

/**
 * Vim's file commands carried out by Eclipse: save, close, open and revert. Which command means
 * what is decided by {@link io.github.jm6cnddxbwftaks.neovim4eclipse.util.HostCommand}; the
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

    /**
     * {@code ZZ}: write if needed, then quit; a failed write keeps the editor open, as in Vim (a new
     * file in a missing folder, a read-only file).
     *
     * <p>{@code ZZ} and {@code ZQ} are mappings in Neovim ({@code IdeAction}), so they arrive as a
     * notification after their key event is over. That matters: closed during the event, the Java
     * editor's own key listeners, which run after the plug-in's, would find a disposed editor
     * ("getViewer() is null").
     */
    static void zz(ITextEditor editor) {
        if (save(editor, false, false)) {
            close(editor, true);
        }
    }

    /** {@code ZQ}: quit without writing; see {@link #zz}. */
    static void zq(ITextEditor editor) {
        close(editor, true);
    }

    /**
     * {@code :w}. An editor whose file does not exist yet is saved by creating the file
     * ({@link NewFiles#save}); any other is saved by Eclipse, as {@code Ctrl+S} would.
     *
     * @param createFolders   {@code :w ++p}: a new file's missing folders may be created
     * @param evenIfUnchanged a new file is created even when nothing was typed ({@code :w}, {@code :wq})
     * @return {@code true} if the editor is clean afterward
     */
    static boolean save(ITextEditor editor, boolean createFolders, boolean evenIfUnchanged) {
        IEditorPart part = EditorAttachManager.getInstance().workbenchPartOf(editor);
        NewFiles.Missing missing = NewFiles.missingFileOf(editor);
        return missing == null ? savePart(part) : NewFiles.save(editor, part, missing, createFolders, evenIfUnchanged);
    }

    /**
     * Writes a copy of {@code editor}'s text to {@code name}, the {@code :w other.txt} part of
     * {@code :wq other.txt} ({@link OtherFileWrites#copy}), and shows Vim's message or the error.
     * Neovim's own {@code :w other.txt} checks for an existing file before Eclipse is asked; here the
     * command is Eclipse's, so this does it: E13 without {@code !}. UI thread.
     *
     * @param name          as typed: relative to the edited file's folder, absolute, or {@code ~/...}
     * @param onlyIfChanged {@code :x}: nothing is written when the editor has no unsaved changes
     * @return whether the copy was written, or nothing needed writing
     */
    static boolean writeCopy(ITextEditor editor, String name, boolean force, boolean onlyIfChanged, boolean createFolders) {
        IEditorPart part = EditorAttachManager.getInstance().workbenchPartOf(editor);
        if (onlyIfChanged && !part.isDirty()) {
            return true;
        }
        Path target = newFilePath(editor, name);
        if (target == null) {
            HostCommands.showError(editor, "Eclipse cannot tell where \"" + name + "\" goes: this file has no folder");
            return false;
        }
        if (!force && Files.exists(target)) {
            HostCommands.showError(editor, "E13: File exists (add ! to override)");
            return false;
        }
        DocumentWrite.Result result = OtherFileWrites.copy(editor, target, createFolders);
        HostCommands.showMessage(editor, result.ok() ? result.message() : result.error(), !result.ok());
        return result.ok();
    }

    /**
     * Vim's message after a write of {@code editor}'s file, as Neovim would show it:
     * {@code "Clazz.java" 12L, 140B written}, the bytes counted in the file's encoding
     * ({@link WrittenMessage}). UI thread.
     */
    static String writtenMessage(ITextEditor editor) {
        IEditorInput input = editor.getEditorInput();
        IDocument document = editor.getDocumentProvider() == null ? null
                : editor.getDocumentProvider().getDocument(input);
        return WrittenMessage.of(input.getName(), false, document == null ? "" : document.get(), charsetOf(editor));
    }

    /** The encoding the editor saves in: the workspace file's, the editor's, or UTF-8. */
    static Charset charsetOf(ITextEditor editor) {
        try {
            IFile file = editor.getEditorInput().getAdapter(IFile.class);
            if (file != null && file.exists()) {
                return Charset.forName(file.getCharset());
            }
            IEncodingSupport encoding = editor.getAdapter(IEncodingSupport.class);
            if (encoding != null) {
                String name = encoding.getEncoding() != null ? encoding.getEncoding() : encoding.getDefaultEncoding();
                if (name != null) {
                    return Charset.forName(name);
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Unknown encoding for " + editor.getTitle(), e);
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * Saves a workbench editor (a multi-page editor as a whole) if it is dirty. UI thread.
     *
     * @return {@code true} if the editor is clean afterward
     */
    static boolean savePart(IEditorPart part) {
        try {
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
     * <p>Editors whose file does not exist yet are written first, creating their files
     * ({@link NewFiles#save}): Eclipse's own save would open Save As for each. If one of them cannot
     * be written (its folder is missing), Eclipse's Save All would still offer Save As for it, so the
     * other editors are then saved one by one instead.
     *
     * @return {@code true} if nothing is left unsaved (an editor can refuse, e.g. on a write error)
     */
    static boolean saveAll() {
        try {
            IWorkbench workbench = PlatformUI.getWorkbench();
            List<IEditorPart> dirty = dirtyEditors(workbench);
            List<IEditorPart> failed = new ArrayList<>();
            for (IEditorPart part : dirty) {
                ITextEditor text = EditorAttachManager.textEditorOf(part);
                NewFiles.Missing missing = text == null ? null : NewFiles.missingFileOf(text);
                if (missing != null && !NewFiles.save(text, part, missing, false, false)) {
                    failed.add(part);
                }
            }
            if (failed.isEmpty()) {
                return workbench.saveAllEditors(false); // false: no "Save resources?" confirmation
            }
            for (IEditorPart part : dirty) {
                if (!failed.contains(part) && part.isDirty()) {
                    savePart(part);
                }
            }
            return false;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Eclipse save all failed", e);
            return false;
        }
    }

    /** The modified editors of every workbench window; editors never shown are not modified. */
    private static List<IEditorPart> dirtyEditors(IWorkbench workbench) {
        List<IEditorPart> dirty = new ArrayList<>();
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
            for (IWorkbenchPage page : window.getPages()) {
                for (IEditorReference reference : page.getEditorReferences()) {
                    IEditorPart part = reference.getEditor(false);
                    if (part != null && part.isDirty()) {
                        dirty.add(part);
                    }
                }
            }
        }
        return dirty;
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
     *   <li>With a file name: opens it (see {@link EditPath} for where it is looked for). A file
     *       that is not found opens as a new, empty editor beside the current file, created by the
     *       first {@code :w} ({@link NewFiles}).</li>
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
            Path created = newFilePath(editor, argument);
            if (created == null) {
                HostCommands.showError(editor, "Can't find file \"" + argument + "\"");
            } else {
                NewFiles.open(editor.getEditorSite().getPage(), created, argument);
            }
            return;
        }
        if (Files.isDirectory(file)) {
            HostCommands.showError(editor, "\"" + argument + "\" is a directory");
            return;
        }
        open(editor.getEditorSite().getPage(), file);
    }

    /**
     * Where {@code :e} looks for a relative file name, best first: the edited file's folder, then
     * its project's root (see {@link EditPath}). Entries are {@code null} when unknown. Tab in the
     * {@code :} box completes {@code :e} names in the same folders, so that it offers only names
     * {@code :e} can find.
     */
    static List<Path> editFolders(ITextEditor editor) {
        Path current = EditorAttachManager.fileOf(editor.getEditorInput());
        List<Path> bases = new ArrayList<>();
        bases.add(current == null ? null : current.getParent());
        bases.add(projectRootOf(editor.getEditorInput()));
        return bases;
    }

    /** The first existing {@link EditPath#candidates} entry, or {@code null}. */
    private static Path findFile(ITextEditor editor, String argument) {
        List<Path> bases = editFolders(editor);
        for (Path candidate : EditPath.candidates(argument, Path.of(System.getProperty("user.home")), bases)) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Where {@code :e name} creates a file it did not find: {@code name} beside the current file
     * (absolute and {@code ~} paths as typed), never in the project root. {@code null} when there is
     * no current folder (an untitled document) and the name is relative.
     */
    private static Path newFilePath(ITextEditor editor, String argument) {
        List<Path> currentFolder = Collections.singletonList(editFolders(editor).get(0));
        List<Path> candidates = EditPath.candidates(argument, Path.of(System.getProperty("user.home")), currentFolder);
        return candidates.isEmpty() ? null : candidates.get(0);
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

    /**
     * The root folder of the workspace project the input's file is in, or {@code null}. A new
     * file's editor is opened by location (see {@link NewFiles}), so its project is found from the
     * location when the input is not a workspace file.
     */
    private static Path projectRootOf(IEditorInput input) {
        IFile file = input == null ? null : input.getAdapter(IFile.class);
        if (file == null) {
            Path location = EditorAttachManager.fileOf(input);
            file = location == null ? null : NewFiles.workspaceFileAt(location);
        }
        IPath location = file == null || file.getProject() == null ? null : file.getProject().getLocation();
        return location == null ? null : Path.of(location.toOSString());
    }
}
