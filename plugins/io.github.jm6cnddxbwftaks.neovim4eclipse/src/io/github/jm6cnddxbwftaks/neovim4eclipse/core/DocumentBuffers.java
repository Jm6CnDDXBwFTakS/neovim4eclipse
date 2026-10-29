// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.core.Rpc.await;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Handle;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.NeovimApi;

/**
 * The Neovim buffers that mirror Eclipse documents, the windows of the editors that show them, and
 * which buffer and window are <em>attached</em>.
 *
 * <h2>Buffers in Neovim</h2>
 * A Neovim buffer is the text of one file in memory, with its own undo history and marks. A
 * <em>window</em> shows one buffer at a time. Giving each
 * Eclipse document its own buffer (instead of reloading one buffer on every editor switch) keeps
 * undo history and marks per file, and lets Vim's own file jumps ({@code Ctrl-O} across files,
 * {@code 'A}, {@code :b}) work.
 *
 * <ul>
 *   <li>The <em>home</em> buffer is an unlisted scratch buffer, shown when no document is (at
 *       start-up, after the attached document's buffer was wiped).</li>
 *   <li>The <em>attached</em> buffer is the one mirrored into the attached editor: buffer reads and
 *       writes go to it, and only its cursor positions reach Eclipse. Its line events go to the
 *       attached editor; those of the other document buffers to their own documents
 *       ({@link #documentOf}, {@link DocumentBufferPort}).</li>
 *   <li>When Neovim enters another buffer by itself, the enter handler decides: follow it (another
 *       open document) or switch back (help, {@code :e} of an unrelated file, a closed document).</li>
 * </ul>
 *
 * <h2>Windows</h2>
 * Each editor can have its own Neovim window ({@link EditorWindow}, docs/design/WINDOWS.md), alone in
 * a hidden tabpage, so that it keeps its own cursor, scroll position, folds and jump history between
 * editor switches. The <em>home window</em>, the one Neovim started with, shows the home buffer, and
 * the document of an editor shown without a window of its own ({@link #show(DocumentBuffer)}, as all
 * editors were before windows). The <em>attached window</em> is the one showing the attached buffer:
 * cursor reports and the state snapshot count only from it ({@link #isAttached(int, int)}), so two
 * editors on one file are told apart. The Neovim side is {@link LuaScripts#WINDOWS}; which token
 * holds which window is {@link EditorWindows}.
 *
 * <p>Methods that talk to Neovim block and must run on the session's input thread.
 */
final class DocumentBuffers {

    private static final Logger LOG = Logger.getLogger(DocumentBuffers.class.getName());

    private final NeovimApi api;
    private final Handle home;
    /** The editor windows (Java side); the home window is not one of them. */
    private final EditorWindows windows = new EditorWindows();
    /**
     * The home window's id; {@link EditorWindow#UNKNOWN} after the user closed it, until the next
     * show of it opens it again ({@link LuaScripts#SHOW_WINDOW} with -1).
     */
    private volatile int homeWin;
    /** The window showing the attached buffer; {@link EditorWindow#UNKNOWN} after it was closed in Neovim. */
    private volatile int attachedWin;
    /** Changed on the input thread only; read from any thread. */
    private volatile Handle attached;
    private volatile int attachedBufnr;
    /** The document mirrored into the attached buffer; {@code null} for the home buffer. */
    private volatile DocumentBuffer attachedDocument;
    /** Buffers created by {@link #show}, by number. */
    private final Map<Integer, Handle> created = new ConcurrentHashMap<>();
    /** The documents mirrored into the buffers in {@link #created}, by number. */
    private final Map<Integer, DocumentBuffer> documents = new ConcurrentHashMap<>();
    /** Called with the number of a document buffer Neovim switched to by itself. */
    private volatile Consumer<Integer> enterHandler;
    /**
     * The buffer Neovim entered last ({@code BufEnter}), that is, its current buffer as far as the
     * notifications read so far tell. Written on the RPC thread, read from any thread.
     */
    private volatile int enteredBufnr;
    /**
     * Whether Neovim's move into {@link #enteredBufnr} is in the window's jump history (see the
     * {@code BufEnter} autocommand of {@link LuaScripts#SETUP}). Written before it, on the same thread.
     */
    private volatile boolean enteredByJump;

