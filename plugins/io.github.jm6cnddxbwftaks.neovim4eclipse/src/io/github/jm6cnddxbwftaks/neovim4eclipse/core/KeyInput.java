// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Mode;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InsertReplay;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ModeTransition;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimKeys;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimModes;

/**
 * What happens when a key is sent to Neovim: the key-input pipeline of a {@link NeovimSession}.
 *
 * <p>For each key: {@code nvim_input}, then {@code nvim_get_mode}; if Neovim is not blocked waiting
 * for more, the buffer is re-synced where needed and the new state is published. Around that sit
 * the special cases that make Eclipse and Neovim cooperate:
 * <ul>
 *   <li><b>Pending commands</b> ({@code f}, {@code r}, {@code gg}, ...): Neovim is blocked, so
 *       nothing else is asked (see {@link #input}).</li>
 *   <li><b>Insert mode</b>: Eclipse types; entering insert decides whether Neovim must see the keys
 *       instead ({@link #isNeovimDrivenInsert}); leaving it replays the typed text into Neovim so
 *       {@code .} can repeat it ({@link #leaveInsertMode}); a {@code Ctrl} key Neovim handles in the
 *       middle of it does the same without leaving ({@link #syncInsertThenInput}).</li>
 *   <li><b>Macro playback</b> ({@code @a}): buffer events are held back and the final text is
 *       pulled once ({@link #syncBufferAfterMacro}).</li>
 *   <li><b>The mouse</b> in normal and visual mode goes the same way as a key ({@link #mouse}).</li>
 * </ul>
 *
 * <p>Everything here blocks on RPC and runs on the session's single input thread, which keeps keys
 * in order.
 *
 * <h2>Dependencies</h2>
 * KeyInput does not know {@link NeovimSession}. It is given what it uses:
 * <ul>
 *   <li>{@link StateSync}: the mode, reading it, buffer-event suppression and publishing (shared
 *       with the rest of the session);</li>
 *   <li>{@link StatusTracker}: the status bar message, the pending key, and macro recording;</li>
 *   <li>the small {@link Neovim} interface: the few raw calls it makes itself;</li>
 *   <li>a supplier for what the editor installs later: the document push before leaving insert
 *       mode.</li>
 * </ul>
 * So {@code KeyInputTest} runs it with a fake Neovim and a real {@code StateSync} on a fake (see
 * {@code docs/LEARNING.md}, "Ports and fakes").
 */
final class KeyInput {

    private static final Logger LOG = Logger.getLogger(KeyInput.class.getName());

    /** A key that takes longer than this end to end is logged as a warning (diagnostics). */
    private static final long SLOW_KEY_WARNING_MS = 250;
    /** Default upper bound for waiting on a macro to finish ({@code 1000@a} can run for a while). */
    static final Duration MACRO_TIMEOUT = Duration.ofSeconds(60);

    /** The raw Neovim calls KeyInput makes itself. All block, on the input thread. */
    interface Neovim {
        /** Types {@code keys} (Neovim notation) as if the user pressed them ({@code nvim_input}). */
        void type(String keys) throws Exception;

        /**
         * Types raw {@code text} with no mapping or abbreviation applied
         * ({@code nvim_feedkeys(text, 'n')}): the re-typing of what Eclipse typed, which the user's
         * insert mappings must not change.
         */
        void typeText(String text) throws Exception;

        /**
         * Returns once Neovim has consumed all pending input. Any cheap request does that, because
         * Neovim answers requests only when idle ({@code nvim_get_mode} does not: it is answered out
         * of band, even while keys are pending).
         */
        void awaitKeysConsumed() throws Exception;

        /**
         * The attached buffer's lines. Answered only once Neovim is idle, so after {@code @a} it
         * waits for the macro to finish, up to {@code timeout}.
         */
        List<String> bufferLines(Duration timeout) throws Exception;

        /** Moves Neovim's cursor; {@code col0} is a 0-based UTF-8 byte column. */
        void setCursor(int line1, int col0) throws Exception;

