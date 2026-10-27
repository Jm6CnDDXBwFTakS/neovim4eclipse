// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.DocumentWrite;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WrittenMessage;

/**
 * Writes that Neovim asks for to a file other than the editor's own (the plan "writes under another
 * name"). {@code :w other.txt} is a copy, as in Vim: the document's text goes to {@code other.txt},
 * and the editor stays on its own file with its unsaved changes, so {@code :w} afterwards still
 * saves the editor. Neovim decides which writes are copies (the write hook in
 * {@code core/LuaScripts.DOCUMENT_BUFFER}) and has already refused an existing file without
 * {@code !} (E13).
 *
 * <p>How a copy is written, and why:
 * <ul>
 *   <li><b>In the editor's encoding</b>, as Vim writes a copy with the buffer's
 *       {@code 'fileencoding'}. A new file in a project whose default differs gets that encoding
 *       set on it ({@code IFile.setCharset}), so Eclipse reads it back as written. Example: a copy of
 *       an ISO-8859-1 file into a UTF-8 project is ISO-8859-1, and Eclipse knows it.</li>
 *   <li><b>Through the workspace</b> for a file inside an open project ({@code IFile.create} or
 *       {@code setContents}), so the Project Explorer shows it at once; directly to disk for any
 *       other file.</li>
 *   <li><b>Never over unsaved changes.</b> If an Eclipse editor has the target open with unsaved
 *       changes, the copy is refused, even with {@code !}: the editor would keep its text and show
 *       "File Changed" when next activated (experiment X3), and one of the two texts would be lost.
 *       An editor without unsaved changes reloads the new text, and its Neovim buffer follows.</li>
 *   <li><b>No save actions.</b> Eclipse's save actions (format on save, organize imports) belong to
 *       an editor's save; a copy is the text as it is, as in Vim.</li>
 *   <li>A missing folder fails with E212, unless {@code ++p} creates it, as for a new file
 *       ({@link NewFiles}).</li>
 * </ul>
 *
 * <p>UI thread only.
 */
final class OtherFileWrites {

    private static final Logger LOG = Logger.getLogger(OtherFileWrites.class.getName());

    private OtherFileWrites() {
    }

    /**
     * Writes {@code editor}'s whole text to {@code target}, as {@code :w target} does in Vim.
     *
     * @param target        the file, a full path ({@code /ws/proj/other.txt})
     * @param createFolders {@code ++p}: its missing folders may be created
     * @return Vim's written message ({@code "other.txt" [New] 3L, 12B written}), or why not
     */
    static DocumentWrite.Result copy(ITextEditor editor, Path target, boolean createFolders) {
        IDocument document = documentOf(editor);
        if (document == null) {
            return DocumentWrite.Result.failed(editor.getTitle() + " has no text to copy");
        }
        return write(target, document.get(), EclipseFileCommands.charsetOf(editor), createFolders);
    }

    /** Writes {@code text} to {@code target} in {@code charset}; see the class comment. */
    private static DocumentWrite.Result write(Path target, String text, Charset charset, boolean createFolders) {
        String name = target.getFileName() == null ? target.toString() : target.getFileName().toString();
        IFile workspaceFile = NewFiles.workspaceFileAt(target);
        ITextFileBuffer open = openBufferOf(target, workspaceFile);
        if (open != null && open.isDirty()) {
            return DocumentWrite.Result.failed("\"" + name + "\" has unsaved changes in an Eclipse editor; save or close it first");
        }
        try {
            if (!folderExists(target, workspaceFile)) {
                if (!createFolders) {
                    return DocumentWrite.Result.failed(NewFiles.NO_FOLDER);
                }
                createFolder(target, workspaceFile);
            }
            boolean created;
            byte[] bytes = text.getBytes(charset);
            if (workspaceFile != null) {
                created = writeWorkspaceFile(workspaceFile, bytes, charset);
            } else {
                created = !Files.exists(target);
                Files.write(target, bytes);
                if (open != null) {
                    open.revert(null); // no workspace to tell the editor; it would ask "File Changed"
                }
            }
            return DocumentWrite.Result.saved(WrittenMessage.of(name, created, text, charset));
        } catch (Exception e) {
            LOG.log(Level.FINE, "Writing " + target + " for Neovim failed", e);
            return DocumentWrite.Result.failed("E212: Can't open file for writing: " + e.getMessage());
        }
    }

    private static boolean folderExists(Path target, IFile workspaceFile) {
        if (workspaceFile != null) {
            return workspaceFile.getParent().exists();
        }
        Path parent = target.toAbsolutePath().getParent();
        return parent == null || Files.isDirectory(parent);
    }

    private static void createFolder(Path target, IFile workspaceFile) throws Exception {
        if (workspaceFile != null) {
            NewFiles.createWorkspaceFolder(workspaceFile.getParent());
        } else {
            Files.createDirectories(target.toAbsolutePath().getParent());
        }
    }

    /** @return whether the file was created */
    private static boolean writeWorkspaceFile(IFile file, byte[] bytes, Charset charset) throws Exception {
        file.refreshLocal(IResource.DEPTH_ZERO, null); // a file made outside Eclipse is overwritten, not created twice
        if (file.exists()) {
            file.setContents(new ByteArrayInputStream(bytes), IResource.FORCE | IResource.KEEP_HISTORY, null);
            return false;
        }
        file.create(new ByteArrayInputStream(bytes), IResource.FORCE, null);
        if (!Charset.forName(file.getParent().getDefaultCharset()).equals(charset)) {
            file.setCharset(charset.name(), null);
        }
        return true;
    }

    /**
     * The file buffer of an Eclipse editor open on {@code target}, or {@code null}. Looked up as a
     * workspace file, by location (how a new file's editor is opened, {@link NewFiles}) and as a file
     * outside the workspace.
     */
    private static ITextFileBuffer openBufferOf(Path target, IFile workspaceFile) {
        ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
        ITextFileBuffer buffer = null;
        if (workspaceFile != null) {
            buffer = manager.getTextFileBuffer(workspaceFile.getFullPath(), LocationKind.IFILE);
        }
        if (buffer == null) {
            buffer = manager.getTextFileBuffer(IPath.fromOSString(target.toString()), LocationKind.LOCATION);
        }
        if (buffer == null) {
            buffer = manager.getFileStoreTextFileBuffer(EFS.getLocalFileSystem().getStore(target.toUri()));
        }
        return buffer;
    }

    private static IDocument documentOf(ITextEditor editor) {
        IDocumentProvider provider = editor.getDocumentProvider();
        return provider == null ? null : provider.getDocument(editor.getEditorInput());
    }
}