    /**
     * @param home    the home buffer, shown in the home window
     * @param homeWin the home window, as {@link LuaScripts#WINDOWS} returned it
     */
    DocumentBuffers(NeovimApi api, Handle home, int homeWin) {
        this.api = api;
        this.home = home;
        this.homeWin = homeWin;
        this.attachedWin = homeWin;
        this.attached = home;
        this.attachedBufnr = bufnr(home);
        this.enteredBufnr = attachedBufnr;
    }

    static int bufnr(Handle buffer) {
        return buffer.intId();
    }

    /** The buffer mirrored into the attached document (the home buffer when none is). */
    Handle attached() {
        return attached;
    }

    int attachedBufnr() {
        return attachedBufnr;
    }

    /** The window showing the attached buffer: an editor's own, or the home window. */
    int attachedWin() {
        return attachedWin;
    }

    /** The editor windows of this session. */
    EditorWindows windows() {
        return windows;
    }

    /**
     * The buffer Neovim entered last. When it differs from a buffer the enter handler was called
     * with, Neovim has moved on since: {@code :bufdo}, {@code :argdo} and {@code :cdo} enter every
     * buffer in turn, and only the last one is worth following.
     */
    int enteredBufnr() {
        return enteredBufnr;
    }

    /** Whether the move into {@link #enteredBufnr()} is in Neovim's jump history. */
    boolean enteredByJump() {
        return enteredByJump;
    }

    /** The document mirrored into the attached buffer, or {@code null} when the home buffer is attached. */
    DocumentBuffer attachedDocument() {
        return attachedDocument;
    }

    /**
     * Whether an event for {@code bufnr} concerns the attached document. Events whose buffer is
     * unknown are accepted, as they always were before buffers were per document.
     */
    boolean isAttached(int bufnr) {
        return bufnr == NotificationBridge.UNKNOWN_BUFFER || bufnr == attachedBufnr;
    }

    /**
     * Whether a cursor report or a snapshot from window {@code winid} on buffer {@code bufnr} is the
     * attached editor's: both must match ({@link EditorWindow#UNKNOWN} and
     * {@link NotificationBridge#UNKNOWN_BUFFER} match anything). Example: two editors on one file,
     * windows 1001 and 1002; with the first attached, a cursor report from 1002 (a script ran there)
     * is not its caret. And after {@code Ctrl-O} into another file the window matches but the buffer
     * does not.
     */
    boolean isAttached(int winid, int bufnr) {
        return (winid == EditorWindow.UNKNOWN || winid == attachedWin) && isAttached(bufnr);
    }

    /** Whether {@code buffer} was created by this registry and still exists. */
    boolean owns(DocumentBuffer buffer) {
        return buffer.owner == this && buffer.bufnr > 0;
    }

    /**
     * The document mirrored into buffer {@code bufnr}, or {@code null} for any other buffer (the
     * home buffer, help, a buffer the user made with {@code :enew}, one already wiped).
     */
    DocumentBuffer documentOf(int bufnr) {
        return documents.get(bufnr);
    }

    /** Neovim's handle on a document's buffer, or {@code null} while it does not exist in this session. */
    Handle handleOf(DocumentBuffer buffer) {
        return owns(buffer) ? created.get(buffer.bufnr) : null;
    }

    /**
     * Makes {@code buffer} the attached buffer and Neovim's current one, in the home window, creating
     * the buffer on first use (an editor without a window of its own).
     */
    void show(DocumentBuffer buffer) throws Exception {
        show(buffer, null, "");
    }

