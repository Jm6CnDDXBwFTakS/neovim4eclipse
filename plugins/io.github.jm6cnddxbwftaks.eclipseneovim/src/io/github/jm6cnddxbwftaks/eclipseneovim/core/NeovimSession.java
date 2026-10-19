// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ensarsarajcic.neovim.java.api.NeovimApi;
import com.ensarsarajcic.neovim.java.api.buffer.NeovimBufferApi;
import com.ensarsarajcic.neovim.java.api.types.api.VimCoords;
import com.ensarsarajcic.neovim.java.api.types.api.VimMode;
import com.ensarsarajcic.neovim.java.api.window.NeovimWindowApi;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc.Mode;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.Indentation;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.InsertReplay;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.LineDiff;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.StatusItem;

/**
 * Façade over one embedded {@code nvim --embed} process, using
 * <a href="https://codeberg.org/neovim-java/neovim-java">neovim-java</a> (version set in the root {@code pom.xml}).
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
 * and yanks arrive through the autocmds in {@link LuaScripts#SETUP}. After each key the session
 * also polls mode, cursor and visual selection, because notifications can lag a key behind. Both
 * paths go through {@link StateSync}.
 *
 * <p>Each Eclipse document has its own Neovim buffer ({@link DocumentBuffer}), so undo history and
 * marks survive switching editors. Exactly one of them is <em>attached</em> at a time: its events
 * reach the listeners. Line events of the other document buffers go to that document's own listener
 * ({@link DocumentBufferPort}), so {@code :bufdo} edits reach every file; cursor and mode events of
 * other buffers are dropped.
 */
