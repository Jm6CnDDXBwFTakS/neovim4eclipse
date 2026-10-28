// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Handle;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Mode;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.NeovimApi;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.WinCursor;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CmdlineEvent;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CmdlineState;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CmdlineWatcher;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Diagnostic;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FoldRegions;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Indentation;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InsertReplay;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.LatestValue;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.LineDiff;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.MessageEvent;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusItem;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewportSize;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HostCommand;

/**
 * Façade over one embedded {@code nvim --embed} process, through the plug-in's own msgpack-rpc
 * client ({@link NeovimConnection}, {@code core/rpc}).
 *
 * <h2>Threading (important)</h2>
 * <ul>
 *   <li>Every blocking RPC ({@code await}) must run on the single <em>input thread</em>
 *       ({@link InputThread}), never on the SWT UI thread: a stalled call would freeze the IDE.
 *       The {@code *Async} methods queue work onto that thread, which also keeps multi-key
 *       sequences such as {@code ciw} in order.</li>
 *   <li>Listener callbacks run on the input thread or an RPC thread; listeners must hop to the UI
 *       thread themselves ({@code Display.asyncExec}).</li>
 *   <li>The public blocking methods ({@link #input}, {@link #getBufferLines}, ...) are intended
 *       for the input thread and for tests/integration code.</li>
 * </ul>
 *
 * <h2>State flow</h2>
 * Neovim owns the buffer. Text changes arrive through {@code nvim_buf_lines_event}; mode, cursor
 * and yanks arrive through the autocmds in {@link LuaScripts#SETUP}; messages and the pending
 * command arrive as UI events ({@code ext_messages}) and go to {@link StatusTracker}. After each key
 * the session also polls mode, cursor and visual selection, because notifications can lag a key
 * behind. Both paths go through {@link StateSync}.
 *
 * <p>Each Eclipse document has its own Neovim buffer ({@link DocumentBuffer}), so undo history and
 * marks survive switching editors. Exactly one of them is <em>attached</em> at a time: its events
 * reach the listeners. Line events of the other document buffers go to that document's own listener
 * ({@link DocumentBufferPort}), so {@code :bufdo} edits reach every file; cursor and mode events of
 * other buffers are dropped.
 */