        /** Hands one mouse event to Neovim's own mouse ({@link LuaScripts#MOUSE}). */
        void mouse(MouseInput event) throws Exception;
    }

    private final Neovim nvim;
    private final StateSync state;
    private final StatusTracker status;
    /** Pushes the Eclipse document to Neovim before insert mode ends, or {@code null}. */
    private final Supplier<Runnable> leaveInsertPush;
    private final Duration macroTimeout;

    /**
     * True while insert mode was entered from blockwise visual ({@code I}, {@code A}, {@code c}, ...)
     * or while recording a macro. Vim repeats the text typed on the first line onto every line of the
     * block when Esc is pressed, and a macro must contain every typed key; both only work if Neovim
     * itself sees the typing. So in these cases the editor forwards keys to Neovim instead of
     * inserting them itself (see {@code KeyInterceptor}).
     */
    private volatile boolean neovimDrivenInsert;
    /**
     * Set while {@link #leaveInsertMode} runs, until Neovim's state is read at its end, and while
     * {@link #syncInsertThenInput} hands the typed text over, until its key is sent. What Neovim
     * reports until then is of text it does not have yet (the replay types into the old lines) or of
     * a cursor about to be dragged into the editor's view, so the editor ignores those cursors and
     * top lines ({@code NeovimSession.isLeavingInsertMode}). Example: the caret on line 1, the wheel
     * scrolled the editor to line 101 in insert mode; the replay's cursor report on line 1 would
     * make Eclipse reveal line 1 before the editor's view is sent.
     */
    private volatile boolean leavingInsert;
    /**
     * Runs at Esc once Neovim's cursor is final (after the push, the replay's cursor or the
     * {@code <Esc>}): the editor's view goes to Neovim ({@code NeovimSession.showTopLine}). After
     * the cursor, because the view step may drag the cursor, and a later {@code setCursor} would
     * move it back off the screen. {@code null} for none.
     */
    private volatile Runnable leaveInsertView;
    /** Set by a lone {@code @}: the next key names the macro register and starts the playback. */
    private boolean macroRegisterPending;

    KeyInput(Neovim nvim, StateSync state, StatusTracker status, Supplier<Runnable> leaveInsertPush) {
        this(nvim, state, status, leaveInsertPush, MACRO_TIMEOUT);
    }

    /** @param macroTimeout how long to wait for a macro to finish (tests use milliseconds) */
    KeyInput(Neovim nvim, StateSync state, StatusTracker status, Supplier<Runnable> leaveInsertPush,
            Duration macroTimeout) {
        this.nvim = nvim;
        this.state = state;
        this.status = status;
        this.leaveInsertPush = leaveInsertPush;
        this.macroTimeout = macroTimeout;
    }

    /** Insert mode in which Neovim, not Eclipse, must receive the typed keys (see the field comment). */
    boolean isNeovimDrivenInsert() {
        return neovimDrivenInsert;
    }

    /** Makes the current insert Neovim-driven (see {@link NeovimSession#driveInsertFromNeovim()}). */
    void driveFromNeovim() {
        neovimDrivenInsert = true;
    }

    /** Called with every mode change: leaving insert mode ends a Neovim-driven insert. */
    void onModeChanged(String mode) {
        if (!VimModes.isTyping(mode)) {
            neovimDrivenInsert = false;
        }
    }

    /**
     * Sends one key to Neovim and refreshes Eclipse.
     *
     * <p>Every key, including digits (counts), {@code p}, {@code P} and {@code .}, is a plain
     * {@code nvim_input}: Neovim does all the editing, Enter on a {@code :} line too. A command
     * Eclipse carries out ({@code :w}, {@code :q}) reaches it from Neovim, as {@code :Eclipse}
     * ({@link LuaScripts#HOST_COMMANDS}).
     *
     * <p><b>Pending commands.</b> After {@code f}, {@code F}, {@code t}, {@code r}, the first
     * {@code g} of {@code gg}, {@code z}, ... Neovim is <em>blocked</em> waiting for the next
     * character and defers almost every API call (only {@code nvim_get_mode} and {@code nvim_input}
     * are answered). Asking for the cursor then would wait for a reply that cannot arrive until the
     * next key is sent, which is queued behind this very call. So when {@code getMode} reports
     * {@code blocking}, we return immediately and sync after the command completes.
     *
     * <p>Buffer text reaches Eclipse through line events. A full re-sync is done only when entering
     * insert mode and after a few keys where it is cheap insurance ({@link NvimKeys#isBufferMutating}:
     * {@code p P x X s ~ . u <C-r>}); never after every key, which previously caused multi-second
     * hangs on {@code ciw}.
     */
    void input(String keys) throws Exception {
        long startNanos = System.nanoTime();
        try {
            inputKey(keys);
        } finally {
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            if (ms >= SLOW_KEY_WARNING_MS) {
                LOG.warning("input('" + keys + "') took " + ms + " ms (mode=" + state.mode() + ")");
            }
        }
    }