public final class NeovimSession implements AutoCloseable, KeyRouter.Neovim, BufferPort, CursorPort,
        DocumentBufferPort {

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
    /** Asked before a {@code :} command line is executed on Enter (used for host-side {@code :w}/{@code :q}). */
    private volatile Predicate<String> hostExHandler;
    /** Pushes the Eclipse document to Neovim just before leaving insert mode. */
    private volatile Runnable leaveInsertPush;
    /** Saves a document when Neovim writes its buffer; see {@link #setWriteHandler}. */
    private volatile Function<DocumentBuffer, String> writeHandler;

    /** Mode, buffer change, cursor, selection and IDE action listeners. */
    private final SessionEvents events = new SessionEvents();

    /** Single thread that performs all blocking RPC, preserving key order. */
    private final InputThread inputThread = new InputThread();
    /** Runs submitted {@code :} commands, listings, searches and {@code :normal!}. */
    private final CommandRunner commands = new CommandRunner(new CommandRunnerNeovim());

    private NeovimSession(NeovimConnection connection, NeovimBufferApi homeBuffer, ClipboardPort clipboard) {
        this.connection = connection;
        this.api = connection.api();
        this.buffers = new DocumentBuffers(api, homeBuffer);
        // StateSync tells KeyInput about every mode change, and KeyInput uses StateSync. So StateSync is
        // built first and given this::modeChanged, which reaches keyInput only when a mode arrives.
        this.state = new StateSync(new StateSyncNeovim(), events, status, buffers::isAttached,
                this::modeChanged);
        this.keyInput = new KeyInput(new KeyInputNeovim(), state, status, () -> hostExHandler,
                () -> leaveInsertPush);
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
     * @param nvimPath  preference value: command name or absolute path (see {@link NvimExecutable})
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
        NeovimBufferApi homeBuffer = await(api.createBuffer(false, true));
        await(api.setCurrentBuffer(homeBuffer.get()));
        await(homeBuffer.attach(true, Map.of())); // line-change notifications
        await(api.executeLua(LuaScripts.DELETE_STARTUP_BUFFER, List.of(String.valueOf(DocumentBuffers.bufnr(homeBuffer)))));

        NeovimSession session = new NeovimSession(connection, homeBuffer, clipboard);
        NotificationBridge bridge = new NotificationBridge(session.new EventSink());
        connection.addNotificationHandler(bridge);
        connection.setRequestHandler(bridge::onRequest);

        // Asked as a plain RPC call, channel 0 is the caller: this is Eclipse's channel number.
        int channel = await(api.getChannelInfo(0)).getId();
        await(api.executeLua(LuaScripts.SETUP, List.of(String.valueOf(channel))));
        Object clipboardOption = await(api.executeLua(LuaScripts.GET_CLIPBOARD_OPTION, List.of()));
        session.nvimManagesClipboard = managesSystemClipboard(
                clipboardOption == null ? "" : String.valueOf(clipboardOption));
        LOG.info(() -> "Neovim 'clipboard' option = '" + clipboardOption + "'; "
                + (session.nvimManagesClipboard ? "Neovim manages the OS clipboard, bridge disabled"
                        : "bridge copies yanks to the OS clipboard"));
        // Lets a user's init.lua detect the host: `if vim.g.eclipse == 1 then ... end`.
        await(api.setVariable("eclipse", 1));

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

    public void setHostExHandler(Predicate<String> handler) {
        this.hostExHandler = handler;
    }

    public void setLeaveInsertPush(Runnable leaveInsertPush) {
        this.leaveInsertPush = leaveInsertPush;
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

    /** Notified (on an RPC thread) when a key mapped in Neovim asks the IDE to act; see {@link IdeAction}. */
    public void addIdeActionListener(Consumer<IdeAction> listener) {
        events.addIdeActionListener(listener);
    }

    public void removeIdeActionListener(Consumer<IdeAction> listener) {
        events.removeIdeActionListener(listener);
    }

    // ---- per-document buffers (see DocumentBuffers) ----

    /**
     * Makes {@code buffer} the attached buffer and Neovim's current one, creating it on first use.
     * Blocking: input thread only.
     */
    public void showBuffer(DocumentBuffer buffer) throws Exception {
        buffers.show(buffer);
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

    /** Switches Neovim back to the attached buffer (it entered one that Eclipse does not show). */
    public void returnToAttachedBufferAsync() {
        submit("return to attached buffer", Level.FINE, buffers::returnToAttached);
    }

    @Override
    public boolean setDocumentBufferLines(DocumentBuffer buffer, List<String> lines) throws Exception {
        NeovimBufferApi handle = buffers.handleOf(buffer);
        if (handle == null || !buffer.loaded) {
            return false;
        }
        List<String> target = lines.isEmpty() ? List.of("") : lines; // a buffer always has one line
        List<String> current = await(handle.getLines(0, -1, false));
        LineDiff.Change change = LineDiff.between(current == null ? List.of() : current, target);
        if (change == null) {
            return true;
        }
        writingBufnr = buffer.bufnr;
        try {
            await(handle.setLines(change.start(), change.end(), false, change.replacement()));
        } finally {
            writingBufnr = NotificationBridge.UNKNOWN_BUFFER;
        }
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
     * @param handler returns {@code null} when the document was saved, else the error message for
     *                Neovim to show; without a handler a write fails with a message
     */
    public void setWriteHandler(Function<DocumentBuffer, String> handler) {
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
            await(api.executeLua(LuaScripts.LOAD_WITHOUT_UNDO, target));
            document.loaded = true;
            return;
        }
        LineDiff.Change change = LineDiff.between(getBufferLines(), target);
        if (change != null) {
            await(buffers.attached().setLines(change.start(), change.end(), false, change.replacement()));
        }
    }

    public List<String> getBufferLines() throws Exception {
        return getBufferLines(Duration.ofSeconds(RPC_TIMEOUT_SEC));
    }

    private List<String> getBufferLines(Duration timeout) throws Exception {
        List<String> lines = buffers.attached().getLines(0, -1, false).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return lines != null ? lines : List.of();
    }

    /** @param line1Based 1-based line; @param col0Based 0-based UTF-8 byte column */
    public void setCursor(int line1Based, int col0Based) throws Exception {
        NeovimWindowApi win = await(api.getCurrentWindow());
        await(win.setCursor(new VimCoords(line1Based, col0Based)));
        state.rememberCursor(new CursorPos(line1Based, col0Based));
    }

    public CursorPos getCursor() throws Exception {
        NeovimWindowApi win = await(api.getCurrentWindow());
        VimCoords coords = await(win.getCursor());
        return coords == null ? new CursorPos(1, 0) : new CursorPos(coords.getRow(), coords.getCol());
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
        await(api.executeLua(LuaScripts.SET_INDENTATION, List.of(
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
        return await(api.executeLua(code, List.of()));
    }

    /** Runs an ex command (without the leading {@code :}). */
    public void command(String cmd) throws Exception {
        await(api.executeCommand(cmd));
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

    /** Moves the Neovim cursor (e.g. after a mouse click in Eclipse). */
    public void setCursorAsync(int line1Based, int col0Based) {
        submit("setCursor", Level.FINE, () -> {
            if (!isBlocked()) { // while Neovim waits for the rest of a command it would not answer
                setCursor(line1Based, col0Based);
            }
        });
    }

    /** Runs an ex command, then refreshes buffer, mode and cursor in Eclipse. */
    public void commandAsync(String cmd) {
        commandAsync(cmd, null);
    }

    /**
     * Types {@code :cmd<CR>}, then refreshes Eclipse and passes what the command printed to
     * {@code onOutput}, on a session thread. See {@link CommandRunner#run}.
     *
     * @param onOutput receives the printed text; {@code null} to ignore it
     */
    public void commandAsync(String cmd, Consumer<String> onOutput) {
        submit("command " + cmd, Level.WARNING, () -> commands.run(cmd, onOutput));
    }

    /**
     * Runs an ex command that only displays information and passes what it printed, or its error
     * message, to {@code onOutput} on a session thread. See {@link CommandRunner#capture}.
     */
    public void captureOutputAsync(String cmd, Consumer<String> onOutput) {
        submit("capture " + cmd, Level.WARNING, () -> commands.capture(cmd, onOutput));
    }

    /** Runs normal-mode {@code keys} without mappings, then refreshes Eclipse. See {@link CommandRunner#normal}. */
    public void normalAsync(String keys) {
        submit("normal! " + keys, Level.WARNING, () -> commands.normal(keys));
    }

    /** Runs a search ({@code /} or {@code ?}) and moves the cursor to the match. */
    public void search(String pattern, boolean backward) {
        if (pattern == null || pattern.isEmpty()) {
            return;
        }
        submit("search " + pattern, Level.WARNING, () -> commands.search(pattern, backward));
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

    private void submit(String what, Level failureLevel, InputThread.Task work) {
        inputThread.submit(what, failureLevel, work);
    }

    // ---- key input (see KeyInput) ----

    /** Sends one key to Neovim and refreshes Eclipse. Blocking: call on the input thread. See {@link KeyInput#input}. */
    public void input(String keys) throws Exception {
        keyInput.input(keys);
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
        public void onCursor(int line1, int col0, int bufnr) {
            if (buffers.isAttached(bufnr)) {
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
        public void onBufferEnter(int bufnr) {
            buffers.onEntered(bufnr, NeovimSession.this::returnToAttachedBufferAsync);
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
        public String onWrite(int bufnr) {
            DocumentBuffer document = buffers.documentOf(bufnr);
            Function<DocumentBuffer, String> handler = writeHandler;
            if (document == null || handler == null) {
                return "Eclipse has no editor for buffer " + bufnr + "; nothing was saved";
            }
            return handler.apply(document);
        }

        @Override
        public Object onClipboardPaste(String register) {
            ClipboardRegisters.Paste paste = clipboard.paste(register);
            LOG.fine(() -> "clipboard paste register=" + register + " regtype=" + paste.regtype()
                    + " lines=" + paste.lines().size());
            return paste.toRpc();
        }

        @Override
        public void onIdeAction(IdeAction action) {
            LOG.fine(() -> "IDE action " + action);
            events.ideAction(action);
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return Rpc.await(future);
    }

    /** Gives {@link CommandRunner} the Neovim calls it needs. */
    private final class CommandRunnerNeovim implements CommandRunner.Neovim {
        @Override
        public void type(String keys) throws Exception {
            await(api.input(keys));
        }

        @Override
        public boolean isBlocked() {
            return NeovimSession.this.isBlocked();
        }

        @Override
        public void startCapture() throws Exception {
            await(api.executeLua(LuaScripts.START_OUTPUT_CAPTURE, List.of()));
        }

        @Override
        public CompletableFuture<Object> stopCapture() {
            return api.executeLua(LuaScripts.STOP_OUTPUT_CAPTURE, List.of());
        }

        @Override
        public Object captureOutput(String cmd) throws Exception {
            return await(api.executeLua(LuaScripts.CAPTURE_OUTPUT, List.of(cmd)));
        }

        @Override
        public void normalWithoutMappings(String keys) throws Exception {
            await(api.executeLua(LuaScripts.NORMAL_WITHOUT_MAPPINGS, List.of(keys)));
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
            // neovim-java's VimMode stays here, the one place it is read; the rest of core/ sees only
            // our own Mode (docs/RPC_CLIENT.md, step 1).
            VimMode read = await(api.getMode());
            return read == null ? null : new Mode(read.getMode(), read.isBlocking());
        }

        @Override
        public Object stateSnapshot(boolean statusline, boolean showcmd, boolean message) throws Exception {
            // Short timeout: see STATE_TIMEOUT_MS.
            return api.executeLua(LuaScripts.STATE_SNAPSHOT, List.of(flag(statusline), flag(showcmd), flag(message)))
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
            await(api.executeLua("return 0", List.of()));
        }

        @Override
        public String commandLineType() throws Exception {
            return text(await(api.executeLua(LuaScripts.GET_CMDTYPE, List.of())));
        }

        @Override
        public String commandLineText() throws Exception {
            return text(await(api.executeLua(LuaScripts.GET_CMDLINE, List.of())));
        }

        @Override
        public List<String> bufferLines(Duration timeout) throws Exception {
            return getBufferLines(timeout);
        }

        @Override
        public void setCursor(int line1, int col0) throws Exception {
            NeovimSession.this.setCursor(line1, col0);
        }

        private static String text(Object raw) {
            return raw == null ? "" : String.valueOf(raw);
        }
    }
}
