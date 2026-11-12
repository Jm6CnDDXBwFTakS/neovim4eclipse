// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Mode;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusItem;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimModes;

/**
 * The session's view of Neovim's state (mode, cursor, whether Eclipse may type), and how that state
 * is read from Neovim and passed to the listeners.
 *
 * <p>State arrives two ways: pushed by Neovim (autocmd notifications, buffer line events, through
 * {@link NotificationBridge}) and pulled after each key ({@link #publishState()}), because the
 * notifications can lag a key behind. Both end in the {@code dispatch*} methods, which update the
 * state and tell the {@link Listeners}.
 *
 * <p>Example: after {@code v} then {@code l}, {@link #syncModeAndCursor()} reads mode {@code "v"},
 * the cursor and the selection in one request, and the listeners get the mode, the new cursor and an
 * active selection. After {@code Esc} they get mode {@code "n"} and {@link VisualSelection#none()}.
 *
 * <p>The reading methods block and run on the session's input thread; the getters and dispatch
 * methods may be called from any thread (the fields are volatile; the typing generation is guarded
 * by the instance, see {@link #typingGeneration}). Neovim is reached only through
 * the small {@link Neovim} interface, so tests use a fake.
 */
final class StateSync {

    private static final Logger LOG = Logger.getLogger(StateSync.class.getName());

    /** What state reading needs from Neovim. Blocking, on the input thread. */
    interface Neovim {
        /**
         * {@code nvim_get_mode}: one of the few calls Neovim answers even while it waits for the rest
         * of a command, which is why it is the gate for everything else.
         */
        Mode getMode() throws Exception;

        /**
         * Runs {@link LuaScripts#STATE_SNAPSHOT} (with a short timeout) and returns its raw answer,
         * read by {@link StateSnapshot}.
         *
         * @param statuslineWidth the statusline's width in columns, 0 for none (not evaluated)
         * @param folds           whether to read the closed folds
         */
        Object stateSnapshot(int statuslineWidth, boolean folds) throws Exception;

        /** The attached buffer's lines. */
        List<String> bufferLines() throws Exception;
    }

    /** Who hears about state changes ({@link SessionEvents} in the session). Any thread. */
    interface Listeners {
        void mode(String mode);

        void cursor(CursorPos cursor);

        void selection(VisualSelection selection);

        void bufferChange(BufferChange change);

        /** The closed folds of the attached buffer, after each key while folding is on. */
        void folds(FoldState folds);
    }

    private final Neovim nvim;
    private final Listeners listeners;
    private final StatusTracker status;
    /** Whether a window and buffer are the attached ones (the editor's window, the document it mirrors). */
    private final Attached isAttached;
    /** Told about every mode change before the listeners (see {@code KeyInput#onModeChanged}). */
    private final Consumer<String> onModeChanged;

    /** Set only through {@link #setMode}, which keeps {@link #typingGeneration} with it. */
    private volatile String mode = "n";
    private volatile CursorPos lastCursor = new CursorPos(1, 0);
    /**
     * Counts every start and end of a typing mode (insert, replace) and every
     * {@link #pauseEclipseTyping pause}: a full sync lets Eclipse type only in the generation it was
     * read in ({@link #isEclipseTypingReady()}). Guarded by {@code this}, as is
     * {@link #readyGeneration}.
     *
     * <p>Why a count and not a flag: the editor applies a sync later, on the UI thread, and by then
     * the generation may have moved on. Example: a sync read in normal mode (after a {@code :}
     * command) is applied only once {@code i} has been read, and the user's next key comes in
     * between. A flag set when that sync was applied let Eclipse type the key, as the mode was
     * {@code "i"} by then; then the insert's own sync, read before the key, replaced the document,
     * and the key was lost in Eclipse and, at Esc, in Neovim too. With the count, that old sync
     * belongs to generation 4 and the insert is generation 5: Eclipse waits for the insert's sync,
     * and the key goes to Neovim meanwhile ({@code KeyRouter}).
     */
    private long typingGeneration;
    /** The generation in which Eclipse may type, -1 for none yet (see {@link #typingGeneration}). */
    private long readyGeneration = -1;
    /**
     * True while Neovim waits for one more character after {@code f}, {@code r}, {@code @}, {@code q}
     * ... (as of the last {@code nvim_get_mode}). The key listener then forwards a typed {@code :},
     * {@code /} or {@code ?} to Neovim as the argument instead of opening a prompt.
     */
    private volatile boolean waitingForArgument;
    /**
     * While true, Neovim's own buffer events are not forwarded to listeners. Used while text that
     * Eclipse already contains is re-typed into Neovim, where applying those events to the document
     * would insert the text twice, and during macro playback (see {@link KeyInput}).
     */
    private volatile boolean suppressBufferEvents;
    /**
     * Whether the snapshot reads the closed folds: set once at session start, from the user's
     * {@code vim.g.neovim4eclipse_folds} ({@code NeovimSession.foldsEnabled()}).
     */
    private volatile boolean foldsEnabled;

