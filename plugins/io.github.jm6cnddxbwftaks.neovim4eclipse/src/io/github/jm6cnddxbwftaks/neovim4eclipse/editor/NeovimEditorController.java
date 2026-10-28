// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension;
import org.eclipse.jface.text.source.projection.ProjectionViewer;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.ITextEditorActionConstants;
import org.eclipse.ui.texteditor.ITextEditorExtension6;
import org.eclipse.ui.texteditor.IUpdate;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.CommandOutput;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.CursorPos;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.DocumentBuffer;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.FoldState;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.IdeAction;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.VisualSelection;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.StatusInfo;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.ViewportPort;
import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PreferenceConstants;
import io.github.jm6cnddxbwftaks.neovim4eclipse.sync.BufferSynchronizer;
import io.github.jm6cnddxbwftaks.neovim4eclipse.sync.DocumentPush;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CmdlineState;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Indentation;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InsertReplay;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimModes;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HostCommand;

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
 *       {@link CommandLineBox}, {@link MessageLine}: what is drawn;</li>
 *   <li>{@link InsertTracker}: what was typed in insert mode, for {@code .}.</li>
 *   <li>{@link FoldMirror}: the editor's folds and Neovim's, kept the same (when the editor folds
 *       and the user has not turned it off with {@code vim.g.neovim4eclipse_folds = 0}).</li>
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
    /** Eclipse's Copy, Paste, Cut and Select All, pointed at {@link #commandLine} while it is open. */
    private final CommandLineEditHandlers commandLineEdits;
    /** The left mouse button, handed to Neovim in normal and visual mode. */
    private final NeovimMouse mouse;
    /** Neovim's messages and errors, and the plug-in's own, along the bottom of the editor. */
    private final MessageLine messageLine;
    /** The message last handed to {@link #messageLine}, so that other status changes do not show it again. */
    private String shownMessage = "";
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
    /** The Neovim buffer of this editor's document. */
    private final DocumentBuffer buffer;
    /**
     * The editor's folds mirrored into Neovim; {@code null} when folding is off in Neovim (the
     * opt-out) or the editor cannot fold (no {@link ProjectionViewer}). See {@link #sendFolds()}.
     */
    private final FoldMirror foldMirror;
    /**
     * Neovim's window kept the size of this editor's text area, and {@code 'wrap'} its word wrap;
     * {@code null} when the user turned the viewport off ({@code vim.g.neovim4eclipse_viewport = 0}).
     */
    private final ViewportFollower viewport;
    /** The wheel through Neovim; {@code null} with the viewport off. */
    private final NeovimWheel wheel;
    private volatile boolean closed;
    /** When false, keys go to Eclipse (plain editing); Neovim session stays alive. */
    private volatile boolean neovimModeActive = true;

    private final Consumer<String> modeListener = this::onModeChanged;
    private final Consumer<CursorPos> cursorListener = this::onCursorChanged;
    private final Consumer<VisualSelection> selectionListener = this::onVisualSelection;
    private final Consumer<String> recordingListener = this::onRecordingChanged;
    private final Consumer<StatusInfo> statusListener = this::onStatusChanged;
    private final Consumer<CommandOutput> outputListener = this::onOutput;
    private final Consumer<IdeAction> ideActionListener = this::onIdeAction;
    private final Consumer<HostCommand> hostCommandListener = this::onHostCommand;
    private final Consumer<CmdlineState.View> cmdlineListener = this::onCmdline;
    private final Consumer<FoldState> foldListener = this::onFolds;
    private final Consumer<ViewportPort.WrapChange> wrapListener = this::onNeovimWrap;
    private final Consumer<ViewportPort.TopLine> topLineListener = this::onNeovimTopLine;
    /** The mode last logged: Neovim reports the mode again after every key, which is not a change. */
    private volatile String loggedMode;

    private NeovimEditorController(ITextEditor editor, NeovimSession session, StyledText styledText,
            DocumentBuffer buffer, boolean takeNeovimCursor) {
        this.editor = editor;
        this.session = session;
        this.styledText = styledText;
        this.keyInterceptor = new KeyInterceptor(session);
        this.keyInterceptor.setCtrlKeyFilter(EditorKeyContext::isCtrlKeyToNeovim);
        this.synchronizer = new BufferSynchronizer(documentOf(editor), session, SwtUiExecutor.INSTANCE);
        this.offsets = new WidgetOffsets(styledText, viewerOf(editor));
        this.buffer = buffer;
        this.foldMirror = session.foldsEnabled() && viewerOf(editor) instanceof ProjectionViewer projection
                ? new FoldMirror(projection, SwtUiExecutor.INSTANCE, folds -> session.setFoldsAsync(buffer, folds),
                        session::isTypingMode) // held while Eclipse types: Neovim lacks those lines until Esc
                : null;
        StyledTextCaret caret = new StyledTextCaret(styledText, offsets);
        if (foldMirror != null) {
            caret.setFoldCaption(foldMirror::captionOffset); // a closed fold's caption, without unfolding
        }
        this.viewport = session.viewportEnabled()
                ? new ViewportFollower(styledText, new ViewportSession(session, buffer), new ViewportEditor())
                : null;
        this.caretSync = new CaretSync(caret, session, SwtUiExecutor.INSTANCE,
                () -> documentOf(editor), this::isNeovimModeActive, synchronizer::isApplyingRemote);
        this.synchronizer.setCaretRestorer(() -> caretSync.showNeovimCursor(session.getLastCursor()));
        this.documentPush = new DocumentPush(new SessionPushTarget(session, buffer), synchronizer, caretSync);
        // Eclipse types (not Neovim): Neovim's lines are behind until Esc, so its cursor reports are
        // echoes; and while Esc is handled they are of a cursor about to move (KeyInput.leavingInsert).
        this.caretSync.dropNeovimCursorWhile(() -> eclipseTyping() || session.isLeavingInsertMode());
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
        keyInterceptor.install(styledText); // an Esc that Eclipse took first (linked mode) still ends insert mode
        this.selectionView = new VisualSelectionView(caret, new BlockOverlay(styledText, offsets),
                caretSync::runAsFromNeovim);
        this.keyContext = new EditorKeyContext(styledText, session::getMode, this::isNeovimModeActive,
                this::commandLineHasFocus);
        this.messageLine = new MessageLine(styledText, NeovimEditorController::flashOnError);
        this.commandLine = new CommandLineBox(styledText, new SessionCommands(session), messageLine,
                this::onCommandLineStateChanged);
        this.commandLineEdits = new CommandLineEditHandlers(
                editor.getSite().getService(IHandlerService.class), commandLine);
        this.mouse = new NeovimMouse(styledText, offsets::toModel, () -> documentOf(editor),
                this::neovimHandlesMouse, session::mouseAsync, messageLine::hide);
        this.wheel = viewport == null ? null
                : new NeovimWheel(styledText, this::neovimHandlesMouse, viewport::rows,
                        lines -> session.scrollLinesAsync(buffer, lines));
        keyInterceptor.setInsertReplaySupplier(this::consumeInsertReplayPlan);
        session.setLeaveInsertPush(() -> {
            try {
                synchronizer.pushDocumentToNeovim();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "push on leave insert failed", e);
            }
        });
        if (viewport != null) {
            // Eclipse scrolled alone while it typed (D6): its view is kept, and Neovim's window,
            // which has the lines and the final cursor now, is scrolled to it; a cursor the view
            // left behind is dragged in, as Neovim's own wheel would have dragged it. Read on the
            // UI thread, which never waits for the session's threads (no deadlock).
            session.setLeaveInsertView(() -> {
                int[] top = { 0 };
                SwtUiExecutor.INSTANCE.syncExec(() -> {
                    if (!closed && !styledText.isDisposed()) {
                        top[0] = viewport.eclipseTopLine();
                    }
                });
                try {
                    session.showTopLine(buffer, top[0]);
                } catch (Exception e) {
                    LOG.log(Level.FINE, "the editor's view at Esc", e);
                }
            });
        }

        session.addModeListener(modeListener);
        session.addCursorListener(cursorListener);
        session.addSelectionListener(selectionListener);
        session.addRecordingListener(recordingListener);
        session.addStatusListener(statusListener);
        session.addOutputListener(outputListener);
        session.addIdeActionListener(ideActionListener);
        session.addHostCommandListener(hostCommandListener);
        session.addCmdlineListener(cmdlineListener);
        if (foldMirror != null) {
            session.addFoldStateListener(foldListener);
        }
        if (viewport != null) {
            session.addWrapListener(wrapListener);
            session.addTopLineListener(topLineListener);
        }

        modeStatus.addTo(editor.getEditorSite());

        int[] lineCol = DocumentPositions.offsetToLineCol(documentOf(editor), offsets.caretModelOffset());

        pushDocumentAndCursorAsync(lineCol[0], lineCol[1], takeNeovimCursor);
        setWindowFolder();
        if (viewport != null) {
            viewport.start(); // after the push: the wrap is for this editor's buffer
        }

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
        sendFolds();
    }

    /**
     * Sends the editor's folds to Neovim, queued after the push (one input thread), so that the lines
     * are there: Eclipse's regions when the editor folds, an empty list when it cannot, which turns
     * folding off in Neovim's window so that a {@code 'foldmethod'} of the user's cannot hide lines
     * Eclipse shows. Nothing when the user turned folding off ({@code vim.g.neovim4eclipse_folds = 0}):
     * then Neovim's folding is left as the user has it. UI thread.
     */
    private void sendFolds() {
        if (foldMirror != null) {
            foldMirror.send();
        } else if (session.foldsEnabled()) {
            session.setFoldsAsync(buffer, List.of());
        }
    }

    /**
     * Neovim's closed folds after a key: collapse and expand Eclipse's to match, on the UI thread,
     * before the caret moves (the session reports folds before the cursor, and both go through the
     * same UI queue). Only for this editor's buffer and in Neovim mode. Session thread.
     */
    private void onFolds(FoldState folds) {
        if (closed || !neovimModeActive || folds.bufnr() != buffer.bufnr()) {
            return;
        }
        SwtUiExecutor.INSTANCE.asyncExec(() -> {
            if (!closed && !styledText.isDisposed()) {
                // As from Neovim: collapsing a fold that holds the caret makes the widget move the
                // caret out of the hidden lines (to the line after the fold), and that move must not
                // reach Neovim as the user's. Example: zc inside a method left Neovim's cursor on the
                // line after it, where zo found no fold. The cursor that follows puts the caret on
                // the fold's caption line.
                caretSync.runAsFromNeovim(() -> foldMirror.applyNeovimState(folds.closed()));
            }
        });
    }

    /**
     * Neovim's {@code 'wrap'} changed ({@code :set wrap}) in its window: the editor's word wrap
     * follows, when the window shows this editor's buffer. Session thread.
     */
    private void onNeovimWrap(ViewportPort.WrapChange change) {
        if (closed || change.bufnr() != buffer.bufnr()) {
            return;
        }
        SwtUiExecutor.INSTANCE.asyncExec(() -> {
            if (!closed && !styledText.isDisposed()) {
                viewport.onNeovimWrap(change.wrap());
            }
        });
    }

    /**
     * Neovim's window scrolled: the editor scrolls to its top line, on the UI thread, before the
     * caret of the same key moves (the session reports the top line first, and both go through the
     * same UI queue). Only for this editor's buffer and in Neovim mode, and not while Eclipse types
     * or Esc is handled: Neovim's lines are behind until the push at Esc, so its view is of another
     * text (decision D6: Eclipse scrolls alone in insert mode, and its view is kept at Esc; the
     * replay of the typed text would otherwise scroll the editor back first). Session thread.
     */
    private void onNeovimTopLine(ViewportPort.TopLine top) {
        if (closed || !neovimModeActive || top.bufnr() != buffer.bufnr() || eclipseTyping()
                || session.isLeavingInsertMode()) {
            return;
        }
        SwtUiExecutor.INSTANCE.asyncExec(() -> {
            if (!closed && !styledText.isDisposed()) {
                caretSync.runAsFromNeovim(() -> viewport.onNeovimTopLine(top.line1()));
            }
        });
    }

    /** Whether Eclipse types in insert mode now (Neovim gets the text at Esc). */
    private boolean eclipseTyping() {
        return session.isEclipseTypingReady() && !session.isNeovimDrivenInsert();
    }

    /** {@link ViewportFollower}'s way to the session, for this editor's buffer. */
    private final class ViewportSession implements ViewportFollower.Neovim {
        private final NeovimSession session;
        private final DocumentBuffer buffer;

        ViewportSession(NeovimSession session, DocumentBuffer buffer) {
            this.session = session;
            this.buffer = buffer;
        }

        @Override
        public void sendSize(int columns, int rows) {
            session.setEditorSizeAsync(columns, rows);
        }

        @Override
        public void sendWrap(boolean wrap) {
            session.setWrapAsync(buffer, wrap);
        }

        @Override
        public void sendTopLine(int line1) {
            session.setTopLineAsync(buffer, line1);
        }

        @Override
        public void sendScroll(int line1) {
            // In insert mode Eclipse scrolls alone (D6) and Esc sends the view; in Eclipse mode
            // (the toolbar button off) Neovim does not follow the editor at all.
            if (neovimModeActive && !session.isTypingMode()) {
                session.scrollToAsync(buffer, line1);
            }
        }
    }

    /**
     * {@link ViewportFollower}'s view of this editor: word wrap through the editor, top lines through
     * its text viewer, in document lines (the viewer converts to the widget's lines where code is
     * folded). A top line hidden in a fold that Neovim keeps closed becomes that fold's caption line,
     * which the widget shows (as for the caret, {@link FoldMirror#captionOffset}). UI thread.
     */
    private final class ViewportEditor implements ViewportFollower.Editor {
        @Override
        public void setWordWrap(boolean wrap) {
            if (editor instanceof ITextEditorExtension6 wrapping) { // it does nothing where wrap is not supported
                wrapping.setWordWrap(wrap);
                // Eclipse's key and button run an action that updates the toolbar's toggle buttons
                // afterward; setWordWrap alone does not. Word wrap on also ends block selection, so
                // that button would otherwise stay pressed after a :set wrap.
                updateAction(ITextEditorActionConstants.WORD_WRAP);
                updateAction(ITextEditorActionConstants.BLOCK_SELECTION_MODE);
            }
        }

        private void updateAction(String id) {
            if (editor.getAction(id) instanceof IUpdate action) {
                action.update();
            }
        }

        @Override
        public int topLine() {
            ITextViewer viewer = viewerOf(editor);
            return viewer == null ? styledText.getTopIndex() : Math.max(0, viewer.getTopIndex());
        }

        @Override
        public void setTopLine(int line0) {
            ITextViewer viewer = viewerOf(editor);
            if (viewer == null) {
                styledText.setTopIndex(line0);
                return;
            }
            viewer.setTopIndex(captionLine(viewer, line0));
        }

        private int captionLine(ITextViewer viewer, int line0) {
            IDocument document = viewer.getDocument();
            if (foldMirror == null || document == null) {
                return line0;
            }
            try {
                int caption = foldMirror.captionOffset(document.getLineOffset(line0));
                return caption < 0 ? line0 : document.getLineOfOffset(caption);
            } catch (BadLocationException e) {
                return line0; // past the end: the viewer clamps
            }
        }
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
     * Neovim's window works in this editor's project ({@code :lcd}): {@code :e} completion,
     * {@code :!} and {@code :find} start at the project's root, or at the file's folder for a file
     * outside any project (decision D5 of the cleanup plan). {@code :w}, {@code :wq} and {@code :x}
     * names are relative to it, as in Neovim; {@code :e} still looks beside the file first
     * ({@code EclipseFileCommands.editFolders}).
     */
    private void setWindowFolder() {
        Path folder = EclipseFileCommands.windowFolder(editor);
        if (folder != null) {
            session.setWindowFolderAsync(folder.toString());
        }
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
            messageLine.hide(); // Neovim's messages are about keys it no longer gets
            cursorStyle.updateForMode("i"); // thin caret for normal typing
            selectionView.clearBlock();
            LOG.info("Mode → ECLIPSE (keys to IDE)");
        }
        keyContext.update();
        ToggleButtonRefresh.request(); // the toolbar button: pressed or raised
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

    /**
     * Neovim handed a command to Eclipse ({@code :q} as {@code :Eclipse quit}). It is this editor's
     * when Neovim drives it: one Neovim serves every editor, and only the attached one listens.
     * Session thread; the work goes to the UI thread.
     */
    private void onHostCommand(HostCommand command) {
        if (closed || !neovimModeActive) {
            return;
        }
        HostCommands.run(command, editor);
    }

    /**
     * Neovim's command line changed: the box shows it (opens, redraws, closes) if Neovim drives
     * this editor. Every command line is Neovim's, whoever opened it: {@code :} typed here, a
     * mapping, a count, {@code input()}. RPC thread.
     */
    private void onCmdline(CmdlineState.View view) {
        if (closed || !neovimModeActive) {
            return;
        }
        HostCommands.runOnUi(() -> {
            if (!closed && !styledText.isDisposed()) {
                commandLine.show(view);
            }
        });
    }

    /**
     * Whether a left click goes to Neovim's mouse ({@link NeovimMouse}): in Neovim mode, except in
     * insert and replace mode, where Eclipse types and so places the caret itself.
     */
    private boolean neovimHandlesMouse() {
        return !closed && neovimModeActive && !VimModes.isTyping(session.getMode());
    }

    /** Whether the {@code :} box has the keyboard focus (the Vim Ctrl keys then go to its line). */
    private boolean commandLineHasFocus() {
        return commandLine != null && commandLine.hasFocus();
    }

    /**
     * The {@code :} box opened, closed, gained or lost the focus: Eclipse's Copy and Paste act on it
     * while it is open ({@link CommandLineEditHandlers}), and the Vim Ctrl keys reach its line while it
     * has the focus ({@link EditorKeyContext}). UI thread.
     */
    private void onCommandLineStateChanged() {
        if (closed || commandLineEdits == null) {
            return; // the editor is going away, or the box reports while the controller is being built
        }
        commandLineEdits.update();
        keyContext.update();
    }

    /**
     * Command, message or statusline text changed: the command and statusline go to the status bar
     * item, the message to the message line. Called on the session's thread.
     */
    private void onStatusChanged(StatusInfo info) {
        modeStatus.setStatusInfo(info);
        HostCommands.runOnUi(() -> {
            if (closed || styledText.isDisposed() || !isNeovimModeActive() || info.message().equals(shownMessage)) {
                return;
            }
            shownMessage = info.message();
            messageLine.show(info.message(), info.error());
        });
    }

    /**
     * Shows what Neovim shows now ({@link NeovimSession#currentStatus()}): used when Neovim moved into
     * this editor by itself, so that a {@code :b}'s message reaches the editor it moved to, as on
     * Neovim's single screen. UI thread.
     */
    void showNeovimsCurrentStatus() {
        onStatusChanged(session.currentStatus());
    }

    /**
     * Output for the popup (a listing, shell output, messages of more than one line): shown over
     * this editor if Neovim drives it. The message line is cleared, as the popup replaces it. Called
     * on the session's thread.
     */
    private void onOutput(CommandOutput output) {
        HostCommands.runOnUi(() -> {
            if (closed || styledText.isDisposed() || !isNeovimModeActive()) {
                return;
            }
            commandLine.showOutput(output.title(), output.text());
        });
    }

    /**
     * Shows a message of the plug-in's own in the message line, e.g. {@code E37: No write since last
     * change} from {@code :e}. It goes away with the next key, like Neovim's messages. UI thread.
     */
    void showMessage(String message, boolean error) {
        if (!closed && !styledText.isDisposed()) {
            messageLine.show(message, error);
        }
    }

    /** The message line, for tests. */
    MessageLine messageLine() {
        return messageLine;
    }

    /** The preference <em>Flash the editor on errors</em>. */
    private static boolean flashOnError() {
        Activator activator = Activator.getDefault();
        return activator != null && activator.getPreferenceStore().getBoolean(PreferenceConstants.FLASH_ON_ERROR);
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
        if (!closed && neovimModeActive && commandLine.isOpen()) {
            commandLine.ctrlKey(nvimKey); // the : box has the focus: its line, or its paste and copy
            return;
        }
        if (!closed && neovimModeActive) {
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
        if (!Objects.equals(mode, loggedMode)) {
            loggedMode = mode;
            LOG.fine(() -> "Mode → " + mode);
        }
        if (!neovimModeActive) {
            return; // status shows ECLIPSE until user toggles back
        }
        keyContext.updateFromAnyThread(mode);
        if (!VimModes.isTyping(mode)) {
            insertTracker.reset(); // an insert ended (or never started): drop any stale snapshot
            if (foldMirror != null) {
                // Folds held while Eclipse typed; queued after the push at Esc, so the lines are there.
                SwtUiExecutor.INSTANCE.asyncExec(() -> {
                    if (!closed && !styledText.isDisposed()) {
                        foldMirror.resume();
                    }
                });
            }
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
        caretSync.onNeovimCursor(pos);
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
        ToggleButtonRefresh.request(); // greys the toolbar button out, unless another editor attaches
        try {
            session.removeModeListener(modeListener);
            session.removeCursorListener(cursorListener);
            session.removeSelectionListener(selectionListener);
            session.removeRecordingListener(recordingListener);
            session.removeStatusListener(statusListener);
            session.removeOutputListener(outputListener);
            session.removeIdeActionListener(ideActionListener);
            session.removeHostCommandListener(hostCommandListener);
            session.removeCmdlineListener(cmdlineListener);
            session.removeFoldStateListener(foldListener);
            session.removeWrapListener(wrapListener);
            session.removeTopLineListener(topLineListener);
            if (viewport != null) {
                session.setLeaveInsertView(null); // the next editor's attach sets its own
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "remove listeners", e);
        }
        try {
            if (foldMirror != null) {
                foldMirror.close();
            }
            if (viewport != null) {
                viewport.close();
            }
            caretSync.dispose();
            mouse.dispose();
            if (wheel != null) {
                wheel.dispose();
            }
            commandLineEdits.dispose();
            commandLine.dispose();
            messageLine.dispose();
            selectionView.dispose();
            if (keyViewer != null) {
                keyViewer.removeVerifyKeyListener(keyInterceptor);
            } else {
                styledText.removeVerifyKeyListener(keyInterceptor);
            }
            keyInterceptor.uninstall(styledText);
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

    /**
     * {@link CommandLineBox.Commands} on the session: keys and pastes go to Neovim's command line, in
     * order with the editor's own keys (one input thread). File and tab commands still reach this
     * editor, through the session's host handler on Enter.
     */
    private record SessionCommands(NeovimSession session) implements CommandLineBox.Commands {
        @Override
        public void type(String keys) {
            session.inputAsync(keys);
        }

        @Override
        public void paste(String text) {
            session.pasteAsync(text);
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
