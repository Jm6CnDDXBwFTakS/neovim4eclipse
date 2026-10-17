// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ensarsarajcic.neovim.java.api.NeovimApi;
import com.ensarsarajcic.neovim.java.api.buffer.NeovimBufferApi;
import com.ensarsarajcic.neovim.java.api.types.api.VimCoords;
import com.ensarsarajcic.neovim.java.api.types.api.VimMode;
import com.ensarsarajcic.neovim.java.api.window.NeovimWindowApi;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.InsertReplay;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.LineDiff;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.NvimKeys;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.OutputCapture;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.SearchCommand;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.StatusItem;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.VimModes;

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
 * also polls mode, cursor and visual selection, because notifications can lag a key behind.
 *
 * <p>Each Eclipse document has its own Neovim buffer ({@link DocumentBuffer}), so undo history and
 * marks survive switching editors. Exactly one of them is <em>attached</em> at a time: its events
 * reach the listeners, events of other buffers are dropped.
 */
public final class NeovimSession implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(NeovimSession.class.getName());

    /** Upper bound for any single RPC round trip. */
    private static final long RPC_TIMEOUT_SEC = Rpc.TIMEOUT_SEC;
    /**
     * Upper bound for the per-key state read. Short on purpose: if Neovim unexpectedly defers the
     * request, a keystroke should cost a fraction of a second, not {@value #RPC_TIMEOUT_SEC}s.
     */
    private static final long STATE_TIMEOUT_MS = 750;
    /** How long a typed {@code :} command may run before its output capture gives up. */
    private static final long COMMAND_TIMEOUT_SEC = 60;
    /** How often a running command is checked for a prompt that waits for a key. */
    private static final long CAPTURE_POLL_MS = 250;

    // ---- connection ----

    /** The process and RPC stack. */
    private final NeovimConnection connection;
    /** Shortcut for {@code connection.api()}. */
    private final NeovimApi api;
    private final ClipboardPort clipboard;

    // ---- buffers and keys ----

    /** The key-input pipeline. */
    private final KeyInput keyInput;
    /** Status bar parts and macro recording, published to listeners. */
    private final StatusTracker status = new StatusTracker();

    /** One Neovim buffer per Eclipse document, and which one is attached. */
    private final DocumentBuffers buffers;

    // ---- state ----

    private volatile String currentMode = "n";
    private volatile CursorPos lastCursor = new CursorPos(1, 0);
    private volatile boolean closed;
    /**
     * True when the user's config makes Neovim read/write the OS clipboard itself
     * ({@code clipboard=unnamed[plus]}). Then we must not also write it: the two writers race, the
     * text can differ (line separators), Neovim then no longer recognizes its own register contents
     * and pastes a whole-line yank ({@code yyP}) as characters at the cursor.
     */
    private volatile boolean nvimManagesClipboard;
    /**
     * While true, Neovim's own buffer events are not forwarded to listeners. Used while text that
     * Eclipse already contains is re-typed into Neovim, where applying those events to the document
     * would insert the text twice, and during macro playback (see {@link KeyInput}).
     */
    private volatile boolean suppressBufferEvents;
    /** See {@link #isEclipseTypingReady()}. Set on the UI thread, cleared on any thread. */
    private volatile boolean eclipseTypingReady;
    /**
     * True while Neovim waits for one more character after {@code f}, {@code r}, {@code @}, {@code q}
     * ... (as of the last {@code nvim_get_mode}). The key listener then forwards a typed {@code :},
     * {@code /} or {@code ?} to Neovim as the argument instead of opening a prompt.
     */
    private volatile boolean waitingForArgument;

    /**
     * Whether a {@code :redir} typed by the user is collecting output (from {@code :redir @a} until
     * {@code :redir END}); no output capture is done meanwhile. Input thread only.
     */
    private boolean userRedirActive;

    /** Asked before a {@code :} command line is executed on Enter (used for host-side {@code :w}/{@code :q}). */
    private volatile Predicate<String> hostExHandler;
    /** Pushes the Eclipse document to Neovim just before leaving insert mode. */
    private volatile Runnable leaveInsertPush;

    private final ListenerList<String> modeListeners = new ListenerList<>("mode", Level.FINE);
    private final ListenerList<BufferChange> bufferChangeListeners = new ListenerList<>("buffer change", Level.WARNING);
    private final ListenerList<CursorPos> cursorListeners = new ListenerList<>("cursor", Level.WARNING);
    private final ListenerList<VisualSelection> selectionListeners = new ListenerList<>("selection", Level.WARNING);
    private final ListenerList<IdeAction> ideActionListeners = new ListenerList<>("IDE action", Level.WARNING);

    /** Single thread that performs all blocking RPC, preserving key order. */
    private final InputThread inputThread = new InputThread();

    private NeovimSession(NeovimConnection connection, NeovimBufferApi homeBuffer, ClipboardPort clipboard) {
        this.connection = connection;
        this.api = connection.api();
        this.buffers = new DocumentBuffers(api, homeBuffer);
        this.keyInput = new KeyInput(this, api);
        this.clipboard = clipboard;
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
     * @param clipboard receives text yanked in Neovim
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
        connection.addNotificationHandler(new NotificationBridge(session.new EventSink()));

        await(api.executeLua(LuaScripts.SETUP, List.of()));
        Object clipboardOption = await(api.executeLua(LuaScripts.GET_CLIPBOARD_OPTION, List.of()));
        session.nvimManagesClipboard = managesSystemClipboard(
                clipboardOption == null ? "" : String.valueOf(clipboardOption));
        LOG.info(() -> "Neovim 'clipboard' option = '" + clipboardOption + "'; "
                + (session.nvimManagesClipboard ? "Neovim manages the OS clipboard, bridge disabled"
                        : "bridge copies yanks to the OS clipboard"));
        // Lets a user's init.lua detect the host: `if vim.g.eclipse == 1 then ... end`.
        await(api.setVariable("eclipse", 1));

        try {
            VimMode mode = await(api.getMode());
            if (mode != null && mode.getMode() != null) {
                session.currentMode = mode.getMode();
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not read initial mode", e);
        }
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
        return currentMode;
    }

    /** Last cursor reported by Neovim (used to restore the caret after the document is rewritten). */
    public CursorPos getLastCursor() {
        return lastCursor;
    }

    /** Insert/replace: printable keys are handled by Eclipse, not forwarded to Neovim. */
    public boolean isTypingMode() {
        return VimModes.isTyping(currentMode);
    }

    /**
     * Insert mode where Neovim, not Eclipse, must receive the typed keys: after a block-visual
     * insert, while recording a macro, or after {@link #driveInsertFromNeovim()}.
     */
    public boolean isNeovimDrivenInsert() {
        return keyInput.isNeovimDrivenInsert() && isTypingMode();
    }

    /**
     * Whether Eclipse may type in the current insert mode. Entering insert mode is followed by a
     * full buffer sync from Neovim; until the editor has applied it, text typed by Eclipse would be
     * overwritten by that sync. So this becomes true only once a full sync has been applied while in
     * insert mode, and false again when insert mode ends.
     */
    public boolean isEclipseTypingReady() {
        return eclipseTypingReady && isTypingMode();
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
        return waitingForArgument;
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
        modeListeners.add(listener);
    }

    public void removeModeListener(Consumer<String> listener) {
        modeListeners.remove(listener);
    }

    public void addBufferChangeListener(Consumer<BufferChange> listener) {
        bufferChangeListeners.add(listener);
    }

    public void removeBufferChangeListener(Consumer<BufferChange> listener) {
        bufferChangeListeners.remove(listener);
    }

    public void addCursorListener(Consumer<CursorPos> listener) {
        cursorListeners.add(listener);
    }

    public void removeCursorListener(Consumer<CursorPos> listener) {
        cursorListeners.remove(listener);
    }

    public void addSelectionListener(Consumer<VisualSelection> listener) {
        selectionListeners.add(listener);
    }

    public void removeSelectionListener(Consumer<VisualSelection> listener) {
        selectionListeners.remove(listener);
    }

    /** Notified (on an RPC thread) when a key mapped in Neovim asks the IDE to act; see {@link IdeAction}. */
    public void addIdeActionListener(Consumer<IdeAction> listener) {
        ideActionListeners.add(listener);
    }

    public void removeIdeActionListener(Consumer<IdeAction> listener) {
        ideActionListeners.remove(listener);
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

    /** Number of the buffer mirrored into the attached document. */
    public int getAttachedBufnr() {
        return buffers.attachedBufnr();
    }

    // ---- buffer and cursor access (blocking) ----

    /**
     * Makes the attached buffer's text equal to {@code lines}. Only the differing line range is
     * replaced, and nothing at all when the text is equal, so that pushing the document does not add
     * undo steps or delete the marks of unchanged lines.
     */
    public void setBufferLines(List<String> lines) throws Exception {
        List<String> target = lines.isEmpty() ? List.of("") : lines; // a buffer always has one line
        LineDiff.Change change = LineDiff.between(getBufferLines(), target);
        if (change != null) {
            await(buffers.attached().setLines(change.start(), change.end(), false, change.replacement()));
        }
    }

    public List<String> getBufferLines() throws Exception {
        return getBufferLines(RPC_TIMEOUT_SEC);
    }

    List<String> getBufferLines(long timeoutSec) throws Exception {
        List<String> lines = buffers.attached().getLines(0, -1, false).get(timeoutSec, TimeUnit.SECONDS);
        return lines != null ? lines : List.of();
    }

    /** @param line1Based 1-based line; @param col0Based 0-based UTF-8 byte column */
    public void setCursor(int line1Based, int col0Based) throws Exception {
        NeovimWindowApi win = await(api.getCurrentWindow());
        await(win.setCursor(new VimCoords(line1Based, col0Based)));
        lastCursor = new CursorPos(line1Based, col0Based);
    }

    public CursorPos getCursor() throws Exception {
        NeovimWindowApi win = await(api.getCurrentWindow());
        VimCoords coords = await(win.getCursor());
        return coords == null ? new CursorPos(1, 0) : new CursorPos(coords.getRow(), coords.getCol());
    }

    /**
     * Makes Neovim's {@code 'tabstop'} equal to the editor's tab width, so that the block selection
     * Neovim computes in screen columns lines up with what Eclipse draws. Blocking: input thread only.
     */
    public void setTabstop(int tabWidth) throws Exception {
        if (tabWidth >= 1 && tabWidth <= 64) {
            await(api.executeLua("vim.bo.tabstop = " + tabWidth, List.of()));
        }
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
        submit("attach setup", Level.WARNING, work::run);
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
     * Runs an ex command, then refreshes buffer, mode and cursor in Eclipse, and passes what the
     * command printed ({@code :echo}, {@code :set ts?}, {@code :!ls}) to {@code onOutput}, on a
     * session thread. Nothing is passed when it printed nothing, or only an error (the status bar
     * shows errors).
     *
     * <p>The command is typed, not run with {@code nvim_command}: Neovim then records it in a macro
     * being recorded and knows it for {@code @:} and the history. Its output is collected with
     * {@code :redir} around the typed command ({@link LuaScripts#START_OUTPUT_CAPTURE}).
     *
     * @param onOutput receives the printed text; {@code null} to ignore it
     */
    public void commandAsync(String cmd, Consumer<String> onOutput) {
        submit("command " + cmd, Level.WARNING, () -> {
            // While the user's own :redir runs, a capture would take it over (see OutputCapture).
            OutputCapture.Redir redir = OutputCapture.redirOf(cmd);
            boolean capture = onOutput != null && redir == OutputCapture.Redir.NONE && !userRedirActive;
            if (redir != OutputCapture.Redir.NONE) {
                userRedirActive = redir == OutputCapture.Redir.START;
            }
            String output = "";
            if (capture) {
                await(api.executeLua(LuaScripts.START_OUTPUT_CAPTURE, List.of()));
            }
            try {
                await(api.input(NvimKeys.exCommand(cmd)));
                if (isBlocked()) {
                    await(api.input("<Esc>")); // a "press Enter" prompt: dismiss it
                }
            } finally {
                if (capture) {
                    output = stopOutputCapture();
                }
            }
            syncBufferFromNeovim();
            syncModeAndCursorFromNeovim();
            if (onOutput != null && !output.isBlank()) {
                onOutput.accept(output);
            }
        });
    }

    /**
     * Ends the {@code :redir} capture and returns what it collected.
     *
     * <p>Neovim answers this request only once the typed command has finished, which can take a
     * while ({@code :!make}). While waiting, the mode is checked every {@value #CAPTURE_POLL_MS} ms
     * (Neovim answers that at once): if Neovim is stuck at a prompt, an Esc dismisses it.
     *
     * <p>A command still running after {@value #COMMAND_TIMEOUT_SEC} s is not waited for any longer:
     * its output is dropped and the editor is refreshed. The request stays queued in Neovim, which
     * handles requests in order, so {@code redir END} still runs when the command finishes, before
     * any later capture starts; the redirection is never left on.
     *
     * @return the collected text, or "" when the command did not finish in time
     */
    private String stopOutputCapture() throws Exception {
        CompletableFuture<Object> stop = api.executeLua(LuaScripts.STOP_OUTPUT_CAPTURE, List.of());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(COMMAND_TIMEOUT_SEC);
        while (true) {
            try {
                Object raw = stop.get(CAPTURE_POLL_MS, TimeUnit.MILLISECONDS);
                return raw == null ? "" : String.valueOf(raw);
            } catch (TimeoutException e) {
                if (System.nanoTime() > deadline) {
                    LOG.warning("':' command still running after " + COMMAND_TIMEOUT_SEC
                            + " s; its output is not shown");
                    return "";
                }
                if (isBlocked()) {
                    LOG.fine("command left Neovim at a prompt; sending Esc");
                    await(api.input("<Esc>"));
                }
            }
        }
    }

    /**
     * Runs an ex command that only displays information (see {@code ListingCommand}) and passes what
     * it printed, or its error message, to {@code onOutput} on a session thread. Not typed into
     * Neovim, so a macro being recorded does not include it (it changes nothing anyway).
     */
    public void captureOutputAsync(String cmd, Consumer<String> onOutput) {
        submit("capture " + cmd, Level.WARNING, () -> {
            String output;
            try {
                Object raw = await(api.executeLua(LuaScripts.CAPTURE_OUTPUT, List.of(cmd)));
                output = raw == null ? "" : String.valueOf(raw);
            } catch (Exception e) {
                output = Rpc.errorMessage(e);
            }
            onOutput.accept(output.startsWith("\n") ? output.substring(1) : output);
        });
    }

    /** Runs a search ({@code /} or {@code ?}) and moves the cursor to the match. */
    public void search(String pattern, boolean backward) {
        if (pattern == null || pattern.isEmpty()) {
            return;
        }
        submit("search " + pattern, Level.WARNING, () -> {
            await(api.input(SearchCommand.build(pattern, backward)));
            syncBufferFromNeovim();
            syncModeAndCursorFromNeovim();
        });
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

    /**
     * Reads the mode. {@code nvim_get_mode} is one of the few calls Neovim answers even while it is
     * blocked waiting for input, which is why it is the gate for everything else.
     *
     * @return the mode, or {@code null} if it could not be read
     */
    VimMode readMode() {
        try {
            VimMode mode = await(api.getMode());
            if (mode != null && mode.getMode() != null) {
                currentMode = mode.getMode();
            }
            waitingForArgument = mode != null && mode.isBlocking();
            return mode;
        } catch (Exception e) {
            LOG.log(Level.FINE, "getMode failed", e);
            return null;
        }
    }

    /** Whether Neovim is waiting for the rest of a command (and would defer most API calls). */
    private boolean isBlocked() {
        VimMode mode = readMode();
        return mode != null && mode.isBlocking();
    }

    /**
     * Reads mode, cursor and visual selection from Neovim and notifies listeners. Does nothing while
     * Neovim waits for the rest of a command; the next completed key will publish the state.
     */
    public void syncModeAndCursorFromNeovim() {
        if (isBlocked()) {
            return;
        }
        publishState();
    }

    /**
     * Publishes mode, cursor and visual selection using a single RPC ({@link LuaScripts#STATE_SNAPSHOT}).
     * Must only be called when Neovim is not blocked, and uses a short timeout as a safety net.
     */
    void publishState() {
        String mode = currentMode;
        try {
            Set<StatusItem> items = status.items();
            Object raw = api.executeLua(LuaScripts.STATE_SNAPSHOT, List.of(
                    flag(items.contains(StatusItem.STATUSLINE)),
                    flag(items.contains(StatusItem.CMD)),
                    flag(items.contains(StatusItem.MSG))))
                    .get(STATE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            dispatchMode(mode);
            int bufnr = StateSnapshot.buffer(raw);
            // After a jump to another file the cursor is in a buffer the editor does not show (yet).
            if (buffers.isAttached(bufnr)) {
                CursorPos cursor = StateSnapshot.cursor(raw);
                if (cursor != null) {
                    dispatchCursor(cursor.line1Based(), cursor.col0Based());
                }
                selectionListeners.fire(VimModes.isVisual(mode)
                        ? StateSnapshot.selection(raw, mode)
                        : VisualSelection.none());
            }
            status.updateRecording(StateSnapshot.recording(raw));
            status.publish(StateSnapshot.status(raw));
        } catch (Exception e) {
            LOG.log(Level.FINE, "state snapshot failed", e);
        }
    }

    private static String flag(boolean enabled) {
        return enabled ? "1" : "0";
    }

    /**
     * Pulls the whole buffer and publishes it as a full-buffer change. Not for per-key use. Once the
     * editor has applied it in insert mode, Eclipse may type ({@link #isEclipseTypingReady()}).
     */
    public void syncBufferFromNeovim() {
        try {
            List<String> lines = getBufferLines();
            LOG.fine(() -> "full buffer sync: " + lines.size() + " lines");
            dispatchBufferChange(0, -1, lines, this::markTypingReadyIfTyping);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "buffer sync failed", e);
        }
    }

    /** After-apply action of a full sync: the document now matches Neovim, so Eclipse may type. */
    private void markTypingReadyIfTyping() {
        if (isTypingMode()) {
            eclipseTypingReady = true;
        }
    }

    /** Whether a {@code 'clipboard'} option value makes Neovim use the OS clipboard for yank/put. */
    static boolean managesSystemClipboard(String clipboardOption) {
        return clipboardOption != null && clipboardOption.contains("unnamed"); // unnamed, unnamedplus
    }

    // ---- hooks for KeyInput (package-private) ----

    /** The status bar state (message, pending keys), shared with {@link KeyInput}. */
    StatusTracker status() {
        return status;
    }

    /** The per-document buffer registry, for {@link DocumentBuffer#isOpenIn}. */
    DocumentBuffers buffers() {
        return buffers;
    }

    /**
     * While true, Neovim's own buffer events are not forwarded to listeners (see
     * {@link #suppressBufferEvents}).
     */
    void setSuppressBufferEvents(boolean suppress) {
        suppressBufferEvents = suppress;
    }

    /** The handler asked before a {@code :} command runs, or {@code null}. */
    Predicate<String> hostExHandler() {
        return hostExHandler;
    }

    /** What pushes the Eclipse document to Neovim when insert mode ends, or {@code null}. */
    Runnable leaveInsertPush() {
        return leaveInsertPush;
    }

    // ---- dispatch (package-private for tests) ----

    void dispatchMode(String mode) {
        currentMode = mode;
        if (!VimModes.isTyping(mode)) {
            eclipseTypingReady = false;
        }
        keyInput.onModeChanged(mode);
        modeListeners.fire(mode);
    }

    void dispatchCursor(int line1, int col0) {
        CursorPos pos = new CursorPos(line1, col0);
        lastCursor = pos;
        cursorListeners.fire(pos);
    }

    void dispatchBufferChange(int firstLine, int lastLine, List<String> newLines) {
        dispatchBufferChange(firstLine, lastLine, newLines, null);
    }

    void dispatchBufferChange(int firstLine, int lastLine, List<String> newLines, Runnable afterApply) {
        if (suppressBufferEvents) {
            return;
        }
        bufferChangeListeners.fire(new BufferChange(firstLine, lastLine, newLines, afterApply));
    }

    /** Routes {@link NotificationBridge} events into this session. */
    private final class EventSink implements NotificationBridge.Sink {
        @Override
        public void onMode(String mode) {
            dispatchMode(mode);
        }

        @Override
        public void onCursor(int line1, int col0, int bufnr) {
            if (buffers.isAttached(bufnr)) {
                dispatchCursor(line1, col0);
            }
        }

        @Override
        public void onBufferLines(int bufnr, int firstLine, int lastLine, List<String> newLines) {
            // Other buffers change too (e.g. one Neovim just jumped to); the document mirrors one only.
            if (buffers.isAttached(bufnr)) {
                dispatchBufferChange(firstLine, lastLine, newLines);
            }
        }

        @Override
        public void onBufferEnter(int bufnr) {
            buffers.onEntered(bufnr, NeovimSession.this::returnToAttachedBufferAsync);
        }

        @Override
        public void onYank(List<String> lines, boolean linewise) {
            LOG.fine(() -> "yank linewise=" + linewise + " lines=" + lines.size()
                    + " bridge=" + !nvimManagesClipboard);
            if (!nvimManagesClipboard) {
                clipboard.copyToSystem(lines, linewise);
            }
        }

        @Override
        public void onIdeAction(IdeAction action) {
            LOG.fine(() -> "IDE action " + action);
            ideActionListeners.fire(action);
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return Rpc.await(future);
    }
}