    /**
     * Makes {@code buffer} the attached buffer and {@code window} the attached window, Neovim's
     * current one, creating either on first use (and again after Restart Neovim, or after the window
     * was closed in Neovim: the safety net).
     *
     * @param window the editor's window, or {@code null} for the home window
     * @param folder the folder for {@code :lcd} when the window is opened ({@link LuaScripts#OPEN_WINDOW}),
     *               normally the editor's project; {@code null} or "" for a document with no folder
     *               of its own (an untitled one): see {@link #folderOfNewWindow}
     */
    void show(DocumentBuffer buffer, EditorWindow window, String folder) throws Exception {
        Handle target = owns(buffer) ? created.get(buffer.bufnr) : null;
        if (target == null) {
            target = create(buffer.name);
            buffer.bufnr = bufnr(target);
            buffer.owner = this;
            buffer.loaded = false;
            buffer.markOutOfSync();
            documents.put(buffer.bufnr, buffer);
        }
        // The window attached before, whose folder a new untitled window inherits (folderOfNewWindow).
        int before = attachedWin;
        int win = window == null ? homeWin : windowFor(window, target, folder, before);
        // Attach first: the BufEnter this switch causes is then recognized as Eclipse's own.
        attached = target;
        attachedBufnr = buffer.bufnr;
        attachedDocument = buffer;
        attachedWin = win;
        int shown = showWindow(win, buffer.bufnr);
        if (shown == EditorWindow.UNKNOWN && window != null) {
            // Closed in Neovim since (the WinClosed notice may still be on its way): a new one.
            // SHOW_WINDOW did nothing, so the current window is still the one attached before.
            windows.forget(window);
            win = windowFor(window, target, folder, before);
            attachedWin = win;
            shown = showWindow(win, buffer.bufnr);
        }
        attachedWin = shown;
    }

    /**
     * The editor's window, opened in a new tabpage on {@code buffer} if it has none in this session.
     *
     * @param before the window attached before this show (see {@link #folderOfNewWindow})
     */
    private int windowFor(EditorWindow window, Handle buffer, String folder, int before) throws Exception {
        if (windows.owns(window)) {
            return window.winid;
        }
        Object answer = await(api.execLua(LuaScripts.OPEN_WINDOW,
                List.of(String.valueOf(bufnr(buffer)), folderOfNewWindow(folder, before))));
        if (!(answer instanceof Number id)) {
            throw new IllegalStateException("Unexpected answer opening a window: " + answer);
        }
        windows.add(window, id.intValue());
        LOG.fine(() -> "Opened window " + id + " on buffer " + bufnr(buffer));
        return id.intValue();
    }

    /**
     * The folder a new window is given (docs/design/WINDOWS.md, "Working folder per window"):
     * {@code folder} when there is one; else "" (keep the folder it copies, as a Vim window inherits
     * it) when the current window is another editor's, the editor attached just before; else, with
     * no editor before it, the user's home folder. Example: working in {@code app/src/Main.java},
     * open an untitled document: {@code :pwd} there shows {@code app}, and {@code :w notes.txt}
     * writes {@code app/notes.txt}.
     *
     * @param before the window attached before (and still Neovim's current one): an editor's
     *               window, or the home window
     */
    private String folderOfNewWindow(String folder, int before) {
        if (folder != null && !folder.isEmpty()) {
            return folder;
        }
        return windows.of(before) != null ? "" : System.getProperty("user.home", "");
    }

    /**
     * Enters window {@code win} showing buffer {@code bufnr} ({@link LuaScripts#SHOW_WINDOW}), and
     * returns the window shown: for the home window its (perhaps new) id, which is remembered; for
     * an editor window {@link EditorWindow#UNKNOWN} when it was closed in Neovim.
     */
    private int showWindow(int win, int bufnr) throws Exception {
        boolean toHome = win == EditorWindow.UNKNOWN || win == homeWin;
        Object answer = await(api.execLua(LuaScripts.SHOW_WINDOW,
                List.of(String.valueOf(toHome ? -1 : win), String.valueOf(bufnr))));
        int shown = answer instanceof Number id && id.intValue() > 0 ? id.intValue() : EditorWindow.UNKNOWN;
        if (toHome && shown != EditorWindow.UNKNOWN) {
            homeWin = shown;
        }
        return shown;
    }

    /**
     * Closes an editor's window and its tabpage (its editor closed). The buffer stays, for the
     * document's other editors; {@link #close(DocumentBuffer)} wipes it after the last one. When it
     * was the attached window, the home buffer in the home window is attached until Eclipse shows the
     * next editor.
     */
    void closeWindow(EditorWindow window) throws Exception {
        if (!windows.owns(window)) {
            return;
        }
        int win = window.winid;
        windows.forget(window);
        if (attachedWin == win) {
            attached = home;
            attachedBufnr = bufnr(home);
            attachedDocument = null;
            attachedWin = homeWin;
        }
        Object answer = await(api.execLua(LuaScripts.CLOSE_WINDOW,
                List.of(String.valueOf(win), String.valueOf(attachedBufnr))));
        if (answer instanceof Number id) {
            boolean attachedHome = attachedWin == homeWin;
            homeWin = id.intValue();
            if (attachedHome) {
                attachedWin = homeWin;
            }
        }
    }

