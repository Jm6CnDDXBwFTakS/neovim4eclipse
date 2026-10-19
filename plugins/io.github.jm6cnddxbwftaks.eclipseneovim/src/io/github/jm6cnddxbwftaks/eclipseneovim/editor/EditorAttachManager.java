// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IPathEditorInput;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.MultiPageEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.eclipseneovim.Activator;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.DocumentBuffer;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.EditorBuffers;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.NeovimSession;

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
 */
public final class EditorAttachManager {

    private static final Logger LOG = Logger.getLogger(EditorAttachManager.class.getName());

    private static final EditorAttachManager INSTANCE = new EditorAttachManager();

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
    private volatile boolean autoAttach = true;
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
        } catch (Exception e) {
            StartFailureNotice.report(Activator.getDefault().getNvimExecutable(), e);
            throw e;
        }
        if (session != followedSession) { // first attach, or Neovim was restarted
            followedSession = session;
            session.setBufferEnterHandler(this::onNeovimEnteredBuffer);
        }
        IDocument document = documentOf(textEditor);
        if (document == null) {
            throw new IllegalStateException("Editor has no document: " + editor.getTitle());
        }
        DocumentBuffer buffer = documents.add(document, editor, () -> bufferName(textEditor.getEditorInput()));
        activeController = NeovimEditorController.attach(textEditor, session, buffer, takeNeovimCursor);
        attachedPart = editor;
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

    /** Forgets {@code part}; the buffer of a document whose last editor closed is wiped in Neovim. */
    private void releaseDocumentsOf(IEditorPart part) {
        List<DocumentBuffer> unused;
        NeovimSession session;
        synchronized (this) {
            session = followedSession;
            unused = documents.remove(part);
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
    }

    public synchronized void detach() {
        detachQuietly();
        LOG.info("Detached Neovim");
    }
}
