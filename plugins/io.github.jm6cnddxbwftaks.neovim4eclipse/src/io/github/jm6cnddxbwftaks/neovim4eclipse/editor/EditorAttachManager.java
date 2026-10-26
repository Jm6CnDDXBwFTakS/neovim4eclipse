// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPropertyListener;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IPathEditorInput;
import org.eclipse.ui.IReusableEditor;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.MultiPageEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.DocumentBuffer;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.DocumentWrite;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.EditorBuffers;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.sync.UnattachedDocumentSync;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.BufferRefs;

/**
 * Manages attach/detach of Neovim to text editors, including automatic attach
 * when a document is opened or activated.
 *
 * <p>Multi-page editors (m2e's POM editor, PDE's manifest/plugin.xml/feature editors) are not
 * {@link ITextEditor}s themselves; their source tab is a nested one. Neovim attaches while that tab
 * is selected and detaches when the user switches to a form page.
 *
 * <p>Each open document has its own Neovim buffer (named after the file), kept while any editor on
 * it is open, so undo history and marks survive switching editors. When Neovim itself switches to
 * another document's buffer (Ctrl-O across files, {@code 'A}, {@code :b}), Eclipse follows by
 * activating that document's editor (only the last one, when a command such as {@code :bufdo}
 * walks through many). Which editors share, find and give up a buffer is decided by
 * {@link EditorBuffers} (unit-tested without Eclipse); this class adds the workbench glue.
 *
 * <p>Neovim can also change documents that are not attached ({@code :bufdo}), and Eclipse can change
 * documents whose editor is in the background (a rename refactoring). An
 * {@link UnattachedDocumentSync} per document with a buffer carries those changes across. When
 * Neovim writes a document's buffer ({@code :bufdo update}), Eclipse saves the document
 * ({@link #saveForNeovim}).
 */
public final class EditorAttachManager {

    private static final Logger LOG = Logger.getLogger(EditorAttachManager.class.getName());

    private static final EditorAttachManager INSTANCE = new EditorAttachManager();

    /** How long Neovim waits for Eclipse to save a document whose buffer it writes; see {@link #saveForNeovim}. */
    static final long SAVE_TIMEOUT_MS = 30_000;

    /** Guarded by {@code this}. At most one editor is attached at a time (Neovim has one window). */
    private NeovimEditorController activeController;
    /** Guarded by {@code this}. The workbench part attached to: the text editor or its multi-page host. */
    private IEditorPart attachedPart;
    /** UI thread only. Multi-page editors already given a page-change listener. */
    private final Set<MultiPageEditorPart> watchedMultiPageEditors =
            Collections.newSetFromMap(new WeakHashMap<>());
    /** Guarded by {@code this}. The Neovim buffer of every document opened while attached, with its editors. */
    private final EditorBuffers<IDocument, IEditorPart> documents = new EditorBuffers<>();
    /** Guarded by {@code this}. The session whose buffer switches are followed. */
    private NeovimSession followedSession;
    /**
     * Guarded by {@code this}. Keeps each document with a buffer in {@link #followedSession} in step
     * with it while it is not attached; created on the document's first attach.
     */
    private final Map<DocumentBuffer, UnattachedDocumentSync> unattachedSyncs = new HashMap<>();
    /** Guarded by {@code this}. The buffer of the attached document, or {@code null}. */
    private DocumentBuffer attachedBuffer;
    private volatile boolean autoAttach = true;
    /**
     * An editor's dirty flag changed (it was saved, reverted, or edited): Neovim's {@code 'modified'}
     * for its document follows, so {@code :update} writes what Eclipse would save. UI thread.
     */
    private final IPropertyListener dirtyListener = (source, propId) -> {
        if (propId == IEditorPart.PROP_DIRTY && source instanceof IEditorPart part) {
            onDirtyChanged(part);
        }
    };
    /** Guarded by {@code this}. */
    private boolean listenersRegistered;
    private final IPartListener2 partListener = new IPartListener2() {
        @Override
        public void partOpened(IWorkbenchPartReference partRef) {
            maybeAutoAttach(partRef);
        }

        @Override
        public void partActivated(IWorkbenchPartReference partRef) {
            maybeAutoAttach(partRef);
            ToggleButtonRefresh.request(); // the button is about the editor in front, Neovim or not
        }

        @Override
        public void partClosed(IWorkbenchPartReference partRef) {
            if (partRef instanceof IEditorReference eref) {
                IEditorPart closed = eref.getEditor(false);
                if (closed == null) {
                    return;
                }
                if (isAttachedTo(closed)) {
                    // Re-checked later: by then the next editor may already be attached, and stays so.
                    Display.getDefault().asyncExec(() -> {
                        if (isAttachedTo(closed)) {
                            detachQuietly();
                        }
                    });
                }
                releaseDocumentsOf(closed);
                ToggleButtonRefresh.request(); // the last editor closed: "no editor open"
            }
        }

        @Override public void partBroughtToTop(IWorkbenchPartReference partRef) { }
        @Override public void partDeactivated(IWorkbenchPartReference partRef) { }
        @Override public void partHidden(IWorkbenchPartReference partRef) { }
        @Override public void partVisible(IWorkbenchPartReference partRef) { }
        @Override public void partInputChanged(IWorkbenchPartReference partRef) { }
    };