    /**
     * The Neovim half of a window travelling on {@code Ctrl-O} across files (docs/design/WINDOWS.md,
     * E2): {@code mover}'s window jumped into the buffer {@code other}'s editor shows. {@code other}'s
     * window is quietly given {@code leftBehind} and its cursor ({@link LuaScripts#TRAVEL_WINDOW}),
     * and the two tokens swap windows ({@link EditorWindows#swap}). Example: editor A (window 1001,
     * a.txt) runs {@code :b b.txt}; editor B holds 1002 on b.txt. After the travel B holds 1001, which
     * shows b.txt and keeps the whole jump history, and A holds 1002, now on a.txt at A's cursor.
     * Eclipse then activates B's editor, whose show finds its window current already. When
     * {@code other} has no window yet (B was never attached in this session), it simply takes
     * {@code mover}'s, and {@code mover} gets a new one at its next show.
     *
     * @param line1 the 1-based line of {@code mover}'s cursor in {@code leftBehind} before the jump
     * @param col0  its 0-based byte column
     */
    void travel(EditorWindow mover, EditorWindow other, DocumentBuffer leftBehind, int line1, int col0)
            throws Exception {
        Handle back = handleOf(leftBehind);
        if (!windows.owns(mover) || back == null) {
            throw new IllegalStateException("travel needs an open window and buffer: " + mover + ", " + leftBehind.name);
        }
        int moverWin = mover.winid;
        if (!windows.owns(other)) {
            windows.transfer(mover, other);
            if (attachedWin == moverWin) {
                // The attached editor has no window now: should the attach of other's editor fail,
                // returnToAttached shows the attached buffer in the home window, not in other's.
                attachedWin = homeWin;
            }
            return;
        }
        await(api.execLua(LuaScripts.TRAVEL_WINDOW, List.of(String.valueOf(other.winid),
                String.valueOf(bufnr(back)), String.valueOf(line1), String.valueOf(col0))));
        windows.swap(mover, other);
        // The attached buffer (leftBehind, until Eclipse attaches other's editor) is now in mover's
        // new window. Should that attach fail, returnToAttached then puts it back there, and not
        // into the window other's editor now holds.
        if (attachedWin == moverWin) {
            attachedWin = mover.winid;
        }
    }

    /**
     * Neovim closed window {@code winid} by itself ({@code :tabclose}; the safety net of
     * {@link LuaScripts#WINDOWS}). Its editor gets a new window at its next show. When it was the
     * attached window, events count from no window until Eclipse returns to the attached buffer
     * (Neovim has entered some other window, whose {@code BufEnter} asks for that).
     */
    void onWindowClosed(int winid) {
        EditorWindow window = windows.forget(winid);
        LOG.fine(() -> "Neovim closed window " + winid + (window == null ? "" : ", an editor's"));
        if (winid == homeWin) {
            homeWin = EditorWindow.UNKNOWN;
        }
        if (winid == attachedWin) {
            attachedWin = EditorWindow.UNKNOWN;
        }
    }

    private Handle create(String name) throws Exception {
        // Listed, so :ls and :b see it. Scratch: no swap file, and no filetype detection runs. Its
        // buftype=nofile is then changed to acwrite, so that a write asks Eclipse to save
        // (LuaScripts.DOCUMENT_BUFFER): Neovim never writes the file itself.
        Handle buffer = name.isEmpty() ? await(api.createBuf(true, true)) : createNamed(name);
        await(api.execLua(LuaScripts.DOCUMENT_BUFFER, List.of(String.valueOf(bufnr(buffer)))));
        // nvim_buf_attach: Neovim now sends nvim_buf_lines_event for this buffer. No initial
        // full-buffer event (false): the document is pushed into the buffer instead.
        await(api.bufAttach(buffer, false));
        created.put(bufnr(buffer), buffer);
        return buffer;
    }