    /**
     * One mouse event (a click, a drag, a release in the editor), handled by Neovim's own mouse as a
     * key is: Neovim moves the cursor or selects, then the new mode, cursor and selection are
     * published. The editor's selection then shows Neovim's visual selection.
     *
     * <p>While Neovim waits for the rest of a command ({@code d}, {@code f}) the event is dropped:
     * Neovim would not answer the script until that command is complete, and the script needs it to
     * find the clicked character on Neovim's screen ({@link LuaScripts#MOUSE}).
     */
    void mouse(MouseInput event) throws Exception {
        Mode before = state.readMode();
        if (before == null || before.blocking()) {
            LOG.fine(() -> "mouse " + event + " dropped: Neovim waits for the rest of a command");
            return;
        }
        status.clearMessage(); // as a key does: a message stays until the next key or click
        nvim.mouse(event);
        nvim.awaitKeysConsumed(); // the script queued the event as input
        Mode mode = state.readMode();
        if (mode != null && mode.blocking()) {
            return;
        }
        state.publishState();
    }

    private void inputKey(String keys) throws Exception {
        LOG.fine(() -> "input(" + keys + ") mode=" + state.mode());
        status.clearMessage(); // a message stays visible until the next key

        boolean wasTyping = state.isTypingMode();
        String modeBefore = state.mode();
        // The key after a lone @ names the macro register and starts the playback.
        boolean startsMacro = macroRegisterPending && !wasTyping;
        macroRegisterPending = false;

        if (startsMacro) {
            state.setSuppressBufferEvents(true); // see syncBufferAfterMacro
        }
        Mode mode;
        try {
            nvim.type(keys);
            mode = state.readMode();
        } catch (Exception e) {
            state.setSuppressBufferEvents(false);
            throw e;
        }
        if (mode != null && mode.blocking()) {
            // Waiting for the rest of the command (see the method comment): nothing to sync yet, and
            // a buffer request would not be answered until the next key arrives.
            state.setSuppressBufferEvents(false);
            macroRegisterPending = "@".equals(keys) && !wasTyping;
            return; // Neovim sends the pending command itself (msg_showcmd "2f")
        }
        if (startsMacro) {
            syncBufferAfterMacro();
            state.publishState();
            return;
        }
        ModeTransition transition = ModeTransition.after(modeBefore, state.mode(), neovimDrivenInsert,
                !status.recordingRegister().isEmpty(), keys);
        switch (transition) {
            case ENTER_NEOVIM_INSERT -> neovimDrivenInsert = true;
            case LEAVE_NEOVIM_INSERT -> neovimDrivenInsert = false;
            // ENTER_INSERT leaves the flag alone, never clears it: the key listener may already have
            // made this insert Neovim-driven (a key typed before the sync below reached the editor).
            case ENTER_INSERT, TEXT_CHANGED, NONE -> { }
        }
        if (transition.syncsBuffer()) {
            state.syncBuffer();
        }
        state.publishState();
    }

