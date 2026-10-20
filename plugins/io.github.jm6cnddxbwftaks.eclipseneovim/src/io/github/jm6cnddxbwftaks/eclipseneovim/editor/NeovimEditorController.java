// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.eclipseneovim.core.CursorPos;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.DocumentBuffer;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.IdeAction;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.NeovimSession;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.VisualSelection;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.StatusInfo;
import io.github.jm6cnddxbwftaks.eclipseneovim.sync.BufferSynchronizer;
import io.github.jm6cnddxbwftaks.eclipseneovim.sync.DocumentPush;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.CommandLine;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.Completion;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.HostExCommand;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.Indentation;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.InsertReplay;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.VimModes;

/**
 * Binds one {@link ITextEditor} to a {@link NeovimSession}.
 * Neovim owns keys; Eclipse shows mode status, caret style, visual selection, and buffer text.
 *
 * <p>This class is the wiring: it creates one helper per concern, connects each to the session's
 * listeners, and switches them together between Neovim mode and Eclipse mode. The helpers:
 * <ul>
 *   <li>{@link KeyInterceptor}: which keys go to Neovim;</li>
 *   <li>{@link BufferSynchronizer}: document text, both directions;</li>
 *   <li>{@link CaretSync}: caret and cursor, both directions;</li>
 *   <li>{@link EditorKeyContext}: when the Vim Ctrl-key bindings are active;</li>
 *   <li>{@link VisualSelectionView}, {@link CursorStyleController}, {@link ModeStatusContribution},
 *       {@link CommandLineBox}: what is drawn;</li>
 *   <li>{@link InsertTracker}: what was typed in insert mode, for {@code .}.</li>
 * </ul>
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

    private final ITextEditor editor;
    private final NeovimSession session;
    private final StyledText styledText;
    private final KeyInterceptor keyInterceptor;
    /** Viewer the key interceptor is prepended to; {@code null} if it sits on the widget instead. */
    private final ITextViewerExtension keyViewer;
    private final BufferSynchronizer synchronizer;
    /** Pushes the document into Neovim on attach and when Neovim mode is switched back on. */
    private final DocumentPush documentPush;
    /** Remembers the text before an Eclipse-driven insert so "." can repeat what was typed. */
    private final InsertTracker insertTracker;
    /** The inline {@code :} / {@code /} / {@code ?} command line along the bottom of the editor. */
    private final CommandLineBox commandLine;
    private final ModeStatusContribution modeStatus;
    private final CursorStyleController cursorStyle;
    /** Neovim's visual selection shown in the widget (native selection or blockwise overlay). */
    private final VisualSelectionView selectionView;
    /** When the Vim Ctrl-key bindings are active for this editor. */
    private final EditorKeyContext keyContext;
    /** Caret and Neovim cursor, kept in the same place. */
    private final CaretSync caretSync;
    /** Widget offsets to document offsets and back (folded code is not in the widget). */
    private final WidgetOffsets offsets;
    private volatile boolean closed;
    /** When false, keys go to Eclipse (plain editing); Neovim session stays alive. */
    private volatile boolean neovimModeActive = true;

    private final Consumer<String> modeListener = this::onModeChanged;
    private final Consumer<CursorPos> cursorListener = this::onCursorChanged;
    private final Consumer<VisualSelection> selectionListener = this::onVisualSelection;
    private final Consumer<String> recordingListener = this::onRecordingChanged;
    private final Consumer<StatusInfo> statusListener = this::onStatusChanged;
    private final Consumer<IdeAction> ideActionListener = this::onIdeAction;

    private NeovimEditorController(ITextEditor editor, NeovimSession session, StyledText styledText,
            DocumentBuffer buffer, boolean takeNeovimCursor) {
        this.editor = editor;
        this.session = session;
        this.styledText = styledText;
        this.keyInterceptor = new KeyInterceptor(session);
        this.keyInterceptor.setCtrlKeyFilter(EditorKeyContext::isCtrlKeyToNeovim);
        this.synchronizer = new BufferSynchronizer(documentOf(editor), session, SwtUiExecutor.INSTANCE);
        this.documentPush = new DocumentPush(new SessionPushTarget(session, buffer), synchronizer);
        this.offsets = new WidgetOffsets(styledText, viewerOf(editor));
        EditorCaret caret = new StyledTextCaret(styledText, offsets);
        this.caretSync = new CaretSync(caret, session, SwtUiExecutor.INSTANCE,
                () -> documentOf(editor), this::isNeovimModeActive, synchronizer::isApplyingRemote);
        this.synchronizer.setCaretRestorer(() -> caretSync.showNeovimCursor(session.getLastCursor()));
        this.synchronizer.pushLocalChanges(() -> isNeovimModeActive() && !session.isTypingMode(),
                caretSync::sendEclipseCaretNow);
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
        this.selectionView = new VisualSelectionView(caret, new BlockOverlay(styledText, offsets),
                caretSync::runAsFromNeovim);
        this.keyContext = new EditorKeyContext(styledText, session::getMode, this::isNeovimModeActive);
        this.commandLine = new CommandLineBox(styledText, new SessionCommands(session, editor));
        keyInterceptor.setSpecialKeyHandler(this::handleExOrSearchKey);
        keyInterceptor.setEditor(editor);
        keyInterceptor.setInsertReplaySupplier(this::consumeInsertReplayPlan);
        session.setHostExHandler(cmd -> HostCommands.tryHandleEx(cmd, editor));
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
        session.addIdeActionListener(ideActionListener);

        modeStatus.addTo(editor.getEditorSite());

        int[] lineCol = DocumentPositions.offsetToLineCol(documentOf(editor), offsets.caretModelOffset());

        pushDocumentAndCursorAsync(lineCol[0], lineCol[1], takeNeovimCursor);

        onModeChanged(session.getMode());
        LOG.fine("Attached Neovim to editor: " + editor.getTitle());
    }

    /**
     * Pushes the Eclipse document and caret into Neovim without blocking the UI thread; the steps and
     * their order are in {@link DocumentPush}. The tab width and indentation are read here, on the UI
     * thread.
     */
    private void pushDocumentAndCursorAsync(int line1, int col0, boolean takeNeovimCursor) {
        int tabWidth = styledText.isDisposed() ? 0 : styledText.getTabs();
        documentPush.start(tabWidth, indentationOf(tabWidth), line1, col0, takeNeovimCursor);
    }

    /**
     * The document's indentation style (read from its text), for Neovim's indent options. A file
     * with no indented lines yet uses the "Insert spaces for tabs" text editor preference. UI thread.
     */
    private Indentation indentationOf(int tabWidth) {
        IDocument doc = documentOf(editor);
        boolean spaces = EditorsUI.getPreferenceStore()
                .getBoolean(AbstractDecoratedTextEditorPreferenceConstants.EDITOR_SPACES_FOR_TABS);
        List<String> lines = doc == null ? List.of() : BufferSynchronizer.documentToLines(doc);
        return Indentation.detect(editor.getEditorInput().getName(), lines, Math.max(1, tabWidth), spaces);
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
        return viewerOf(editor) instanceof ITextViewerExtension extension ? extension : null;
    }

    /** The editor's text viewer, or {@code null}. */
    private static ITextViewer viewerOf(ITextEditor editor) {
        if (editor.getAdapter(ITextViewer.class) instanceof ITextViewer viewer) {
            return viewer;
        }
        // AbstractTextEditor answers its source viewer as the text operation target.
        return editor.getAdapter(ITextOperationTarget.class) instanceof ITextViewer viewer ? viewer : null;
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
            int offset = styledText.isDisposed() ? 0 : offsets.caretModelOffset();
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
        keyContext.update();
        return neovimModeActive;
    }

    /** A key mapped in Neovim ({@code gd}, {@code gt}, ...) asks Eclipse to act. Session thread. */
    private void onIdeAction(IdeAction action) {
        if (closed || !neovimModeActive) {
            return;
        }
        HostCommands.runOnUi(() -> {
            if (!closed && !styledText.isDisposed()) {
                IdeActions.run(action, editor, viewerOf(editor), offsets.caretModelOffset(),
                        () -> session.normalAsync("gd"));
            }
        });
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

    /** Whether a document change right now is typing to replay at Esc; see {@link InsertReplay#tracksChange}. */
    private boolean isTrackableInsertChange() {
        return InsertReplay.tracksChange(!closed && neovimModeActive, synchronizer.isApplyingRemote(),
                session.getMode(), session.isNeovimDrivenInsert());
    }

    /** Called by the key listener at Esc, on the UI thread. */
    private InsertReplay.Plan consumeInsertReplayPlan() {
        return styledText.isDisposed() ? null : insertTracker.consumePlan(offsets.caretModelOffset());
    }

    /** Sends one key (Neovim notation) to Neovim; used by the key-binding command for Vim Ctrl keys. */
    public void sendKey(String nvimKey) {
        if (!closed && neovimModeActive) {
            keyInterceptor.resetPendingArgument();
            session.inputAsync(nvimKey);
        }
    }

    /** Re-applies the Ctrl-key preferences (see {@link EditorKeyContext#refresh()}). */
    void refreshKeyContext() {
        keyContext.refresh();
    }

    private void onModeChanged(String mode) {
        if (closed) {
            return;
        }
        LOG.fine(() -> "Mode → " + mode);
        if (!neovimModeActive) {
            return; // status shows ECLIPSE until user toggles back
        }
        keyContext.updateFromAnyThread(mode);
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
        caretSync.showNeovimCursor(pos);
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
            session.removeIdeActionListener(ideActionListener);
        } catch (Exception e) {
            LOG.log(Level.FINE, "remove listeners", e);
        }
        try {
            caretSync.dispose();
            commandLine.dispose();
            selectionView.dispose();
            if (keyViewer != null) {
                keyViewer.removeVerifyKeyListener(keyInterceptor);
            } else {
                styledText.removeVerifyKeyListener(keyInterceptor);
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "removeVerifyKeyListener", e);
        }
        keyContext.dispose(); // deactivates the Vim key contexts
        try {
            synchronizer.close();
            insertTracker.dispose();
        } catch (Exception e) {
            LOG.log(Level.FINE, "synchronizer.close", e);
        }
        modeStatus.removeFrom(editor.getEditorSite());
        modeStatus.dispose();
        cursorStyle.dispose();
        LOG.fine("Detached Neovim from editor: " + editor.getTitle());
    }

    /** {@link CommandLineBox.Commands} on the session, with file and tab commands run by this editor. */
    private record SessionCommands(NeovimSession session, ITextEditor editor) implements CommandLineBox.Commands {
        @Override
        public void leaveVisualMode() {
            session.inputAsync("<Esc>");
        }

        @Override
        public boolean runInIde(String exCommand) {
            return HostCommands.tryHandleEx(exCommand, editor);
        }

        @Override
        public void capture(String exCommand, Consumer<String> onOutput) {
            session.captureOutputAsync(exCommand, onOutput);
        }

        @Override
        public void run(String exCommand, Consumer<String> onOutput) {
            session.commandAsync(exCommand, onOutput);
        }

        @Override
        public void search(String pattern, boolean backward) {
            session.search(pattern, backward);
        }

        /** {@code :e} names complete in the folders {@code :e} looks in; other lines need none. */
        @Override
        public void complete(String line, String steps, Consumer<Completion> onResult) {
            List<String> folders = new ArrayList<>();
            if (HostExCommand.parse(line).kind() == HostExCommand.Kind.EDIT) {
                for (Path folder : EclipseFileCommands.editFolders(editor)) {
                    folders.add(folder == null ? "" : folder.toString());
                }
            }
            session.completeAsync(line, steps, folders, onResult);
        }
    }

    /** {@link DocumentPush.Neovim} on the session, for this editor's buffer. */
    private record SessionPushTarget(NeovimSession session, DocumentBuffer buffer) implements DocumentPush.Neovim {
        @Override
        public void queue(Runnable work) {
            session.runAttachSetup(work);
        }

        @Override
        public void showDocumentBuffer() throws Exception {
            session.showBuffer(buffer);
        }

        @Override
        public void setIndentation(int tabWidth, Indentation indentation) throws Exception {
            session.setIndentation(tabWidth, indentation);
        }

        @Override
        public void setCursor(int line1, int col0) throws Exception {
            session.setCursor(line1, col0);
        }

        @Override
        public void takeCursorFromNeovim() {
            session.syncModeAndCursorFromNeovim();
        }
    }
}