public final class NeovimSession implements AutoCloseable, KeyRouter.Neovim, BufferPort, CursorPort,
        DocumentBufferPort, DiagnosticsPort, FoldsPort, ViewportPort {

    private static final Logger LOG = Logger.getLogger(NeovimSession.class.getName());

    /** Upper bound for any single RPC round trip. */
    private static final long RPC_TIMEOUT_SEC = Rpc.TIMEOUT_SEC;
    /**
     * Upper bound for the per-key state read. Short on purpose: if Neovim unexpectedly defers the
     * request, a keystroke should cost a fraction of a second, not {@value #RPC_TIMEOUT_SEC}s.
     */
    private static final long STATE_TIMEOUT_MS = 750;

    // ---- connection ----

    /** The process and RPC stack. */
    private final NeovimConnection connection;
    /** Shortcut for {@code connection.api()}. */
    private final NeovimApi api;
    /** Neovim's {@code "+} and {@code "*} registers in the system clipboard (Eclipse is the provider). */
    private final ClipboardRegisters clipboard;

    // ---- buffers and keys ----

    /** The key-input pipeline. */
    private final KeyInput keyInput;
    /** Status bar parts and macro recording, published to listeners. */
    private final StatusTracker status = new StatusTracker();

    /** One Neovim buffer per Eclipse document, and which one is attached. */
    private final DocumentBuffers buffers;
    /** Who hears of Neovim's changes to each document buffer that is not attached. */
    private final Map<DocumentBuffer, Consumer<BufferChange>> unattachedListeners = new ConcurrentHashMap<>();
    /**
     * The buffer {@link #setDocumentBufferLines} is writing, or {@link NotificationBridge#UNKNOWN_BUFFER}.
     * Neovim sends a write's line events before its reply, so they are all read while this is set
     * and can be dropped: they would echo the write back to the document it came from.
     */
    private volatile int writingBufnr = NotificationBridge.UNKNOWN_BUFFER;

    // ---- state ----

    /** Mode, cursor and typing state, and reading them from Neovim. */
    private final StateSync state;
    private volatile boolean closed;
    /**
     * True when the user's config makes every yank go to the clipboard provider
     * ({@code clipboard=unnamed[plus]}): the provider writes the OS clipboard (ours, see
     * {@link ClipboardRegisters}, or the user's own {@code g:clipboard}). Then plain yanks must not
     * also be copied: the two writers race, the text can differ (line separators), Neovim then no
     * longer recognizes its own register contents and pastes a whole-line yank ({@code yyP}) as
     * characters at the cursor. (The Lua hook stands down too; this is the second guard.)
     */
    private volatile boolean nvimManagesClipboard;
    /**
     * Whether Eclipse's folds are mirrored into Neovim ({@link FoldsPort#foldsEnabled()}): the answer
     * of {@link LuaScripts#FOLDS}, run once at start after the user's config. Written once, in
     * {@link #initialize}, before the session is handed out.
     */
    private volatile boolean foldsEnabled;
    /**
     * Whether Eclipse's problems are sent to Neovim ({@link DiagnosticsPort#problemsEnabled()}): the
     * answer of {@link LuaScripts#PROBLEMS}, run once at start after the user's config. Written once,
     * in {@link #initialize}, before the session is handed out.
     */
    private volatile boolean problemsEnabled;
    /**
     * Whether Neovim's window follows the editor ({@link ViewportPort#viewportEnabled()}): the answer
     * of {@link LuaScripts#VIEWPORT}, run once at start after the user's config. Written once, in
     * {@link #initialize}, before the session is handed out.
     */
    private volatile boolean viewportEnabled;
    /** Editor sizes not sent yet: only the newest counts (a window being dragged larger). */
    private final LatestValue<int[]> pendingEditorSize = new LatestValue<>();
    /** Wheel lines not sent yet, added up ({@link #scrollLinesAsync}); and the buffer they are for. */
    private final AtomicInteger pendingWheelLines = new AtomicInteger();
    private volatile DocumentBuffer wheelBuffer;
    /** The editor's newest scroll, not sent yet: a scroll-bar drag sends many, Neovim needs the last. */
    private final LatestValue<Scroll> pendingScroll = new LatestValue<>();
    /**
     * The last editor size, {@code {columns, rows}}, kept for a resize that Neovim asks for itself
     * ({@code :set number}) and while no editor is attached (D8). {@code null} until an editor sent one.
     */
    private volatile int[] editorSize;
    /** The grid last sent to Neovim: the same size is not sent twice. Input thread only. */
    private ViewportSize gridSent;
    /**
     * Set when {@link #resizeGrid} was skipped because Neovim waited for the rest of a command
     * ({@code z}, {@code r}, {@code "}): the resize is tried again after each piece of input-thread
     * work until it goes through. Input thread.
     */
    private boolean gridPending;
    /** Closes the editor of a buffer Neovim deleted by itself; see {@link #setBufferClosedHandler}. */
    private volatile Consumer<Integer> bufferClosedHandler;
    /** Pushes the Eclipse document to Neovim just before leaving insert mode. */
    private volatile Runnable leaveInsertPush;
    /** Saves a document when Neovim writes its buffer; see {@link #setWriteHandler}. */
    private volatile DocumentWrite.Handler writeHandler;

    /** Mode, buffer change, cursor, selection and IDE action listeners. */
    private final SessionEvents events = new SessionEvents();

    /** Single thread that performs all blocking RPC, preserving key order. */
    private final InputThread inputThread = new InputThread();
    /** The newest mouse drag not sent yet (see {@link #mouseAsync}). */
    private final LatestValue<MouseInput> pendingDrag = new LatestValue<>();
    /** Runs submitted {@code :} commands, listings, searches and {@code :normal!}. */
    private final CommandRunner commands = new CommandRunner(new CommandRunnerNeovim());
    /** Tells Neovim's command lines the user opened from the ones the plug-in types. */
    private final CmdlineWatcher cmdlineWatcher = new CmdlineWatcher();
    /** Neovim's command line, from its UI events; read and written on the RPC reader thread only. */
    private final CmdlineState cmdline = new CmdlineState();
    /** The view last passed to the command-line listeners. */
    private CmdlineState.View lastCmdline = CmdlineState.View.CLOSED;

    private NeovimSession(NeovimConnection connection, Handle homeBuffer, int homeWin, ClipboardPort clipboard) {
        this.connection = connection;
        this.api = connection.api();
        this.buffers = new DocumentBuffers(api, homeBuffer, homeWin);
        // StateSync tells KeyInput about every mode change, and KeyInput uses StateSync. So StateSync is
        // built first and given this::modeChanged, which reaches keyInput only when a mode arrives.
        this.state = new StateSync(new StateSyncNeovim(), events, status, buffers::isAttached,
                this::modeChanged);
        this.keyInput = new KeyInput(new KeyInputNeovim(), state, status, () -> leaveInsertPush);
        this.clipboard = new ClipboardRegisters(clipboard, System.lineSeparator());
    }

    // ---- lifecycle ----

    /** Starts a session with no clipboard integration (tests, headless use). */
    public static NeovimSession start(String nvimPath, List<String> extraArgs) throws Exception {
        return start(nvimPath, extraArgs, ClipboardPort.NONE, "0.0.0");
    }

    /**
     * Launches Neovim, attaches as a UI, creates a scratch buffer and installs the Lua hooks.
     *
     * @param nvimPath  preference value: blank, a folder or an absolute path (see {@link NvimExecutable})
     * @param extraArgs extra command-line arguments, e.g. {@code --clean} for tests
     * @param clipboard the system clipboard: receives text yanked in Neovim, and is read when Neovim
     *                  pastes from {@code "+} or {@code "*}
     * @param clientVersion this plug-in's version (OSGi {@code major.minor.micro.qualifier}), reported to Neovim
     * @throws Exception if the process cannot start or does not answer within {@value #RPC_TIMEOUT_SEC}s per call
     */
    public static NeovimSession start(String nvimPath, List<String> extraArgs, ClipboardPort clipboard,
            String clientVersion) throws Exception {
        NeovimConnection connection = NeovimConnection.open(nvimPath, extraArgs, clientVersion);
        try {
            return initialize(connection, clipboard);
        } catch (Exception e) {
            connection.close();
            throw e;
        }
    }

    /** Creates the home buffer, routes notifications here and installs the Lua hooks. */
    private static NeovimSession initialize(NeovimConnection connection, ClipboardPort clipboard) throws Exception {
        NeovimApi api = connection.api();

        // Until a document is shown, Neovim shows an unlisted scratch buffer (also what tests edit).
        Handle homeBuffer = await(api.createBuf(false, true));
        await(api.execLua(LuaScripts.SHOW_BUFFER, List.of(String.valueOf(homeBuffer.id()))));
        await(api.bufAttach(homeBuffer, true)); // line-change notifications
        await(api.execLua(LuaScripts.DELETE_STARTUP_BUFFER, List.of(String.valueOf(homeBuffer.id()))));
        // A window per editor, each in its own tabpage (docs/design/WINDOWS.md); the current window
        // stays the home window.
        Object homeWin = await(api.execLua(LuaScripts.WINDOWS, List.of(String.valueOf(homeBuffer.id()))));
        if (!(homeWin instanceof Number)) {
            throw new IllegalStateException("Unexpected answer installing the windows module: " + homeWin);
        }

        NeovimSession session = new NeovimSession(connection, homeBuffer, ((Number) homeWin).intValue(), clipboard);
        NotificationBridge bridge = new NotificationBridge(session.new EventSink());
        connection.setIncoming(bridge);

        // Asked as a plain RPC call, channel 0 is the caller: this is Eclipse's channel number.
        int channel = await(api.getChannelId());
        await(api.execLua(LuaScripts.SETUP, List.of(String.valueOf(channel))));
        await(api.execLua(LuaScripts.HOST_COMMANDS, List.of(HostCommand.rulesForNeovim())));
        await(api.execLua(LuaScripts.WINDOW_COMMANDS, List.of())); // after HOST_COMMANDS: its refuse hook
        // Once, here: the opt-out then costs nothing per key (docs/design/FOLDING.md, 3.6).
        session.foldsEnabled = Boolean.TRUE.equals(await(api.execLua(LuaScripts.FOLDS, List.of())));
        session.state.setFoldsEnabled(session.foldsEnabled);
        LOG.info(() -> "folds " + (session.foldsEnabled ? "mirrored into Neovim" : "off (g:neovim4eclipse_folds)"));
        // The same for the problems (docs/design/DIAGNOSTICS.md, "Turning it off").
        session.problemsEnabled = Boolean.TRUE.equals(await(api.execLua(LuaScripts.PROBLEMS, List.of())));
        LOG.info(() -> "Eclipse's problems " + (session.problemsEnabled ? "sent to Neovim" : "off (g:neovim4eclipse_problems)"));
        // And for the viewport (docs/design/VIEWPORT.md, D5): when off, the screen stays 120 × 40.
        session.viewportEnabled = Boolean.TRUE.equals(await(api.execLua(LuaScripts.VIEWPORT, List.of())));
        LOG.info(() -> "viewport " + (session.viewportEnabled ? "follows the editor" : "off (g:neovim4eclipse_viewport)"));
        Object clipboardOption = await(api.execLua(LuaScripts.GET_CLIPBOARD_OPTION, List.of()));
        session.nvimManagesClipboard = managesSystemClipboard(
                clipboardOption == null ? "" : String.valueOf(clipboardOption));
        LOG.info(() -> "Neovim 'clipboard' option = '" + clipboardOption + "'; "
                + (session.nvimManagesClipboard ? "Neovim manages the OS clipboard, bridge disabled"
                        : "bridge copies yanks to the OS clipboard"));

        session.state.readMode();
        LOG.info("NeovimSession ready");
        return session;
    }

    public boolean isAlive() {
        return !closed && connection.isAlive();
    }

    /** Alias of {@link #close()}. */
    public void shutdown() {
        close();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        connection.requestQuit();
        inputThread.shutdown();
        connection.close();
        LOG.info("NeovimSession closed");
    }

    // ---- state accessors and listeners ----

    /**
     * The errors the user's configuration ({@code init.lua}) reported while this Neovim started, or
     * "" if none: Neovim started anyway (see {@code NeovimConnection.waitForStartup}).
     */
    public String startupErrors() {
        return connection.startupErrors();
    }

    public String getMode() {
        return state.mode();
    }

    /** Last cursor reported by Neovim (used to restore the caret after the document is rewritten). */
    public CursorPos getLastCursor() {
        return state.lastCursor();
    }

    /** Insert/replace: printable keys are handled by Eclipse, not forwarded to Neovim. */
    public boolean isTypingMode() {
        return state.isTypingMode();
    }

    /**
     * Insert mode where Neovim, not Eclipse, must receive the typed keys: after a block-visual
     * insert, while recording a macro, or after {@link #driveInsertFromNeovim()}.
     */
    public boolean isNeovimDrivenInsert() {
        return keyInput.isNeovimDrivenInsert() && isTypingMode();
    }

    /**
     * Whether Esc is being handled now: the typed text replayed into Neovim, the document pushed,
     * the editor's top line sent ({@link #setLeaveInsertPush}). What Neovim reports meanwhile is of
     * a window about to be scrolled to the editor's view, so the editor ignores its top lines.
     */
    public boolean isLeavingInsertMode() {
        return keyInput.isLeavingInsertMode();
    }

    /** Whether Eclipse may type in the current insert mode. See {@link StateSync#isEclipseTypingReady()}. */
    public boolean isEclipseTypingReady() {
        return state.isEclipseTypingReady();
    }

    /**
     * Makes the rest of the current insert driven by Neovim: every key goes to Neovim until insert
     * mode ends. Used when a key arrives before Eclipse may type ({@link #isEclipseTypingReady()}):
     * once one key of an insert went to Neovim, Eclipse must not type the following ones itself, or
     * Neovim's update for the earlier key could overwrite them. Any thread.
     */
    public void driveInsertFromNeovim() {
        keyInput.driveFromNeovim();
    }

    public void setLeaveInsertPush(Runnable leaveInsertPush) {
        this.leaveInsertPush = leaveInsertPush;
    }

    /**
     * Runs at Esc on the input thread once Neovim's cursor is final, before Neovim's state is read:
     * the editor sends its view ({@link #showTopLine}). {@code null} for none.
     */
    public void setLeaveInsertView(Runnable view) {
        keyInput.setLeaveInsertView(view);
    }

    /** Register being recorded into, or "" when not recording. */
    public String getRecordingRegister() {
        return status.recordingRegister();
    }

    public boolean isRecording() {
        return !status.recordingRegister().isEmpty();
    }

    /** Whether Neovim is waiting for the argument character of a command ({@code f}, {@code @}, ...). */
    public boolean isWaitingForArgument() {
        return state.isWaitingForArgument();
    }

    /**
     * Notified (on a session thread) of output for the popup: a listing, shell output, or messages
     * of more than one line ({@code util/MessageRoute}). Passed again, whole, while it grows.
     */
    public void addOutputListener(Consumer<CommandOutput> listener) {
        status.addOutputListener(listener);
    }

    public void removeOutputListener(Consumer<CommandOutput> listener) {
        status.removeOutputListener(listener);
    }

    /**
     * What Neovim shows now: the message of the last command (until the next key) and the status
     * line items. An editor that Neovim moved into by itself ({@code :b}) shows it, as the message
     * may have arrived while the editor it left was still attached. Any thread.
     */
    public StatusInfo currentStatus() {
        return status.current();
    }

    /** Notified (on a session thread) when the command, message or statusline text changes. */
    public void addStatusListener(Consumer<StatusInfo> listener) {
        status.addStatusListener(listener);
    }

    public void removeStatusListener(Consumer<StatusInfo> listener) {
        status.removeStatusListener(listener);
    }

    /**
     * Chooses which optional parts the status bar shows, so that Neovim is only asked for those
     * (the mode is always known). Takes effect with the next key; call {@link #refreshStatusAsync()}
     * to apply it immediately.
     */
    public void setStatusItems(Set<StatusItem> items) {
        status.setItems(items);
    }

    /** Re-reads mode, cursor and status bar parts from Neovim (e.g. after the preference changed). */
    public void refreshStatusAsync() {
        submit("refresh status", Level.FINE, this::syncModeAndCursorFromNeovim);
    }

    /** Notified with the register name when recording starts, and with "" when it stops. */
    public void addRecordingListener(Consumer<String> listener) {
        status.addRecordingListener(listener);
    }

    public void removeRecordingListener(Consumer<String> listener) {
        status.removeRecordingListener(listener);
    }

    public void addModeListener(Consumer<String> listener) {
        events.addModeListener(listener);
    }

    public void removeModeListener(Consumer<String> listener) {
        events.removeModeListener(listener);
    }

    public void addBufferChangeListener(Consumer<BufferChange> listener) {
        events.addBufferChangeListener(listener);
    }

    public void removeBufferChangeListener(Consumer<BufferChange> listener) {
        events.removeBufferChangeListener(listener);
    }

    /**
     * Notified (on the input thread) after each key, command or other piece of queued work, once
     * Neovim has handled it. Every buffer change it caused has been delivered by then: Neovim sends
     * its change notifications before the answer to the work's last request, which the work waits
     * for (the state snapshot after a key). The editor's synchronizer uses it to make one undo step
     * of all the changes of one command ({@code J} changes one line and deletes the next).
     */
    @Override
    public void addInputHandledListener(Runnable listener) {
        events.addInputHandledListener(listener);
    }

    @Override
    public void removeInputHandledListener(Runnable listener) {
        events.removeInputHandledListener(listener);
    }

    public void addCursorListener(Consumer<CursorPos> listener) {
        events.addCursorListener(listener);
    }

    public void removeCursorListener(Consumer<CursorPos> listener) {
        events.removeCursorListener(listener);
    }

    public void addSelectionListener(Consumer<VisualSelection> listener) {
        events.addSelectionListener(listener);
    }

    public void removeSelectionListener(Consumer<VisualSelection> listener) {
        events.removeSelectionListener(listener);
    }

    /**
     * Notified (on an RPC thread) when Neovim hands a command to Eclipse: {@code :q} typed, from a
     * mapping or a macro, run as {@code :Eclipse quit} (see {@link HostCommand}).
     */
    public void addHostCommandListener(Consumer<HostCommand> listener) {
        events.addHostCommandListener(listener);
    }

    public void removeHostCommandListener(Consumer<HostCommand> listener) {
        events.removeHostCommandListener(listener);
    }

    /** Notified (on an RPC thread) when a key mapped in Neovim asks the IDE to act; see {@link IdeAction}. */
    public void addIdeActionListener(Consumer<IdeAction> listener) {
        events.addIdeActionListener(listener);
    }

    public void removeIdeActionListener(Consumer<IdeAction> listener) {
        events.removeIdeActionListener(listener);
    }

    /**
     * Notified (on an RPC thread) whenever Neovim's command line changes: opened (by {@code :},
     * {@code /}, a mapping, a count, {@code input()}), typed in, completed, or closed. The editor's
     * box draws the {@link CmdlineState.View}. The plug-in's own typed lines are not reported
     * ({@link CmdlineWatcher}).
     */
    public void addCmdlineListener(Consumer<CmdlineState.View> listener) {
        events.addCmdlineListener(listener);
    }

    public void removeCmdlineListener(Consumer<CmdlineState.View> listener) {
        events.removeCmdlineListener(listener);
    }

    // ---- per-document buffers (see DocumentBuffers) ----

    /**
     * Makes {@code buffer} the attached buffer and Neovim's current one, creating it on first use.
     * Blocking: input thread only.
     */
    public void showBuffer(DocumentBuffer buffer) throws Exception {
        buffers.show(buffer);
    }

    /**
     * Makes {@code buffer} the attached buffer, shown in the editor's own Neovim window, which
     * becomes the current one: opened in a new tabpage on first use, and again after Restart Neovim
     * or after it was closed in Neovim (docs/design/WINDOWS.md). Blocking: input thread only.
     *
     * @param window the editor's window token; {@code null} shows the buffer in the home window,
     *               as {@link #showBuffer} does
     * @param folder the window's folder ({@code :lcd}), set once when it is opened: normally the
     *               editor's project; {@code null} or "" (an untitled document) takes the folder of
     *               the editor attached before, as a Vim window inherits it, or the home folder if
     *               none was. Ignored when {@code 'autochdir'} is set.
     */
    public void showWindow(DocumentBuffer buffer, EditorWindow window, String folder) throws Exception {
        buffers.show(buffer, window, folder);
    }

    /**
     * Closes an editor's window and its tabpage (the editor closed). The buffer stays for the
     * document's other editors; {@link #closeBufferAsync} wipes it after the last.
     */
    public void closeWindowAsync(EditorWindow window) {
        submit("close window", Level.FINE, () -> buffers.closeWindow(window));
    }

    /**
     * A window travelling on {@code Ctrl-O} across files (docs/design/WINDOWS.md, E2): {@code mover},
     * the attached editor's window, has jumped into the buffer {@code other}'s editor shows. The two
     * editors swap windows; {@code other}'s old window gets {@code leftBehind} with the attached
     * editor's last cursor in it. The caller then shows {@code other}'s editor as usual. Blocking:
     * input thread only.
     */
    public void travelWindow(EditorWindow mover, EditorWindow other, DocumentBuffer leftBehind) throws Exception {
        // The last cursor reported in the attached buffer: the cursor reports of the jump, in the
        // other buffer, were not taken (DocumentBuffers.isAttached).
        CursorPos cursor = state.lastCursor();
        buffers.travel(mover, other, leftBehind, cursor.line1Based(), cursor.col0Based());
    }

    /**
     * {@link #travelWindow}, queued on the input thread, so that the attach that follows (its push
     * shows {@code other}'s editor) runs after it. UI thread.
     */
    public void travelWindowAsync(EditorWindow mover, EditorWindow other, DocumentBuffer leftBehind) {
        submit("window travel", Level.WARNING, () -> travelWindow(mover, other, leftBehind));
    }

    /** The id of the window showing the attached buffer (an editor's own, or the home window). */
    public int getAttachedWinid() {
        return buffers.attachedWin();
    }

    /** Wipes a document's buffer, with its undo history and marks (its last editor closed). */
    public void closeBufferAsync(DocumentBuffer buffer) {
        submit("close buffer", Level.FINE, () -> buffers.close(buffer));
    }

    /**
     * Handles Neovim switching by itself to the buffer of another open document; the handler should
     * show that document, or call {@link #returnToAttachedBufferAsync()}. Without a handler, and for
     * any buffer that is not a document's, Neovim is switched back to the attached buffer.
     */
    public void setBufferEnterHandler(Consumer<Integer> handler) {
        buffers.setEnterHandler(handler);
    }

    /**
     * Handles Neovim deleting a document's buffer, or quitting its window, by itself
     * ({@code <Cmd>bd}, {@code vim.cmd.quit()}; the safety nets of {@link LuaScripts#HOST_COMMANDS}).
     * The handler should close the editor showing that buffer, if any. Called on an RPC thread; not
     * while the session is closing (Eclipse's own {@code :qa!} fires {@code QuitPre} too).
     */
    public void setBufferClosedHandler(Consumer<Integer> handler) {
        this.bufferClosedHandler = handler;
    }

    /** Switches Neovim back to the attached buffer (it entered one that Eclipse does not show). */
    public void returnToAttachedBufferAsync() {
        submit("return to attached buffer", Level.FINE, buffers::returnToAttached);
    }

    @Override
    public boolean setDocumentBufferLines(DocumentBuffer buffer, List<String> lines) throws Exception {
        Handle handle = buffers.handleOf(buffer);
        if (handle == null || !buffer.loaded) {
            return false;
        }
        List<String> target = lines.isEmpty() ? List.of("") : lines; // a buffer always has one line
        List<String> current = await(api.bufGetLines(handle, 0, -1, false));
        LineDiff.Change change = LineDiff.between(current == null ? List.of() : current, target);
        if (change == null) {
            return true;
        }
        writingBufnr = buffer.bufnr;
        try {
            await(api.bufSetLines(handle, change.start(), change.end(), false, change.replacement()));
        } finally {
            writingBufnr = NotificationBridge.UNKNOWN_BUFFER;
        }
        applyModified(buffer);
        return true;
    }

    @Override
    public void addUnattachedChangeListener(DocumentBuffer buffer, Consumer<BufferChange> listener) {
        unattachedListeners.put(buffer, listener);
    }

    @Override
    public void removeUnattachedChangeListener(DocumentBuffer buffer, Consumer<BufferChange> listener) {
        unattachedListeners.remove(buffer, listener);
    }

    /**
     * Saves the document when Neovim writes its buffer ({@code :w} from a mapping, {@code :update},
     * {@code :bufdo update}, {@code :wall}; see {@link LuaScripts#DOCUMENT_BUFFER}). Called on the RPC
     * thread while Neovim waits for the answer, so it must return, even if the UI thread is busy.
     *
     * @param handler saves the document and answers with the written message or the error;
     *                without a handler a write fails with a message
     */
    public void setWriteHandler(DocumentWrite.Handler handler) {
        this.writeHandler = handler;
    }

    /** Number of the buffer mirrored into the attached document. */
    public int getAttachedBufnr() {
        return buffers.attachedBufnr();
    }

    /**
     * Number of the buffer Neovim entered last, as far as its notifications have been read. A buffer
     * enter handler compares it with its argument to skip a buffer Neovim has already left again
     * ({@code :bufdo} walks through every buffer). Any thread.
     */
    public int getEnteredBufnr() {
        return buffers.enteredBufnr();
    }

    // ---- buffer and cursor access (blocking) ----

    /**
     * Makes the attached buffer's text equal to {@code lines}. Only the differing line range is
     * replaced, and nothing at all when the text is equal, so that pushing the document does not add
     * undo steps or delete the marks of unchanged lines.
     *
     * <p>The first push into a new buffer loads the file: like Vim reading a file, it is not an undo
     * step ({@link LuaScripts#LOAD_WITHOUT_UNDO}), so {@code u} right after opening does nothing.
     */
    public void setBufferLines(List<String> lines) throws Exception {
        List<String> target = lines.isEmpty() ? List.of("") : lines; // a buffer always has one line
        DocumentBuffer document = buffers.attachedDocument();
        if (document != null && !document.loaded) {
            await(api.execLua(LuaScripts.LOAD_WITHOUT_UNDO, target));
            document.loaded = true;
            applyModified(document);
            return;
        }
        LineDiff.Change change = LineDiff.between(getBufferLines(), target);
        if (change != null) {
            await(api.bufSetLines(buffers.attached(), change.start(), change.end(), false, change.replacement()));
            if (document != null) {
                applyModified(document); // the change set 'modified'; Eclipse decides (a revert is not dirty)
            }
        }
    }

    /**
     * Eclipse's dirty flag for {@code buffer}'s document changed (UI thread, from the editor's dirty
     * property): Neovim's {@code 'modified'} follows it ({@link LuaScripts#SET_MODIFIED}). Every push of
     * Eclipse's text sets it again, since writing lines marks a buffer modified. Any thread.
     */
    public void setModifiedAsync(DocumentBuffer buffer, boolean dirty) {
        buffer.eclipseDirty = dirty;
        submit("modified " + dirty + " for " + buffer.name(), Level.FINE, () -> applyModified(buffer));
    }

    /** Sets the buffer's {@code 'modified'} to its document's dirty flag, if the buffer exists. */
    private void applyModified(DocumentBuffer buffer) throws Exception {
        if (buffers.handleOf(buffer) == null || !buffer.loaded) {
            return;
        }
        await(api.execLua(LuaScripts.SET_MODIFIED, List.of(String.valueOf(buffer.bufnr), buffer.eclipseDirty ? "1" : "0")));
    }

    @Override
    public void setDiagnosticsAsync(DocumentBuffer buffer, List<Diagnostic> diagnostics) {
        List<Diagnostic> copy = List.copyOf(diagnostics);
        submit(copy.size() + " problems for " + buffer.name(), Level.FINE, () -> {
            // Not created yet, wiped, or from a Neovim that was restarted: the number could be another
            // buffer's. Not loaded: the problems would land in an empty buffer, before its text.
            if (buffers.handleOf(buffer) == null || !buffer.loaded) {
                return;
            }
            await(api.execLua(LuaScripts.SET_DIAGNOSTICS, Diagnostic.toLuaArgs(buffer.bufnr, copy)));
        });
    }

    @Override
    public boolean problemsEnabled() {
        return problemsEnabled;
    }

    @Override
    public boolean foldsEnabled() {
        return foldsEnabled;
    }

    @Override
    public void setFoldsAsync(DocumentBuffer buffer, List<FoldRegions.Fold> folds) {
        if (!foldsEnabled) {
            return;
        }
        List<FoldRegions.Fold> copy = List.copyOf(folds);
        submit(copy.size() + " folds for " + buffer.name(), Level.FINE, () -> {
            // As for the diagnostics: the number could be another buffer's, or the text not there yet.
            // SET_FOLDS itself skips a buffer that is not the current one. The number is read here, on
            // the input thread: at attach the buffer is created by the push queued just before.
            if (buffers.handleOf(buffer) == null || !buffer.loaded) {
                return;
            }
            await(api.execLua(LuaScripts.SET_FOLDS, FoldRegions.toLuaArgs(buffer.bufnr, copy)));
        });
    }

    @Override
    public boolean viewportEnabled() {
        return viewportEnabled;
    }

    @Override
    public void setEditorSizeAsync(int columns, int rows) {
        if (!viewportEnabled) {
            return;
        }
        if (pendingEditorSize.offer(new int[] { columns, rows })) {
            submit("editor size", Level.FINE, () -> {
                int[] latest = pendingEditorSize.take();
                if (latest != null) {
                    editorSize = latest;
                    resizeGrid();
                }
            });
        }
    }

    /**
     * Resizes Neovim's screen for the last editor size: asks Neovim what its screen has besides the
     * window ({@link LuaScripts#VIEWPORT_OVERHEAD}), adds it ({@link ViewportSize#forEditor}) and
     * sends the grid if it changed. Skipped while Neovim waits for the rest of a command
     * ({@code nvim_get_mode().blocking}: after {@code z}, {@code r}, {@code "}, in {@code getchar()}):
     * it would not answer the overhead question then. The skip is remembered ({@link #gridPending})
     * and the resize sent after the key that ends the command. Example: {@code z} typed, the window
     * resized, then {@code <Esc>}: the resize goes out after the {@code <Esc>}. Input thread.
     */
    private void resizeGrid() throws Exception {
        int[] size = editorSize;
        if (size == null) {
            return;
        }
        if (isBlocked()) {
            gridPending = true;
            return;
        }
        gridPending = false;
        Object answer = await(api.execLua(LuaScripts.VIEWPORT_OVERHEAD, List.of()));
        ViewportSize.Overhead overhead = ViewportSize.Overhead.NONE;
        if (answer instanceof List<?> pair && pair.size() == 2 && pair.get(0) instanceof Number rows
                && pair.get(1) instanceof Number columns) {
            overhead = new ViewportSize.Overhead(Math.max(0, rows.intValue()), Math.max(0, columns.intValue()));
        }
        ViewportSize grid = ViewportSize.forEditor(size[0], size[1], overhead);
        if (!grid.equals(gridSent)) {
            ViewportSize.Overhead used = overhead;
            LOG.fine(() -> "Neovim's screen " + grid + " for an editor of " + size[0] + "×" + size[1]
                    + " (overhead " + used.rows() + " rows, " + used.columns() + " columns)");
            await(api.uiTryResize(grid.columns(), grid.rows()));
            gridSent = grid;
        }
    }

    @Override
    public void setWrapAsync(DocumentBuffer buffer, boolean wrap) {
        if (!viewportEnabled) {
            return;
        }
        submit("wrap " + wrap + " for " + buffer.name(), Level.FINE, () -> {
            if (buffers.handleOf(buffer) == null || !buffer.loaded) {
                return; // not created yet, or gone; SET_WRAP itself skips a buffer not shown
            }
            await(api.execLua(LuaScripts.SET_WRAP, List.of(String.valueOf(buffer.bufnr), flag(wrap))));
        });
    }

    @Override
    public void setTopLineAsync(DocumentBuffer buffer, int line1) {
        if (!viewportEnabled || line1 < 1) {
            return;
        }
        submit("top line " + line1 + " for " + buffer.name(), Level.FINE, () -> sendTopLine(buffer, line1, "keep"));
    }

    @Override
    public void scrollToAsync(DocumentBuffer buffer, int line1) {
        if (!viewportEnabled || line1 < 1) {
            return;
        }
        if (pendingScroll.offer(new Scroll(buffer, line1))) {
            submit("scroll", Level.FINE, () -> {
                Scroll latest = pendingScroll.take();
                if (latest != null) {
                    sendTopLine(latest.buffer(), latest.line1(), "drag");
                }
            });
        }
    }

    @Override
    public void scrollLinesAsync(DocumentBuffer buffer, int lines) {
        if (!viewportEnabled || lines == 0) {
            return;
        }
        wheelBuffer = buffer;
        // Added up, not replaced: every wheel event counts. The task takes the sum so far; one
        // that finds 0 (events that cancelled out) sends nothing.
        if (pendingWheelLines.getAndAdd(lines) == 0) {
            submit("wheel", Level.FINE, () -> {
                int sum = pendingWheelLines.getAndSet(0);
                DocumentBuffer target = wheelBuffer;
                if (sum != 0 && target != null && buffers.handleOf(target) != null && target.loaded) {
                    await(api.execLua(LuaScripts.SCROLL_LINES,
                            List.of(String.valueOf(target.bufnr), String.valueOf(sum))));
                }
            });
        }
    }

    /** A scroll of the editor, for {@link #pendingScroll}. */
    private record Scroll(DocumentBuffer buffer, int line1) {
    }

    /**
     * At Esc, after Eclipse scrolled alone while it typed (decision D6): Neovim's window takes the
     * editor's top line, and its cursor is dragged into that view only if it is outside it (mode
     * {@code show} of {@link LuaScripts#SET_TOPLINE}). So the view stays where it was scrolled, as
     * with Neovim's own wheel, where the cursor would have been dragged along. Example: lines 1 to
     * 20 shown, the caret on line 10, the wheel scrolls the editor to 60; at Esc Neovim's window
     * shows 60 to 79 with the cursor on 60. Runs once the cursor is final
     * ({@link #setLeaveInsertView}). Input thread only.
     */
    public void showTopLine(DocumentBuffer buffer, int line1) throws Exception {
        sendTopLine(buffer, line1, "show");
    }

    /** {@link LuaScripts#SET_TOPLINE}: {@code mode} is {@code keep}, {@code drag} or {@code show}. Input thread. */
    private void sendTopLine(DocumentBuffer buffer, int line1, String mode) throws Exception {
        if (!viewportEnabled || line1 < 1 || buffers.handleOf(buffer) == null || !buffer.loaded) {
            return; // off, not created yet, or gone; SET_TOPLINE itself skips a buffer not shown
        }
        await(api.execLua(LuaScripts.SET_TOPLINE,
                List.of(String.valueOf(buffer.bufnr), String.valueOf(line1), mode)));
    }

    @Override
    public void addTopLineListener(Consumer<TopLine> listener) {
        events.addTopLineListener(listener);
    }

    @Override
    public void removeTopLineListener(Consumer<TopLine> listener) {
        events.removeTopLineListener(listener);
    }

    @Override
    public void addWrapListener(Consumer<WrapChange> listener) {
        events.addWrapListener(listener);
    }

    @Override
    public void removeWrapListener(Consumer<WrapChange> listener) {
        events.removeWrapListener(listener);
    }

    @Override
    public void addFoldStateListener(Consumer<FoldState> listener) {
        events.addFoldListener(listener);
    }

    @Override
    public void removeFoldStateListener(Consumer<FoldState> listener) {
        events.removeFoldListener(listener);
    }

    public List<String> getBufferLines() throws Exception {
        return getBufferLines(Duration.ofSeconds(RPC_TIMEOUT_SEC));
    }

    private List<String> getBufferLines(Duration timeout) throws Exception {
        List<String> lines = api.bufGetLines(buffers.attached(), 0, -1, false).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return lines != null ? lines : List.of();
    }

    /** @param line1Based 1-based line; @param col0Based 0-based UTF-8 byte column */
    public void setCursor(int line1Based, int col0Based) throws Exception {
        await(api.winSetCursor(Handle.CURRENT_WINDOW, new WinCursor(line1Based, col0Based)));
        state.rememberCursor(new CursorPos(line1Based, col0Based));
    }

    public CursorPos getCursor() throws Exception {
        WinCursor cursor = await(api.winGetCursor(Handle.CURRENT_WINDOW));
        // WinCursor is the RPC layer's type, CursorPos the listeners' (see WinCursor): same meaning.
        return cursor == null ? new CursorPos(1, 0) : new CursorPos(cursor.row(), cursor.col());
    }

    /**
     * Sets the attached buffer's tab and indent options. {@code 'tabstop'} is the editor's tab width,
     * so that the block selection Neovim computes in screen columns lines up with what Eclipse draws;
     * the indent options make {@code o}, {@code O}, {@code >>} indent like the code around them (see
     * {@link Indentation}). Blocking: input thread only.
     *
     * @param tabWidth the editor's displayed tab width
     */
    public void setIndentation(int tabWidth, Indentation indentation) throws Exception {
        await(api.execLua(LuaScripts.SET_INDENTATION, List.of(
                String.valueOf(tabWidth),
                flag(indentation.useTabs()),
                String.valueOf(indentation.width()),
                flag(indentation.braces()),
                indentation.cinoptions())));
    }

    /** A Lua script argument for a boolean: {@code "1"} or {@code "0"}. */
    private static String flag(boolean enabled) {
        return enabled ? "1" : "0";
    }

    /** Evaluates Lua in Neovim and returns its result. For tests that inspect Neovim's state. */
    Object lua(String code) throws Exception {
        return await(api.execLua(code, List.of()));
    }

    /** Runs an ex command (without the leading {@code :}). */
    public void command(String cmd) throws Exception {
        await(api.command(cmd));
    }

    // ---- asynchronous entry points (safe from the UI thread) ----

    /** Runs attach-time setup (document push, initial cursor) on the input thread. */
    public void runAttachSetup(Runnable work) {
        runAsync("attach setup", work);
    }

    /**
     * Runs {@code work} on the input thread, after every key and command queued before it. For
     * blocking calls from editor code, such as pushing the document.
     *
     * @param what short description for the log, should {@code work} fail
     */
    public void runAsync(String what, Runnable work) {
        submit(what, Level.WARNING, work::run);
    }

    /** Queues one key (Neovim notation) for ordered processing. */
    public void inputAsync(String keys) {
        submit("input '" + keys + "'", Level.WARNING, () -> input(keys));
    }

    /**
     * Hands a mouse event in the editor to Neovim's own mouse ({@link KeyInput#mouse}), on the input
     * thread, in order with the keys. Drags that arrive faster than Neovim answers replace each other
     * ({@link LatestValue}): only the newest position is sent, and a release still comes after it.
     */
    public void mouseAsync(MouseInput event) {
        if (event.action() == MouseInput.Action.DRAG) {
            if (pendingDrag.offer(event)) {
                submit("mouse drag", Level.FINE, () -> {
                    MouseInput latest = pendingDrag.take();
                    if (latest != null) {
                        keyInput.mouse(latest);
                    }
                });
            }
            return;
        }
        submit("mouse " + event.action().luaName(), Level.FINE, () -> keyInput.mouse(event));
    }

    /** Moves the Neovim cursor (e.g. after a mouse click in Eclipse). */
    public void setCursorAsync(int line1Based, int col0Based) {
        submit("setCursor", Level.FINE, () -> {
            if (!isBlocked()) { // while Neovim waits for the rest of a command it would not answer
                setCursor(line1Based, col0Based);
            }
        });
    }

    /**
     * Types {@code :cmd<CR>}, then refreshes buffer, mode and cursor in Eclipse. What the command
     * prints reaches the status and output listeners ({@link #addOutputListener}). See
     * {@link CommandRunner#run}.
     */
    public void commandAsync(String cmd) {
        submit("command " + cmd, Level.WARNING, () -> commands.run(cmd));
    }

    /**
     * Evaluates a Vim expression ({@code bufnr('other.txt')}, {@link LuaScripts#EVAL}) and passes its
     * value as text, or the error message, to {@code onResult}, on a session thread. Not typed: it
     * asks Neovim something, so it belongs in no macro or history.
     */
    public void evalAsync(String expression, Consumer<String> onResult) {
        submit("eval " + expression, Level.WARNING, () -> {
            String result;
            try {
                Object raw = await(api.execLua(LuaScripts.EVAL, List.of(expression)));
                result = raw == null ? "" : String.valueOf(raw);
            } catch (Exception e) {
                result = Rpc.errorMessage(e);
            }
            onResult.accept(result);
        });
    }

    /**
     * Pastes {@code text} where Neovim's cursor is, as a paste ({@code nvim_paste}): in the command
     * line, the box's Ctrl+V or an input method's committed text. On the input thread, in order with
     * the keys.
     */
    public void pasteAsync(String text) {
        submit("paste", Level.WARNING, () -> await(api.execLua(LuaScripts.PASTE, List.of(text))));
    }

    /**
     * Moves Neovim out of {@code folder} ({@link LuaScripts#LEAVE_FOLDER}) and waits until it has,
     * for at most {@code timeout}: called before Eclipse deletes or closes the project in that
     * folder, which Windows refuses while Neovim's process works in it.
     *
     * <p>The one wait for Neovim the UI thread may do (Eclipse deletes a project from the UI thread
     * too). It is bounded, so that a Neovim that holds requests (waiting for the character after
     * {@code f}) or waits for the UI thread itself costs at most {@code timeout}, after which the
     * delete goes on as it would have without this call. Sent straight to Neovim, not queued on the
     * input thread: work there can wait for the UI thread (a key whose changes the UI thread
     * applies), which here is waiting for this answer, so a queued request missed the bound in
     * about one project delete out of five in the UI tests. Writes to Neovim are serialized by the
     * RPC channel, so this cannot cut into a request of the input thread.
     *
     * @return how many working folders Neovim moved, or {@code -1} when it did not answer in time,
     *         failed, or is closed
     */
    public int leaveFolder(String folder, Duration timeout) {
        if (!isAlive()) {
            return -1;
        }
        try {
            Object moved = api.execLua(LuaScripts.LEAVE_FOLDER, List.of(folder)).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return moved instanceof Number n ? n.intValue() : 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (java.util.concurrent.TimeoutException e) {
            LOG.warning(() -> "Neovim did not leave " + folder + " within " + timeout.toMillis() + " ms");
            return -1;
        } catch (Exception e) { // Neovim's error, or a closed connection
            LOG.log(Level.WARNING, "Neovim could not leave " + folder, e);
            return -1;
        }
    }

    /** Runs normal-mode {@code keys} without mappings, then refreshes Eclipse. See {@link CommandRunner#normal}. */
    public void normalAsync(String keys) {
        submit("normal! " + keys, Level.WARNING, () -> commands.normal(keys));
    }

    /** Leaves insert mode without replaying typed text (see {@link #leaveInsertModeAsync(InsertReplay.Plan)}). */
    public void leaveInsertModeAsync() {
        leaveInsertModeAsync(null);
    }

    /**
     * Leaves insert mode after Eclipse handled the typing, re-typing the text into Neovim first when
     * {@code replay} allows it, so that {@code .} repeats it. See {@link KeyInput#leaveInsertMode}.
     *
     * @param replay what to re-type, or {@code null}
     */
    public void leaveInsertModeAsync(InsertReplay.Plan replay) {
        submit("leave insert mode", Level.WARNING, () -> keyInput.leaveInsertMode(replay));
    }

    /** See {@link InputThread#submit}; {@code false} if {@code work} will never run. */
    /**
     * Queues {@code work} on the input thread. Afterward, even if it failed, the input-handled
     * listeners hear that Neovim has done what it asked (see {@link #addInputHandledListener}).
     */
    private boolean submit(String what, Level failureLevel, InputThread.Task work) {
        return inputThread.submit(what, failureLevel, () -> {
            try {
                work.run();
                if (gridPending && !closed) {
                    resizeGrid(); // skipped while Neovim waited; this work may have ended the wait
                }
            } finally {
                events.inputHandled();
            }
        });
    }

    // ---- key input (see KeyInput) ----

    /** Sends one key to Neovim and refreshes Eclipse. Blocking: call on the input thread. See {@link KeyInput#input}. */
    public void input(String keys) throws Exception {
        keyInput.input(keys);
    }

    /** One mouse event, handled by Neovim's mouse. Blocking: call on the input thread. See {@link KeyInput#mouse}. */
    public void mouse(MouseInput event) throws Exception {
        keyInput.mouse(event);
    }

    // ---- synchronization from Neovim to listeners ----

    /** Whether Neovim is waiting for the rest of a command (and would defer most API calls). */
    private boolean isBlocked() {
        return state.isBlocked();
    }

    /**
     * Reads mode, cursor and visual selection from Neovim and notifies listeners, unless Neovim
     * waits for the rest of a command. See {@link StateSync#syncModeAndCursor()}.
     */
    public void syncModeAndCursorFromNeovim() {
        state.syncModeAndCursor();
    }

    /**
     * Pulls the whole buffer and publishes it as a full-buffer change. Not for per-key use. See
     * {@link StateSync#syncBuffer()}.
     */
    public void syncBufferFromNeovim() {
        state.syncBuffer();
    }

    /** Whether a {@code 'clipboard'} option value makes Neovim use the OS clipboard for yank/put. */
    static boolean managesSystemClipboard(String clipboardOption) {
        return clipboardOption != null && clipboardOption.contains("unnamed"); // unnamed, unnamedplus
    }

    /** Every mode change reaches KeyInput before the listeners (see the constructor). */
    private void modeChanged(String mode) {
        keyInput.onModeChanged(mode);
    }

    // ---- for DocumentBuffer (package-private) ----

    /** The per-document buffer registry, for {@link DocumentBuffer#isOpenIn}. */
    DocumentBuffers buffers() {
        return buffers;
    }

    /** Routes {@link NotificationBridge} events into this session. */
    private final class EventSink implements NotificationBridge.Sink {
        @Override
        public void onMode(String mode) {
            state.dispatchMode(mode);
        }

        @Override
        public void onCursor(int line1, int col0, int bufnr, int winid) {
            if (buffers.isAttached(winid, bufnr)) {
                state.dispatchCursor(line1, col0);
            }
        }

        @Override
        public void onBufferLines(int bufnr, int firstLine, int lastLine, List<String> newLines) {
            if (bufnr != NotificationBridge.UNKNOWN_BUFFER && bufnr == writingBufnr) {
                return; // the echo of Eclipse's own write (setDocumentBufferLines)
            }
            if (buffers.isAttached(bufnr)) {
                state.dispatchBufferChange(firstLine, lastLine, newLines);
                return;
            }
            // Another document's buffer (:bufdo, :argdo, a plug-in), or one that is no document's
            // (help, :enew), which Eclipse does not mirror.
            DocumentBuffer document = buffers.documentOf(bufnr);
            Consumer<BufferChange> listener = document == null ? null : unattachedListeners.get(document);
            if (listener != null) {
                try {
                    listener.accept(new BufferChange(firstLine, lastLine, newLines));
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Change of buffer " + bufnr + " not mirrored", e);
                }
            }
        }

        @Override
        public void onBufferEnter(int bufnr, int winid) {
            buffers.onEntered(bufnr, winid, NeovimSession.this::returnToAttachedBufferAsync);
        }

        @Override
        public void onWindowClosed(int winid) {
            if (!closed) {
                // On the input thread, where the windows change: in order with the shows queued there.
                submit("window closed", Level.FINE, () -> buffers.onWindowClosed(winid));
            }
        }

        @Override
        public void onBufferClosed(int bufnr) {
            Consumer<Integer> handler = bufferClosedHandler;
            if (!closed && handler != null) {
                LOG.fine(() -> "Neovim closed buffer " + bufnr + " by itself");
                handler.accept(bufnr);
            }
        }

        @Override
        public void onYank(List<String> lines, String regtype) {
            LOG.fine(() -> "yank regtype=" + regtype + " lines=" + lines.size()
                    + " bridge=" + !nvimManagesClipboard);
            if (!nvimManagesClipboard) {
                clipboard.copyYank(lines, regtype);
            }
        }

        @Override
        public void onClipboardCopy(String register, List<String> lines, String regtype) {
            LOG.fine(() -> "clipboard copy register=" + register + " regtype=" + regtype + " lines=" + lines.size());
            clipboard.copy(register, lines, regtype);
        }

        @Override
        public DocumentWrite.Result onWrite(int bufnr, DocumentWrite.Request request) {
            DocumentBuffer document = buffers.documentOf(bufnr);
            DocumentWrite.Handler handler = writeHandler;
            if (document == null || handler == null) {
                return DocumentWrite.Result.failed("Eclipse has no editor for buffer " + bufnr + "; nothing was saved");
            }
            return handler.save(document, request);
        }

        @Override
        public Object onClipboardPaste(String register) {
            ClipboardRegisters.Paste paste = clipboard.paste(register);
            LOG.fine(() -> "clipboard paste register=" + register + " regtype=" + paste.regtype()
                    + " lines=" + paste.lines().size());
            return paste.toRpc();
        }

        @Override
        public void onWrap(int bufnr, boolean wrap, int winid) {
            if (winid != EditorWindow.UNKNOWN && winid != buffers.attachedWin()) {
                return; // another editor's window (a script ran there); its editor keeps its word wrap
            }
            LOG.fine(() -> "Neovim set " + (wrap ? "wrap" : "nowrap") + " for buffer " + bufnr);
            events.wrap(new WrapChange(bufnr, wrap));
        }

        @Override
        public void onTopLine(int bufnr, int line1, int winid) {
            if (viewportEnabled && buffers.isAttached(winid, bufnr)) {
                LOG.finest(() -> "Neovim's top line " + line1 + " in buffer " + bufnr);
                events.topLine(new TopLine(bufnr, line1));
            }
        }

        @Override
        public void onViewportOverheadChanged() {
            if (!closed && viewportEnabled) {
                submit("viewport overhead", Level.FINE, NeovimSession.this::resizeGrid);
            }
        }

        @Override
        public void onIdeAction(IdeAction action) {
            LOG.fine(() -> "IDE action " + action);
            events.ideAction(action);
        }

        @Override
        public void onHostCommand(HostCommand command) {
            LOG.fine(() -> "host command " + command);
            events.hostCommand(command);
        }

        @Override
        public void onMessageEvent(MessageEvent event) {
            status.onMessageEvent(event);
        }

        /**
         * Feeds the command-line state and passes a changed view on once per notification. While a
         * search is typed, Neovim's view follows the match ({@code 'incsearch'}); its cursor goes
         * to the editor's caret as any cursor from Neovim does, and Esc or Enter then sets it for
         * good through the state read after that key.
         */
        @Override
        public void onCmdline(List<CmdlineEvent> cmdlineEvents) {
            long now = System.currentTimeMillis();
            boolean changed = false;
            for (CmdlineEvent event : cmdlineEvents) {
                if (event instanceof CmdlineEvent.Show show && show.level() == 1) {
                    status.commandLine(show.firstc() + show.content()); // the title of its output
                }
                if (cmdlineWatcher.isOwn(event, now)) {
                    continue;
                }
                if (event instanceof CmdlineEvent.Viewport viewport) {
                    if (cmdline.view().isSearch()) {
                        state.dispatchCursor(viewport.curline() + 1, viewport.curcol());
                    }
                    continue;
                }
                changed |= cmdline.apply(event);
            }
            CmdlineState.View view = cmdline.view();
            if (changed && !view.equals(lastCmdline)) {
                lastCmdline = view;
                events.cmdline(view);
            }
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return Rpc.await(future);
    }

    /** Gives {@link CommandRunner} the Neovim calls it needs. */
    private final class CommandRunnerNeovim implements CommandRunner.Neovim {
        @Override
        public void type(String keys) throws Exception {
            // :cmd<CR> and /pattern<CR> open a command line of the plug-in's own: tell the
            // watcher, so that Neovim's report of it is not taken for one the user opened.
            if (keys.startsWith(":") || keys.startsWith("/") || keys.startsWith("?")) {
                cmdlineWatcher.expectOwn(keys.substring(0, 1), System.currentTimeMillis());
            }
            await(api.input(keys));
        }

        @Override
        public void awaitIdle(Duration timeout) throws Exception {
            api.execLua("return 0", List.of()).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void clearMessages() {
            status.clearMessage();
        }

        @Override
        public void normalWithoutMappings(String keys) throws Exception {
            await(api.execLua(LuaScripts.NORMAL_WITHOUT_MAPPINGS, List.of(keys)));
        }

        @Override
        public void syncBuffer() {
            syncBufferFromNeovim();
        }

        @Override
        public void syncModeAndCursor() {
            syncModeAndCursorFromNeovim();
        }
    }

    /** Gives {@link StateSync} the Neovim calls it needs. */
    private final class StateSyncNeovim implements StateSync.Neovim {
        @Override
        public Mode getMode() throws Exception {
            return await(api.getMode());
        }

        @Override
        public Object stateSnapshot(boolean statusline, boolean folds) throws Exception {
            // Short timeout: see STATE_TIMEOUT_MS.
            return api.execLua(LuaScripts.STATE_SNAPSHOT, List.of(flag(statusline), flag(folds)))
                    .get(STATE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        @Override
        public List<String> bufferLines() throws Exception {
            return getBufferLines();
        }
    }

    /** Gives {@link KeyInput} the raw Neovim calls it makes itself. */
    private final class KeyInputNeovim implements KeyInput.Neovim {
        @Override
        public void type(String keys) throws Exception {
            await(api.input(keys));
        }

        @Override
        public void awaitKeysConsumed() throws Exception {
            await(api.execLua("return 0", List.of()));
        }

        @Override
        public List<String> bufferLines(Duration timeout) throws Exception {
            return getBufferLines(timeout);
        }

        @Override
        public void setCursor(int line1, int col0) throws Exception {
            NeovimSession.this.setCursor(line1, col0);
        }

        @Override
        public void mouse(MouseInput event) throws Exception {
            await(api.execLua(LuaScripts.MOUSE, List.of("left", event.action().luaName(), event.modifiers(),
                    String.valueOf(event.line1()), String.valueOf(event.col0()))));
        }

        private static String text(Object raw) {
            return raw == null ? "" : String.valueOf(raw);
        }
    }
}
