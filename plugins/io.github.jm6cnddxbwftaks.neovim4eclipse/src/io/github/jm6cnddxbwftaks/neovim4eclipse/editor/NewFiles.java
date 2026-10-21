// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.editors.text.IEncodingSupport;
import org.eclipse.ui.editors.text.IStorageDocumentProvider;
import org.eclipse.ui.ide.FileStoreEditorInput;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.IDocumentProviderExtension;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WrittenMessage;

/**
 * Files that do not exist yet, as Vim has them: {@code :e new.txt} opens an empty editor, and the
 * first {@code :w} creates the file. Design and experiments: {@code docs/NEW_FILE.md}.
 *
 * <p>Three things in Eclipse need working around:
 * <ul>
 *   <li><b>How to open it.</b> A text editor refuses to show a workspace file that does not exist:
 *       for a file editor input ({@code IFileEditorInput}) {@code TextFileDocumentProvider.getStatus}
 *       reports "File ... does not exist." on purpose ("we don't open an empty document for a
 *       non-existent IFile"), and the editor shows that message as a read-only page in front of its
 *       text, so nothing can be typed. Every new file is therefore opened by its location
 *       ({@link FileStoreEditorInput}), as files outside the workspace always were ({@link #open}).
 *       Such an editor writes to disk, not through the workspace, so two things the workspace would
 *       do are done here for a file inside a project: it takes the encoding the file will have
 *       there (the project's or folder's, set when it opens), and after the creating write the
 *       workspace is refreshed, so the file appears in the project at once. That write then
 *       switches the editor to the workspace file ({@link #becomeWorkspaceFileEditor}), so later
 *       saves and project features go through the workspace as for any project file.</li>
 *   <li><b>Which editor.</b> Eclipse's default lookup for a file that does not exist picks the
 *       system's external editor (Notepad or similar). The editor is chosen by the file's name
 *       instead, falling back to the Text Editor ({@link #editorIdFor}).</li>
 *   <li><b>Saving.</b> Eclipse treats a missing file like a deleted one, so its own save opens Save
 *       As. Saving through the document provider, as {@code AbstractTextEditor.performSave} does
 *       without that step, creates the file instead ({@link #save}). {@code Ctrl+S} keeps Eclipse's
 *       Save As.</li>
 * </ul>
 * Nothing is kept about which editors are new: an editor whose file does not exist is saved this
 * way, so {@code :w} also recreates a file deleted while its editor was open, as in Vim.
 *
 * <p>UI thread only.
 */
final class NewFiles {

    private static final Logger LOG = Logger.getLogger(NewFiles.class.getName());

    /** The Text Editor's ID, as in Open With › Text Editor. */
    static final String TEXT_EDITOR = "org.eclipse.ui.DefaultTextEditor";

    /** Vim's E212, with a hint at Neovim's {@code ++p} (which Vim does not have). */
    static final String NO_FOLDER = "E212: Can't open file for writing: no such file or directory (:w ++p creates it)";

    /**
     * An editor's file that does not exist on disk.
     *
     * @param workspaceFile the workspace file, or {@code null} for a file outside the workspace
     * @param path          where it is on disk
     */
    record Missing(IFile workspaceFile, Path path) {
    }

    /**
     * The workspace file at {@code path} in an open project, existing or not, or {@code null} when
     * the path is outside every open project. Example: {@code /ws/proj/src/new.txt} gives
     * {@code /proj/src/new.txt} while project {@code proj} (at {@code /ws/proj}) is open.
     *
     * <p>Needed because a new file's editor is opened by location (see the class comment), so its
     * input does not adapt to {@code IFile}; this finds the project it belongs to anyway.
     */
    static IFile workspaceFileAt(Path path) {
        for (IFile file : ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(path.toUri())) {
            if (file.getProject().isAccessible()) {
                return file;
            }
        }
        return null;
    }

    private NewFiles() {
    }