    private EditorAttachManager() {
    }

    public static EditorAttachManager getInstance() {
        return INSTANCE;
    }

    /** Redraws the status bar of the attached editor (a status bar preference changed). */
    public void refreshStatusLine() {
        NeovimEditorController controller = getActiveController();
        if (controller != null) {
            controller.refreshStatusLine();
        }
    }

    /** Re-applies the Ctrl-key choices to the attached editor (a Ctrl-key preference changed). */
    public void refreshKeyContext() {
        NeovimEditorController controller = getActiveController();
        if (controller != null) {
            controller.refreshKeyContext();
        }
    }

    public synchronized NeovimEditorController getActiveController() {
        return activeController;
    }

    public void setAutoAttach(boolean autoAttach) {
        this.autoAttach = autoAttach;
    }

    public boolean isAutoAttach() {
        return autoAttach;
    }

    /**
     * Register workbench part listeners (idempotent). Call once the workbench is up.
     */
    public void installWorkbenchListeners() {
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null) {
            return;
        }
        // Called from both Activator.start and EarlyStartup; claim the registration atomically.
        synchronized (this) {
            if (listenersRegistered) {
                return;
            }
            listenersRegistered = true;
        }
        workbench.getDisplay().asyncExec(() -> {
            for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
                addPartListener(window);
            }
            workbench.addWindowListener(new IWindowListener() {
                @Override
                public void windowOpened(IWorkbenchWindow window) {
                    addPartListener(window);
                }

                @Override
                public void windowClosed(IWorkbenchWindow window) { }

                @Override
                public void windowActivated(IWorkbenchWindow window) { }

                @Override
                public void windowDeactivated(IWorkbenchWindow window) { }
            });
            LOG.info("EditorAttachManager: auto-attach listeners installed");

            attachActiveEditor();
        });
    }

    private void addPartListener(IWorkbenchWindow window) {
        for (IWorkbenchPage page : window.getPages()) {
            page.addPartListener(partListener);
        }
    }

    /**
     * Attaches to the currently active text editor, if any (no dialog). Must run on the UI thread.
     */
    public void attachActiveEditor() {
        IWorkbench workbench = PlatformUI.getWorkbench();
        IWorkbenchWindow window = workbench == null ? null : workbench.getActiveWorkbenchWindow();
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        IEditorPart editor = page == null ? null : page.getActiveEditor();
        watchPageChanges(editor);
        if (textEditorOf(editor) != null) {
            try {
                attach(editor, false);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Auto-attach to active editor failed", e);
            }
        }
    }

    private void maybeAutoAttach(IWorkbenchPartReference partRef) {
        if (!autoAttach) {
            return;
        }
        if (!(partRef instanceof IEditorReference eref)) {
            return;
        }
        Display.getDefault().asyncExec(() -> {
            try {
                IEditorPart editor = eref.getEditor(false);
                watchPageChanges(editor);
                if (textEditorOf(editor) != null) {
                    attach(editor, false);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Auto-attach failed for " + partRef.getTitle(), e);
            }
        });
    }

    /**
     * The text editor to drive for a workbench editor: the editor itself, or the selected page of a
     * multi-page editor when that page is a text editor. {@code null} otherwise.
     */
    static ITextEditor textEditorOf(IEditorPart editor) {
        if (editor instanceof ITextEditor textEditor) {
            return textEditor;
        }
        if (editor instanceof MultiPageEditorPart multiPage
                && multiPage.getSelectedPage() instanceof ITextEditor textEditor) {
            return textEditor;
        }
        return null;
    }

    /** Follows tab switches in a multi-page editor (idempotent). Must run on the UI thread. */
    private void watchPageChanges(IEditorPart editor) {
        if (editor instanceof MultiPageEditorPart multiPage && watchedMultiPageEditors.add(multiPage)) {
            multiPage.addPageChangedListener(event -> onPageChanged(multiPage));
        }
    }

    private void onPageChanged(MultiPageEditorPart multiPage) {
        try {
            if (textEditorOf(multiPage) == null) {
                if (isAttachedTo(multiPage)) {
                    detachQuietly(); // switched to a form page: hand keys back to Eclipse
                }
            } else if (autoAttach) {
                attach(multiPage, false);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Attach after page change failed for " + multiPage.getTitle(), e);
        }
        ToggleButtonRefresh.request(); // a text page or a form page: the button's tooltip differs
    }

    /**
     * The workbench part that owns {@code editor}: its multi-page host when attached through one,
     * else the editor itself. Save and close must go through this part, since a nested page is not
     * a workbench part of its own.
     */
    synchronized IEditorPart workbenchPartOf(ITextEditor editor) {
        if (activeController != null && activeController.getEditor() == editor && attachedPart != null) {
            return attachedPart;
        }
        return editor;
    }

    private synchronized boolean isAttachedTo(IEditorPart editor) {
        return activeController != null && editor.equals(attachedPart);
    }

    /**
     * Attaches Neovim to the given editor (detaching any other one first). Preserves the Eclipse
     * caret as the Neovim cursor. Attaching the already-attached editor is a no-op.
     *
     * @param manual {@code true} when triggered by the user from the menu (currently informational)
     */
    public NeovimEditorController attach(IEditorPart editor, boolean manual) throws Exception {
        return attach(editor, manual, false);
    }

    /** @param takeNeovimCursor Neovim jumped into this editor's document: keep Neovim's cursor */
    private synchronized NeovimEditorController attach(IEditorPart editor, boolean manual,
            boolean takeNeovimCursor) throws Exception {
        ITextEditor textEditor = textEditorOf(editor);
        if (textEditor == null) {
            throw new IllegalArgumentException("Not a text editor (for a multi-page editor, select its "
                    + "source tab): " + editor.getTitle());
        }

        // Already attached to this editor
        if (activeController != null && activeController.getEditor().equals(textEditor)
                && !activeController.isClosed()) {
            return activeController;
        }

        detachQuietly();

        NeovimSession session;
        try {
            session = Activator.getDefault().getOrCreateSession();
            StartFailureNotice.clear();
            WelcomeNotice.afterStart(); // the welcome, once per installation (and per minor version)
            StartupErrorNotice.afterStart(session); // errors in init.lua, once per Neovim
        } catch (Exception e) {
            StartFailureNotice.report(Activator.getDefault().getNvimExecutable(), e);
            throw e;
        }
        if (session != followedSession) { // first attach, or Neovim was restarted
            followedSession = session;
            session.setBufferEnterHandler(this::onNeovimEnteredBuffer);
            session.setBufferClosedHandler(this::onNeovimClosedBuffer);
            session.setWriteHandler(this::saveForNeovim);
            // A new Neovim has none of the old buffers; each document gets a new one on its next attach.
            unattachedSyncs.values().forEach(UnattachedDocumentSync::close);
            unattachedSyncs.clear();
        }
        IDocument document = documentOf(textEditor);
        if (document == null) {
            throw new IllegalStateException("Editor has no document: " + editor.getTitle());
        }
        DocumentBuffer buffer = documents.add(document, editor, () -> bufferName(textEditor.getEditorInput()));
        // Neovim's 'modified' follows the editor's dirty flag, from the first load on.
        editor.addPropertyListener(dirtyListener); // once per editor: a listener list holds it once
        session.setModifiedAsync(buffer, editor.isDirty());
        UnattachedDocumentSync sync = unattachedSyncs.computeIfAbsent(buffer, b -> {
            UnattachedDocumentSync created = new UnattachedDocumentSync(document, b, session, SwtUiExecutor.INSTANCE);
            created.start();
            return created;
        });
        sync.setAttached(true); // before the controller's synchronizer takes over
        attachedBuffer = buffer;
        activeController = NeovimEditorController.attach(textEditor, session, buffer, takeNeovimCursor);
        attachedPart = editor;
        ToggleButtonRefresh.request(); // the toolbar button shows this editor's mode
        LOG.fine("Attached Neovim to: " + textEditor.getTitle());
        return activeController;
    }

    /**
     * Neovim switched by itself to the buffer of another document (called on an RPC thread). Shows
     * that document's editor, or sends Neovim back if no editor can show it.
     */
    private void onNeovimEnteredBuffer(int bufnr) {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed()) {
            display.asyncExec(() -> followNeovimInto(bufnr));
        }
    }

    private void followNeovimInto(int bufnr) {
        NeovimSession session;
        Optional<EditorBuffers.Showing<IDocument, IEditorPart>> showing;
        synchronized (this) {
            session = followedSession;
            if (session == null) {
                return;
            }
            if (bufnr == session.getAttachedBufnr()) {
                return; // Eclipse switched there itself in the meantime
            }
            if (bufnr != session.getEnteredBufnr()) {
                // Neovim has entered another buffer since, whose own follow-up is queued behind this
                // one. :bufdo, :argdo and :cdo enter every buffer in turn; following each would flick
                // through all their editors (and attach to each), so only the last one is followed,
                // and Eclipse ends where Vim ends.
                return;
            }
            showing = documents.editorShowing(bufnr, buffer -> buffer.isOpenIn(session));
        }
        try {
            if (showing.isPresent()) {
                IEditorPart target = showing.get().editor();
                target.getSite().getPage().activate(target);
                ITextEditor textEditor = textEditorOf(target);
                // A multi-page editor may open on a form page: no text editor to follow into.
                if (textEditor != null && documentOf(textEditor) == showing.get().document()) {
                    attach(target, false, true);
                    return;
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not follow Neovim into buffer " + bufnr, e);
        }
        session.returnToAttachedBufferAsync();
    }

    /**
     * {@code :bd 3 other.txt}: closes the editors of the named buffers, as {@code :bd} alone closes
     * the current one ({@code HostCommand.Kind.BUFFER_DELETE}). A number is a buffer number; a
     * name is looked up in Neovim with {@code bufnr()} ({@link BufferRefs}), asynchronously, so its
     * editor closes a moment later. A buffer no editor shows gets Vim's error in {@code from}'s
     * message line. With two editors on one file (Window > New Editor), the first is closed. UI
     * thread.
     *
     * @param from    the editor the command was typed in, for error messages
     * @param buffers the arguments as typed: {@code [3, other.txt]}
     * @param force   {@code :bd!}: close without asking about changes
     */
    void closeBuffers(ITextEditor from, List<String> buffers, boolean force) {
        NeovimSession session;
        synchronized (this) {
            session = followedSession;
        }
        Display display = Display.getCurrent();
        for (String ref : buffers) {
            if (session == null || display == null) {
                HostCommands.showError(from, BufferRefs.notFoundMessage(ref));
            } else if (BufferRefs.isNumber(ref)) {
                closeBufferEditor(session, Integer.parseInt(ref), ref, force, from);
            } else {
                // The answer comes on a session thread; the closing belongs on the UI thread.
                session.evalAsync(BufferRefs.bufnrExpression(ref), value -> {
                    if (!display.isDisposed()) {
                        display.asyncExec(() -> closeBufferEditor(session, BufferRefs.parseBufnr(value), ref,
                                force, from));
                    }
                });
            }
        }
    }

    /**
     * Neovim deleted buffer {@code bufnr}, or quit its window, by itself ({@code <Cmd>bd},
     * {@code vim.cmd.quit()}, {@code :bufdo bd}): closes the editor showing it, asking about changes
     * as {@code :q} does. Nothing happens for a buffer no editor shows, such as one this class wiped
     * after its last editor closed. RPC thread; the closing is done on the UI thread.
     */
    private void onNeovimClosedBuffer(int bufnr) {
        NeovimSession session;
        synchronized (this) {
            session = followedSession;
        }
        Display display = Display.getDefault();
        if (session == null || display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            Optional<EditorBuffers.Showing<IDocument, IEditorPart>> showing;
            synchronized (this) {
                showing = session != followedSession ? Optional.empty()
                        : documents.editorShowing(bufnr, buffer -> buffer.isOpenIn(session));
            }
            showing.ifPresent(s -> s.editor().getSite().getPage().closeEditor(s.editor(), true));
        });
    }

    /** Closes the editor showing buffer {@code bufnr}, or shows {@code ref}'s error. UI thread. */
    private void closeBufferEditor(NeovimSession session, int bufnr, String ref, boolean force, ITextEditor from) {
        Optional<EditorBuffers.Showing<IDocument, IEditorPart>> showing;
        synchronized (this) {
            showing = bufnr <= 0 || session != followedSession ? Optional.empty()
                    : documents.editorShowing(bufnr, buffer -> buffer.isOpenIn(session));
        }
        if (showing.isEmpty()) {
            HostCommands.showError(from, BufferRefs.notFoundMessage(ref));
            return;
        }
        IEditorPart part = showing.get().editor();
        part.getSite().getPage().closeEditor(part, !force);
    }

    /**
     * Neovim writes a document's buffer ({@code :update}, {@code :bufdo update}, {@code :wall}, a
     * mapping's {@code :w}; see {@code core/LuaScripts.DOCUMENT_BUFFER}). Called on the RPC thread
     * while Neovim waits for the answer; saves on the UI thread.
     *
     * <p>The wait is bounded ({@value #SAVE_TIMEOUT_MS} ms), like the clipboard paste's
     * ({@link ClipboardBridge}): were the UI thread ever waiting for Neovim, the two would otherwise
     * wait for each other forever. It is long because a save may ask a question (a read-only file, a
     * file changed on disk). A save that takes longer still finishes; Neovim is told it did not, and
     * leaves the buffer modified.
     *
     * @param createFolders {@code :w ++p}: a new file's missing folders may be created
     * @return the written message, or the error that fails the write
     */
    private DocumentWrite.Result saveForNeovim(DocumentBuffer buffer, boolean createFolders) {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return DocumentWrite.Result.failed("Eclipse is shutting down; " + buffer.name() + " was not saved");
        }
        CompletableFuture<DocumentWrite.Result> result = new CompletableFuture<>();
        display.asyncExec(() -> {
            try {
                result.complete(saveOnUi(buffer, createFolders));
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        try {
            return result.get(SAVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOG.warning("Eclipse did not save " + buffer.name() + " within " + SAVE_TIMEOUT_MS + " ms; Neovim was told so");
            return DocumentWrite.Result.failed("Eclipse did not finish saving " + buffer.name()
                    + " in time; check whether it was saved");
        } catch (ExecutionException e) {
            LOG.log(Level.WARNING, "Saving " + buffer.name() + " for Neovim failed", e.getCause());
            return DocumentWrite.Result.failed("Eclipse could not save " + buffer.name() + " (see the Error Log)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DocumentWrite.Result.failed("Interrupted; " + buffer.name() + " may not have been saved");
        }
    }

    /**
     * Saves {@code buffer}'s document through the first editor open on it, as {@code :w} does: a file
     * that does not exist yet is created ({@code NewFiles}, which shows its own {@code [New]}
     * message), any other is saved by Eclipse, and Vim's written message is the answer. UI thread.
     */
    private DocumentWrite.Result saveOnUi(DocumentBuffer buffer, boolean createFolders) {
        Optional<EditorBuffers.Showing<IDocument, IEditorPart>> showing;
        synchronized (this) {
            NeovimSession session = followedSession;
            showing = session == null ? Optional.empty()
                    : documents.editorShowing(buffer.bufnr(), b -> b == buffer && b.isOpenIn(session));
        }
        if (showing.isEmpty()) {
            return DocumentWrite.Result.failed("Eclipse has no editor open on " + buffer.name() + "; nothing was saved");
        }
        IEditorPart part = showing.get().editor();
        ITextEditor text = textEditorOf(part);
        if (text == null) {
            return EclipseFileCommands.savePart(part) ? DocumentWrite.Result.saved("")
                    : DocumentWrite.Result.failed("Eclipse could not save " + part.getTitle() + " (see the Error Log)");
        }
        boolean created = NewFiles.missingFileOf(text) != null;
        // Even if unchanged: Neovim asks only for :w, or for :update of a modified buffer, and
        // 'modified' follows Eclipse's dirty flag; so this is a write Vim would make.
        if (!EclipseFileCommands.save(text, createFolders, true)) {
            // No "see the Error Log": usually Eclipse has already said why in a dialog (a read-only
            // file whose "make writable?" was answered No), and the log has nothing more.
            return DocumentWrite.Result.failed("Eclipse could not save " + part.getTitle());
        }
        return DocumentWrite.Result.saved(created ? "" : EclipseFileCommands.writtenMessage(text));
    }

    /**
     * Gives {@code editor} a new input for the same file while keeping its Neovim buffer, and with
     * it Neovim's undo history, marks and buffer number. UI thread.
     *
     * <p>Why it is needed: Eclipse reads the new input into a new {@link IDocument}, and buffers are
     * kept per document ({@link EditorBuffers}). Without the move the next attach would find no
     * buffer for the new document and make a fresh one, as if the file had just been opened.
     *
     * <p>The steps: stop listening to the old document (the controller and the document's
     * {@link UnattachedDocumentSync}), set the input, move the buffer to the new document
     * ({@link EditorBuffers#move}), and attach again if the editor was attached, with the caret
     * following Neovim's cursor, which did not move. The text is the same, so the attach's push
     * changes nothing in Neovim (it sends only the lines that differ).
     *
     * <p>Example: a new file's editor is opened by location; after its first {@code :w} it is
     * switched to the workspace file ({@link NewFiles#save}).
     *
     * @return {@code false}, with the input unchanged, if the editor cannot take a new input or
     *         another editor shows the same document (it would be left on a document without a
     *         buffer)
     */
    boolean replaceInputKeepingBuffer(ITextEditor editor, IEditorInput input) throws Exception {
        if (!(editor instanceof IReusableEditor reusable)) {
            return false;
        }
        IDocument old = documentOf(editor);
        boolean wasAttached;
        synchronized (this) {
            if (old != null && documents.isSharedWithAnotherEditor(old, editor)) {
                return false;
            }
            wasAttached = isAttachedTo(editor);
            if (wasAttached) {
                activeController.close();
                activeController = null;
                attachedPart = null;
                attachedBuffer = null;
            }
        }
        reusable.setInput(input); // outside the lock: it notifies listeners
        IDocument fresh = documentOf(editor);
        synchronized (this) {
            if (old != null && fresh != null && fresh != old) {
                DocumentBuffer buffer = documents.move(old, fresh);
                UnattachedDocumentSync sync = buffer == null ? null : unattachedSyncs.remove(buffer);
                if (sync != null) {
                    sync.close(); // listens to the old document; the attach below makes one for the new
                }
            }
        }
        if (wasAttached) {
            attach(editor, false, true);
        }
        return true;
    }

    /** Tells Neovim whether {@code part}'s document has unsaved changes. UI thread. */
    private void onDirtyChanged(IEditorPart part) {
        ITextEditor text = textEditorOf(part); // a multi-page editor's text page too
        IDocument document = text == null ? null : documentOf(text);
        Optional<DocumentBuffer> buffer;
        NeovimSession session;
        synchronized (this) {
            session = followedSession;
            buffer = document == null ? Optional.empty() : documents.bufferOf(document);
        }
        if (session != null && buffer.isPresent() && buffer.get().isOpenIn(session)) {
            session.setModifiedAsync(buffer.get(), part.isDirty());
        }
    }

    /** Forgets {@code part}; the buffer of a document whose last editor closed is wiped in Neovim. */
    private void releaseDocumentsOf(IEditorPart part) {
        part.removePropertyListener(dirtyListener);
        List<DocumentBuffer> unused;
        NeovimSession session;
        synchronized (this) {
            session = followedSession;
            unused = documents.remove(part);
            for (DocumentBuffer buffer : unused) {
                UnattachedDocumentSync sync = unattachedSyncs.remove(buffer);
                if (sync != null) {
                    sync.close();
                }
            }
        }
        if (session != null && session.isAlive()) {
            unused.forEach(session::closeBufferAsync);
        }
    }

    private static IDocument documentOf(ITextEditor editor) {
        return editor.getDocumentProvider() == null ? null
                : editor.getDocumentProvider().getDocument(editor.getEditorInput());
    }

    /**
     * Name for a document's Neovim buffer: the file's absolute path when there is one (shown by
     * {@code :ls} and {@code %f}), else the editor input's name.
     */
    static String bufferName(IEditorInput input) {
        if (input == null) {
            return "";
        }
        Path file = fileOf(input);
        if (file != null) {
            return file.toString();
        }
        if (input instanceof IURIEditorInput uriInput && uriInput.getURI() != null) {
            return uriInput.getURI().toString(); // a remote or virtual file: no local path
        }
        return input.getName();
    }

    /**
     * The local file an editor input shows, or {@code null} (an untitled document, a remote file, a
     * class file inside a jar). Workspace files are resolved to their location on disk.
     */
    static Path fileOf(IEditorInput input) {
        if (input == null) {
            return null;
        }
        try {
            IFile file = input.getAdapter(IFile.class);
            if (file != null && file.getLocation() != null) {
                return Path.of(file.getLocation().toOSString());
            }
            if (input instanceof IPathEditorInput pathInput) {
                return Path.of(pathInput.getPath().toOSString());
            }
            if (input instanceof IURIEditorInput uriInput && uriInput.getURI() != null
                    && "file".equals(uriInput.getURI().getScheme())) {
                return Path.of(uriInput.getURI());
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "No path for editor input " + input.getName(), e);
        }
        return null;
    }

    public synchronized void detachQuietly() {
        if (activeController != null) {
            try {
                activeController.close();
            } catch (Exception e) {
                LOG.log(Level.FINE, "Error closing controller", e);
            }
            activeController = null;
        }
        attachedPart = null;
        if (attachedBuffer != null) {
            UnattachedDocumentSync sync = unattachedSyncs.get(attachedBuffer);
            if (sync != null) {
                sync.setAttached(false); // after the synchronizer closed: from now on this one pushes
            }
            attachedBuffer = null;
        }
    }

    public synchronized void detach() {
        detachQuietly();
        LOG.info("Detached Neovim");
    }
}
