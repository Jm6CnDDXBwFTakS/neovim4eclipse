// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IReusableEditor;
import org.eclipse.ui.ide.FileStoreEditorInput;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.DocumentWrite;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TextLines;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WrittenMessage;

/**
 * Writes that Neovim asks for to a file other than the editor's own (the plan "writes under another
 * name"). {@code :w other.txt} is a copy, as in Vim: the document's text goes to {@code other.txt},
 * and the editor stays on its own file with its unsaved changes, so {@code :w} afterwards still
 * saves the editor. {@code :5,10w part.txt} writes those lines only ({@link #lines}), and
 * {@code :w >> log.txt} adds lines to the end of a file ({@link #append}). {@code :saveas new.txt}
 * is Eclipse's Save As without its dialog: the editor moves to the new file ({@link #saveAs}). Neovim decides which
 * writes these are (the write hooks in {@code core/LuaScripts.DOCUMENT_BUFFER}) and has already
 * refused an existing file without {@code !} (E13).
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
 *       ({@link NewFiles}). An append to a missing file needs {@code !}, as in Vim
 *       ({@code :w! >> log.txt}); without it, E212.</li>
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
        return write(target, document.get(), EclipseFileCommands.charsetOf(editor), false, true, createFolders);
    }

    /**
     * Writes lines {@code first} to {@code last} (1-based) of {@code editor}'s text to {@code target},
     * which they replace, as {@code :5,10w target} does in Vim. Example: {@code :2,3w part.txt} of
     * {@code "a\nb\nc\n"} makes {@code part.txt} {@code "b\nc\n"}.
     */
    static DocumentWrite.Result lines(ITextEditor editor, Path target, int first, int last, boolean createFolders) {
        IDocument document = documentOf(editor);
        if (document == null) {
            return DocumentWrite.Result.failed(editor.getTitle() + " has no text to write");
        }
        return write(target, TextLines.of(document.get(), first, last), EclipseFileCommands.charsetOf(editor), false,
                true, createFolders);
    }

    /**
     * Adds lines {@code first} to {@code last} (1-based) of {@code editor}'s text to the end of
     * {@code target}, as {@code :5,10w >> target} does in Vim; {@code :w >> target} sends all lines.
     *
     * @param force {@code !}: a missing file is created ({@code :w! >> log.txt}); without it, E212
     */
    static DocumentWrite.Result append(ITextEditor editor, Path target, int first, int last, boolean force,
            boolean createFolders) {
        IDocument document = documentOf(editor);
        if (document == null) {
            return DocumentWrite.Result.failed(editor.getTitle() + " has no text to write");
        }
        return write(target, TextLines.of(document.get(), first, last), EclipseFileCommands.charsetOf(editor), true,
                force, createFolders);
    }

    /**
     * Saves {@code editor}'s document as {@code target} and moves the editor there, as
     * {@code :saveas target} does in Vim and File › Save As in Eclipse. Neovim has already renamed its
     * buffer to {@code target}; the editor
     * keeps that buffer, with its undo history and marks. The old file stays as it was on disk.
     *
     * <p>How: as Eclipse's own Save As ({@code AbstractDecoratedTextEditor.performSaveAs}) without the
     * dialog. The document provider saves the document to the new input, then the editor takes that
     * input ({@link EditorAttachManager#replaceInputKeepingBuffer}). Experiment X4 checked it for a
     * workspace file, a file outside the workspace and a Java file. Eclipse's save actions do not run
     * in such a save (X4: format on save left the text as it was); a {@code :w} afterwards runs them.
     *
     * <p>Refused when another Eclipse editor has {@code target} open (Vim's E139 is for a file another
     * buffer has; Neovim gives it itself when that editor has a Neovim buffer), and for an editor
     * that cannot take another file (a part of a multi-page editor, say).
     *
     * @param createFolders {@code ++p}: missing folders may be created
     */
    static DocumentWrite.Result saveAs(ITextEditor editor, Path target, boolean createFolders) {
        String name = target.getFileName() == null ? target.toString() : target.getFileName().toString();
        IDocumentProvider provider = editor.getDocumentProvider();
        IDocument document = documentOf(editor);
        if (provider == null || document == null || !(editor instanceof IReusableEditor)) {
            return DocumentWrite.Result.failed("Eclipse cannot save " + editor.getTitle() + " as another file here; use File > Save As");
        }
        IFile workspaceFile = NewFiles.workspaceFileAt(target);
        if (openBufferOf(target, workspaceFile) != null) {
            return DocumentWrite.Result.failed("E139: File is loaded in another buffer (an Eclipse editor has \"" + name + "\" open)");
        }
        try {
            if (!folderExists(target, workspaceFile)) {
                if (!createFolders) {
                    return DocumentWrite.Result.failed(NewFiles.NO_FOLDER);
                }
                createFolder(target, workspaceFile);
            }
            boolean created = !Files.exists(target);
            IEditorInput input = workspaceFile != null ? new FileEditorInput(workspaceFile)
                    : new FileStoreEditorInput(EFS.getLocalFileSystem().getStore(target.toUri()));
            provider.aboutToChange(input);
            try {
                provider.saveDocument(new NullProgressMonitor(), input, document, true);
            } finally {
                provider.changed(input);
            }
            if (workspaceFile != null) {
                workspaceFile.refreshLocal(IResource.DEPTH_ZERO, null);
            }
            if (!EditorAttachManager.getInstance().replaceInputKeepingBuffer(editor, input)) {
                // Another editor shows the same document: that one would be left on the old file.
                return DocumentWrite.Result.failed("Saved " + name + ", but another editor shows this document, so this "
                        + "one stays on " + editor.getTitle());
            }
            return DocumentWrite.Result.saved(WrittenMessage.of(name, created, document.get(), EclipseFileCommands.charsetOf(editor)));
        } catch (Exception e) {
            LOG.log(Level.FINE, "Saving " + editor.getTitle() + " as " + target + " failed", e);
            return DocumentWrite.Result.failed("E212: Can't open file for writing: " + e.getMessage());
        }
    }

    /**
     * Writes {@code text} to {@code target} in {@code charset}, replacing the file or, with
     * {@code append}, after its text; see the class comment.
     *
     * @param mayCreate whether a missing file may be made (an append needs {@code !} for it)
     */
    private static DocumentWrite.Result write(Path target, String text, Charset charset, boolean append,
            boolean mayCreate, boolean createFolders) {
        String name = target.getFileName() == null ? target.toString() : target.getFileName().toString();
        IFile workspaceFile = NewFiles.workspaceFileAt(target);
        ITextFileBuffer open = openBufferOf(target, workspaceFile);
        if (open != null && open.isDirty()) {
            return DocumentWrite.Result.failed("\"" + name + "\" has unsaved changes in an Eclipse editor; save or close it first");
        }
        try {
            boolean created = !Files.exists(target);
            if (created && !mayCreate) {
                return DocumentWrite.Result.failed("E212: Can't open file for writing: no such file or directory");
            }
            if (!folderExists(target, workspaceFile)) {
                if (!createFolders) {
                    return DocumentWrite.Result.failed(NewFiles.NO_FOLDER);
                }
                createFolder(target, workspaceFile);
            }
            byte[] bytes = text.getBytes(charset);
            if (workspaceFile != null) {
                writeWorkspaceFile(workspaceFile, bytes, charset, append);
            } else {
                if (append) {
                    Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } else {
                    Files.write(target, bytes);
                }
                if (open != null) {
                    open.revert(null); // no workspace to tell the editor; it would ask "File Changed"
                }
            }
            return DocumentWrite.Result.saved(append ? WrittenMessage.appended(name, created, text, charset)
                    : WrittenMessage.of(name, created, text, charset));
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

    private static void writeWorkspaceFile(IFile file, byte[] bytes, Charset charset, boolean append) throws Exception {
        file.refreshLocal(IResource.DEPTH_ZERO, null); // a file made outside Eclipse is overwritten, not created twice
        if (file.exists()) {
            if (append) {
                file.appendContents(new ByteArrayInputStream(bytes), IResource.FORCE | IResource.KEEP_HISTORY, null);
            } else {
                file.setContents(new ByteArrayInputStream(bytes), IResource.FORCE | IResource.KEEP_HISTORY, null);
            }
            return;
        }
        file.create(new ByteArrayInputStream(bytes), IResource.FORCE, null);
        if (!Charset.forName(file.getParent().getDefaultCharset()).equals(charset)) {
            file.setCharset(charset.name(), null);
        }
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
