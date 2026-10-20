// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.editors.text.IEncodingSupport;
import org.eclipse.ui.ide.FileStoreEditorInput;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.IDocumentProviderExtension;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WrittenMessage;

/**
 * Files that do not exist yet, as Vim has them: {@code :e new.txt} opens an empty editor, and the
 * first {@code :w} creates the file. Design and experiments: {@code docs/NEW_FILE.md}.
 *
 * <p>Two things in Eclipse need working around:
 * <ul>
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
            return Files.exists(path) ? null : new Missing(null, path);
        }
        return null;
    }

    /**
     * Opens an empty editor on {@code path}, which does not exist, and shows {@code "name" [New]}.
     * A path inside a project opens as a workspace file, so the editor knows its project.
     *
     * @param name the name as typed after {@code :e}, for the message
     */
    static void open(IWorkbenchPage page, Path path, String name) {
        try {
            String editorId = editorIdFor(path.getFileName().toString());
            IEditorPart opened = null;
            for (IFile file : ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(path.toUri())) {
                if (file.getProject().isAccessible()) {
                    opened = IDE.openEditor(page, file, editorId, true);
                    break;
                }
            }
            if (opened == null) {
                IEditorInput outside = new FileStoreEditorInput(EFS.getLocalFileSystem().getStore(path.toUri()));
                opened = page.openEditor(outside, editorId, true);
            }
            ITextEditor text = EditorAttachManager.textEditorOf(opened);
            if (text != null) {
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
            } else {
                createEmpty(missing);
                if (provider instanceof IDocumentProviderExtension extension) {
                    extension.synchronize(input); // so Eclipse does not report the file as changed on disk
                }
            }
            HostCommands.showMessage(editor,
                    WrittenMessage.of(input.getName(), true, document.get(), charsetOf(editor, missing)), false);
            return !part.isDirty();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not create " + missing.path(), e);
            HostCommands.showError(editor, "E212: Can't open file for writing: " + e.getMessage());
            return false;
        }
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

    private static void createEmpty(Missing missing) throws Exception {
        if (missing.workspaceFile() != null) {
            missing.workspaceFile().create(new ByteArrayInputStream(new byte[0]), true, null);
        } else {
            Files.createFile(missing.path());
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