    /**
     * After {@code @a}: Neovim has replayed a whole macro. Its many buffer events were not forwarded
     * (buffer events were suppressed); instead the final buffer is pulled once and applied as a
     * single minimal edit. This is also much faster than applying every intermediate change on the
     * UI thread, and it cannot show half-finished states. The request waits until the macro has
     * finished (Neovim answers it only when idle), hence the long timeout.
     */
    private void syncBufferAfterMacro() {
        try {
            List<String> lines = nvim.bufferLines(macroTimeout);
            state.setSuppressBufferEvents(false); // before dispatching, or the sync itself would be dropped
            LOG.fine(() -> "buffer sync after macro: " + lines.size() + " lines");
            state.dispatchBufferChange(0, -1, lines);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "buffer sync after macro failed", e);
        } finally {
            state.setSuppressBufferEvents(false);
        }
    }

    /**
     * Leaves insert mode after Eclipse handled the typing.
     *
     * <p>With a {@code replay} plan, the typed text is first re-typed into Neovim's insert mode
     * (Neovim's buffer still has the text from before the insert), so that Neovim records it for
     * {@code .} and {@code ".}, and then {@code <Esc>} is sent. Eclipse's document is authoritative
     * (it may differ from what Neovim produced: auto-indent, bracket completion), so it is pushed
     * afterward and the cursor is put where Vim leaves it after Esc.
     *
     * <p>Without a plan, or if the replay fails, the document is pushed first and then {@code <Esc>}
     * is sent: the text is right, but {@code .} does not know what was typed.
     *
     * @param replay what to re-type, or {@code null}
     */
    void leaveInsertMode(InsertReplay.Plan replay) throws Exception {
        leavingInsert = true;
        try {
            leaveInsertModeNow(replay);
        } finally {
            leavingInsert = false;
        }
        // After the flag: the editor ignores Neovim's cursor and top line while Esc is handled
        // (the replay's and the <Esc>'s reports would scroll the editor to the old caret), and
        // takes the final ones from here.
        state.syncModeAndCursor();
    }

    /** See {@link #leaveInsertView}. */
    void setLeaveInsertView(Runnable view) {
        this.leaveInsertView = view;
    }

    /** Whether {@link #leaveInsertMode} runs now (see {@link #leavingInsert}). Any thread. */
    boolean isLeavingInsertMode() {
        return leavingInsert;
    }

    private void leaveInsertModeNow(InsertReplay.Plan replay) throws Exception {
        Runnable push = leaveInsertPush.get();
        boolean replayed = replay != null && replayTypedText(replay);
        if (replayed) {
            try {
                if (push != null) {
                    push.run();
                }
                nvim.setCursor(replay.finalLine1Based(), replay.finalCol0Based());
            } finally {
                state.setSuppressBufferEvents(false);
            }
        } else {
            if (push != null) {
                push.run();
            }
            nvim.type("<Esc>");
        }
        Runnable view = leaveInsertView;
        if (view != null) {
            nvim.awaitKeysConsumed(); // the <Esc> has moved the cursor
            view.run();
        }
        neovimDrivenInsert = false;
    }

    /**
     * Types the plan's text at its base position in Neovim's insert mode, then {@code <Esc>}.
     * Leaves buffer events suppressed on success (the caller resumes them after pushing the
     * document; applying them would insert the text twice) and resumes them on failure.
     *
     * @return {@code true} if the text was typed and insert mode was left
     */
    private boolean replayTypedText(InsertReplay.Plan plan) {
        state.setSuppressBufferEvents(true);
        try {
            typeAtBase(plan);
            nvim.type("<Esc>");
            nvim.awaitKeysConsumed();
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "replaying typed text into Neovim failed; dot-repeat will not include it", e);
            state.setSuppressBufferEvents(false);
            return false;
        }
    }

    /**
     * Types the plan's text at its base position into Neovim's open insert, with no mapping or
     * abbreviation applied ({@link Neovim#typeText}), so the user's own insert mappings can stay
     * (docs/design/INSERT_CTRL_KEYS.md, phase E). Example: with {@code inoremap jk <Esc>}, Eclipse's
     * typed {@code jk} is re-typed as two letters and does not end the insert half way.
     * {@link Neovim#awaitKeysConsumed()} follows every chunk, so the text is processed before the
     * next step.
     */
    private void typeAtBase(InsertReplay.Plan plan) throws Exception {
        nvim.setCursor(plan.baseLine1Based(), plan.baseCol0Based());
        for (String chunk : plan.textChunks()) {
            nvim.typeText(chunk);
            nvim.awaitKeysConsumed();
        }
    }

    /**
     * A <em>sync point</em>: a {@code Ctrl} key Neovim handles in the middle of an insert Eclipse
     * types (docs/design/INSERT_CTRL_KEYS.md, phase C). Example: Eclipse typed {@code foo bar}, the
     * user presses {@code Ctrl+W}.
     * <ol>
     *   <li>The typed text is re-typed into Neovim's still-open insert ({@code foo bar}), as at Esc
     *       but without the {@code <Esc>}, so the insert Neovim records goes on.</li>
     *   <li>The document is pushed: Eclipse's text is authoritative (auto-indent, a closing bracket
     *       Eclipse added, or typing the re-typing cannot express).</li>
     *   <li>Neovim's cursor goes to the caret, and Neovim's window to the editor's view (Eclipse
     *       scrolled alone while it typed).</li>
     *   <li>The key is sent: Neovim's own {@code <C-w>} deletes {@code bar} as Vim does, {@code .}
     *       later repeats the whole insert, {@code Ctrl-U} still knows where the insert began.</li>
     *   <li>Its change reaches the document as a buffer event. If Neovim is still in insert mode, a
     *       full sync follows, after which Eclipse types again: the same handshake as at the start of
     *       an insert ({@link StateSync#isEclipseTypingReady()}). After {@code Ctrl-O} Neovim is in
     *       {@code niI} until its one command ends, and entering insert again syncs then.</li>
     * </ol>
     * If the key leaves Neovim waiting for another ({@code Ctrl-R} for a register, {@code Ctrl-V},
     * {@code Ctrl-K}), Neovim drives the rest of this insert: every key goes to it until Esc
     *. It answers no request until that key arrives, so nothing is read here.
     *
     * <p>Buffer events stay suppressed from the re-typing until the key: Eclipse has that text
     * already, and applying Neovim's events for it would insert it twice. Their cursor reports are
     * dropped by the editor meanwhile ({@link #isLeavingInsertMode()}).
     *
     * @param sync what to hand over, or {@code null} for nothing but the document
     * @param key  the key, in Neovim notation ({@code <C-w>})
     */
    void syncInsertThenInput(InsertReplay.SyncPoint sync, String key) throws Exception {
        leavingInsert = true;
        try {
            handOverTypedText(sync);
        } finally {
            leavingInsert = false;
        }
        status.clearMessage(); // as a key does
        nvim.type(key);
        Mode mode = state.readMode();
        if (mode != null && mode.blocking()) {
            neovimDrivenInsert = true;
            return;
        }
        // The key's buffer events have been dispatched once Neovim has consumed it; the cursor is
        // published after them, so the caret lands on the changed text.
        nvim.awaitKeysConsumed();
        state.publishState();
        if (state.isTypingMode()) {
            state.syncBuffer(); // the handshake: Eclipse types again once the document has Neovim's text
        }
    }

    /** Steps 1 to 3 of {@link #syncInsertThenInput}. */
    private void handOverTypedText(InsertReplay.SyncPoint sync) throws Exception {
        Runnable push = leaveInsertPush.get();
        state.setSuppressBufferEvents(true);
        try {
            InsertReplay.Plan typed = sync == null ? null : sync.typed();
            if (typed != null) {
                try {
                    typeAtBase(typed);
                } catch (Exception e) {
                    // The push below still makes the text right; only . misses the typed text.
                    LOG.log(Level.WARNING, "re-typing the typed text into Neovim failed; dot-repeat will not include it", e);
                }
            }
            if (push != null) {
                push.run();
            }
            if (sync != null) {
                nvim.setCursor(sync.caretLine1Based(), sync.caretCol0Based());
            }
        } finally {
            state.setSuppressBufferEvents(false);
        }
        Runnable view = leaveInsertView;
        if (view != null) {
            nvim.awaitKeysConsumed();
            view.run();
        }
    }
}