    /**
     * The buffer for {@code name}, as {@code :e} names one ({@link LuaScripts#CREATE_DOCUMENT_BUFFER}):
     * the file's buffer kept from an earlier editor (same number, marks), or a new one; left unnamed
     * if another document's buffer has that name already.
     */
    private Handle createNamed(String name) throws Exception {
        Object result = await(api.execLua(LuaScripts.CREATE_DOCUMENT_BUFFER, List.of(name)));
        if (!(result instanceof List<?> pair) || pair.size() != 2 || !(pair.get(0) instanceof Number bufnr)) {
            throw new IllegalStateException("Unexpected answer creating the buffer for " + name + ": " + result);
        }
        if (!Boolean.TRUE.equals(pair.get(1))) {
            LOG.fine(() -> "Buffer name already in use, left unnamed: " + name);
        }
        return Handle.buffer(bufnr.longValue());
    }

    /**
     * Closes a document's buffer, its last editor closed: as Vim's {@code :bd}, unloaded and unlisted
     * but kept, with its number and marks, for the file's next editor
     * ({@link LuaScripts#CLOSE_DOCUMENT_BUFFER}). Its undo history goes, as in Vim.
     */
    void close(DocumentBuffer buffer) throws Exception {
        if (!owns(buffer)) {
            return;
        }
        Handle doomed = created.remove(buffer.bufnr);
        documents.remove(buffer.bufnr);
        buffer.owner = null;
        if (doomed == null) {
            return;
        }
        if (attachedBufnr == buffer.bufnr) {
            attached = home;
            attachedBufnr = bufnr(home);
            attachedDocument = null;
        }
        // Leave the buffer first: unloading the window's buffer makes Neovim enter some other one,
        // which Eclipse would follow by activating that editor. Editor windows still showing it
        // are closed; the home window shows the attached buffer instead.
        Object closed = await(api.execLua(LuaScripts.RELEASE_BUFFER,
                List.of(String.valueOf(bufnr(doomed)), String.valueOf(attachedBufnr))));
        for (int winid : ids(closed)) {
            windows.forget(winid);
            if (winid == attachedWin) {
                attachedWin = homeWin;
            }
        }
        returnToAttached();
        await(api.execLua(LuaScripts.CLOSE_DOCUMENT_BUFFER, List.of(String.valueOf(bufnr(doomed)))));
    }

    private static List<Integer> ids(Object answer) {
        List<Integer> ids = new ArrayList<>();
        if (answer instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n) {
                    ids.add(n.intValue());
                }
            }
        }
        return ids;
    }

    /**
     * Switches Neovim back to the attached buffer in the attached window. If that window was closed
     * in Neovim, the home window shows the attached buffer from then on, until the editor's next
     * show opens it a window again.
     */
    void returnToAttached() throws Exception {
        int shown = showWindow(attachedWin, attachedBufnr);
        if (shown == EditorWindow.UNKNOWN) {
            attachedWin = homeWin;
            shown = showWindow(EditorWindow.UNKNOWN, attachedBufnr);
        }
        attachedWin = shown;
    }

    void setEnterHandler(Consumer<Integer> handler) {
        this.enterHandler = handler;
    }

    /**
     * Neovim entered buffer {@code bufnr} in window {@code winid} ({@code BufEnter}). In the attached
     * window, another document's buffer goes to the enter handler; anything else is undone with
     * {@code returnBack}: another buffer, or another window Neovim entered by itself (a
     * {@code :tabnext} the user typed, the window it entered after a {@code :tabclose}).
     *
     * @param jumped whether the move is in the window's jump history ({@code :b}, {@code Ctrl-O};
     *               not {@code :keepjumps})
     */
    void onEntered(int bufnr, int winid, boolean jumped, Runnable returnBack) {
        enteredByJump = jumped;
        enteredBufnr = bufnr; // first: a follow-up queued for an earlier buffer must see it
        boolean attachedWindow = winid == EditorWindow.UNKNOWN || winid == attachedWin;
        if (attachedWindow && bufnr == attachedBufnr) {
            return; // Eclipse's own switch, or Neovim came back
        }
        if (!attachedWindow) {
            returnBack.run();
            return;
        }
        Consumer<Integer> handler = enterHandler;
        if (handler != null && created.containsKey(bufnr)) {
            handler.accept(bufnr);
        } else {
            returnBack.run(); // help, :e of another file, a closed document, ...
        }
    }
}
