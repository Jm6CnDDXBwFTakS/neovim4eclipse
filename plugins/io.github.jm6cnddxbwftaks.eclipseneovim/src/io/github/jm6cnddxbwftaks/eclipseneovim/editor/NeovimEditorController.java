// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.events.FocusListener;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.eclipseneovim.Activator;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.CursorPos;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.DocumentBuffer;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.NeovimSession;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.VisualSelection;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.StatusInfo;
import io.github.jm6cnddxbwftaks.eclipseneovim.sync.BufferSynchronizer;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.CommandLine;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.InsertReplay;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.VimCtrlKeys;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.VimModes;

/**
 * Binds one {@link ITextEditor} to a {@link NeovimSession}.
 * Neovim owns keys; Eclipse shows mode status, caret style, visual selection, and buffer text.
 *
 * <h2>Columns</h2>
 * Neovim columns are UTF-8 <em>byte</em> offsets; Eclipse columns are UTF-16 chars. Every
 * conversion between the two goes through {@link DocumentPositions}; never pass a Neovim column
 * straight to an Eclipse offset.
 *
 * <h2>Threading</h2>
 * Constructed and closed on the UI thread. Session callbacks arrive on other threads and hop to
 * the UI thread before touching SWT.
 */
public final class NeovimEditorController implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(NeovimEditorController.class.getName());

    /**
     * Key-binding context active while Neovim owns the keys and is not in insert mode. Below it, one
     * context per Vim {@code Ctrl} key (plugin.xml) that Eclipse would otherwise consume first: for
     * example {@code Ctrl-V} is Paste in Eclipse but starts blockwise visual in Vim.
     */
    static final String KEY_CONTEXT_ID = VimCtrlKeys.NORMAL_CONTEXT_ID;

    private final ITextEditor editor;
    private final NeovimSession session;
    /** The editor's document's own Neovim buffer (shared with other editors on the same document). */
    private final DocumentBuffer buffer;
    private final StyledText styledText;
    private final KeyInterceptor keyInterceptor;
    /** Viewer the key interceptor is prepended to; {@code null} if it sits on the widget instead. */
    private final ITextViewerExtension keyViewer;
    private final BufferSynchronizer synchronizer;
    /** Remembers the text before an Eclipse-driven insert so "." can repeat what was typed. */
    private final InsertTracker insertTracker;
    /** The inline {@code :} / {@code /} / {@code ?} command line along the bottom of the editor. */
    private final CommandLineBox commandLine;
    private final ModeStatusContribution modeStatus;
    private final CursorStyleController cursorStyle;
    /** Neovim's visual selection shown in the widget (native selection or blockwise overlay). */
    private final VisualSelectionView selectionView;
    private volatile boolean closed;
    /** Suppress mouse/caret feedback while applying Neovim cursor. */
    private volatile boolean applyingCaretFromNvim;
    /** When false, keys go to Eclipse (plain editing); Neovim session stays alive. */
    private volatile boolean neovimModeActive = true;
    /** Workbench-wide activation of {@link #KEY_CONTEXT_ID} and the Ctrl-letter contexts. UI thread only. */
    private final VimKeyContexts keyContexts = new VimKeyContexts();
    private final FocusListener focusListener = new FocusListener() {
        @Override
        public void focusGained(FocusEvent e) {
            updateKeyContext(session.getMode());
        }

        @Override
        public void focusLost(FocusEvent e) {
            updateKeyContext(session.getMode());
        }
    };

    private final Consumer<String> modeListener = this::onModeChanged;
    private final Consumer<CursorPos> cursorListener = this::onCursorChanged;
    private final Consumer<VisualSelection> selectionListener = this::onVisualSelection;
    private final Consumer<String> recordingListener = this::onRecordingChanged;
    private final Consumer<StatusInfo> statusListener = this::onStatusChanged;

    private NeovimEditorController(ITextEditor editor, NeovimSession session, StyledText styledText,
            DocumentBuffer buffer, boolean takeNeovimCursor) {
        this.editor = editor;
        this.session = session;
        this.buffer = buffer;
        this.styledText = styledText;
        this.keyInterceptor = new KeyInterceptor(session);
        this.keyInterceptor.setCtrlKeyFilter(NeovimEditorController::isCtrlKeyToNeovim);
        this.synchronizer = new BufferSynchronizer(documentOf(editor), session);
        this.synchronizer.setCaretRestorer(() -> applyCaret(session.getLastCursor()));
        this.insertTracker = new InsertTracker(documentOf(editor), this::isTrackableInsertChange);
        this.modeStatus = new ModeStatusContribution();
        this.cursorStyle = new CursorStyleController(styledText);

        // Prepend on the viewer so Neovim sees keys before the editor's own listeners: otherwise
        // content assist auto-activates on keys Neovim consumes (e.g. the "i" that enters insert
        // mode in m2e's POM editor), since a listener added to the widget runs after them.
        this.keyViewer = viewerExtensionOf(editor);
        if (keyViewer != null) {
            keyViewer.prependVerifyKeyListener(keyInterceptor);
        } else {
            styledText.addVerifyKeyListener(keyInterceptor);
        }
        this.selectionView = new VisualSelectionView(styledText, this::runAsCaretFromNeovim);
        styledText.addFocusListener(focusListener);
        this.commandLine = new CommandLineBox(styledText, session, editor);
        keyInterceptor.setSpecialKeyHandler(this::handleExOrSearchKey);
        keyInterceptor.setEditor(editor);
        keyInterceptor.setInsertReplaySupplier(this::consumeInsertReplayPlan);
        styledText.addCaretListener(e -> onEclipseCaretMoved());
        styledText.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseDown(MouseEvent e) {
                onEclipseCaretMoved();
            }
        });
        session.setHostExHandler(cmd -> EclipseFileCommands.tryHandleEx(cmd, editor));
        session.setLeaveInsertPush(() -> {
            try {
                synchronizer.pushDocumentToNeovim();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "push on leave insert failed", e);
            }
        });

        session.addModeListener(modeListener);
        session.addCursorListener(cursorListener);
        session.addSelectionListener(selectionListener);
        session.addRecordingListener(recordingListener);
        session.addStatusListener(statusListener);

        installStatusLine();

        int caretOffset = styledText.getCaretOffset();
        int[] lineCol = DocumentPositions.offsetToLineCol(documentOf(editor), caretOffset);

        pushDocumentAndCursorAsync(lineCol[0], lineCol[1], takeNeovimCursor);

        onModeChanged(session.getMode());
        LOG.info("Attached Neovim to editor: " + editor.getTitle());
    }

    /**
     * Pushes the Eclipse document and caret into Neovim without blocking the UI thread.
     *
     * <p>Order matters: listening for Neovim changes is paused during the push and resumed after
     * it, so the echo of the push cannot be applied back (and cannot deadlock with the UI thread,
     * see {@link BufferSynchronizer#stopRemoteListening()}). Queuing on the session's input thread
     * also guarantees that keys typed right afterward are processed after the push.
     */
    private void pushDocumentAndCursorAsync(int line1, int col0, boolean takeNeovimCursor) {
        synchronizer.stopRemoteListening();
        final int tabWidth = styledText.isDisposed() ? 0 : styledText.getTabs();
        try {
            session.runAttachSetup(() -> {
                try {
                    session.showBuffer(buffer);
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, "Could not switch Neovim to the document's buffer", e);
                    return; // pushing now would overwrite another document's buffer
                }
                try {
                    // Blockwise selections are measured in screen columns, so tabs must be as wide in
                    // Neovim as in the editor.
                    session.setTabstop(tabWidth);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Could not set Neovim tabstop", e);
                }
                synchronizer.pushDocumentToNeovim();
                synchronizer.startRemoteListening();
                if (takeNeovimCursor) {
                    // Neovim jumped into this file (Ctrl-O, 'A): the caret goes where Neovim put the cursor.
                    session.syncModeAndCursorFromNeovim();
                    return;
                }
                try {
                    session.setCursor(line1, col0);
                    LOG.info("Preserved cursor at line=" + line1 + " col=" + col0);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to set Neovim cursor after push", e);
                }
            });
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Document push could not be scheduled", e);
            synchronizer.startRemoteListening();
        }
    }

    private void installStatusLine() {
        try {
            IEditorSite site = editor.getEditorSite();
            if (site == null) {
                return;
            }
            IStatusLineManager mgr = site.getActionBars().getStatusLineManager();
            if (mgr.find(ModeStatusContribution.ID) == null) {
                mgr.add(modeStatus);
                mgr.update(true);
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not install mode status line", e);
        }
    }

    private void uninstallStatusLine() {
        try {
            IEditorSite site = editor.getEditorSite();
            if (site == null) {
                return;
            }
            IStatusLineManager mgr = site.getActionBars().getStatusLineManager();
            mgr.remove(ModeStatusContribution.ID);
            mgr.update(true);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not remove mode status line", e);
        }
    }

    /**
     * Opens the inline command line for {@code :} / {@code /} / {@code ?}. Returns true if handled.
     */
    private boolean handleExOrSearchKey(String nvimKey) {
        CommandLine.Kind kind = CommandLine.Kind.forKey(nvimKey);
        if (kind == null) {
            return false;
        }
        commandLine.open(kind, VimModes.isVisual(session.getMode()));
        return true;
    }

    /** The editor's current document (looked up each time; may be {@code null}). */
    private static IDocument documentOf(ITextEditor editor) {
        return editor.getDocumentProvider().getDocument(editor.getEditorInput());
    }

    /** The editor's text viewer, if it supports ordered key listeners (all JFace source viewers do). */
    private static ITextViewerExtension viewerExtensionOf(ITextEditor editor) {
        if (editor.getAdapter(ITextViewer.class) instanceof ITextViewerExtension extension) {
            return extension;
        }
        // AbstractTextEditor answers its source viewer as the text operation target.
        if (editor.getAdapter(ITextOperationTarget.class) instanceof ITextViewerExtension extension) {
            return extension;
        }
        return null;
    }

    /**
     * @param buffer           the Neovim buffer of the editor's document
     * @param takeNeovimCursor {@code true} when Neovim jumped into this document: the caret follows
     *                         Neovim's cursor instead of Neovim's cursor following the caret
     */
    public static NeovimEditorController attach(IEditorPart part, NeovimSession session, DocumentBuffer buffer,
            boolean takeNeovimCursor) throws Exception {
        if (!(part instanceof ITextEditor textEditor)) {
            throw new IllegalArgumentException("Active part is not an ITextEditor");
        }

        StyledText widget = null;
        Control control = textEditor.getAdapter(Control.class);
        if (control instanceof StyledText st) {
            widget = st;
        } else {
            ITextViewer viewer = textEditor.getAdapter(ITextViewer.class);
            if (viewer != null) {
                widget = viewer.getTextWidget();
            }
        }
        if (widget == null) {
            throw new IllegalStateException("Could not obtain StyledText from editor");
        }

        if (documentOf(textEditor) == null) {
            throw new IllegalStateException("Editor has no IDocument");
        }
        return new NeovimEditorController(textEditor, session, widget, buffer, takeNeovimCursor);
    }

    public ITextEditor getEditor() {
        return editor;
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * @return {@code true} if Neovim currently owns keys
     */
    public boolean isNeovimModeActive() {
        return neovimModeActive && !closed;
    }

    /**
     * Toggle Neovim key handling vs plain Eclipse editing.
     * @return the new state ({@code true} = Neovim mode)
     */
    public boolean toggleNeovimMode() {
        return setNeovimModeActive(!neovimModeActive);
    }

    /**
     * Enable or disable Neovim key handling without detaching the session.
     * Switching <em>to</em> Neovim mode pushes the current Eclipse document into Neovim.
     *
     * @return the applied state ({@code true} = Neovim mode)
     */
    public boolean setNeovimModeActive(boolean active) {
        if (closed) {
            return false;
        }
        neovimModeActive = active;
        keyInterceptor.setEnabled(active);
        if (active) {
            // Eclipse may have edited the document while Neovim mode was off: resync into Neovim.
            // Must not block here (this runs on the UI thread).
            int offset = styledText.isDisposed() ? 0 : styledText.getCaretOffset();
            int[] lineCol = DocumentPositions.offsetToLineCol(documentOf(editor), offset);
            pushDocumentAndCursorAsync(lineCol[0], lineCol[1], false);
            modeStatus.setMode(session.getMode());
            cursorStyle.updateForMode(session.getMode());
            LOG.info("Mode → NEOVIM (keys to nvim)");
        } else {
            modeStatus.setHostEclipseMode();
            cursorStyle.updateForMode("i"); // thin caret for normal typing
            selectionView.clearBlock();
            LOG.info("Mode → ECLIPSE (keys to IDE)");
        }
        updateKeyContext(session.getMode());
        return neovimModeActive;
    }

    /** Command, message or statusline text changed: show it in the status bar item. */
    private void onStatusChanged(StatusInfo info) {
        modeStatus.setStatusInfo(info);
    }

    /** Redraws the status bar item and re-reads Neovim's side of it (a preference changed). */
    void refreshStatusLine() {
        modeStatus.refreshNow();
        session.refreshStatusAsync();
    }

    /** Shows (or clears) {@code recording @a} in the status line; called when a macro recording starts or stops. */
    private void onRecordingChanged(String register) {
        modeStatus.setRecording(register);
    }

    /**
     * Whether a document change right now is the user typing in a plain Eclipse-driven insert (the
     * only kind whose text is re-typed into Neovim at Esc). Excluded: changes the plug-in applies
     * from Neovim, Eclipse mode, replace mode (Eclipse inserts where Vim would overwrite) and the
     * Neovim-driven insert after a block selection (Neovim already sees those keys).
     */
    private boolean isTrackableInsertChange() {
        String mode = session.getMode();
        return !closed && neovimModeActive && !synchronizer.isApplyingRemote()
                && mode != null && mode.startsWith("i") && !session.isNeovimDrivenInsert();
    }

    /** Called by the key listener at Esc, on the UI thread. */
    private InsertReplay.Plan consumeInsertReplayPlan() {
        return styledText.isDisposed() ? null : insertTracker.consumePlan(styledText.getCaretOffset());
    }

    /** Sends one key (Neovim notation) to Neovim; used by the key-binding command for Vim Ctrl keys. */
    public void sendKey(String nvimKey) {
        if (!closed && neovimModeActive) {
            keyInterceptor.resetPendingArgument();
            session.inputAsync(nvimKey);
        }
    }

    /**
     * Activates the Vim key-binding context while Neovim owns normal/visual keys <em>and</em> this
     * editor's text widget has focus; deactivates it in insert mode, in Eclipse mode, on focus loss
     * and on close, so Eclipse shortcuts (paste, content assist, other views) are untouched there.
     *
     * <p>The activation is workbench-wide (the standard, well-trodden path) and the focus check keeps
     * it from leaking into other parts. Must run on the UI thread.
     */
    private void updateKeyContext(String mode) {
        boolean wanted = !closed && neovimModeActive && !VimModes.isTyping(mode)
                && !styledText.isDisposed() && styledText.isFocusControl();
        try {
            IContextService service = PlatformUI.getWorkbench().getService(IContextService.class);
            if (service == null) {
                LOG.warning("No workbench IContextService; Vim Ctrl keys cannot be enabled");
                return;
            }
            if (wanted && !keyContexts.isActive()) {
                keyContexts.activate(service, NeovimEditorController::isCtrlKeyToNeovim);
                LOG.info("Neovim key context activated (Vim Ctrl keys now go to Neovim)");
                // Report what the workbench would run for Ctrl-V now; see KeyContextDiagnostics.
                Display.getDefault().asyncExec(() -> KeyContextDiagnostics.logResolution("CTRL+V"));
            } else if (!wanted && keyContexts.isActive()) {
                keyContexts.deactivate(service);
                LOG.fine("Neovim key context deactivated");
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Neovim key context update failed", e);
        }
    }

    /**
     * Re-applies the Ctrl-key preferences: the active letter contexts are dropped and, if Neovim
     * still owns the keys, activated again with the current choice of letters.
     */
    void refreshKeyContext() {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            IContextService service = PlatformUI.getWorkbench().getService(IContextService.class);
            if (service != null) {
                keyContexts.deactivate(service);
            }
            updateKeyContext(session.getMode());
        });
    }

    /** Whether {@code Ctrl+letter} goes to Neovim; all letters do while the preferences are unavailable. */
    static boolean isCtrlKeyToNeovim(char letter) {
        Activator activator = Activator.getDefault();
        return activator == null || activator.isCtrlKeyToNeovim(letter);
    }

    private void updateKeyContextOnUi(String mode) {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        if (display.getThread() == Thread.currentThread()) {
            updateKeyContext(mode);
        } else {
            display.asyncExec(() -> updateKeyContext(mode));
        }
    }

    private void onModeChanged(String mode) {
        if (closed) {
            return;
        }
        LOG.fine(() -> "Mode → " + mode);
        if (!neovimModeActive) {
            return; // status shows ECLIPSE until user toggles back
        }
        updateKeyContextOnUi(mode);
        if (!VimModes.isTyping(mode)) {
            insertTracker.reset(); // an insert ended (or never started): drop any stale snapshot
        }
        try {
            modeStatus.setMode(mode);
        } catch (Exception e) {
            LOG.log(Level.FINE, "modeStatus update failed", e);
        }
        try {
            cursorStyle.updateForMode(mode);
        } catch (Exception e) {
            LOG.log(Level.FINE, "cursorStyle update failed", e);
        }
    }

    private void onCursorChanged(CursorPos pos) {
        if (VimModes.isVisual(session.getMode())) {
            return;
        }
        applyCaret(pos);
    }

    private void applyCaret(CursorPos pos) {
        if (pos == null || closed) {
            return;
        }
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        Runnable apply = () -> {
            if (closed || styledText.isDisposed()) {
                return;
            }
            applyingCaretFromNvim = true;
            try {
                IDocument doc = documentOf(editor);
                if (doc == null || doc.getNumberOfLines() <= 0) {
                    return;
                }
                int offset = Math.min(DocumentPositions.byteColToOffset(doc, pos.line1Based(), pos.col0Based()), doc.getLength());
                styledText.setCaretOffset(offset);
                styledText.showSelection();
            } catch (Exception e) {
                LOG.log(Level.FINE, "Cursor apply failed", e);
            } finally {
                applyingCaretFromNvim = false;
            }
        };
        // If already on UI (e.g. after a document edit), apply immediately so nothing
        // can interleave and leave the caret at 0.
        if (display.getThread() == Thread.currentThread()) {
            apply.run();
        } else {
            display.asyncExec(apply);
        }
    }

    private void onVisualSelection(VisualSelection sel) {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed() || closed) {
            return;
        }
        display.asyncExec(() -> {
            if (closed || styledText.isDisposed()) {
                return;
            }
            try {
                IDocument doc = documentOf(editor);
                if (doc != null) {
                    selectionView.show(doc, sel);
                }
            } catch (Exception e) {
                LOG.log(Level.FINE, "Visual selection apply failed", e);
            }
        });
    }

    /** Runs a caret change that comes from Neovim, so {@link #onEclipseCaretMoved} does not echo it back. */
    private void runAsCaretFromNeovim(Runnable change) {
        applyingCaretFromNvim = true;
        try {
            change.run();
        } finally {
            applyingCaretFromNvim = false;
        }
    }

    /** User clicked or moved the caret in Eclipse: push the position to Neovim. */
    private void onEclipseCaretMoved() {
        // document.set resets caret to 0 and fires this — must NOT push 1:1 back into Neovim
        if (closed || applyingCaretFromNvim || styledText.isDisposed() || !neovimModeActive
                || synchronizer.isApplyingRemote()) {
            return;
        }
        try {
            int offset = styledText.getCaretOffset();
            int[] lineCol = DocumentPositions.offsetToLineCol(documentOf(editor), offset);
            session.setCursorAsync(lineCol[0], lineCol[1]);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Eclipse→Neovim cursor failed", e);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            session.removeModeListener(modeListener);
            session.removeCursorListener(cursorListener);
            session.removeSelectionListener(selectionListener);
            session.removeRecordingListener(recordingListener);
            session.removeStatusListener(statusListener);
        } catch (Exception e) {
            LOG.log(Level.FINE, "remove listeners", e);
        }
        try {
            commandLine.dispose();
            selectionView.dispose();
            if (keyViewer != null) {
                keyViewer.removeVerifyKeyListener(keyInterceptor);
            } else {
                styledText.removeVerifyKeyListener(keyInterceptor);
            }
            if (!styledText.isDisposed()) {
                styledText.removeFocusListener(focusListener);
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "removeVerifyKeyListener", e);
        }
        updateKeyContext(session.getMode()); // closed == true: deactivates the key context
        try {
            synchronizer.close();
            insertTracker.dispose();
        } catch (Exception e) {
            LOG.log(Level.FINE, "synchronizer.close", e);
        }
        uninstallStatusLine();
        modeStatus.dispose();
        cursorStyle.dispose();
        LOG.info("Detached Neovim from editor: " + editor.getTitle());
    }
}