    /**
     * The editor's file when it does not exist: a workspace file, or a local file opened from
     * outside the workspace. {@code null} when it exists, or when the editor has no file (an untitled
     * document, which keeps Eclipse's Save As).
     */
    static Missing missingFileOf(ITextEditor editor) {
        IEditorInput input = editor.getEditorInput();
        if (input == null) {
            return null;
        }
        IFile file = input.getAdapter(IFile.class);
        if (file != null) {
            return file.exists() || file.getLocation() == null ? null
                    : new Missing(file, Path.of(file.getLocation().toOSString()));
        }
        if (input instanceof FileStoreEditorInput store && store.getURI() != null
                && "file".equals(store.getURI().getScheme())) {
            Path path = Path.of(store.getURI());
            return Files.exists(path) ? null : new Missing(workspaceFileAt(path), path);
        }
        return null;
    }

    /**
     * Opens an empty editor on {@code path}, which does not exist, and shows {@code "name" [New]}.
     * Always by location, also inside a project: as a workspace file the editor would show "File
     * ... does not exist." instead of its text (see the class comment). Code that needs the project
     * finds it from the location ({@link #workspaceFileAt}).
     *
     * @param name the name as typed after {@code :e}, for the message
     */
    static void open(IWorkbenchPage page, Path path, String name) {
        try {
            String editorId = editorIdFor(path.getFileName().toString());
            IEditorInput input = new FileStoreEditorInput(EFS.getLocalFileSystem().getStore(path.toUri()));
            IEditorPart opened = page.openEditor(input, editorId, true);
            ITextEditor text = EditorAttachManager.textEditorOf(opened);
            if (text != null) {
                useWorkspaceEncoding(text, workspaceFileAt(path));
                // After the plug-in attaches, which the editor's activation has queued.
                Display.getCurrent().asyncExec(() -> HostCommands.showMessage(text, "\"" + name + "\" [New]", false));
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not open a new editor on " + path, e);
            HostCommands.showError(EditorAttachManager.textEditorOf(page.getActiveEditor()),
                    "Could not open \"" + name + "\": " + e.getMessage());
        }
    }

    /**
     * Gives a new file's editor the encoding the file will have in the workspace. Opened by
     * location, the editor would use the workspace default, so in a project set to ISO-8859-1 an
     * {@code é} would be written as two UTF-8 bytes. {@code IFile.getCharset()} answers for a file
     * that does not exist too: its folder's or project's encoding. The editor is still empty, so
     * nothing needs to be re-read (which {@code IEncodingSupport.setEncoding} would do).
     */
    private static void useWorkspaceEncoding(ITextEditor editor, IFile workspaceFile) {
        if (workspaceFile == null || !(editor.getDocumentProvider() instanceof IStorageDocumentProvider provider)) {
            return;
        }
        try {
            provider.setEncoding(editor.getEditorInput(), workspaceFile.getCharset());
        } catch (CoreException e) {
            LOG.log(Level.FINE, "No workspace encoding for " + workspaceFile, e);
        }
    }

    /**
     * The editor for a new file, chosen by its name only: never the default lookup on the missing
     * file, which picks the system's external editor. An external or in-place (OLE) editor is
     * replaced by the Text Editor, so {@code :e notes.xyz} never launches another program.
     */
    static String editorIdFor(String fileName) {
        try {
            IEditorDescriptor descriptor = IDE.getEditorDescriptor(fileName, true, false);
            if (descriptor != null && descriptor.isInternal()) {
                return descriptor.getId();
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "No editor descriptor for " + fileName, e);
        }
        return TEXT_EDITOR;
    }

    /**
     * Writes a new file's editor, creating the file:
     * <ol>
     *   <li>A missing folder fails with E212, unless {@code createFolders} ({@code :w ++p}) creates
     *       it. Checked first, because the provider's save would create missing workspace folders on
     *       its own.</li>
     *   <li>A changed editor is saved through its document provider, which creates the file, writes
     *       the text and leaves the editor clean, with no dialog.</li>
     *   <li>An unchanged one: the provider would write nothing, so the empty file is created here,
     *       for {@code :w} and {@code :wq} ({@code evenIfUnchanged}); {@code :x} and {@code ZZ}
     *       create nothing, as in Vim.</li>
     * </ol>
     * Then Vim's message, such as {@code "new.txt" [New] 1L, 6B written}.
     *
     * @param part the workbench editor ({@code editor} itself, or the multi-page editor around it)
     * @return {@code true} if the editor is clean afterward
     */
    static boolean save(ITextEditor editor, IEditorPart part, Missing missing, boolean createFolders,
            boolean evenIfUnchanged) {
        boolean changed = part.isDirty();
        if (!changed && !evenIfUnchanged) {
            return true;
        }
        try {
            if (!parentExists(missing)) {
                if (!createFolders) {
                    HostCommands.showError(editor, NO_FOLDER);
                    return false;
                }
                createParent(missing);
            }
            IDocumentProvider provider = editor.getDocumentProvider();
            IEditorInput input = editor.getEditorInput();
            IDocument document = provider.getDocument(input);
            if (changed) {
                provider.aboutToChange(input);
                try {
                    provider.saveDocument(new NullProgressMonitor(), input, document, true);
                } finally {
                    provider.changed(input);
                }
                refreshWorkspaceFile(missing);
            } else {
                createEmpty(missing);
                // The workspace first, then the editor: the editor's synchronize compares with what
                // the workspace knows, so in the other order Eclipse later asks "File Changed".
                refreshWorkspaceFile(missing);
                if (provider instanceof IDocumentProviderExtension extension) {
                    extension.synchronize(input); // so Eclipse does not report the file as changed on disk
                }
            }
            String written = WrittenMessage.of(input.getName(), true, document.get(), charsetOf(editor, missing));
            boolean clean = !part.isDirty();
            if (clean && missing.workspaceFile() != null && part == editor) {
                becomeWorkspaceFileEditor(editor, missing.workspaceFile(), missing.path());
            }
            HostCommands.showMessage(editor, written, false); // after the switch: it re-attaches
            return clean;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not create " + missing.path(), e);
            HostCommands.showError(editor, "E212: Can't open file for writing: " + e.getMessage());
            return false;
        }
    }

    /**
     * Switches a new file's editor, opened by location, to the workspace file now that it exists.
     *
     * <p>Why: an editor opened by location writes to disk without telling the workspace, so every
     * later save would leave the workspace out of date (a stale Project Explorer, no build), and
     * project features such as problem markers and a Java editor's code assist work only for
     * workspace files. Eclipse's own File › Open File makes the same switch for a file inside a
     * project ({@code IDE.openEditorOnFileStore}).
     *
     * <p>The Neovim buffer stays the same ({@link EditorAttachManager#replaceInputKeepingBuffer}),
     * so undo, marks and the buffer number survive, as they do in Vim after {@code :w}. Eclipse's
     * own undo history starts over, as for any new input. Not switched (only logged) when another
     * editor shows the same document or the editor cannot take a new input; that editor then
     * stays as it is, saving to disk.
     *
     * @param path the file's location, under which Eclipse kept the editor's old file buffer
     */
    private static void becomeWorkspaceFileEditor(ITextEditor editor, IFile workspaceFile, Path path) {
        try {
            if (EditorAttachManager.getInstance().replaceInputKeepingBuffer(editor, new FileEditorInput(workspaceFile))) {
                releaseLeftOverBuffer(path, documentOfEditor(editor));
            } else {
                LOG.fine("Kept the location input of " + workspaceFile + ": shared or not reusable");
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not switch the editor to the workspace file " + workspaceFile, e);
        }
    }

    /**
     * Releases the file buffer the editor had while it was opened by location, which Eclipse fails to
     * release itself. Without this, the next {@code :e} of the same name, after the file was deleted,
     * showed the old text in a clean editor.
     *
     * <p>Why Eclipse misses it: file buffers are kept under a key that
     * {@code FileBuffers.normalizeLocation} computes, both when they are connected and when they are
     * disconnected. For a location inside a project the key is the workspace path
     * ({@code /proj/new.txt}) only once the file exists, and the location itself
     * ({@code /ws/proj/new.txt}) before. The editor connected while the file did not exist, so its
     * buffer is kept under the location; when the switch disconnects it, the file exists, the key
     * comes out as the workspace path, nothing is found there, and the buffer stays connected, with
     * its text, until Eclipse exits. The next editor opened by location on that path, once the file
     * is gone again, connects to it and shows that text.
     *
     * <p>Releasing it here, by the location without normalizing ({@code LocationKind.LOCATION}),
     * ends that one connection. Only a buffer that is not the editor's current one is released, and
     * the switch is made only when no other editor shows the document, so no other user of the
     * buffer is cut off.
     */
    private static void releaseLeftOverBuffer(Path path, IDocument current) {
        ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
        IPath location = IPath.fromOSString(path.toString());
        ITextFileBuffer leftOver = manager.getTextFileBuffer(location, LocationKind.LOCATION);
        if (leftOver == null || leftOver.getDocument() == current) {
            return;
        }
        try {
            manager.disconnect(location, LocationKind.LOCATION, null);
        } catch (CoreException e) {
            LOG.log(Level.FINE, "Could not release the old file buffer of " + path, e);
        }
    }

    private static IDocument documentOfEditor(ITextEditor editor) {
        IDocumentProvider provider = editor.getDocumentProvider();
        return provider == null ? null : provider.getDocument(editor.getEditorInput());
    }

    private static boolean parentExists(Missing missing) {
        if (missing.workspaceFile() != null) {
            return missing.workspaceFile().getParent().exists();
        }
        Path parent = missing.path().getParent();
        return parent != null && Files.isDirectory(parent);
    }

    private static void createParent(Missing missing) throws Exception {
        if (missing.workspaceFile() != null) {
            createWorkspaceFolder(missing.workspaceFile().getParent());
        } else {
            Files.createDirectories(missing.path().getParent());
        }
    }

    /** Creates {@code container} and its missing parents, top first. A project is never created. */
    private static void createWorkspaceFolder(IContainer container) throws CoreException {
        if (container.exists() || !(container instanceof IFolder folder)) {
            return;
        }
        createWorkspaceFolder(folder.getParent());
        folder.create(true, true, null);
    }

    /**
     * Creates the empty file on disk, inside a project too: the editor reads and writes the disk
     * (it is opened by location), so creating it through the workspace would leave the editor's
     * view of the file out of date and Eclipse would later ask "File Changed". The workspace learns
     * of it from {@link #refreshWorkspaceFile}.
     */
    private static void createEmpty(Missing missing) throws Exception {
        Files.createFile(missing.path());
    }

    /**
     * Makes a file just written to disk inside a project known to the workspace: without it the
     * Project Explorer does not show it and reading it through the workspace fails ("Resource ...
     * does not exist") until a manual refresh (F5).
     */
    private static void refreshWorkspaceFile(Missing missing) throws CoreException {
        if (missing.workspaceFile() != null) {
            missing.workspaceFile().refreshLocal(IResource.DEPTH_ZERO, null);
        }
    }

    /** The encoding the file was written in, for the byte count. */
    private static Charset charsetOf(ITextEditor editor, Missing missing) {
        try {
            if (missing.workspaceFile() != null && missing.workspaceFile().exists()) {
                return Charset.forName(missing.workspaceFile().getCharset());
            }
            IEncodingSupport encoding = editor.getAdapter(IEncodingSupport.class);
            if (encoding != null) {
                String name = encoding.getEncoding() != null ? encoding.getEncoding() : encoding.getDefaultEncoding();
                if (name != null) {
                    return Charset.forName(name);
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Unknown encoding for " + missing.path(), e);
        }
        return StandardCharsets.UTF_8;
    }
}