    /**
     * Whether Neovim's current window and buffer are the attached ones; {@code DocumentBuffers.isAttached}
     * in the session. Example: after {@code Ctrl-O} into another file, the window is the editor's but
     * the buffer is not, and the cursor there is not the editor's caret.
     */
    @FunctionalInterface
    interface Attached {
        boolean test(int winid, int bufnr);
    }

    StateSync(Neovim nvim, Listeners listeners, StatusTracker status, Attached isAttached,
            Consumer<String> onModeChanged) {
        this.nvim = nvim;
        this.listeners = listeners;
        this.status = status;
        this.isAttached = isAttached;
        this.onModeChanged = onModeChanged;
    }

    // ---- state ----

    /** The mode as last read or reported, for example {@code "n"}, {@code "i"}, {@code "v"}. */
    String mode() {
        return mode;
    }

    /** Insert/replace: printable keys are handled by Eclipse, not forwarded to Neovim. */
    boolean isTypingMode() {
        return VimModes.isTyping(mode);
    }

    /**
     * Whether Eclipse may type in the current insert mode. Entering insert mode is followed by a
     * full buffer sync from Neovim; until the editor has applied it, text typed by Eclipse would be
     * overwritten by that sync. So this becomes true only once a full sync read in this insert (and
     * since the last pause) has been applied, and false again when insert mode ends. A sync read
     * earlier does not count, even if it is applied during this insert ({@link #typingGeneration}).
     */
    synchronized boolean isEclipseTypingReady() {
        return readyGeneration == typingGeneration && isTypingMode();
    }

    /**
     * Eclipse stops typing in the current insert until a full sync read after this call has been
     * applied: at a sync point ({@code KeyInput#syncInsertThenInput}), Neovim's answer to the key is
     * about to change the text, and keys that follow go to Neovim, in order. A sync read before the
     * pause holds Neovim's text from before the key, so it starts a new generation. UI thread, at
     * the key.
     */
    synchronized void pauseEclipseTyping() {
        typingGeneration++;
    }

    /**
     * Records the mode; a start or end of a typing mode begins a new {@link #typingGeneration}.
     * Example: {@code "n"} to {@code "i"} does, {@code "i"} to {@code "R"} (the Insert key) does not,
     * so a replace that follows an insert keeps Eclipse typing.
     */
    private synchronized void setMode(String newMode) {
        if (VimModes.isTyping(newMode) != VimModes.isTyping(mode)) {
            typingGeneration++;
        }
        mode = newMode;
    }

    /** The current {@link #typingGeneration}: what a full sync remembers when it is read. */
    private synchronized long currentGeneration() {
        return typingGeneration;
    }

    /** Whether Neovim is waiting for the argument character of a command ({@code f}, {@code @}, ...). */
    boolean isWaitingForArgument() {
        return waitingForArgument;
    }

    /** Last cursor reported by Neovim or set by Eclipse. */
    CursorPos lastCursor() {
        return lastCursor;
    }

    /** Records a cursor Eclipse has just set in Neovim (no listener is told: Eclipse moved it). */
    void rememberCursor(CursorPos cursor) {
        lastCursor = cursor;
    }

    /** See {@link #foldsEnabled}. */
    void setFoldsEnabled(boolean enabled) {
        foldsEnabled = enabled;
    }

    /** See {@link #suppressBufferEvents}. */
    void setSuppressBufferEvents(boolean suppress) {
        suppressBufferEvents = suppress;
    }

    // ---- reading from Neovim (input thread) ----

    /**
     * Reads the mode and remembers it, and whether Neovim waits for an argument.
     *
     * @return the mode, or {@code null} if it could not be read
     */
    Mode readMode() {
        try {
            Mode read = nvim.getMode();
            if (read != null && read.mode() != null) {
                setMode(read.mode());
            }
            waitingForArgument = read != null && read.blocking();
            return read;
        } catch (Exception e) {
            LOG.log(Level.FINE, "getMode failed", e);
            return null;
        }
    }

    /** Whether Neovim is waiting for the rest of a command (and would defer most API calls). */
    boolean isBlocked() {
        Mode read = readMode();
        return read != null && read.blocking();
    }

    /**
     * Reads mode, cursor and visual selection from Neovim and notifies listeners. Does nothing while
     * Neovim waits for the rest of a command; the next completed key will publish the state.
     */
    void syncModeAndCursor() {
        if (isBlocked()) {
            return;
        }
        publishState();
    }

