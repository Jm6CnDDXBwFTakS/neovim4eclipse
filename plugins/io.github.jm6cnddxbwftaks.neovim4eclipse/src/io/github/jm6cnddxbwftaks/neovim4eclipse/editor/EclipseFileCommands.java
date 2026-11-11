// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWorkbenchWizard;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.editors.text.IEncodingSupport;
import org.eclipse.ui.ide.FileStoreEditorInput;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.wizards.IWizardDescriptor;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.DocumentWrite;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.EditPath;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.EnewEditors;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FilePaths;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WriteCopyChoice;
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
        return save(editor, createFolders, evenIfUnchanged, false);
    }

    /**
     * {@link #save(ITextEditor, boolean, boolean)}, for {@code :w!} with {@code force}: a new file
     * that is a dangling symbolic link may then be written through it ({@link DanglingLinks}).
     */
    static boolean save(ITextEditor editor, boolean createFolders, boolean evenIfUnchanged, boolean force) {
        IEditorPart part = EditorAttachManager.getInstance().workbenchPartOf(editor);
        NewFiles.Missing missing = NewFiles.missingFileOf(editor);
        return missing == null ? savePart(part)
                : NewFiles.save(editor, part, missing, createFolders, evenIfUnchanged, force);
    }

    /**
     * Writes a copy of {@code editor}'s text to {@code name}, the {@code :w other.txt} part of
     * {@code :wq other.txt} ({@link OtherFileWrites#copy}), and shows Vim's message or the error. An
     * editor without a file (an untitled one) is saved as {@code name} instead, as Vim names such a
     * buffer after the file it writes ({@link OtherFileWrites#copyOrSaveAs}).
     * Neovim's own {@code :w other.txt} checks for an existing file before Eclipse is asked; here the
     * command is Eclipse's, so this does it: E13 without {@code !}, and the other checks of
     * {@link WriteCopyChoice}, which decides. UI thread.
     *
     * @param name          usually a full path (Neovim expanded it as its own {@code :w} would); a
     *                      relative one is relative to the window's folder ({@link EditPath})
     * @param onlyIfChanged {@code :x}: nothing is written when the editor has no unsaved changes
     * @return whether the copy was written, or nothing needed writing
     */
    static boolean writeCopy(ITextEditor editor, String name, boolean force, boolean onlyIfChanged, boolean createFolders) {
        IEditorPart part = EditorAttachManager.getInstance().workbenchPartOf(editor);
        Path target = EditPath.resolve(name, Path.of(System.getProperty("user.home")), windowFolder(editor));
        Path own = EditorAttachManager.fileOf(editor.getEditorInput());
        // Which of the checks applies is WriteCopyChoice (tested in tests/unit); here the editor and
        // the disk are read for it, and the choice is carried out.
        WriteCopyChoice choice = WriteCopyChoice.of(part.isDirty(), onlyIfChanged, force,
                target == null ? null : new WriteCopyChoice.Target() {
                    @Override
                    public boolean isOwnFile() {
                        return own != null && FilePaths.same(own, target);
                    }

                    @Override
                    public boolean exists() {
                        return Files.exists(target);
                    }

                    @Override
                    public boolean isDanglingLink() {
                        return DanglingLinks.isDangling(target);
                    }
                });
        return switch (choice) { // every choice named: a new one fails to compile here until handled
            case NOTHING -> true;
            case NO_FOLDER -> {
                HostCommands.showError(editor, "Eclipse cannot tell where \"" + name + "\" goes: this file has no folder");
                yield false;
            }
            case SAVE_OWN -> save(editor, createFolders, !onlyIfChanged, force);
            case FILE_EXISTS -> {
                HostCommands.showError(editor, WriteCopyChoice.E13);
                yield false;
            }
            case DANGLING_LINK -> {
                HostCommands.showError(editor, DanglingLinks.refusal(name, target));
                yield false;
            }
            case COPY -> {
                // An untitled editor is saved as the file instead, so the close that follows has no
                // unsaved changes to ask about (OtherFileWrites.copyOrSaveAs; the Neovim buffer goes
                // with the editor).
                DocumentWrite.Result result = OtherFileWrites.copyOrSaveAs(editor, part, target, createFolders, force);
                HostCommands.showMessage(editor, result.ok() ? result.message() : result.error(), !result.ok());
                yield result.ok();
            }
        };
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
     * @param throughLink {@code :wa!}: a new file that is a symbolic link to a missing file is
     *                    written through it, as {@code :w!} writes one ({@link DanglingLinks});
     *                    {@code :wa} leaves that editor unsaved and shows the refusal
     * @return {@code true} if nothing is left unsaved (an editor can refuse, e.g. on a write error)
     */
    static boolean saveAll(boolean throughLink) {
        try {
            IWorkbench workbench = PlatformUI.getWorkbench();
            List<IEditorPart> dirty = dirtyEditors(workbench);
            List<IEditorPart> failed = new ArrayList<>();
            for (IEditorPart part : dirty) {
                ITextEditor text = EditorAttachManager.textEditorOf(part);
                NewFiles.Missing missing = text == null ? null : NewFiles.missingFileOf(text);
                if (missing != null && !NewFiles.save(text, part, missing, false, false, throughLink)) {
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
     * <p>When another editor shows the same document (a split, a clone, one of Eclipse's split
     * halves), Eclipse is not asked about saving: the changes stay in the other editor, as Vim's
     * {@code :q} in one of two windows on a buffer closes that window without a word. Eclipse would
     * ask, since each editor counts as a thing to save of its own. Not for a multi-page editor,
     * which may hold other documents too ({@link #isKeptByAnotherEditor}).
     *
     * <p>Closing one of Eclipse's split halves leaves the focus outside the editor that stays; it
     * goes back to the editor in front, so the next key reaches Neovim.
     *
     * @param force {@code true} closes without prompting to save (discarding changes);
     *              {@code false} lets Eclipse ask
     */
    static void close(ITextEditor editor, boolean force) {
        try {
            IWorkbenchPage page = editor.getEditorSite().getPage();
            if (page != null) {
                IEditorPart part = EditorAttachManager.getInstance().workbenchPartOf(editor);
                page.closeEditor(part, !force && !isKeptByAnotherEditor(page, part, List.of()));
                // Once Eclipse is done: joining its halves again happens after the close returns.
                Display display = Display.getCurrent();
                if (display != null) {
                    display.asyncExec(() -> focusActiveEditor(page));
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Eclipse close failed", e);
        }
    }

    /**
     * Whether closing {@code closing} without Eclipse's question loses nothing: it is a plain text
     * editor (one document) and an editor that stays open shows that document too, so the changes
     * stay there. Editors in {@code closingToo} close as well and keep nothing: two clones closed
     * together by {@code :tabonly} are asked about. A multi-page editor (the plug-in manifest
     * editor, the POM editor) may hold more documents than its text page's, so Eclipse asks for it
     * as always. The one rule for {@code :q}, {@code :tabclose N} and {@code :tabonly}.
     *
     * <p>Two editors on one file share one document (the file buffer behind every text editor's
     * document provider), so the documents are compared as objects. An editor Eclipse has not made
     * yet (a tab never shown since the start) shows none.
     */
    static boolean isKeptByAnotherEditor(IWorkbenchPage page, IEditorPart closing, Collection<IEditorPart> closingToo) {
        ITextEditor text = EditorAttachManager.textEditorOf(closing);
        IDocument document = text == closing ? documentOf(text) : null;
        if (document == null) {
            return false;
        }
        for (IEditorReference reference : page.getEditorReferences()) {
            IEditorPart open = reference.getEditor(false);
            if (open != null && open != closing && !closingToo.contains(open)) {
                ITextEditor other = EditorAttachManager.textEditorOf(open);
                if (other != null && documentOf(other) == document) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The document a text editor shows, or {@code null}. */
    private static IDocument documentOf(ITextEditor text) {
        return text.getDocumentProvider() == null ? null : text.getDocumentProvider().getDocument(text.getEditorInput());
    }

    /**
     * Gives the keyboard focus to the editor in front, when no control has it or the editor is the
     * active part without its text having it. A view Eclipse made active instead keeps it. Called
     * after {@link #close} (a closed split half leaves the focus on nothing), after {@code :only},
     * and once one of Eclipse's split halves has opened ({@link EditorSplits#opened}: on macOS the
     * first half stays active and the new one only opens, which leaves the focus on nothing too).
     */
    static void focusActiveEditor(IWorkbenchPage page) {
        IEditorPart next = page.getActiveEditor();
        if (next == null) {
            return;
        }
        Control focus = Display.getCurrent().getFocusControl();
        boolean nowhere = focus == null;
        boolean notInItsText = page.getActivePart() == next && focus != next.getAdapter(Control.class);
        if (nowhere || notInItsText) {
            page.activate(next);
            next.setFocus();
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
     *   <li>With a file name: opens it. The name is the one Neovim's {@code :edit} would open,
     *       relative to the window's folder ({@link EditPath}, {@link #windowFolder}). A file that
     *       does not exist opens as a new, empty editor, created by the first {@code :w}
     *       ({@link NewFiles}).</li>
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
        // Usually already a full path: Neovim expanded the name as its own :edit would (see EditPath).
        // Messages name it as Neovim would, relative to the window's folder ("new.txt" [New]).
        Path home = Path.of(System.getProperty("user.home"));
        Path folder = windowFolder(editor);
        Path file = EditPath.resolve(argument, home, folder);
        if (file == null) {
            HostCommands.showError(editor, "Can't find file \"" + argument + "\"");
            return;
        }
        String shown = EditPath.shown(file, home, folder);
        // A dangling symbolic link is missing too (Files.exists follows links): it opens as a new
        // file, as in Vim, and its write needs ! (DanglingLinks, NewFiles.save).
        if (!Files.exists(file)) {
            NewFiles.open(editor.getEditorSite().getPage(), file, shown);
            return;
        }
        if (Files.isDirectory(file)) {
            HostCommands.showError(editor, "\"" + shown + "\" is a directory");
            return;
        }
        open(editor.getEditorSite().getPage(), file);
    }

    /** The wizard behind <i>File &gt; New &gt; Untitled Text File</i> (org.eclipse.ui.editors). */
    static final String UNTITLED_TEXT_FILE_WIZARD = "org.eclipse.ui.editors.wizards.UntitledTextFileWizard";

    /**
     * The untitled editors {@code :enew} opened: their Neovim window keeps the folder of the window
     * it opens from ({@link NeovimEditorController}'s {@code windowFolder}). UI thread.
     */
    static final EnewEditors ENEW_EDITORS = new EnewEditors();

    /**
     * {@code :enew}: opens a new untitled editor, as <i>File &gt; New &gt; Untitled Text File</i>
     * does, which works in {@code editor}'s folder ({@link EnewEditors}). The wizard has no pages and
     * may finish at once, so it is finished without a dialog, as Eclipse's own shortcut to it does:
     * created from the workbench's wizard registry (public API), initialized, finished, disposed.
     * The new editor is then the active one, and Neovim attaches to it as to any editor; its
     * window's alternate file is {@code editor}'s file, so {@code Ctrl-^} goes back.
     */
    static void newUntitled(ITextEditor editor) {
        IWorkbenchWindow window = editor.getSite().getWorkbenchWindow();
        IWizardDescriptor descriptor = PlatformUI.getWorkbench().getNewWizardRegistry()
                .findWizard(UNTITLED_TEXT_FILE_WIZARD);
        if (descriptor == null) {
            HostCommands.showError(editor, "Cannot open an untitled editor: this Eclipse has no Untitled Text File wizard");
            return;
        }
        IWorkbenchWizard wizard = null;
        try {
            wizard = descriptor.createWizard();
            wizard.init(PlatformUI.getWorkbench(), StructuredSelection.EMPTY);
            ENEW_EDITORS.opening();
            boolean finished;
            try {
                finished = wizard.performFinish();
            } finally {
                ENEW_EDITORS.opened();
            }
            IEditorPart opened = window.getActivePage() == null ? null : window.getActivePage().getActiveEditor();
            if (finished && opened != null && opened != EditorAttachManager.getInstance().workbenchPartOf(editor)) {
                ENEW_EDITORS.add(opened.getEditorInput()); // in case Neovim attaches to it only now
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not open an untitled editor", e);
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            HostCommands.showError(editor, "Could not open an untitled editor: " + reason);
        } finally {
            if (wizard != null) {
                wizard.dispose();
            }
        }
    }

    /**
     * The folder Neovim's window works in for this editor ({@code :lcd}, set when its window opens): the
     * project's root, or the file's own folder for a file outside any project; {@code null} for a
     * document without a file outside any project. A relative {@code :e} or {@code :w} name is
     * relative to it, as in Neovim (see {@link EditPath}).
     */
    static Path windowFolder(ITextEditor editor) {
        Path root = projectRootOf(editor.getEditorInput());
        if (root != null) {
            return root;
        }
        Path current = EditorAttachManager.fileOf(editor.getEditorInput());
        return current == null ? null : current.getParent();
    }

    /**
     * Opens {@code file} in an editor inside Eclipse: through the workspace if it belongs to a
     * project (so that the editor knows its project, builders, markers), else as an outside file.
     *
     * <p><b>Never a program outside Eclipse.</b> Eclipse's default lookup picks the system's
     * program for a file type it has no editor for, and opening it runs that program on the file:
     * on Windows {@code :e build.bat} of a cloned repository's script would run it (0.2.1 security
     * review). So the editor is the file's own internal editor (its "Open With" choice, its content
     * type, its name), or the Text Editor when that is external or in-place, or not for text (an
     * {@code svg}'s Web Browser editor would run its script), as gf and quickfix jumps do
     * ({@code ProjectFileEditors}) and a new file does ({@link NewFiles#internalOr}).
     */
    private static void open(IWorkbenchPage page, Path file) {
        open(page, file, IWorkbenchPage.MATCH_INPUT);
    }

    /**
     * {@link #open(IWorkbenchPage, Path)} with Eclipse's rule for reusing an editor: with
     * {@code MATCH_INPUT} an editor that has the file open already comes to the front, as for
     * {@code :e}; with {@code MATCH_NONE} the file gets a new editor even then, as a Vim window of
     * its own does ({@code :split other.txt}; see {@link EditorSplits}).
     *
     * @return the editor that shows the file, or {@code null} if it could not be opened (logged)
     */
    static IEditorPart open(IWorkbenchPage page, Path file, int matchFlags) {
        try {
            IFile candidate = NewFiles.workspaceFileAt(file);
            if (candidate != null && candidate.isAccessible()) {
                // By the file's content type as its project has it, not by its name alone (NewFiles.internalOr).
                String editorId = NewFiles.internalOr(IDE.getEditorDescriptor(candidate, true, false), candidate);
                return page.openEditor(new FileEditorInput(candidate), editorId, true, matchFlags);
            }
            return page.openEditor(new FileStoreEditorInput(EFS.getLocalFileSystem().getStore(file.toUri())),
                    NewFiles.editorIdFor(file.getFileName().toString()), true, matchFlags);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not open " + file, e);
            return null;
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
