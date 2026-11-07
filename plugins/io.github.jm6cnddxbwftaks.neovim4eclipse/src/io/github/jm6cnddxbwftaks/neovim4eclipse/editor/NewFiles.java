// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.content.IContentDescription;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.core.runtime.content.IContentTypeManager;
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

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FilePaths;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WrittenMessage;

/**
 * Files that do not exist yet, as Vim has them: {@code :e new.txt} opens an empty editor, and the
 * first {@code :w} creates the file. Design and experiments: {@code docs/design/NEW_FILE.md}.
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
     *
     * <p><b>The one lookup of a path's workspace file</b>, for every caller ({@code :e}, a file
     * Neovim moved into, a new file's project): the path as given first, then with its links resolved
     * ({@link FilePaths#resolved}, {@link java.nio.file.Path#toRealPath}). Eclipse's own lookup
     * already canonicalizes the location ({@code File.getCanonicalPath}: symbolic links on Linux and
     * macOS, case and 8.3 short names on Windows; checked 2026-10-29, a link on Linux needs no retry),
     * but that leaves out Windows junctions, which {@code toRealPath} resolves. Example: with
     * {@code C:\work} a junction to {@code C:\ws}, {@code C:\work\proj\A.java} gives
     * {@code /proj/A.java}. Case needs nothing here: Eclipse's local file system ignores it on Windows
     * and macOS itself.
     *
     * <p><b>The other way round</b>: a project or linked folder whose location Eclipse recorded
     * through a junction, reached by the real path. Resolving the path cannot help (it is real
     * already); the recorded location is the one to resolve. So, last, each folder Neovim was told
     * about ({@link ProjectFolders#locations}) is compared by the one rule ({@link FilePaths#below}),
     * and a match is looked up again under the folder's recorded spelling ({@link #throughAFolder}).
     * Example: {@code mt/ext} linked to {@code C:\Users\me\home\src\outside}, with
     * {@code C:\Users\me\home\src} a junction to {@code C:\Users\me\src} (the user's
     * {@code ~/src} when {@code HOME} is {@code C:\Users\me\home}): {@code gf} onto
     * {@code C:\Users\me\src\outside\ext.txt} gives {@code /mt/ext/ext.txt}. Before (found
     * 2026-10-31, Windows, with a link made by {@code IFolder.createLink}), no workspace file was
     * found, no editor opened, and Neovim read the file itself, though it counted the folder as the
     * project's. The same link made with <i>New → Folder</i> was found by Eclipse's lookup anyway,
     * for a reason not known (same JDK, whose {@code getCanonicalPath} resolves the junction); this
     * step makes the answer not depend on how the link was made.
     */
    static IFile workspaceFileAt(Path path) {
        IFile file = inOpenProject(path);
        if (file == null) {
            Path real = FilePaths.resolved(path);
            if (!real.equals(path)) {
                file = inOpenProject(real);
            }
        }
        return file != null ? file : throughAFolder(path);
    }

    /**
     * The workspace file at {@code path} found under the recorded spelling of the project or linked
     * folder it is in by the one rule, or {@code null} (see {@link #workspaceFileAt}). Example:
     * folder {@code C:\work\ext} (a junction's path) and {@code path}
     * {@code C:\ws\ext\a.txt} (its target's): looked up as {@code C:\work\ext\a.txt}.
     */
    private static IFile throughAFolder(Path path) {
        FilePaths.Platform here = FilePaths.Platform.current();
        for (String folder : ProjectFolders.locations()) {
            String below = FilePaths.below(path.toString(), folder, here);
            if (below != null) {
                IFile file = inOpenProject(Path.of(folder).resolve(below));
                if (file != null) {
                    return file;
                }
            }
        }
        return null;
    }

    private static IFile inOpenProject(Path path) {
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
                // A dangling link opens as a new file, as in Vim, but says where :w! would write.
                String message = "\"" + name + "\" [New]"
                        + (DanglingLinks.isDangling(path) ? " " + DanglingLinks.newFileNote(path) : "");
                // After the plug-in attaches, which the editor's activation has queued.
                Display.getCurrent().asyncExec(() -> HostCommands.showMessage(text, message, false));
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
            return internalOr(IDE.getEditorDescriptor(fileName, true, false), fileName);
        } catch (Exception e) {
            LOG.log(Level.FINE, "No editor descriptor for " + fileName, e);
        }
        return TEXT_EDITOR;
    }

    /**
     * The editor a file Neovim opens ({@code :e}, {@code gf}, a quickfix jump) gets:
     * {@code descriptor}'s, if it is an editor inside Eclipse for text, else the Text Editor, which
     * shows the file as Vim would. Not:
     * <ul>
     *   <li>an external editor (the system's program for the file) or an in-place one (an OLE
     *       document on Windows): they run something outside Eclipse on the file (0.2.1 security
     *       review: on Windows, {@code :e build.bat} ran the script);</li>
     *   <li>an editor inside Eclipse for a file whose content type is not text. Example: Eclipse's
     *       Web Browser editor is bound to the image content type, which includes {@code svg}, so
     *       {@code :e logo.svg} rendered a cloned repository's SVG and ran its script (file-by-file
     *       security review of 0.2.1). A file with no content type is text, as Eclipse's own
     *       default editor treats it.</li>
     * </ul>
     *
     * <p>This one is for a file outside the workspace (or one that does not exist yet), whose
     * content type only its name gives; a workspace file's goes by {@link #internalOr(IEditorDescriptor, IFile)}.
     *
     * @param fileName the file's name, for its content type ({@code logo.svg})
     */
    static String internalOr(IEditorDescriptor descriptor, String fileName) {
        return isInside(descriptor) && isText(fileName) ? descriptor.getId() : TEXT_EDITOR;
    }

    /**
     * {@link #internalOr(IEditorDescriptor, String)} for a workspace file: its content type as the
     * workspace has it, which is what chose {@code descriptor} ({@code IDE.getEditorDescriptor(IFile)}
     * goes by {@code IFile.getContentDescription}).
     *
     * <p>Why not by the name: a project can map file names to content types of its own
     * (<i>Project Properties › Resource › Content types</i>, stored in its
     * {@code .settings/org.eclipse.core.runtime.prefs}, which a cloned repository brings along).
     * Such a project could map {@code *.n4e} to the image content type, which the Web Browser editor
     * is bound to, and ship an SVG named {@code x.n4e}: by the name alone (the global content types)
     * {@code x.n4e} is text, the browser editor passed, and {@code :e x.n4e} ran the SVG's script.
     * So every content type the workspace could give the file must be text: the one from its
     * description (contents and the project's names), the project's for its name, and the global
     * one for its name. Example: in that project {@code x.n4e} opens in the Text Editor.
     */
    static String internalOr(IEditorDescriptor descriptor, IFile file) {
        return isInside(descriptor) && isText(file) ? descriptor.getId() : TEXT_EDITOR;
    }

    /** Whether {@code descriptor} is an editor inside Eclipse: not external, not in-place (OLE). */
    private static boolean isInside(IEditorDescriptor descriptor) {
        return descriptor != null && descriptor.isInternal() && !descriptor.isOpenExternal()
                && !descriptor.isOpenInPlace();
    }

    /** Whether {@code fileName}'s content type is text, or it has none (see {@link #internalOr}). */
    static boolean isText(String fileName) {
        return isTextKind(Platform.getContentTypeManager().findContentTypeFor(fileName));
    }

    /**
     * Whether every content type the workspace gives {@code file} is text, or it has none (see
     * {@link #internalOr(IEditorDescriptor, IFile)}). A content type that cannot be read (the file
     * is out of sync with the disk) is left out; the ones by name still count.
     */
    static boolean isText(IFile file) {
        List<IContentType> types = new ArrayList<>(3);
        try {
            IContentDescription description = file.getContentDescription();
            if (description != null) {
                types.add(description.getContentType());
            }
        } catch (CoreException e) {
            LOG.log(Level.FINE, "No content description for " + file.getFullPath(), e);
        }
        try {
            types.add(file.getProject().getContentTypeMatcher().findContentTypeFor(file.getName()));
        } catch (CoreException e) {
            LOG.log(Level.FINE, "No project content types for " + file.getFullPath(), e);
        }
        types.add(Platform.getContentTypeManager().findContentTypeFor(file.getName()));
        return types.stream().allMatch(NewFiles::isTextKind);
    }

    /** Whether {@code type} is text, or {@code null} (no content type: text, as Eclipse's default editor has it). */
    private static boolean isTextKind(IContentType type) {
        IContentType text = Platform.getContentTypeManager().getContentType(IContentTypeManager.CT_TEXT);
        return type == null || text == null || type.isKindOf(text);
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
     * <p>A "missing" file that is a symbolic link to a file that does not exist is written only with
     * {@code throughLink} ({@code :w!}): the write would create the link's target, which may be
     * anywhere ({@link DanglingLinks}).
     *
     * @param part        the workbench editor ({@code editor} itself, or the multi-page editor around it)
     * @param throughLink {@code :w!}: a dangling link may be written through
     * @return {@code true} if the editor is clean afterward
     */
    static boolean save(ITextEditor editor, IEditorPart part, Missing missing, boolean createFolders,
            boolean evenIfUnchanged, boolean throughLink) {
        boolean changed = part.isDirty();
        if (!changed && !evenIfUnchanged) {
            return true;
        }
        if (!throughLink && DanglingLinks.isDangling(missing.path())) {
            HostCommands.showError(editor, DanglingLinks.refusal(editor.getEditorInput().getName(), missing.path()));
            return false;
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
    static void createWorkspaceFolder(IContainer container) throws CoreException {
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