    /**
     * Publishes mode, cursor, visual selection, the statusline and the closed folds from one request
     * ({@link LuaScripts#STATE_SNAPSHOT}). Must only be called when Neovim is not blocked.
     *
     * <p>Cursor and selection are only published when Neovim's current buffer is the attached one:
     * after a jump to another file the cursor is in a buffer the editor does not show (yet). A
     * failed request publishes nothing; the next key tries again.
     */
    void publishState() {
        try {
            Set<StatusItem> items = status.items();
            boolean folds = foldsEnabled;
            Object raw = nvim.stateSnapshot(items.contains(StatusItem.STATUSLINE) ? status.statuslineColumns() : 0, folds);
            // The mode in the answer, not one read before the request. Example: Eclipse runs :w, the
            // plug-in closes Neovim's command line with Esc and publishes. The mode read before was
            // "c"; Neovim's ModeChanged notice "n" can arrive while the request is out, and publishing
            // the old "c" after it left the status bar on -- COMMAND -- in normal mode.
            String snapshotMode = StateSnapshot.mode(raw);
            String current = snapshotMode != null ? snapshotMode : mode;
            dispatchMode(current);
            if (isAttached.test(StateSnapshot.window(raw), StateSnapshot.buffer(raw))) {
                // The folds before the cursor: the editor applies them in this order (one UI queue),
                // so the caret lands on text that is shown. Example: after zo, a caret placed while
                // the fold was still collapsed in Eclipse would go to its caption line and stay there.
                List<Integer> closed = folds ? StateSnapshot.closedFolds(raw) : null;
                if (closed != null) {
                    listeners.folds(new FoldState(StateSnapshot.buffer(raw), closed));
                }
                CursorPos cursor = StateSnapshot.cursor(raw);
                if (cursor != null) {
                    dispatchCursor(cursor.line1Based(), cursor.col0Based());
                }
                listeners.selection(VimModes.isVisual(current)
                        ? StateSnapshot.selection(raw, current)
                        : VisualSelection.none());
            }
            status.updateRecording(StateSnapshot.recording(raw));
            status.publishStatusline(StateSnapshot.statusline(raw));
        } catch (Exception e) {
            LOG.log(Level.FINE, "state snapshot failed", e);
        }
    }

    /**
     * Pulls the whole buffer and publishes it as a full-buffer change. Not for per-key use. Once the
     * editor has applied it in insert mode, Eclipse may type ({@link #isEclipseTypingReady()}).
     */
    void syncBuffer() {
        syncBuffer(() -> true);
    }

    /**
     * {@link #syncBuffer()}, but Eclipse may type afterward only if {@code mayType} says so when the
     * editor has applied it (on the UI thread, where keys are routed, so no key slips in between).
     * Example: handing an insert back to Eclipse after {@code Ctrl-R a}
     * ({@code KeyInput#handBackToEclipse}) is called off if a key reached Neovim after the read.
     */
    void syncBuffer(BooleanSupplier mayType) {
        // Before the read: a mode change between the two then makes the sync not count, as it should.
        long generation = currentGeneration();
        try {
            List<String> lines = nvim.bufferLines();
            LOG.fine(() -> "full buffer sync: " + lines.size() + " lines");
            dispatchBufferChange(0, -1, lines, () -> {
                if (mayType.getAsBoolean()) {
                    letEclipseTypeIn(generation);
                }
            });
        } catch (Exception e) {
            LOG.log(Level.WARNING, "buffer sync failed", e);
        }
    }

    /**
     * Lets Eclipse type once the editor has applied the buffer events sent so far, without reading
     * Neovim's buffer ({@link BufferChange#marker}): the cheaper handshake at a sync point in a long
     * document ({@code KeyInput#syncInsertThenInput}).
     */
    void letEclipseTypeOnceApplied() {
        long generation = currentGeneration();
        dispatchBufferChange(0, 0, List.of(), () -> letEclipseTypeIn(generation));
    }

    /**
     * After-apply action of a full sync read in {@code generation}: the document now matches
     * Neovim, so Eclipse may type, if the sync was read in this insert and since the last pause.
     */
    private synchronized void letEclipseTypeIn(long generation) {
        if (generation == typingGeneration && isTypingMode()) {
            readyGeneration = generation;
        }
    }

    // ---- dispatch (any thread) ----

    /** Records {@code newMode} and tells {@code onModeChanged}, then the listeners. */
    void dispatchMode(String newMode) {
        setMode(newMode); // leaving insert ends Eclipse's typing (a new generation)
        onModeChanged.accept(newMode);
        listeners.mode(newMode);
    }

    void dispatchCursor(int line1, int col0) {
        CursorPos pos = new CursorPos(line1, col0);
        lastCursor = pos;
        listeners.cursor(pos);
    }

    void dispatchBufferChange(int firstLine, int lastLine, List<String> newLines) {
        dispatchBufferChange(firstLine, lastLine, newLines, null);
    }

    /** @param afterApply run by the editor once it applied the change, or {@code null} */
    void dispatchBufferChange(int firstLine, int lastLine, List<String> newLines, Runnable afterApply) {
        if (suppressBufferEvents) {
            return;
        }
        listeners.bufferChange(new BufferChange(firstLine, lastLine, newLines, afterApply));
    }
}
