// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Handle;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Mode;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.NeovimApi;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.NeovimErrorException;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.WinCursor;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CommandOrigin;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CommandWindowView;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Diagnostic;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FloatWindow;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FoldRegions;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridEvent;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.MouseOption;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Grids;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HostCommand;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Indentation;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InsertReplay;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.JumpLabels;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.LatestValue;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimHighlight;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PageBuffer;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.QuickfixEntries;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.SearchMatch;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusItem;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewContent;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewText;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewportSize;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimModes;

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
        DocumentBufferPort, DiagnosticsPort, FoldsPort, ViewportPort, QuickfixPort, SearchPort {

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
     * Documents whose editor has let go though Neovim may still be in their window
     * ({@link #setEditorMirrors}): their changes take the unattached route. Any thread.
     */
    private final Set<DocumentBuffer> editorLetGo = ConcurrentHashMap.newKeySet();
    /**
     * The buffer whose line events are now the echo of {@link #setDocumentBufferLines}, or
     * {@link NotificationBridge#UNKNOWN_BUFFER}: set and cleared by the {@code eclipse_echo}
     * notifications around the write ({@link LuaScripts#SET_DOCUMENT_LINES}), so exactly the write's
     * own events are dropped; they would bring Eclipse's text back to the document it came from.
     * RPC reader thread only.
     */
    private int echoBufnr = NotificationBridge.UNKNOWN_BUFFER;

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
     * Whether search matches are reported ({@link SearchPort#searchEnabled()}): the answer of
     * {@link LuaScripts#SEARCH}, run once at start after the user's config. Written once, in
     * {@link #initialize}, before the session is handed out.
     */
    private volatile boolean searchEnabled;
    /**
     * Whether Neovim's own highlights are reported ({@link SearchPort#highlightsEnabled()}): the
     * answer of {@link LuaScripts#HIGHLIGHTS}, run once at start after the user's config. Written
     * once, as {@link #searchEnabled}.
     */
    private volatile boolean highlightsEnabled;
    /**
     * Whether plug-ins' floating windows are drawn ({@link #floatingWindowsDrawn()}): false until the
     * start is done ({@link #initialize}), then for good. Before, Neovim's windows are not set up,
     * no editor shows them, and no key is typed, so no plug-in's float is drawn, and none is closed
     * for being undrawn ({@code Grids.unseenCursor}, which follows the same value). Written once,
     * read on the reader thread.
     */
    private volatile boolean floatsDrawn;
    /** The floating windows last reported to the listeners, lowest in the stack first. */
    private volatile List<FloatWindow> floatingWindows = List.of();
    /**
     * The plug-in's float Neovim's cursor is in, as of the last redraw, or -1
     * ({@code util/Grids.focusedFloat}): leaving the editor then moves Neovim out of it
     * ({@link #moveOutOfFloatAsync}), and Eclipse mode closes it ({@link #leaveFloatAsync}).
     */
    private volatile int focusedFloat = -1;
    /**
     * Where the cursor was last seen undrawn and Neovim asked about it ({@code util/Grids.unseenCursor}),
     * so that it is asked once while the cursor stays there (each redraw would ask again); null
     * once the cursor is on a drawn grid. Reader thread.
     */
    private Grids.UnseenCursor unseenAsked;
    /** Editor sizes not sent yet: only the newest counts (a window being dragged larger). */
    private final LatestValue<int[]> pendingEditorSize = new LatestValue<>();
    /** Wheel lines not sent yet, added up ({@link #scrollLinesAsync}); and the buffer they are for. */
    private final AtomicInteger pendingWheelLines = new AtomicInteger();
    private volatile DocumentBuffer wheelBuffer;
    /** The editor's newest scroll, not sent yet: a scroll-bar drag sends many, Neovim needs the last. */
    private final LatestValue<Scroll> pendingScroll = new LatestValue<>();
    /** The editor's newest view for search highlighting, not sent yet: only the last counts. */
    private final LatestValue<View> pendingSearchView = new LatestValue<>();
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
    /**
     * Work that needs Neovim to answer, held while Neovim waits for the rest of a command
     * ({@link #whenAnswering}) and run after the input-thread work that ends the wait. Example: a
     * marker changes while the user has typed {@code ]} of {@code ]d}; asking Neovim for the current
     * quickfix list then would hold the input thread until {@link Rpc#TIMEOUT_SEC}, and the {@code d}
     * queued behind it with it (found 2026-10-29: 5 s lost in a macOS UI test). Filled on the input
     * thread; emptied there, or dropped by {@link #close}.
     */
    private final Queue<Held> held = new ConcurrentLinkedQueue<>();
    /**
     * True from document work that found Neovim waiting for the rest of a command until a key ends
     * the wait ({@link #runAsync}: an editor's detach and attach, a push). Meanwhile all queued work
     * but the keys is held behind it, since it follows from that work. Written on the input thread;
     * read by the synchronizers on the reader thread ({@link #isHoldingDocumentWork}).
     */
    private volatile boolean documentWorkWaiting;

    /** Held work and its name for the log. */
    private record Held(String what, InputThread.Task work) {
    }

    /**
     * Answers promised to a caller ({@link #currentQuickfixAsync}) and not given yet. {@link #close}
     * fails them, before it asks Neovim to quit: their work may be held, queued and dropped with the
     * input thread, or being sent, and a caller waiting for one would otherwise wait for its own
     * timeout.
     */
    private final Set<CompletableFuture<?>> unanswered = ConcurrentHashMap.newKeySet();
    /** Loads a document's text into its buffer again; see {@link #setReloadHandler}. */
    private volatile Consumer<DocumentBuffer> reloadHandler;
    /** Pushes the Eclipse document to Neovim just before leaving insert mode. */
    private volatile Runnable leaveInsertPush;
    /** Saves a document when Neovim writes its buffer; see {@link #setWriteHandler}. */
    private volatile DocumentWrite.Handler writeHandler;
    /** Fills the quickfix lists of {@code :Eproblems} and {@code :Etasks}; see {@link #setQuickfixHandler}. */
    private volatile QuickfixPort.Handler quickfixHandler;

    /** Mode, buffer change, cursor, selection and IDE action listeners. */
    private final SessionEvents events = new SessionEvents();
    /** Hands the focus events to the part with the focus ({@link #focus}). */
    private final FocusRouter focus = new FocusRouter();
    /** The page editors to close because Neovim closed their window ({@link #addPageClosedListener}). */
    private final ListenerList<Integer> pageClosedListeners = new ListenerList<>("page closed", Level.WARNING);

    /** Single thread that performs all blocking RPC, preserving key order. */
    private final InputThread inputThread = new InputThread();
    /** The newest mouse drag not sent yet (see {@link #mouseAsync}). */
    private final LatestValue<MouseInput> pendingDrag = new LatestValue<>();
    /** Runs submitted {@code :} commands, listings, searches and {@code :normal!}. */
    private final CommandRunner commands = new CommandRunner(new CommandRunnerNeovim());
    /**
     * Whether a search is being typed ({@code /} or {@code ?}), from ui2's command-line type
     * ({@link LuaScripts#UI2}): the editor then follows the match Neovim previews
     * ({@code 'incsearch'}, a {@code win_viewport}). RPC reader thread only.
     */
    private boolean searching;
    /** The type of the command line open now ({@code ":"}, {@code "/"} ...), {@code ""} for none; from ui2. */
    private volatile String cmdlineType = "";
    /** {@link #commandLineRows()}: written on the reader thread, read on the UI thread. */
    private volatile int commandLineRows = 1;
    /**
     * Whether ui2 has reported the rows yet ({@code eclipse_ui2_windows}): until then
     * {@link #commandLineRows} is a guess, Neovim's own default. The first report is passed on even
     * when it equals the guess, so that an editor that kept another Neovim's rows (a restart,
     * {@code editor/StatusRows}) hears this one's. Written on the reader thread, read on any.
     */
    private volatile boolean commandLineRowsReported;
    /** Neovim's screen grids, from its UI events ({@code ext_multigrid}); RPC reader thread only. */
    private final Grids grids = new Grids();
    /**
     * The command-line window as last passed to its listeners ({@link #addCommandWindowListener}).
     * Written on the RPC reader thread; read by {@link #resizeGrid} on the input thread.
     */
    private volatile CommandWindowView commandWindow = CommandWindowView.CLOSED;
    /**
     * The grid the command-line window is drawn from, for its mouse events ({@link #commandWindowMouseAsync});
     * -1 while it is closed or not drawn yet. Written on the reader thread, with each drawn view.
     */
    private volatile int commandWindowGrid = -1;
    /**
     * The grid each drawn page window is drawn from, for its mouse events ({@link #pageMouseAsync}).
     * Written on the reader thread with each page snapshot, read on the input thread.
     */
    private final Map<Integer, Integer> pageGrids = new ConcurrentHashMap<>();
    /**
     * Neovim's {@code 'mouse'} as last reported ({@code LuaScripts.WINDOWS}), for {@link #gridMouse};
     * {@code null} before the first report. Written on the reader thread, read on the input thread.
     */
    private volatile String mouseOption;

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
        focus.setCommandRule((c, target) -> CommandOrigin.belongsToFocus(c.origin(), target.pageWindow(),
                buffers.isPageWindow(c.origin())));
        buffers.setPageClosedHandler(win -> {
            grids.stopDrawingPage(win);
            pageGrids.remove(win);
            pageClosedListeners.fire(win);
        });
        listenForFocus();
    }

    /**
     * The focus router hears every focus event once, for the whole session; the part with the
     * focus gets them from it ({@link #focus}). Before the router, each text editor's controller
     * added and removed these ten listeners itself.
     */
    private void listenForFocus() {
        events.addModeListener(focus.mode);
        status.addRecordingListener(focus.recording);
        events.addIdeActionListener(focus.ideAction);
        events.addHostCommandListener(focus.hostCommand);
        events.addCmdlineTypeListener(focus.cmdlineType);
        events.addCommandWindowListener(focus.commandWindow);
        events.addFloatingWindowListener(focus.floatingWindows);
    }

    // ---- focus ----

    /**
     * Gives the focus events ({@link FocusTarget}: mode, status, {@code :} line, floats,
     * completion, host commands, ...) to {@code target}, the part that has the focus now. The text
     * editor's controller takes it when it attaches. Any thread.
     */
    public void focus(FocusTarget target) {
        focus.focus(target);
    }

    /** Whether {@code target} has the focus events now ({@link FocusRouter#has}). Any thread. */
    public boolean hasFocus(FocusTarget target) {
        return focus.has(target);
    }

    /** Whether a page editor's window has the focus events now ({@link FocusRouter#pageHasFocus}). Any thread. */
    public boolean pageHasFocus() {
        return focus.pageHasFocus();
    }

    /**
     * Hears a page's window that Neovim closed by itself (its buffer wiped, a plug-in closing
     * windows), or one that could not be entered, on the input thread: the page editor showing it
     * closes
     * ({@link DocumentBuffers#setPageClosedHandler}).
     */
    public void addPageClosedListener(Consumer<Integer> listener) {
        pageClosedListeners.add(listener);
    }

    public void removePageClosedListener(Consumer<Integer> listener) {
        pageClosedListeners.remove(listener);
    }

    /** Takes the focus from {@code target} if it still has it ({@link FocusRouter#release}). Any thread. */
    public void releaseFocus(FocusTarget target) {
        focus.release(target);
    }

    /**
     * Makes a page's Neovim window {@code win} the current one, with the focus, while the attached
     * document stays attached ({@link DocumentBuffers#focusPage}). Give the page's target the
     * focus events too ({@link #focus}). Queued on the input thread.
     */
    public void focusPageAsync(int win) {
        submit("focus page window " + win, Level.FINE, () -> {
            if (!buffers.focusPage(win)) {
                pageClosedListeners.fire(win); // gone, or no page's: its editor cannot show it
            }
        });
    }

    /** Gives the focus back to the attached editor's window ({@link DocumentBuffers#focusAttached}). Queued. */
    public void focusAttachedAsync() {
        submit("focus attached window", Level.FINE, buffers::focusAttached);
    }

    /**
     * Opens a page's window on buffer {@code bufnr} ({@link DocumentBuffers#openPage}) and returns its
     * id, or {@link EditorWindow#UNKNOWN} when the buffer is gone. Blocking: input thread only.
     */
    public int openPageWindow(int bufnr) throws Exception {
        return buffers.openPage(bufnr);
    }

    /**
     * Shows {@code text} as a page of the editing Neovim ({@link LuaScripts#PAGE_TEXT}): fills the
     * page's buffer ({@code neovim4eclipse://messages}), and when {@code win} is no open page window
     * opens one on it ({@link #openPageWindow}) and draws it ({@link #drawPageWindow}). Then calls
     * {@code shown} with the window, on the input thread; not at all when Neovim refused. Queued.
     * Example: {@code :messages} again: the same buffer refilled, the page's window kept.
     */
    public void showPageTextAsync(ViewText text, int win, IntConsumer shown) {
        submit("page " + text.title(), Level.WARNING, () -> {
            List<List<Object>> highlights = new ArrayList<>();
            for (ViewText.LineHighlight h : text.highlights()) {
                highlights.add(List.of(h.line(), h.group()));
            }
            String cursor = ViewText.MESSAGES.equals(text.title()) ? "end" : "top";
            Object answer = await(api.execLua(LuaScripts.PAGE_TEXT, List.of(text.title(), text.lines(), highlights, cursor)));
            if (!(answer instanceof Number buf)) {
                return;
            }
            int window = buffers.isPageWindow(win) ? win : buffers.openPage(buf.intValue());
            if (window != EditorWindow.UNKNOWN) {
                grids.drawPage(window);
                shown.accept(window);
            }
        });
    }

    /**
     * Shows {@code page}, a buffer Neovim made for a page ({@code :help}, {@code :checkhealth}), in
     * page window {@code win}, or in a new page window when {@code win} is none
     * ({@link DocumentBuffers#showPage}), draws it, and calls {@code shown} with the window on the
     * input thread; not at all when the buffer is gone. Queued. Example: {@code :help options}
     * with the help editor open: its window changes to {@code options.txt}.
     */
    public void showPageBufferAsync(PageBuffer page, int win, IntConsumer shown) {
        submit("page " + page.title(), Level.WARNING, () -> {
            int window = buffers.showPage(page, win);
            if (window != EditorWindow.UNKNOWN) {
                grids.drawPage(window);
                shown.accept(window);
            }
        });
    }

    /**
     * Opens another window like page window {@code win}, on the buffer it shows (a split of the
     * page: a second view, each with its own cursor, as {@code :split} gives in Neovim), draws it,
     * and calls {@code shown} with it on the input thread. Nothing when {@code win} is no page
     * window. Queued.
     */
    public void openSecondPageAsync(int win, IntConsumer shown) {
        submit("second page window", Level.WARNING, () -> {
            int window = buffers.openPageLike(win);
            if (window != EditorWindow.UNKNOWN) {
                grids.drawPage(window);
                shown.accept(window);
            }
        });
    }

    /** Closes page window {@code win} and draws it no more (its editor closed; {@link DocumentBuffers#closePage}). Queued. */
    public void closePageWindowAsync(int win) {
        stopDrawingPageWindow(win);
        submit("close page window " + win, Level.FINE, () -> buffers.closePage(win));
    }

    /** Puts a page window's own buffer back into it ({@link DocumentBuffers#restorePage}). Queued. */
    public void restorePageAsync(int win) {
        submit("restore page window " + win, Level.FINE, () -> buffers.restorePage(win));
    }

    /**
     * Whether Neovim's last move into another buffer was in a page's window ({@code gf} on a path in
     * the messages page): the editor followed into takes its own window, and the page's does not
     * travel. Read by the enter handlers, as {@link #wasEnteredByJump()}.
     */
    public boolean wasEnteredFromPage() {
        return buffers.enteredFromPage();
    }

    /** The focused page's window, or {@link EditorWindow#UNKNOWN} while the attached window has the focus. */
    public int focusedPage() {
        return buffers.focusedPage();
    }

    // ---- lifecycle ----

    /** Starts a session with no clipboard integration (tests, headless use). */
    public static NeovimSession start(String nvimPath, List<String> extraArgs) throws Exception {
        return start(nvimPath, extraArgs, ClipboardPort.NONE, "0.0.0");
    }

    /**
     * Launches Neovim, attaches as a UI, creates a scratch buffer and installs the Lua hooks.
     *
     * @param nvimPath  the {@code -Dneovim4eclipse.nvim} value: blank for the dedicated Neovim, or the
     *                  absolute path of a Neovim program (see {@link NvimExecutable}; a folder is refused)
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
            // For a failure before the session exists. After that, initialize has already closed
            // the whole session (abandon), this connection with it, and a second close does nothing.
            connection.close();
            throw e;
        }
    }

    /** Creates the home buffer, routes notifications here and installs the Lua hooks. */
    private static NeovimSession initialize(NeovimConnection connection, ClipboardPort clipboard) throws Exception {
        NeovimApi api = connection.api();
        // First: the one rule for comparing file paths, which later scripts use (LuaScripts.PATHS).
        await(api.execLua(LuaScripts.PATHS, List.of()));
        // Then what makes a buffer a document buffer, which uses PATHS, before any buffer is made
        // one (LuaScripts.DOCUMENT_BUFFER_MODULE; each editor's buffer: DOCUMENT_BUFFER).
        await(api.execLua(LuaScripts.DOCUMENT_BUFFER_MODULE, List.of()));
        // Before the home buffer goes into the current window: the config may have left the focus
        // in a floating window (lazy.nvim's installer), closed below (CLOSE_START_FLOAT).
        Object startFloat = await(api.execLua(LuaScripts.LEAVE_START_FLOAT, List.of()));

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
        // From here on the session exists, with its input thread and the bridge's routing: a step
        // that fails (a script broken by the user's init.lua, a call that times out) closes it
        // whole, not just the connection (which start() closes), or each failed start would leave
        // one behind.
        try {
            NotificationBridge bridge = new NotificationBridge(session.new EventSink());
            connection.setIncoming(bridge);

            // Asked as a plain RPC call, channel 0 is the caller: this is Eclipse's channel number.
            int channel = await(api.getChannelId());
            await(api.execLua(LuaScripts.SETUP, List.of(String.valueOf(channel))));
            // Neovim draws its own command line, prompts, pager and messages (LuaScripts.UI2): always,
            // and before anything the plug-in echoes, so that ui2 shows it.
            Object ui2 = await(api.execLua(LuaScripts.UI2, List.of()));
            if (!Boolean.TRUE.equals(ui2)) {
                LOG.warning(() -> "Neovim's command line is not shown: " + ui2);
            }
            await(api.execLua(LuaScripts.IDE_KEYS, IdeKeys.toLuaArgs())); // gd, K, gr*: after init.lua
            // Before any document's buffer: each new buffer is checked against the project folders,
            // which Eclipse sends at the first attach (docs/design/BUFFER_NUMBERS.md, 2.3).
            await(api.execLua(LuaScripts.PROJECT_FILES, List.of()));
            await(api.execLua(LuaScripts.HOST_COMMANDS, List.of(HostCommand.rulesForNeovim())));
            await(api.execLua(LuaScripts.WINDOW_COMMANDS, List.of())); // after HOST_COMMANDS: its refuse hook
            await(api.execLua(LuaScripts.QUICKFIX, List.of())); // :Eproblems, :Etasks; :copen through WINDOWS
            // Once, here: the opt-out then costs nothing per key (docs/design/FOLDING.md, 3.6).
            session.foldsEnabled = Boolean.TRUE.equals(await(api.execLua(LuaScripts.FOLDS, List.of())));
            // Each editor's status line, drawn under its text (LuaScripts.STATUS_LINE).
            await(api.execLua(LuaScripts.STATUS_LINE, List.of()));
            session.state.setFoldsEnabled(session.foldsEnabled);
            LOG.info(() -> "folds " + (session.foldsEnabled ? "mirrored into Neovim" : "off (g:neovim4eclipse_folds)"));
            // The same for the problems (docs/design/DIAGNOSTICS.md, "Turning it off").
            session.problemsEnabled = Boolean.TRUE.equals(await(api.execLua(LuaScripts.PROBLEMS, List.of())));
            LOG.info(() -> "Eclipse's problems " + (session.problemsEnabled ? "sent to Neovim" : "off (g:neovim4eclipse_problems)"));
            // Neovim's window follows the editor: its size, scrolling and word wrap (docs/design/VIEWPORT.md).
            await(api.execLua(LuaScripts.VIEWPORT, List.of()));
            // And for search highlighting (docs/design/SEARCH_HIGHLIGHTING.md, "Turning it off").
            session.searchEnabled = Boolean.TRUE.equals(await(api.execLua(LuaScripts.SEARCH, List.of())));
            LOG.info(() -> "search matches " + (session.searchEnabled ? "highlighted in the editor" : "off (g:neovim4eclipse_search_highlight)"));
            // And for the yank flash and window matches (the same doc, "The yank flash and window matches").
            // Its argument: the group prefixes of the jump plug-ins known without a setting.
            session.highlightsEnabled = Boolean.TRUE.equals(
                    await(api.execLua(LuaScripts.HIGHLIGHTS, List.of(JumpLabels.BUILT_IN_PREFIXES))));
            LOG.info(() -> "Neovim's highlights " + (session.highlightsEnabled ? "shown in the editor" : "off (g:neovim4eclipse_highlights)"));
            // Plug-ins' floating windows are drawn from here on (docs/ARCHITECTURE.md, "Neovim's
            // windows drawn from their grids"). Keys typed while Neovim starts go where a terminal
            // sends them: a float the editor draws keeps the keyboard, one it does not is closed, with
            // a message, now that ui2 shows messages (WINDOWS' close_start_floats: at UIEnter, or at once).
            session.floatsDrawn = true;
            int left = startFloat instanceof Number win ? win.intValue() : 0;
            await(api.execLua(LuaScripts.CLOSE_START_FLOAT, List.of(String.valueOf(left))));
            // Always: the Ctrl-X completions that could run a file's text are refused in Neovim itself.
            await(api.execLua(LuaScripts.CTRL_X_GUARD, List.of()));
            // And no file's modeline sets options (LuaScripts.NO_MODELINES).
            await(api.execLua(LuaScripts.NO_MODELINES, List.of()));
            // And a buffer named term://... starts no program (LuaScripts.NO_TERMINAL_URLS).
            await(api.execLua(LuaScripts.NO_TERMINAL_URLS, List.of()));
            // And a terminal Eclipse cannot show is named, not left unseen (LuaScripts.TERMINALS).
            await(api.execLua(LuaScripts.TERMINALS, List.of()));
            Object clipboardOption = await(api.execLua(LuaScripts.GET_CLIPBOARD_OPTION, List.of()));
            session.nvimManagesClipboard = managesSystemClipboard(
                    clipboardOption == null ? "" : String.valueOf(clipboardOption));
            LOG.info(() -> "Neovim 'clipboard' option = '" + clipboardOption + "'; "
                    + (session.nvimManagesClipboard ? "Neovim manages the OS clipboard, bridge disabled"
                            : "bridge copies yanks to the OS clipboard"));

            session.state.readMode();
        } catch (Exception | Error e) {
            session.abandon();
            throw e;
        }
        LOG.info("NeovimSession ready");
        return session;
    }

    public boolean isAlive() {
        return !closed && connection.isAlive();
    }

    /**
     * The Neovim program this session started, an absolute path: what <i>Get Neovim</i> compares
     * with the folder it replaces ({@code Activator.swapUnlessRunningFrom}).
     */
    public String program() {
        return connection.program();
    }

    /** Alias of {@link #close()}. */
    public void shutdown() {
        close();
    }

    @Override
    public void close() {
        shutDown(true);
    }

    /**
     * Closes a session whose start failed half-way ({@link #initialize}): as {@link #close}, but
     * without asking Neovim to quit first, which waits up to a second and a half for an answer a
     * Neovim that stopped answering (the usual failure: a call that timed out) will not give.
     */
    private void abandon() {
        shutDown(false);
    }

    /**
     * Ends the session once: {@link #close} with {@code askToQuit}, {@link #abandon} without.
     *
     * @param askToQuit whether Neovim is asked to quit ({@code :qa!}, waited for a moment) before
     *                  its process is ended
     */
    private void shutDown(boolean askToQuit) {
        if (closed) {
            return;
        }
        closed = true;

        // First, before Neovim is asked to quit, so every answer still open fails the same way: as
        // closed. Asked later, a question the input thread is sending just then fails first, with
        // the closed connection's error (seen on a one-core machine), or with the interrupt the
        // shutdown sends its work. Failing a future twice keeps the first failure.
        for (CompletableFuture<?> answer : List.copyOf(unanswered)) {
            answer.completeExceptionally(
                    new IllegalStateException(askToQuit ? "Neovim is closed" : "Neovim did not start"));
        }
        if (askToQuit) {
            connection.requestQuit();
        }
        inputThread.shutdown();
        held.clear();
        connection.close();
        LOG.info(askToQuit ? "NeovimSession closed" : "NeovimSession abandoned: its start failed");
    }

    // ---- state accessors and listeners ----

    /**
     * The errors the user's configuration ({@code init.lua}) reported while this Neovim started, or
     * "" if none: Neovim started anyway (see {@code NeovimConnection.waitForStartup}).
     */
    public String startupErrors() {
        return connection.startupErrors();
    }

    /**
     * Which of {@code names} (global variables, without {@code g:}) are set, and to what, as
     * {@code vim.g.<name>} to the value {@code vim.inspect} prints; in name order. For the problem
     * report: queued like any other request, so the UI thread never waits for Neovim.
     */
    public CompletableFuture<Map<String, String>> initSettingsAsync(List<String> names) {
        CompletableFuture<Map<String, String>> answer = new CompletableFuture<>();
        unanswered.add(answer);
        answer.whenComplete((set, error) -> unanswered.remove(answer));
        boolean queued = submit("the init.lua settings", Level.FINE, () -> whenAnswering("the init.lua settings", () -> {
            try {
                Map<String, String> set = new TreeMap<>();
                if (await(api.execLua(LuaScripts.INIT_SETTINGS, List.of(names))) instanceof List<?> pairs) {
                    for (Object pair : pairs) {
                        if (pair instanceof List<?> p && p.size() == 2) {
                            set.put(String.valueOf(p.get(0)), String.valueOf(p.get(1)));
                        }
                    }
                }
                answer.complete(set);
            } catch (Exception e) {
                answer.completeExceptionally(e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e);
                throw e;
            }
        }));
        if (!queued) {
            answer.completeExceptionally(new IllegalStateException("Neovim is closed"));
        }
        return answer;
    }

    /**
     * Registers {@code :checkhealth neovim4eclipse} ({@link HealthCheck}) and the plug-in's help
     * page, {@code :help neovim4eclipse} ({@link PluginHelp}): writes the stub and the help file
     * into {@code runtimeFolder}, which goes on {@code 'runtimepath'}, and sends the check, both on the input thread, so the UI thread (where
     * the first editor's attach starts the session) waits for neither. Queued like any
     * {@code *Async} call: a {@code :checkhealth} queued after it finds the check. A failure is
     * logged at WARNING and costs only the check. Called once after {@link #start}. Any thread.
     *
     * @throws IllegalArgumentException at once, if a comma in the folder's name would split it on
     *                                  {@code 'runtimepath'} ({@link HealthCheck#fitsRuntimepath})
     */
    public void installHealthCheckAsync(Path runtimeFolder, HealthCheck.Info info) {
        if (!HealthCheck.fitsRuntimepath(runtimeFolder)) {
            throw new IllegalArgumentException("A comma in the folder's name would split it on 'runtimepath': " + runtimeFolder);
        }
        submit("register :checkhealth neovim4eclipse", Level.WARNING, () -> {
            HealthCheck.writeStub(runtimeFolder);
            PluginHelp.write(runtimeFolder);
            await(api.execLua(LuaScripts.HEALTH_CHECK, List.of(runtimeFolder.toString(), info.toLua())));
        });
    }

    /** The running Neovim's version ({@code 0.12.6}), or {@code ""} if unknown. */
    public String neovimVersion() {
        return connection.version();
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
     * insert, while recording a macro, or after {@link #driveInsertFromNeovim()}; and from a
     * {@code Ctrl-R} (or another key Neovim waits after) until Neovim has the key it waits for
     * ({@code KeyInput#handBackToEclipse}).
     */
    public boolean isNeovimDrivenInsert() {
        return keyInput.isNeovimDrivenInsert() && isTypingMode();
    }

    /**
     * Whether a command line is open now, as the last {@code eclipse_cmdline} said
     * ({@link LuaScripts#UI2}); in the order of those notifications, unlike the mode, which comes
     * on its own. Example: {@code :} typed, then a page clicked: {@code true} until the line ends.
     */
    public boolean commandLineOpen() {
        return !cmdlineType.isEmpty();
    }

    /**
     * Whether insert-mode {@code Ctrl-N}, {@code Ctrl-P} and the {@code Ctrl-X} modes are Neovim's
     * own completion, and not Eclipse's Word Completion: always. Neovim draws its popup menu
     * itself, as a float the editor copies ({@code util/Grids}); the switch that once turned the
     * plug-in's own list off was removed in 0.3.0.
     */
    public boolean completesInNeovim() {
        return true;
    }

    /**
     * Whether Neovim is completing in an insert it will hand back to Eclipse, or is about to
     * ({@code KeyInput#isCompleting}): every {@code Ctrl} letter is then Neovim's, whatever the
     * Ctrl Keys preference page says ({@code util/KeyContextRule}).
     */
    public boolean isCompleting() {
        return keyInput.isCompleting();
    }

    /**
     * Whether Esc, or a sync point before its key, is queued or being handled now: the typed text
     * replayed into Neovim, the document pushed, Neovim's cursor and the editor's top line sent
     * ({@link #setLeaveInsertPush}, {@code KeyInput#syncInsertThenInput}). What Neovim reports
     * meanwhile is of text it does not have yet or of a window about to be scrolled to the editor's
     * view, so the editor ignores its cursors and top lines. From the moment the key is routed and
     * Eclipse stops typing ({@code KeyInput#handOver}).
     */
    public boolean isLeavingInsertMode() {
        return keyInput.isLeavingInsertMode();
    }

    /** Whether Eclipse may type in the current insert mode. See {@link StateSync#isEclipseTypingReady()}. */
    public boolean isEclipseTypingReady() {
        return state.isEclipseTypingReady();
    }

    /**
     * Makes the current insert driven by Neovim for now: every key goes to Neovim until Neovim has
     * them all, then Eclipse types again ({@code KeyInput#handBackToEclipse}). Used when a key
     * arrives before Eclipse may type ({@link #isEclipseTypingReady()}): once a key of an insert
     * went to Neovim, Eclipse must not type the following ones itself while it is on its way, or
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
     * Notified (on the RPC reader thread) of what a page editor shows: {@code :messages} with
     * its errors marked ({@code util/ViewText}), and the buffer of a {@code :help} page or a
     * {@code :checkhealth} report ({@code util/PageBuffer}).
     */
    public void addViewContentListener(Consumer<ViewContent> listener) {
        status.addViewContentListener(listener);
    }

    public void removeViewContentListener(Consumer<ViewContent> listener) {
        status.removeViewContentListener(listener);
    }

    /**
     * Window {@code window}'s status line as last evaluated ({@code util/StatusLines}), or
     * {@code null} before the first: an editor shows it as it attaches, until the next one comes.
     * Any thread.
     */
    public GridSnapshot statusLine(int window) {
        return status.statusLine(window);
    }

    /**
     * Notified (on a session thread) with a window's status line each time it changes, for every
     * window: each editor draws its own window's ({@code editor/StatusLineRow}).
     */
    public void addStatusLineListener(Consumer<GridSnapshot> listener) {
        status.addStatusLineListener(listener);
    }

    public void removeStatusLineListener(Consumer<GridSnapshot> listener) {
        status.removeStatusLineListener(listener);
    }

    /**
     * Asks Neovim for every window's status line again ({@code eclipse_status_lines}): an editor
     * that attaches, or whose size changed, shows its line without waiting for a key.
     */
    /**
     * Window {@code window}'s editor is {@code columns} wide: its status line is fitted to that
     * width, not to the window's (every tabpage shares one screen, the focused editor's size), and
     * every line is sent again when it changed ({@code LuaScripts.STATUS_LINE}'s {@code set_width}).
     */
    public void setStatusLineWidthAsync(int window, int columns) {
        submit("status line width", Level.FINE, () -> await(api.execLua(
                "local m = package.loaded['neovim4eclipse.statusline'] if m then m.set_width(...) end",
                List.of(window, columns))));
    }

    public void refreshStatusLinesAsync() {
        submit("status lines", Level.FINE,
                () -> await(api.execLua("local m = package.loaded['neovim4eclipse.statusline'] if m then m.send_all() end",
                        List.of())));
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
     * Hears the command-line window open, change and close ({@link CommandWindowView}), on the RPC
     * reader thread: the listener hops to the UI thread itself. The window's grid comes after it
     * opened, once Neovim has drawn it, and again after each change.
     */
    public void addCommandWindowListener(Consumer<CommandWindowView> listener) {
        events.addCommandWindowListener(listener);
    }

    public void removeCommandWindowListener(Consumer<CommandWindowView> listener) {
        events.removeCommandWindowListener(listener);
    }

    /**
     * Hears which of Neovim's floating windows to draw, lowest in the stack first, each time they
     * change (one opened, was redrawn, moved or closed), on the RPC reader thread: the listener
     * hops to the UI thread itself. An empty list says the last one closed. The one with Neovim's
     * cursor in it carries the cursor ({@code FloatWindow.focused}); no plug-in's float is
     * reported before {@link #floatingWindowsDrawn()}; Neovim's own floats, ui2's and the popup
     * menu, are reported from the start.
     * Example: a plug-in's panel of key hints after {@code <Space>}, gone again with the next key.
     */
    public void addFloatingWindowListener(Consumer<List<FloatWindow>> listener) {
        events.addFloatingWindowListener(listener);
    }

    public void removeFloatingWindowListener(Consumer<List<FloatWindow>> listener) {
        events.removeFloatingWindowListener(listener);
    }

    /** The floating windows to draw as last reported; for an editor that starts listening now. */
    public List<FloatWindow> floatingWindows() {
        return floatingWindows;
    }

    /**
     * Whether plug-ins' floating windows are drawn: once the start is done, always, as a terminal
     * shows them. A float's place is a row and column of Neovim's screen
     * ({@code util/FloatPlacement}), which is the editor's size (docs/design/VIEWPORT.md), so a
     * panel "at the bottom" lands at the editor's bottom.
     */
    public boolean floatingWindowsDrawn() {
        return floatsDrawn;
    }

    /** Whether Neovim's command-line window is open. */
    public boolean isCommandWindowOpen() {
        return commandWindow.open();
    }

    /**
     * Shows a message of the plug-in's own as Neovim shows its own ({@link LuaScripts#ECHO}): in
     * ui2's line, kept in {@code :messages}, an error in {@code ErrorMsg}. Example: {@code :e} with
     * changes gives {@code E37: No write since last change (add ! to override)}.
     */
    public void echoAsync(String message, boolean error) {
        submit("a message", Level.FINE, () -> await(api.execLua(LuaScripts.ECHO, List.of(message, error))));
    }

    /**
     * Sets Neovim's {@code 'background'} from Eclipse's theme ({@link LuaScripts#SET_BACKGROUND}):
     * {@code dark} for a dark editor background. Example: the editor attaches in the dark theme;
     * the user then switches Eclipse to the light one.
     */
    public void setBackgroundAsync(boolean dark) {
        String background = dark ? "dark" : "light";
        submit("'background' " + background, Level.FINE,
                () -> await(api.execLua(LuaScripts.SET_BACKGROUND, List.of(background))));
    }

    /**
     * The keys that close the command-line window without running its line, whatever mode it is
     * in. {@code <C-\><C-N>} ends a command half typed ({@code r}, {@code "}, {@code d}) and Insert
     * or Visual mode; in the window's Normal mode it closes the window, its line dropped, as
     * {@code :quit} there does. So the first one closes a window in Normal mode, the second one one
     * that was in another mode; a second one left over goes to the editor's window, where in Normal
     * mode it does nothing. Keys, not a call: while Neovim waits for the rest of a command it reads
     * keys but answers no call. Not {@code Ctrl-C}: in that window it goes back to the command line
     * with the line under the cursor (and Enter then runs it).
     */
    static final String CLOSE_COMMAND_WINDOW_KEYS = "<C-\\><C-N><C-\\><C-N>";

    /**
     * Draws page window {@code win} ({@link #openPageWindow}) from now on: its grid goes to the
     * page grid listeners ({@link #addPageGridListener}) whenever it changes, which is while the
     * page has the focus (a page in a tabpage that is not current gets no redraws, and keeps its
     * last cells). Any thread.
     */
    public void drawPageWindow(int win) {
        grids.drawPage(win);
    }

    /** Draws page window {@code win} no more (its editor closed). Any thread. */
    public void stopDrawingPageWindow(int win) {
        grids.stopDrawingPage(win);
        pageGrids.remove(win);
    }

    /**
     * Hears each drawn page window's grid when it changes ({@link #drawPageWindow}), on the RPC
     * reader thread: the listener takes its own window's ({@link GridSnapshot#window()}) and hops
     * to the UI thread itself.
     */
    public void addPageGridListener(Consumer<GridSnapshot> listener) {
        events.addPageGridListener(listener);
    }

    public void removePageGridListener(Consumer<GridSnapshot> listener) {
        events.removePageGridListener(listener);
    }

    /**
     * Hears each drawn page window's status line row when it changes (the screen's row below the
     * page's window, as Neovim draws it, with its colors), on the RPC reader thread; the listener
     * takes its own window's ({@link GridSnapshot#window()}) and hops to the UI thread itself.
     */
    public void addPageStatusListener(Consumer<GridSnapshot> listener) {
        events.addPageStatusListener(listener);
    }

    public void removePageStatusListener(Consumer<GridSnapshot> listener) {
        events.removePageStatusListener(listener);
    }

    /**
     * The rows an editor keeps under its status line for Neovim's command line: 1, as Neovim's
     * screen has ({@code 'cmdheight'} 1), or 0 when the user's {@code 'cmdheight'} is 0
     * ({@link LuaScripts#UI2}): the editor then shows one more text row, and ui2's line is drawn
     * over the status line while it is open ({@code util/Grids}). Any thread.
     */
    public int commandLineRows() {
        return commandLineRows;
    }

    /**
     * Whether ui2 has reported {@link #commandLineRows()} yet; until then it is Neovim's default, a
     * guess. Its first report may come before an editor listens (during the start). Any thread.
     */
    public boolean commandLineRowsReported() {
        return commandLineRowsReported;
    }

    /**
     * Hears {@link #commandLineRows()} change (a {@code :set ch=0}), on the RPC reader thread; the
     * listener hops to the UI thread itself.
     */
    public void addCommandLineRowsListener(Consumer<Integer> listener) {
        events.addCommandLineRowsListener(listener);
    }

    public void removeCommandLineRowsListener(Consumer<Integer> listener) {
        events.removeCommandLineRowsListener(listener);
    }

    /**
     * A mouse event on page window {@code win}'s grid, at its {@code row} and {@code col}
     * ({@code nvim_input_mouse} with that grid, as {@link #commandWindowMouseAsync}). Nothing until
     * the page's grid has been drawn once. Queued.
     */
    public void pageMouseAsync(int win, String button, String action, String modifier, int row, int col) {
        submitPastHeld("page mouse " + button + " " + action, Level.FINE, () -> {
            Integer grid = pageGrids.get(win);
            if (grid != null) {
                gridMouse(grid, button, action, modifier, row, col);
            }
        });
    }

    /**
     * A mouse event on a plug-in's floating window, at its {@code row} and {@code col}
     * ({@code nvim_input_mouse} with its grid, {@code FloatWindow.mouseGrid}), in order with the
     * keys. What it does is Neovim's and the plug-in's: Telescope's results select the row, a
     * double click opens it. Nothing in a mode {@code 'mouse'} leaves out ({@link #gridMouse}).
     * A window closed meanwhile costs nothing: Neovim finds no window for
     * its grid and drops the event, and never gives a new window the old number. Queued.
     */
    public void floatMouseAsync(int grid, String button, String action, String modifier, int row, int col) {
        submitPastHeld("floating window mouse " + button + " " + action, Level.FINE,
                () -> gridMouse(grid, button, action, modifier, row, col));
    }

    /**
     * Sends a mouse event on a grid, if {@code 'mouse'} enables Neovim's mode now
     * ({@code util/MouseOption}), the one way all three kinds of grid reach Neovim. The mode is
     * asked with {@code nvim_get_mode}, which Neovim answers even while it waits for a key. Input
     * thread. Example: {@code 'mouse'} {@code "nv"} and a prompt in Insert mode: nothing is sent.
     * Nothing either while Neovim waits for the rest of a command, as for a click in the text
     * ({@link KeyInput#mouse}): the click would be taken as that command's next key.
     */
    private void gridMouse(int grid, String button, String action, String modifier, int row, int col)
            throws Exception {
        // The mode first: its answer comes after any 'mouse' report Neovim sent before it, so a
        // mapping on the press that changed 'mouse' counts for its release.
        Mode mode = await(api.getMode());
        if (!mode.blocking() && MouseOption.allows(mouseOption, mode.mode())) {
            await(api.inputMouse(button, action, modifier, grid, row, col));
        }
    }

    /**
     * A mouse event on cell ({@code row}, {@code col}) of the command-line window, as drawn in its
     * panel: {@code nvim_input_mouse} on the window's grid, in order with the keys, so Neovim counts
     * double clicks and drags itself, and its {@code 'mouse'} and mappings apply. Nothing while the
     * window is not drawn. Any thread. Example: a click on the third history line is
     * {@code ("left", "press", "", 2, 0)}, then the release.
     *
     * @param button   {@code "left"}, {@code "middle"} or {@code "wheel"}
     * @param action   {@code "press"}, {@code "drag"} or {@code "release"}; {@code "up"},
     *                 {@code "down"}, {@code "left"} or {@code "right"} for the wheel
     * @param modifier Neovim's modifier letters ({@code "S"}, {@code "C"}, {@code "A"}), or {@code ""}
     */
    public void commandWindowMouseAsync(String button, String action, String modifier, int row, int col) {
        submitPastHeld("command-line window mouse " + button + " " + action, Level.FINE, () -> {
            int grid = commandWindowGrid;
            if (grid != -1) {
                gridMouse(grid, button, action, modifier, row, col);
            }
        });
    }

    /**
     * The grid the command-line window is drawn from, -1 while it is closed or not drawn yet; for
     * the integration test that sends a click to the grid of a window that has just closed (a click
     * right after an Enter that closed it reaches Neovim after the Enter).
     */
    int commandWindowGrid() {
        return commandWindowGrid;
    }

    /**
     * Closes the command-line window without running its line ({@link #CLOSE_COMMAND_WINDOW_KEYS});
     * nothing when it is not open. Example: the user clicks into another view while {@code q:} is
     * shown, maybe in Insert mode, maybe after a {@code d} still waiting for its motion.
     */
    public void closeCommandWindowAsync() {
        submit("close the command-line window", Level.FINE, () -> await(api.input(CLOSE_COMMAND_WINDOW_KEYS)));
    }

    // ---- per-document buffers (see DocumentBuffers) ----

    /**
     * Makes {@code buffer} the attached buffer and Neovim's current one, creating it on first use.
     * Blocking: input thread only.
     */
    public void showBuffer(DocumentBuffer buffer) throws Exception {
        showWindow(buffer, null, "");
    }

    /**
     * Makes {@code buffer} the attached buffer, shown in the editor's own Neovim window, which
     * becomes the current one: opened in a new tabpage on first use, and again after Restart Neovim
     * or after it was closed in Neovim (docs/design/WINDOWS.md). Blocking: input thread only.
     *
     * @param window the editor's window token; {@code null} shows the buffer in the home window,
     *               as {@link #showBuffer} does
     * @param folder the window's folder ({@code :lcd}), set once when it is opened: normally the
     *               editor's project (for an untitled document, the selected project's);
     *               {@code null} or "" (an untitled document with no project selected) takes the
     *               folder of the editor attached before, as a Vim window inherits it, or the home
     *               folder if none was. Ignored when {@code 'autochdir'} is set.
     */
    public void showWindow(DocumentBuffer buffer, EditorWindow window, String folder) throws Exception {
        int before = buffers.attachedBufnr();
        try {
            buffers.show(buffer, window, folder);
        } finally {
            if (buffers.attachedBufnr() != before) {
                keyInput.onAttachedBufferChanged(); // a macro's pending sync was the other buffer's
            }
        }
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

    /**
     * Unloads a document's buffer and takes it off the buffer list (its last editor closed), as
     * Vim's {@code :bd} does: its text and undo history go, the buffer, its number and its marks
     * stay, so the file opened again gets them back ({@link DocumentBuffer#reopened};
     * {@link LuaScripts#CLOSE_DOCUMENT_BUFFER}, docs/design/BUFFER_NUMBERS.md).
     */
    public void closeBufferAsync(DocumentBuffer buffer) {
        submit("close buffer", Level.FINE, () -> buffers.close(buffer));
    }

    /**
     * The document's file has a new name (a rename or move of the open file, File › Save As): the
     * buffer is renamed after it, and keeps its number, undo history and marks
     * ({@link DocumentBuffers#rename}). The name is recorded at once, before Neovim hears of it, so
     * that a buffer made later, in this Neovim or in one started by Restart Neovim, gets the new
     * name too. Example: {@code a.txt} renamed to {@code b.txt}; {@code u} in {@code b.txt} undoes
     * the edit made in {@code a.txt}, and after Restart Neovim the buffer is {@code b.txt}. UI
     * thread (any thread works).
     */
    public void renameBufferAsync(DocumentBuffer buffer, String name) {
        buffer.rename(name);
        submit("rename buffer", Level.WARNING, () -> buffers.rename(buffer));
    }

    /**
     * Handles Neovim switching by itself to the buffer of another open document; the handler should
     * show that document, or call {@link #returnToAttachedBufferAsync()}. Without a handler, and for
     * any buffer that is not a document's, Neovim is switched back to the attached buffer, unless it
     * is by then in a plug-in's float the editor draws ({@link DocumentBuffers#returnFromEnter}).
     */
    public void setBufferEnterHandler(Consumer<Integer> handler) {
        buffers.setEnterHandler(handler);
    }

    /**
     * Handles Neovim deleting a document's buffer, or quitting its window, by itself
     * ({@code <Cmd>bd}, {@code vim.cmd.quit()}; the safety nets of {@link LuaScripts#HOST_COMMANDS}).
     * The handler gets the document whose buffer it was, as Neovim's notice is read
     * ({@link DocumentBuffers#onClosed}), and should close the editor showing that document, if any:
     * found by the document, not by its buffer number, which a show may have changed since (a
     * buffer wiped in Neovim is made anew). Not called for a buffer that is no document's. Called on
     * an RPC thread; not while the session is closing (Eclipse's own {@code :qa!} fires
     * {@code QuitPre} too).
     */
    public void setBufferClosedHandler(Consumer<DocumentBuffer> handler) {
        buffers.setClosedHandler(handler);
    }

    /**
     * Handles Neovim entering a file that has no editor yet, in a project or not ({@code gf},
     * {@code 'A}, a quickfix entry; read by {@link LuaScripts#PROJECT_FILES}): the handler gets the buffer number
     * and the file, opens the file's editor and follows into it, or calls
     * {@link #returnToAttachedBufferAsync()}. Called on an RPC thread. Without a handler, Neovim is
     * switched back.
     */
    public void setProjectFileHandler(BiConsumer<Integer, String> handler) {
        buffers.setProjectFileHandler(handler);
    }

    /**
     * Handles Neovim loading a document's buffer empty by itself (a plug-in's {@code :bunload}, then
     * a read later: {@link LuaScripts#DOCUMENT_BUFFER_MODULE}; an {@code :edit!} keeps the text instead). The
     * handler should read the document's text and pass it to {@link #reloadDocumentAsync}. Called on
     * an RPC thread.
     */
    public void setReloadHandler(Consumer<DocumentBuffer> handler) {
        this.reloadHandler = handler;
    }

    /**
     * Loads {@code lines}, the document's text, into its buffer again after Neovim loaded it empty
     * ({@link #setReloadHandler}), without an undo step, and has Neovim send the buffer's changes
     * again: unloading a buffer ended {@code nvim_buf_attach} for it. Then {@code 'modified'} follows
     * Eclipse's dirty flag. Nothing for a buffer that is gone. Any thread.
     */
    public void reloadDocumentAsync(DocumentBuffer buffer, List<String> lines) {
        List<String> target = lines.isEmpty() ? List.of("") : List.copyOf(lines);
        submit("reload " + buffer.name(), Level.FINE, () -> {
            Handle handle = buffers.handleOf(buffer);
            if (handle == null) {
                return;
            }
            // Before the attach: no line event for it. The lines as one list, however long.
            await(api.execLua(LuaScripts.RELOAD_DOCUMENT_BUFFER, List.of(String.valueOf(buffer.bufnr), target)));
            await(api.bufAttach(handle, false));
            applyModified(buffer);
        });
    }

    /**
     * Renews {@code buffer}'s attach after Neovim reloaded it with its text kept
     * ({@link LuaScripts#DOCUMENT_BUFFER_MODULE}, "Reloaded by Neovim"): the unload ended
     * {@code nvim_buf_attach}, so Neovim's changes since then reached no one.
     *
     * <p><b>Re-attach after a reload.</b> The attach asks Neovim to send the whole buffer first
     * ({@code send_buffer}), in the same event stream as every later change, so nothing falls
     * between the two: keys queued before this task changed the buffer before the attach and are in
     * the whole buffer, keys after it arrive as normal changes. The synchronizer (attached or not)
     * turns the whole buffer into the minimal edit of the document, or none when nothing changed.
     * Example: {@code :edit!} from a plug-in, then {@code x} typed before the attach: the whole
     * buffer lacks the deleted character, and the document loses it too.
     *
     * <p>Not while Eclipse types in insert mode in this document: the document is then ahead of the
     * buffer until Esc replays the typed text into Neovim, and the whole buffer would take that text
     * out of the document again. No key reaches Neovim's buffer meanwhile, so a plain attach loses
     * nothing. Session thread.
     */
    private void reattachAfterReloadAsync(DocumentBuffer buffer) {
        submit("re-attach " + buffer.name(), Level.FINE, () -> {
            Handle handle = buffers.handleOf(buffer);
            if (handle == null) {
                return;
            }
            boolean eclipseTypes = buffers.isAttached(buffer.bufnr) && isTypingMode() && !isNeovimDrivenInsert();
            await(api.bufAttach(handle, !eclipseTypes));
        });
    }

    /**
     * Tells Neovim the open projects' folders ({@link LuaScripts#SET_PROJECT_FOLDERS}): it reads no
     * file inside them by itself ({@link LuaScripts#PROJECT_FILES}). Any thread.
     *
     * @param folders the folders as Eclipse gives them ({@code IPath.toOSString()})
     */
    public void setProjectFoldersAsync(List<String> folders) {
        List<String> copy = List.copyOf(folders);
        submit(copy.size() + " project folders", Level.FINE, () -> await(api.execLua(LuaScripts.SET_PROJECT_FOLDERS, List.of(copy))));
    }

    /** Switches Neovim back to the attached buffer (it entered one that Eclipse does not show). */
    public void returnToAttachedBufferAsync() {
        submit("return to attached buffer", Level.FINE, buffers::returnToAttached);
    }

    /**
     * After an editor's attach push, places Neovim's cursor where Vim would after {@code :e}
     * ({@link LuaScripts#GO_TO_LAST_POSITION}): runs the {@code +cmd} of the {@code :e +cmd name}
     * that opened this file ({@code :e +40 a.txt}), or, after the first push into a reused buffer
     * ({@link DocumentBuffer#reopened}, once per reopening), puts it on the buffer's {@code '"} mark,
     * as Vim does on {@code :e} of a buffer {@code :bd} kept. Input thread (the attach push).
     *
     * @return whether the cursor was moved (the caret is then to follow Neovim's cursor)
     */
    public boolean goToLastPositionIfReopened(DocumentBuffer buffer) throws Exception {
        boolean reopened = buffer.reopened;
        buffer.reopened = false;
        return Boolean.TRUE.equals(await(api.execLua(LuaScripts.GO_TO_LAST_POSITION,
                List.of(String.valueOf(reopened), String.valueOf(buffer.bufnr),
                        String.valueOf(buffers.attachedWin())))));
    }

    /**
     * Tells Neovim that Eclipse's Back went nowhere for {@code steps} {@code Ctrl-O} steps from
     * step id {@code first} on (the start of its history), so {@code Ctrl-I} does not first undo
     * steps that moved nothing ({@link LuaScripts#HISTORY_NOWHERE}).
     */
    public void historyBacksWentNowhereAsync(long first, int steps) {
        submit("Ctrl-O went nowhere", Level.FINE, () -> await(api.execLua(LuaScripts.HISTORY_NOWHERE,
                List.of(String.valueOf(first), String.valueOf(steps)))));
    }

    /**
     * Tells Neovim to forget its trail of {@code Ctrl-O} steps: the user switched editors in
     * Eclipse, and the Back steps Eclipse remembered are gone ({@link LuaScripts#HISTORY_FORGET}).
     */
    public void historyForgetAsync() {
        submit("forget the Ctrl-O trail", Level.FINE, () -> await(api.execLua(LuaScripts.HISTORY_FORGET, List.of())));
    }

    @Override
    public boolean setDocumentBufferLines(DocumentBuffer buffer, List<String> lines) throws Exception {
        Handle handle = buffers.handleOf(buffer);
        if (handle == null || !buffer.loaded) {
            return false;
        }
        // Read, compared and set in one script, which marks its own echo (see SET_DOCUMENT_LINES).
        Object outcome = await(api.execLua(LuaScripts.SET_DOCUMENT_LINES,
                List.of(String.valueOf(buffer.bufnr), List.copyOf(lines))));
        if ("gone".equals(outcome)) {
            return false; // wiped in Neovim meanwhile
        }
        if ("changed".equals(outcome)) {
            applyModified(buffer); // writing lines set 'modified'; Eclipse's dirty flag decides
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
        editorLetGo.remove(buffer);
    }

    @Override
    public void setEditorMirrors(DocumentBuffer buffer, boolean mirrors) {
        if (mirrors) {
            // Queued, so that it stays behind held work: until the attach has run, a key finishing a
            // command in this buffer has no listening synchronizer, and takes the unattached route.
            submit("the editor mirrors its document", Level.FINE, () -> editorLetGo.remove(buffer));
        } else {
            editorLetGo.add(buffer);
        }
    }

    @Override
    public boolean isHoldingDocumentWork() {
        return documentWorkWaiting;
    }

    /**
     * Saves the document when Neovim writes its buffer ({@code :w} from a mapping, {@code :update},
     * {@code :bufdo update}, {@code :wall}; see {@link LuaScripts#DOCUMENT_BUFFER_MODULE}). Called on the RPC
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

    /**
     * Whether Neovim's move into {@link #getEnteredBufnr()} is in its window's jump history
     * ({@code :b}, {@code Ctrl-O}, {@code :bufdo}; not {@code :keepjumps}). Eclipse's record of the
     * editor switch that follows is then a repeat that Back may step over. Any thread.
     */
    public boolean wasEnteredByJump() {
        return buffers.enteredByJump();
    }

    // ---- buffer and cursor access (blocking) ----

    /**
     * Makes the attached buffer's text equal to {@code lines}. Only the differing line range is
     * replaced, and nothing at all when the text is equal, so that pushing the document does not add
     * undo steps or delete the marks of unchanged lines.
     *
     * <p>The first push into a new buffer loads the file: like Vim reading a file, it is not an undo
     * step ({@link LuaScripts#RELOAD_DOCUMENT_BUFFER}), so {@code u} right after opening does nothing.
     */
    @Override
    public List<String> setBufferLines(List<String> lines) throws Exception {
        List<String> target = lines.isEmpty() ? List.of("") : lines; // a buffer always has one line
        DocumentBuffer document = buffers.attachedDocument();
        if (document != null && !document.loaded) {
            // By number: Neovim may be in a plug-in's float now. One list, however long.
            await(api.execLua(LuaScripts.RELOAD_DOCUMENT_BUFFER, List.of(String.valueOf(document.bufnr), target)));
            document.loaded = true;
            applyModified(document);
            return null;
        }
        List<?> answer = setAttachedLines(0, 0, target, true);
        if ("changed".equals(answer.get(0)) && document != null) {
            applyModified(document); // the change set 'modified'; Eclipse decides (a revert is not dirty)
        }
        List<String> before = new ArrayList<>();
        if (answer.size() > 1 && answer.get(1) instanceof List<?> old) {
            old.forEach(line -> before.add(String.valueOf(line)));
        }
        return before;
    }

    /**
     * Makes the attached buffer's lines from {@code first} up to its last {@code keep} equal to
     * {@code lines}, in one script that reads, compares and sets them and marks the change's line
     * events as its echo, which the session drops ({@link LuaScripts#SET_LINES_MARKED}).
     *
     * @return the script's answer: {@code "changed"}, {@code "equal"} or {@code "outside"} first, the
     *         part's old lines second when {@code wantOld}
     */
    private List<?> setAttachedLines(int first, int keep, List<String> lines, boolean wantOld) throws Exception {
        Object answer = await(api.execLua(LuaScripts.SET_LINES_MARKED,
                List.of(buffers.attachedBufnr(), first, keep, lines, wantOld)));
        if (answer instanceof List<?> list && !list.isEmpty()) {
            return list;
        }
        throw new IllegalStateException("Unexpected answer from SET_LINES_MARKED: " + answer);
    }

    /**
     * See {@link BufferPort#splitLineBreaks}. Splits {@code buffer} whether attached or not (the
     * user may have switched editors since the split was asked for). The script moves the cursor of
     * the attached window only when that window shows the buffer; that cursor is then dispatched as
     * Neovim's, so that the editor's caret is on the split line's right part before Eclipse types
     * again (its own report of the move may come later). A buffer split while another editor is
     * attached moves no cursor, and nothing is dispatched: Neovim's cursor is in that other buffer.
     */
    @Override
    public void splitLineBreaks(DocumentBuffer buffer, Map<Integer, List<String>> parts) throws Exception {
        Handle handle = buffer == null ? buffers.attached() : buffers.handleOf(buffer);
        if (handle == null || parts.isEmpty()) {
            return; // not made yet, or wiped: its next push gives it the document's lines
        }
        int bufnr = DocumentBuffers.bufnr(handle);
        List<List<Object>> splits = new ArrayList<>(parts.size());
        parts.forEach((line0, lines) -> splits.add(List.of(line0 + 1, lines))); // Lua's lines are 1-based
        Object cursor = await(api.execLua(LuaScripts.SPLIT_LINE_BREAKS, List.of(
                String.valueOf(bufnr), String.valueOf(buffers.attachedWin()), splits)));
        if (bufnr == buffers.attachedBufnr() && cursor instanceof List<?> pos && pos.size() == 2
                && pos.get(0) instanceof Number line && pos.get(1) instanceof Number col) {
            state.dispatchCursor(line.intValue(), col.intValue());
        }
    }

    /**
     * See {@link BufferPort#setBufferRegion}. Reads only that part of Neovim's buffer and replaces the
     * lines that differ, as {@link #setBufferLines} does for the whole buffer: nothing when they are
     * equal, so a push after a faithful re-typing adds no undo step. A part outside the buffer is
     * not written, and the caller pushes the whole document.
     */
    @Override
    public boolean setBufferRegion(int first, int unchangedAtEnd, List<String> lines) throws Exception {
        DocumentBuffer document = buffers.attachedDocument();
        Handle handle = buffers.attached();
        if (handle == null || (document != null && !document.loaded)) {
            return false; // the first push loads the file (setBufferLines)
        }
        String outcome = String.valueOf(setAttachedLines(first, unchangedAtEnd, lines, false).get(0));
        if ("outside".equals(outcome)) {
            LOG.fine(() -> "lines " + first + " to end-" + unchangedAtEnd + " are not in the buffer");
            return false;
        }
        if ("changed".equals(outcome) && document != null) {
            applyModified(document);
        }
        return true;
    }

    /**
     * Eclipse's dirty flag for {@code buffer}'s document changed (UI thread, from the editor's dirty
     * property): Neovim's {@code 'modified'} follows it ({@link LuaScripts#SET_MODIFIED}). Every push of
     * Eclipse's text sets it again, since writing lines marks a buffer modified. Any thread.
     */
    public void setModifiedAsync(DocumentBuffer buffer, boolean dirty) {
        buffer.eclipseDirty = dirty;
        // Held while Neovim waits for the rest of a command (whenAnswering): it would not answer, and
        // the key that ends the wait would queue behind this for the RPC limit.
        String what = "modified " + dirty + " for " + buffer.name();
        submit(what, Level.FINE, () -> whenAnswering(what, () -> applyModified(buffer)));
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
        String what = copy.size() + " problems for " + buffer.name();
        // Held while Neovim waits for the rest of a command, as the folds are (setFoldsAsync).
        submit(what, Level.FINE, () -> whenAnswering(what, () -> {
            // Not created yet, wiped, or from a Neovim that was restarted: the number could be another
            // buffer's. Not loaded: the problems would land in an empty buffer, before its text.
            if (buffers.handleOf(buffer) == null || !buffer.loaded) {
                return;
            }
            await(api.execLua(LuaScripts.SET_DIAGNOSTICS, List.of(Diagnostic.toLuaArgs(buffer.bufnr, copy))));
        }));
    }

    @Override
    public void setQuickfixHandler(QuickfixPort.Handler handler) {
        this.quickfixHandler = handler;
    }

    @Override
    public void setQuickfixAsync(QuickfixEntries.Kind kind, String arg, QuickfixEntries.Mode mode, boolean jump,
            List<QuickfixEntries.Entry> entries) {
        List<String> args = QuickfixEntries.toLuaArgs(kind, arg, mode, jump, entries);
        String what = entries.size() + " entries for " + kind.title();
        submit(what, Level.FINE, () -> whenAnswering(what,
                () -> await(api.execLua(LuaScripts.SET_QUICKFIX, List.of(args)))));
    }

    @Override
    public CompletableFuture<QuickfixEntries.Current> currentQuickfixAsync() {
        CompletableFuture<QuickfixEntries.Current> answer = new CompletableFuture<>();
        unanswered.add(answer);
        answer.whenComplete((current, error) -> unanswered.remove(answer));
        boolean queued = submit("current quickfix list", Level.FINE, () -> whenAnswering("current quickfix list", () -> {
            try {
                answer.complete(QuickfixEntries.Current.fromLua(await(api.execLua(LuaScripts.QUICKFIX_CURRENT, List.of()))));
            } catch (Exception e) {
                // await reports Neovim's failure inside an ExecutionException, and answer.get() wraps
                // what it is given in one more: hand on the failure itself, or the caller finds a
                // wrapper where it looks for the cause (ExecutionException, then ExecutionException).
                answer.completeExceptionally(e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e);
                throw e;
            }
        }));
        if (!queued) {
            answer.completeExceptionally(new IllegalStateException("Neovim is closed"));
        }
        return answer;
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
        String what = copy.size() + " folds for " + buffer.name();
        // Held while Neovim waits for the rest of a command (whenAnswering). Example: the user has
        // typed z and thinks about the next key, and Eclipse's reconcile sends new folds: Neovim
        // answers nothing until that key, so asking now would hold the input thread, and the key
        // queued behind it, for the RPC limit. Held sends run in order, so the newest folds win.
        submit(what, Level.FINE, () -> whenAnswering(what, () -> {
            // As for the diagnostics: the number could be another buffer's, or the text not there yet.
            // SET_FOLDS itself skips a buffer that is not the current one. The number is read here, on
            // the input thread: at attach the buffer is created by the push queued just before.
            if (buffers.handleOf(buffer) == null || !buffer.loaded) {
                return;
            }
            await(api.execLua(LuaScripts.SET_FOLDS, List.of(FoldRegions.toLuaArgs(buffer.bufnr, copy))));
        }));
    }

    @Override
    public boolean searchEnabled() {
        return searchEnabled;
    }

    @Override
    public void addSearchListener(Consumer<Matches> listener) {
        events.addSearchListener(listener);
    }

    @Override
    public void removeSearchListener(Consumer<Matches> listener) {
        events.removeSearchListener(listener);
    }

    @Override
    public boolean highlightsEnabled() {
        return highlightsEnabled;
    }

    @Override
    public void addHighlightsListener(Consumer<Highlights> listener) {
        events.addHighlightsListener(listener);
    }

    @Override
    public void removeHighlightsListener(Consumer<Highlights> listener) {
        events.removeHighlightsListener(listener);
    }

    @Override
    public void setSearchViewAsync(DocumentBuffer buffer, int top1, int bottom1) {
        if (!(searchEnabled || highlightsEnabled) || top1 < 1 || bottom1 < top1) {
            return;
        }
        if (pendingSearchView.offer(new View(buffer, top1, bottom1))) {
            submit("search view", Level.FINE, () -> {
                View latest = pendingSearchView.take();
                if (latest == null || buffers.handleOf(latest.buffer()) == null || !latest.buffer().loaded) {
                    return; // not created yet, or gone; SEARCH_VIEW itself skips a buffer not shown
                }
                await(api.execLua(LuaScripts.SEARCH_VIEW, List.of(String.valueOf(latest.buffer().bufnr),
                        String.valueOf(latest.top1()), String.valueOf(latest.bottom1()))));
            });
        }
    }

    @Override
    public void resendSearchAsync(DocumentBuffer buffer) {
        if (!(searchEnabled || highlightsEnabled)) {
            return;
        }
        submit("search matches again for " + buffer.name(), Level.FINE, () -> {
            if (buffers.handleOf(buffer) == null || !buffer.loaded) {
                return; // not created yet, or gone; SEARCH_RESEND itself skips a buffer not shown
            }
            await(api.execLua(LuaScripts.SEARCH_RESEND, List.of(String.valueOf(buffer.bufnr))));
        });
    }

    @Override
    public void setEditorSizeAsync(int columns, int rows) {
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
     * Measures Neovim's screen again for the editor's window at the editor size last given
     * ({@link #setEditorSizeAsync}), and resizes it if that changed: the columns beside the text
     * (line numbers, signs) are the window's own, so they are measured on the document's window,
     * also with Neovim in a float ({@code LuaScripts.WINDOWS}' {@code editor_window}). The attach's
     * push runs this right after attaching that window and before
     * placing the cursor ({@code sync/DocumentPush}), so that the cursor lands in a window of the
     * editor's size. Nothing before any size was given, or while a page
     * editor has the focus: the screen is the page's then, as for {@link #setEditorSizeAsync}'s
     * caller. Input thread.
     */
    public void fitScreenToWindow() throws Exception {
        if (!pageHasFocus()) {
            resizeGrid();
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
        if (isBlocked() || commandWindow.open()) {
            // While the command-line window is open Neovim's editor window is shorter by its
            // rows, and the overhead would count them: the screen keeps its size until it closes.
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
        submit("wrap " + wrap + " for " + buffer.name(), Level.FINE, () -> {
            if (buffers.handleOf(buffer) == null || !buffer.loaded) {
                return; // not created yet, or gone; SET_WRAP itself skips a buffer not shown
            }
            await(api.execLua(LuaScripts.SET_WRAP, List.of(String.valueOf(buffer.bufnr), flag(wrap))));
        });
    }

    @Override
    public void setTopLineAsync(DocumentBuffer buffer, int line1) {
        if (line1 < 1) {
            return;
        }
        submit("top line " + line1 + " for " + buffer.name(), Level.FINE, () -> sendTopLine(buffer, line1, "keep"));
    }

    @Override
    public void scrollToAsync(DocumentBuffer buffer, int line1) {
        if (line1 < 1) {
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
        if (lines == 0) {
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
        if (line1 < 1 || buffers.handleOf(buffer) == null || !buffer.loaded) {
            return; // not created yet, or gone; SET_TOPLINE itself skips a buffer not shown
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
        WinCursor placed = new WinCursor(line1Based, col0Based);
        try {
            await(api.winSetCursor(attachedWindow(), placed));
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof NeovimErrorException refused)) {
                throw e;
            }
            placed = onTheLastLine(line1Based, refused);
        }
        state.rememberCursor(new CursorPos(placed.row(), placed.col()));
    }

    /**
     * The safety net of {@link #setCursor}: Neovim refused a cursor, and if that was because the
     * line is past its last one, the cursor goes to the end of the last line instead, and that is
     * returned; any other refusal is thrown on. Costs nothing on the usual path: only a refusal
     * asks for the line count.
     *
     * <p>Why: the editor keeps the positions it sends on Neovim's lines
     * ({@code editor/DocumentPositions.offsetToCursor}), but Eclipse shows one line more than Neovim
     * has after a final newline, and a position computed some other way can still name that line.
     * Example: the cursor after Esc was counted in the document's own text, and named line 4 of a
     * three-line buffer; the refusal broke off the rest of Esc's work. Neovim knows its own line
     * count, so this holds for every caller, today's and later ones.
     */
    private WinCursor onTheLastLine(int line1Based, NeovimErrorException refused) throws Exception {
        Handle buffer = buffers.attached(); // the attached window's buffer
        int last = await(api.bufLineCount(buffer));
        if (line1Based <= last) {
            throw refused; // not a line past the end: a real error
        }
        List<String> lastLine = await(api.bufGetLines(buffer, last - 1, last, true));
        int end = lastLine.isEmpty() ? 0 : lastLine.get(0).getBytes(StandardCharsets.UTF_8).length;
        WinCursor placed = new WinCursor(last, end);
        await(api.winSetCursor(attachedWindow(), placed));
        LOG.fine(() -> "cursor line " + line1Based + " is past Neovim's last line, " + last + "; put at its end");
        return placed;
    }

    /**
     * The attached editor's Neovim window, for the caret's calls: the current one only while no
     * page has the focus ({@link #focusPageAsync}), so these name it. Neovim's current window when
     * the attached window was closed in Neovim, as before windows were named here.
     */
    private Handle attachedWindow() {
        int win = buffers.attachedWin();
        return win == EditorWindow.UNKNOWN ? Handle.CURRENT_WINDOW : Handle.window(win);
    }

    public CursorPos getCursor() throws Exception {
        WinCursor cursor = await(api.winGetCursor(attachedWindow()));
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
                String.valueOf(buffers.attachedBufnr()),
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

    /** Runs attach-time setup (document push, initial cursor) on the input thread ({@link #runAsync}). */
    public void runAttachSetup(Runnable work) {
        runAsync("attach setup", work);
    }

    /**
     * Runs {@code work} on the input thread, after every key and command queued before it. For
     * blocking calls from editor code, such as pushing the document.
     *
     * <p><b>While Neovim waits for the rest of a command it is held</b>, with all work queued after
     * it but the keys ({@link #submit}). As in Neovim, the command stays in the window it was typed
     * in and the next key finishes it there; then Neovim moves to the new editor, before the key
     * after that. Example: {@code r} in one editor, a switch to another, {@code Q} replaces the
     * character in the first, and {@code x} deletes one in the second. Run at once, this would wait
     * for an answer Neovim gives only after the key queued behind it, and then fail.
     *
     * @param what short description for the log, should {@code work} fail
     */
    public void runAsync(String what, Runnable work) {
        submit(what, Level.WARNING, () -> {
            if (isBlocked()) {
                documentWorkWaiting = true;
                held.add(new Held(what, work::run));
            } else {
                work.run();
            }
        });
    }

    /** Queues one key (Neovim notation) for ordered processing. */
    public void inputAsync(String keys) {
        keyInput.keyQueued(); // before the submit, so a hand-back sees it (KeyInput#handBackToEclipse)
        // No key in the label: a failure logs it at WARNING, into Eclipse's Error Log, and the key
        // may be part of a password typed into inputsecret() (KeyInput#input logs it at FINE).
        submitPastHeld("input of a key", Level.WARNING, () -> keyInput.inputQueued(keys));
    }

    /**
     * Abandons an open {@code :}, {@code /} or {@code ?} line with {@code <C-c>}, for a click in the
     * text or the keyboard leaving, in order with the keys. <b>Nothing while Neovim waits for the
     * rest of a command on that line</b>, read here on the input thread: after {@code Ctrl-V} the
     * {@code <C-c>} would be typed into the line, and after {@code Ctrl-R} it would cancel the line
     * though the wait was all it should end. The line stays, and the next key finishes the command.
     */
    public void abandonLineAsync() {
        keyInput.keyQueued(); // before the submit, as for inputAsync
        submitPastHeld("abandon the command line", Level.WARNING, () -> {
            if (isBlocked()) {
                keyInput.keyDropped();
                LOG.fine("The command line stays: Neovim waits for the rest of a command");
                return;
            }
            keyInput.inputQueued("<C-c>");
        });
    }

    /**
     * Hands a mouse event in the editor to Neovim's own mouse ({@link KeyInput#mouse}), on the input
     * thread, in order with the keys. Drags that arrive faster than Neovim answers replace each other
     * ({@link LatestValue}): only the newest position is sent, and a release still comes after it.
     */
    public void mouseAsync(MouseInput event) {
        if (event.action() == MouseInput.Action.DRAG) {
            if (pendingDrag.offer(event)) {
                submitPastHeld("mouse drag", Level.FINE, () -> {
                    MouseInput latest = pendingDrag.take();
                    if (latest != null) {
                        keyInput.mouse(latest);
                    }
                });
            }
            return;
        }
        submitPastHeld("mouse " + event.action().luaName(), Level.FINE, () -> keyInput.mouse(event));
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
     * prints, Neovim shows itself (ui2, {@link LuaScripts#UI2}). See {@link CommandRunner#run}.
     */
    public void commandAsync(String cmd) {
        // No command line in the label, as for a key (inputAsync): a failure logs the label at
        // WARNING, into Eclipse's Error Log and .metadata/.log, and a line typed in the : box may
        // hold a secret (:!curl -H 'Authorization: token ...').
        submit("a : command", Level.WARNING, () -> commands.run(cmd));
    }

    /**
     * Eclipse's <i>Toggle Split Editor</i> as a Vim split ({@link LuaScripts#SPLIT_WINDOW}): Neovim
     * works out the side from {@code 'splitright'} or {@code 'splitbelow'} and sends the split back
     * as {@code :Eclipse window}, which the attached editor carries out. In order with the keys.
     *
     * @param sideBySide {@code :vsplit}; {@code false} for {@code :split}
     */
    public void splitWindowAsync(boolean sideBySide) {
        submit("split", Level.WARNING,
                () -> await(api.execLua(LuaScripts.SPLIT_WINDOW, List.of(sideBySide ? "1" : "0"))));
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
     * RPC channel, so this cannot cut into a request of the input thread. And the request is written
     * on a thread of its own ({@link BoundedSend}), so that the bound covers the write too: with
     * Neovim not reading its input and the input thread stuck writing a large request, the write
     * would wait for the channel's lock for good.
     *
     * @return how many working folders Neovim moved, or {@code -1} when it did not answer in time,
     *         failed, or is closed
     */
    public int leaveFolder(String folder, Duration timeout) {
        if (!isAlive()) {
            return -1;
        }
        try {
            Object moved = BoundedSend.start("neovim4eclipse-leave-folder",
                    () -> api.execLua(LuaScripts.LEAVE_FOLDER, List.of(folder)))
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
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
     * <p>Eclipse stops typing now, on the UI thread, as at a sync point
     * ({@link #syncInsertThenInputAsync}): the mode stays insert until Neovim reports the change, and
     * until then a key typed after Esc would still be Eclipse's to insert. Example: {@code ihello},
     * then {@code <Esc>:w<CR>} typed fast; without the pause, {@code :w} and Enter landed in the
     * document. Paused, they take the Neovim-driven path ({@code KeyRouter}), queued behind this
     * task, and reach Neovim in Normal mode. The mode change ends the Neovim-driven insert again
     * ({@code KeyInput#onModeChanged}).
     *
     * @param replay what to re-type, or {@code null}
     */
    public void leaveInsertModeAsync(InsertReplay.Plan replay) {
        handOver("leave insert mode", () -> keyInput.leaveInsertMode(replay));
    }

    /**
     * A sync point: a {@code Ctrl} key Neovim handles in an insert Eclipse types. Eclipse stops
     * typing now, on the UI thread, so a key that follows goes to Neovim after this one
     * ({@code KeyRouter}); then, on the input thread, the typed text goes to Neovim and the key is
     * sent. See {@link KeyInput#syncInsertThenInput}.
     *
     * @param sync what to hand over, or {@code null} for nothing but the document
     */
    public void syncInsertThenInputAsync(InsertReplay.SyncPoint sync, String key) {
        handOver(key + " after the typed text", () -> keyInput.syncInsertThenInput(sync, key));
    }

    /**
     * A sync point whose key starts or steers Neovim's completion ({@code <C-n>}, {@code <C-p>},
     * {@code <C-x><C-l>}): as {@link #syncInsertThenInputAsync}, and Neovim types on until the
     * completion has ended ({@code KeyInput#isCompleting}). Counted from now, on the UI thread, so
     * the key contexts give Neovim every {@code Ctrl} letter before the next key is pressed.
     */
    @Override
    public void completeInNeovimAsync(InsertReplay.SyncPoint sync, String keys) {
        keyInput.handOverCompletion(task -> submitPastHeld(keys + " (completion) after the typed text", Level.WARNING, task),
                () -> keyInput.syncInsertThenComplete(sync, keys));
    }

    /**
     * Whether Neovim's cursor is in a plug-in's float (a picker's prompt), as of the last redraw:
     * the editor then moves Neovim out of it when the user leaves ({@link #moveOutOfFloatAsync}).
     * Any thread.
     */
    public boolean inPluginFloat() {
        return focusedFloat != -1;
    }

    /** The plug-in's float Neovim's cursor is in, as of the last redraw, or -1 ({@link #inPluginFloat}). Any thread. */
    public int pluginFloat() {
        return focusedFloat;
    }

    /**
     * Leaves the plug-in's float Neovim's cursor is in, closing it, an insert in it ended first
     * ({@code LuaScripts.WINDOWS}' {@code leave_float}): the user turned Neovim mode off, so its
     * keys are Eclipse's and the float would wait for keys it no longer gets. On the input thread,
     * so that a key typed before goes into the float first. Nothing when the cursor is in no
     * plug-in's float by then. For the user clicking away, {@link #moveOutOfFloatAsync}.
     */
    public void leaveFloatAsync() {
        leaveFloatAsync(-1);
    }

    /**
     * As {@link #leaveFloatAsync()}, and closes {@code win} too, the float the cursor was in when
     * the keyboard left a part ({@link #pluginFloat}, read then), though Neovim may have left it
     * since for the part that took the keyboard: only {@code win} closes then, so that part's own
     * float stays, and an insert Neovim carried out of {@code win} ends. -1: only the one
     * the cursor is in. Example: a picker's prompt open in a page, a click in the code: the
     * prompt closes, though Neovim is in the code's window by the time this runs.
     *
     * <p><b>Nothing while Neovim waits for the rest of a command</b> ({@code Ctrl-W}, {@code f}), as
     * losing the focus changes nothing in a terminal: the float stays, and the next key finishes
     * the command. Neovim would answer only after that key, which is queued behind this, and held
     * until then this would act on what the key began ({@link #leaveFloatWithLineAsync}). No key is
     * sent to end the wait: after {@code r} or {@code Ctrl-V}, {@code <C-c>} and {@code <Esc>} are
     * typed into the text.
     */
    public void leaveFloatAsync(int win) {
        submitPastHeld("leave the floating window", Level.FINE, () -> {
            if (isBlocked()) {
                LOG.fine("The floating window stays: Neovim waits for the rest of a command");
                return;
            }
            buffers.leavingFloat(LuaScripts.LEAVE_FLOAT, List.of(String.valueOf(win)));
        });
    }

    /**
     * Moves Neovim out of the plug-in's float its cursor is in, to the editor's or page's window,
     * and leaves the float open ({@code LuaScripts.WINDOWS}' {@code move_out_of_float}), as a click
     * outside a float does in a terminal: the user clicked the text or left for another part. The
     * plug-in decides whether its window survives it: Telescope closes on {@code BufLeave},
     * {@code :Lazy} stays drawn. On the input thread, so that a key typed before goes into the
     * float first and a click after lands in the editor's window.
     *
     * <p><b>Nothing while Neovim waits for the rest of a command</b>, for the reasons at
     * {@link #leaveFloatAsync(int)}.
     *
     * @param win the float the cursor was in when the keyboard left a part
     *            ({@link #pluginFloat}, read then), or -1: when Neovim has moved on since, only an
     *            insert begun in that float ends
     */
    public void moveOutOfFloatAsync(int win) {
        submitPastHeld("move out of the floating window", Level.FINE, () -> {
            if (isBlocked()) {
                LOG.fine("Neovim stays in the floating window: it waits for the rest of a command");
                return;
            }
            buffers.leavingFloat(LuaScripts.MOVE_OUT_OF_FLOAT, List.of(String.valueOf(win)));
        });
    }

    /**
     * As {@link #moveOutOfFloatAsync}, for a press in the text that stays Eclipse's: a {@code :} line
     * opened from the float goes too, and one opened from the editor stays
     * ({@link LuaScripts#LEAVE_FLOAT_WITH_LINE}). On the input thread, after the keys typed before.
     *
     * <p><b>Dropped while Neovim waits for the rest of a command</b>, as a click in the text is
     * ({@link KeyInput#mouse}). Neovim would answer only after the next key, which is queued behind
     * this; and held until then, it would act on what that key began. Example: a picker, {@code
     * Ctrl-W}, a right click, {@code :d<CR>}: the {@code :} ends the wait, a held click would cancel
     * that line and close the picker, and {@code d<CR>} would delete lines of the document.
     */
    public void leaveFloatWithLineAsync() {
        submitPastHeld("leave the floating window and its line", Level.FINE, () -> {
            if (isBlocked()) {
                LOG.fine("A press left to Eclipse dropped: Neovim waits for the rest of a command");
                return;
            }
            buffers.leavingFloat(LuaScripts.LEAVE_FLOAT_WITH_LINE, List.of());
        });
    }

    /**
     * Ends a completion Neovim is in, the text kept as it stands, so that Eclipse types again
     * ({@code KeyInput#endCompletion}): for a click into the text or a loss of the focus while
     * the list is open. Queued behind the keys typed before; nothing if no completion is going on.
     */
    public void endCompletionAsync() {
        submit("end the completion", Level.WARNING, keyInput::endCompletion);
    }

    /**
     * As {@link #endCompletionAsync()}, then {@code after}, on the input thread: for
     * {@code Ctrl-X Ctrl-O} while Neovim completes, which opens Eclipse's content assist once
     * Eclipse types again. {@code after} runs whether or not a completion was still going on.
     */
    public void endCompletionThenAsync(Runnable after) {
        submit("end the completion, then go on", Level.WARNING, () -> {
            keyInput.endCompletion();
            after.run();
        });
    }

    /**
     * Eclipse stops typing and {@code work} is queued, with Neovim's cursors and top lines ignored
     * from this moment ({@code KeyInput#handOver}). UI thread.
     */
    private void handOver(String what, InputThread.Task work) {
        keyInput.handOver(task -> submitPastHeld(what, Level.WARNING, task), work);
    }

    /**
     * Queues {@code work} on the input thread ({@link InputThread#submit}). Afterward, even if it
     * failed, the input-handled listeners hear that Neovim has done what it asked (see
     * {@link #addInputHandledListener}).
     *
     * @return {@code false} if {@code work} will never run (the session is closed)
     */
    private boolean submit(String what, Level failureLevel, InputThread.Task work) {
        return inputThread.submit(what, failureLevel, () -> {
            try {
                releaseHeldIfWaitEnded();
                if (documentWorkWaiting) {
                    held.add(new Held(what, work)); // behind the document work, which it follows
                } else {
                    runThenHeld(work);
                }
            } finally {
                events.inputHandled();
            }
        });
    }

    /**
     * {@link #submit} for what must run even while document work waits ({@link #runAsync}): the
     * keys, one of which ends the wait, and the mouse and leaving, which do nothing while Neovim
     * waits and must not be held to act on what later keys began.
     */
    private boolean submitPastHeld(String what, Level failureLevel, InputThread.Task work) {
        return inputThread.submit(what, failureLevel, () -> {
            try {
                releaseHeldIfWaitEnded();
                runThenHeld(work);
            } finally {
                events.inputHandled();
            }
        });
    }

    /**
     * Runs the held document work once Neovim no longer waits. Asked before each piece of work too:
     * a wait can end with no key. Example: {@code <Space>} of a mapping, a switch to another editor,
     * {@code 'timeoutlen'} passes; the next key belongs to the new editor, so the switch goes first.
     */
    private void releaseHeldIfWaitEnded() {
        if (documentWorkWaiting && !closed && !isBlocked()) {
            documentWorkWaiting = false;
            runHeld();
        }
    }

    /** Runs {@code work}, then what waited for Neovim to answer, if it answers now. Input thread. */
    private void runThenHeld(InputThread.Task work) throws Exception {
        work.run();
        releaseHeldIfWaitEnded();
        if (documentWorkWaiting) {
            return;
        }
        if (gridPending && !closed) {
            resizeGrid(); // skipped while Neovim waited; this work may have ended the wait
        }
        if (!held.isEmpty() && !closed && !isBlocked()) {
            runHeld(); // the same for work held by whenAnswering
        }
    }

    /**
     * One request to Neovim that changes nothing, sent from the calling thread without going through
     * the input queue. For tests: Neovim answers requests from one client in order, so when this
     * completes, everything Neovim sent before its answer has reached the reader thread. It answers
     * {@code nvim_get_mode} even while it waits for the rest of a command (after {@code g}). Example:
     * a test's {@code settle} uses it in place of a fixed wait for a notification still on its way.
     */
    java.util.concurrent.Future<?> roundTrip() {
        return api.getMode();
    }

    /**
     * Completes once the input thread has run everything queued before this call: every
     * {@code *Async} call so far has been sent to Neovim (and answered, for the ones that wait for an
     * answer). For tests: {@code NeovimBarrier.pass} waits for it before its own round trip, since
     * a call the test thread sends itself overtakes work still in the queue. Example:
     * {@code setBackgroundAsync(true)} only queues the {@code 'background'} change; without this
     * wait a test could read the option before it is set. Work held by {@link #whenAnswering}
     * while Neovim waits for the rest of a command is not covered: it runs when the wait ends.
     * Completes at once if the session is closed (the queue is dropped). Any thread.
     */
    CompletableFuture<Void> queuedWorkDone() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        // inputThread.submit, not submit(): this marker is not input, so the input-handled
        // listeners must not hear of it (some tests count those events).
        if (!inputThread.submit("queue barrier", Level.FINE, () -> done.complete(null))) {
            done.complete(null);
        }
        return done;
    }

    /**
     * Runs {@code work} now, or, while Neovim waits for the rest of a command (and would not answer),
     * after the input-thread work that ends the wait. Input thread. Like {@link #resizeGrid}'s skip,
     * for work that must not be lost.
     */
    private void whenAnswering(String what, InputThread.Task work) throws Exception {
        if (isBlocked()) {
            held.add(new Held(what, work));
        } else {
            work.run();
        }
    }

    /** Runs the held work in order; a failure is logged and does not stop the rest. Input thread. */
    private void runHeld() {
        for (Held h = held.peek(); h != null; h = held.peek()) {
            if (isBlocked()) { // the work before this one left Neovim waiting: the rest waits with it
                documentWorkWaiting = true;
                return;
            }
            held.poll();
            try {
                h.work().run();
            } catch (Exception e) {
                LOG.log(Level.FINE, h.what() + " failed", e);
            }
        }
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
        public void onMode(String mode, boolean inFocusedFloat) {
            // On this reader thread, in the order Neovim reported, with the mode: where an insert began.
            state.dispatchMode(mode, () -> keyInput.onModeReport(mode, inFocusedFloat));
        }

        @Override
        public void onCursor(int line1, int col0, int bufnr, int winid) {
            if (buffers.isAttached(winid, bufnr)) {
                state.dispatchCursor(line1, col0);
            }
        }

        @Override
        public void onBufferLines(int bufnr, int firstLine, int lastLine, List<String> newLines) {
            if (bufnr != NotificationBridge.UNKNOWN_BUFFER && bufnr == echoBufnr) {
                return; // the echo of Eclipse's own write (setDocumentBufferLines)
            }
            DocumentBuffer document = buffers.documentOf(bufnr);
            if (document != null) {
                document.markOutOfSync(); // the next attach pushes again (DocumentBuffer.syncedStamp)
            }
            if (buffers.isAttached(bufnr) && (document == null || !editorLetGo.contains(document))) {
                state.dispatchBufferChange(firstLine, lastLine, newLines);
                return;
            }
            // Another document's buffer (:bufdo, :argdo, a plug-in), or one that is no document's
            // (help, :enew), which Eclipse does not mirror.
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
        public void onEcho(int bufnr, boolean starts) {
            echoBufnr = starts ? bufnr : NotificationBridge.UNKNOWN_BUFFER;
        }

        @Override
        public void onBufferEnter(int bufnr, int winid, boolean jumped, String projectFile, boolean pageFollows,
                boolean floatEntered) {
            buffers.onEntered(bufnr, winid, jumped, projectFile, pageFollows, floatEntered,
                    () -> submit("return to attached buffer", Level.FINE, () -> buffers.returnFromEnter(winid)),
                    () -> restorePageAsync(winid));
        }

        @Override
        public void onBufferReload(int bufnr, boolean kept) {
            DocumentBuffer document = buffers.documentAt(bufnr);
            if (closed || document == null) {
                return;
            }
            if (kept) {
                reattachAfterReloadAsync(document);
                return;
            }
            Consumer<DocumentBuffer> handler = reloadHandler;
            if (handler != null) {
                LOG.fine(() -> "Neovim loaded document buffer " + bufnr + " empty: Eclipse loads its text again");
                handler.accept(document);
            }
        }

        @Override
        public void onUi2Windows(List<Integer> wins, int cmd, int msg, boolean overlay) {
            grids.ui2Windows(wins, cmd, msg, overlay); // on the reader thread, as the grids' redraws
            int rows = overlay ? 0 : 1;
            if (rows != commandLineRows || !commandLineRowsReported) {
                commandLineRows = rows; // before the floats: the editor places them by its rows
                commandLineRowsReported = true; // after the rows: a reader seeing it sees them
                events.commandLineRows(rows);
            }
            // A new ui2 window (ui2 makes its line's window anew after expanded output, and the
            // plug-in names it only once the redraw that made it has ended): until now it was an
            // ordinary float, drawn over the status line with the overlay. Between redraws no
            // flush is coming to send the floats again, so they go now; in a redraw, at its flush.
            if (grids.betweenRedraws()) {
                sendFloatsIfChanged();
            }
        }

        @Override
        public void onStatusLines(List<GridSnapshot> lines) {
            lines.forEach(status::publishStatusLine);
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
            if (!closed) {
                LOG.fine(() -> "Neovim closed buffer " + bufnr + " by itself");
                // Here on the reader thread, not queued: the number is resolved to its document
                // before a queued show can make the buffer anew under another one (onClosed).
                buffers.onClosed(bufnr);
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
        public int attachedWindow() {
            return buffers.attachedWin();
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
            if (buffers.isAttached(winid, bufnr)) {
                LOG.finest(() -> "Neovim's top line " + line1 + " in buffer " + bufnr);
                events.topLine(new TopLine(bufnr, line1));
            }
        }

        @Override
        public void onSearchMatches(int bufnr, List<SearchMatch> matches, int winid) {
            if (searchEnabled && buffers.isAttached(winid, bufnr)) {
                LOG.finest(() -> matches.size() + " search matches in view, buffer " + bufnr);
                events.search(new Matches(bufnr, matches));
            }
        }

        @Override
        public void onHighlights(int bufnr, List<NeovimHighlight> highlights, List<JumpLabels.Label> labels, int winid) {
            if (highlightsEnabled && buffers.isAttached(winid, bufnr)) {
                LOG.finest(() -> highlights.size() + " highlights and " + labels.size() + " labels in view, buffer " + bufnr);
                events.highlights(new Highlights(bufnr, highlights, labels));
            }
        }

        @Override
        public void onViewportOverheadChanged() {
            if (!closed) {
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
        public void onQuickfix(QuickfixEntries.Kind kind, String arg, boolean bang) {
            QuickfixPort.Handler handler = quickfixHandler;
            LOG.fine(() -> ":" + (kind == QuickfixEntries.Kind.PROBLEMS ? "Eproblems" : "Etasks") + (bang ? "! " : " ") + arg);
            if (!closed && handler != null) {
                handler.request(kind, arg, bang);
            }
        }

        @Override
        public void onShowView(QuickfixEntries.Kind kind) {
            QuickfixPort.Handler handler = quickfixHandler;
            if (!closed && handler != null) {
                handler.showView(kind);
            }
        }

        /** {@code :messages}: to the page editor; an empty history shows nothing, as in Vim. */
        @Override
        public void onMessages(List<ViewText.Message> history) {
            if (!history.isEmpty()) {
                status.onViewContent(ViewText.messages(history));
            }
        }

        @Override
        public void onMouseOption(String option) {
            mouseOption = option;
        }

        @Override
        public void onCmdlineType(String type) {
            searching = "/".equals(type) || "?".equals(type);
            cmdlineType = type;
            events.cmdlineType(type);
        }

        @Override
        public void onViewContent(ViewContent content) {
            status.onViewContent(content);
        }

        /**
         * Builds the grids; at each {@code flush} the command-line window's grid, if it is open and
         * changed, goes to the listeners, and so do the floating windows, if they changed.
         */
        @Override
        public void onGridEvents(List<GridEvent> gridEvents) {
            // Read once per batch, here on the grids' own thread: the drawing and the check for an
            // undrawn float (Grids.unseenCursor) both follow it. False until the start is done.
            grids.drawFloats(floatingWindowsDrawn());
            grids.eclipseTypes(isTypingMode() && !isNeovimDrivenInsert());
            for (GridEvent event : gridEvents) {
                // While a search is typed, Neovim's view follows the match ('incsearch'); the attached
                // editor's window's cursor (not ui2's windows', which come in the same redraw) goes to
                // the editor's caret as any cursor from Neovim does, and Esc or Enter then sets it for
                // good through the state read after that key.
                if (event instanceof GridEvent.Viewport viewport && searching && viewport.win() == buffers.attachedWin()) {
                    state.dispatchCursor(viewport.curline() + 1, viewport.curcol());
                }
                grids.apply(event);
                if (event instanceof GridEvent.Flush) {
                    grids.snapshotIfChanged().ifPresent(this::commandWindowDrawn);
                    for (GridSnapshot page : grids.pagesIfChanged()) {
                        int grid = grids.pageGrid(page.window());
                        if (grid != -1) {
                            pageGrids.put(page.window(), grid);
                        }
                        events.pageGrid(page);
                    }
                    for (GridSnapshot status : grids.pageStatusIfChanged()) {
                        events.pageStatus(status);
                    }
                    sendFloatsIfChanged();
                }
            }
        }

        /**
         * The floats to the listeners, if they changed since the last time; and the one check for
         * "keys go only to a window Eclipse draws": with Neovim's cursor on a grid the editor does
         * not draw ({@code Grids.unseenCursor}), Neovim is asked to close the plug-in's float it is
         * in ({@code LuaScripts.CLOSE_UNDRAWN_FLOAT}), which it does only when that is what it
         * finds: a float its plug-in hid, one past {@code Grids}' limits. Every route
         * into an undrawn float ends at a redraw, so this one place covers them all. Reader thread.
         */
        private void sendFloatsIfChanged() {
            grids.floatsIfChanged().ifPresent(floats -> {
                floatingWindows = floats;
                events.floatingWindows(floats);
            });
            int floatBefore = focusedFloat;
            focusedFloat = grids.focusedFloat();
            if (floatBefore != -1 && focusedFloat == -1 && VimModes.isTerminal(getMode())) {
                // A plug-in may close its terminal float with 'eventignore' set (fzf-lua does), and
                // Neovim then leaves Terminal mode without a ModeChanged. Read at the float's closing,
                // so that Eclipse does not show TERMINAL until the next key.
                submit("read the mode after a terminal float closed", Level.FINE, state::syncModeAndCursor);
            }
            Optional<Grids.UnseenCursor> unseen = grids.unseenCursor(); // the decision: util/Grids, GridsTest
            if (unseen.isEmpty()) {
                // Asked again once the cursor has been on a drawn grid: a float Neovim refused to
                // close (a changed buffer) and the user entered again takes the keys unseen again.
                unseenAsked = null;
            } else if (!unseen.get().equals(unseenAsked)) {
                Grids.UnseenCursor at = unseen.get();
                unseenAsked = at; // once for each: each redraw would ask again
                submit("close an undrawn floating window", Level.FINE,
                        () -> buffers.leavingFloat(LuaScripts.CLOSE_UNDRAWN_FLOAT,
                                List.of(String.valueOf(at.window()), Grids.PAST_LIMITS)));
            }
        }

        @Override
        public boolean wantsGridLines(int grid) {
            return grids.wantsLines(grid);
        }

        @Override
        public void onCommandWindow(int winid, String type) {
            grids.draw(winid);
            commandWindow = new CommandWindowView(true, type, null);
            events.commandWindow(commandWindow);
            grids.snapshotIfChanged().ifPresent(this::commandWindowDrawn); // when its grid came first
        }

        @Override
        public void onCommandWindowClosed() {
            grids.stopDrawing();
            commandWindowGrid = -1;
            commandWindow = CommandWindowView.CLOSED;
            events.commandWindow(commandWindow);
            submit("resize after the command-line window", Level.FINE, () -> {
                // nothing: submit runs a resize held while the window was open (gridPending)
            });
        }

        private void commandWindowDrawn(GridSnapshot grid) {
            CommandWindowView view = commandWindow;
            if (view.open()) {
                commandWindowGrid = grids.drawnGrid();
                commandWindow = new CommandWindowView(true, view.type(), grid);
                events.commandWindow(commandWindow);
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
            await(api.input(keys));
        }

        @Override
        public void awaitIdle(Duration timeout) throws Exception {
            api.execLua("return 0", List.of()).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
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
        public Object stateSnapshot(boolean statusLine, boolean folds) throws Exception {
            // Short timeout: see STATE_TIMEOUT_MS.
            return api.execLua(LuaScripts.STATE_SNAPSHOT, List.of(flag(statusLine), flag(folds)))
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
        public boolean typeText(String text) throws Exception {
            return Boolean.TRUE.equals(await(api.execLua(LuaScripts.TYPE_TEXT, List.of(text))));
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
    }
}
