// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Mode;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ChangedLines;
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
         * insert mappings must not change. Only into an open insert ({@link LuaScripts#TYPE_TEXT}):
         * outside Insert and Replace mode nothing is typed, since the text would run as commands.
         *
         * @return {@code true} if typed, {@code false} if Neovim was not in an insert
         */
        boolean typeText(String text) throws Exception;

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
     * True while Neovim, not Eclipse, types the current insert: the editor forwards every key to
     * Neovim instead of inserting it itself (see {@code KeyInterceptor}). For the whole insert when
     * {@link #neovimOwnsInsert}; for a while in the other cases ({@link #handBackPending}).
     */
    private volatile boolean neovimDrivenInsert;
    /**
     * True while insert mode was entered from blockwise visual ({@code I}, {@code A}, {@code c}, ...)
     * or while recording a macro. Vim repeats the text typed on the first line onto every line of the
     * block when Esc is pressed, and a macro must contain every typed key; both only work if Neovim
     * itself sees the typing, to the end of the insert. Cleared when the insert ends.
     */
    private volatile boolean neovimOwnsInsert;
    /**
     * Set when an insert Eclipse types became Neovim-driven for a moment, so that typing goes back to
     * Eclipse once Neovim is in plain insert mode again ({@link #handBackToEclipse}):
     * <ul>
     *   <li>a sync point's key left Neovim waiting for another ({@code Ctrl-R}, {@code Ctrl-V},
     *       {@code Ctrl-K}, ...): {@code Ctrl-R a} inserts register {@code a}, then Eclipse types;</li>
     *   <li>a key was typed within the millisecond before Eclipse had Neovim's text, at the start of
     *       an insert or after a sync point ({@link #driveFromNeovim}): it goes to Neovim, then
     *       Eclipse types the next.</li>
     * </ul>
     * Never for an insert Neovim owns ({@link #neovimOwnsInsert}). Cleared when the insert ends.
     */
    private volatile boolean handBackPending;
    /**
     * Keys queued for Neovim ({@link #keyQueued}, UI thread) and keys taken from that queue
     * ({@link #inputQueued}, input thread). Equal when no key waits: a hand-back starts only then,
     * and is called off if a key was queued after Neovim's text was read for it.
     */
    private final AtomicLong keysQueued = new AtomicLong();
    private volatile long keysTaken;
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
    /**
     * Set when a macro's playback stopped with Neovim waiting for a key the macro did not hold.
     * Example: register {@code a} holds {@code ddf}; {@code @a} deletes a line, then {@code f}
     * waits for its character. The {@code dd}'s line events came while buffer events were
     * suppressed (the playback's, {@link #syncBufferAfterMacro}), so the editor never had them;
     * and the buffer cannot be read now, while Neovim waits. So the key that ends the wait
     * finishes the playback: it is typed as a macro's is, and the buffer is synced after it.
     * Input thread only.
     */
    private boolean macroSyncPending;

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

    /**
     * Makes the current insert Neovim-driven until Neovim has the keys typed meanwhile (see
     * {@link NeovimSession#driveInsertFromNeovim()} and {@link #handBackPending}). Any thread.
     */
    void driveFromNeovim() {
        neovimDrivenInsert = true;
        handBackPending = true;
    }

    /** Called with every mode change: leaving insert mode ends a Neovim-driven insert. */
    void onModeChanged(String mode) {
        if (!VimModes.isTyping(mode)) {
            neovimDrivenInsert = false;
            neovimOwnsInsert = false;
            handBackPending = false;
        }
    }

    /** A key is about to be queued for {@link #inputQueued} (see {@link #keysQueued}). Any thread. */
    void keyQueued() {
        keysQueued.incrementAndGet();
    }

    /** {@link #input} for a key queued after {@link #keyQueued}. Input thread. */
    void inputQueued(String keys) throws Exception {
        keysTaken++; // only the input thread writes it
        input(keys);
    }

    /** Whether a hand-back to Eclipse is waiting for Neovim to stop waiting (tests). */
    boolean isHandBackPending() {
        return handBackPending;
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
                // The key itself only at FINE: a WARNING goes to Eclipse's Error Log (.metadata/.log),
                // and the key may be a character of a password typed into inputsecret() or a :!
                // prompt (0.2.1 security review).
                LOG.warning("A key took " + ms + " ms (mode=" + state.mode() + ")");
                LOG.fine(() -> "the slow key: input('" + keys + "')");
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
        // The key after a lone @ names the macro register and starts the playback; the key after a
        // playback that stopped waiting finishes it (macroSyncPending).
        boolean startsMacro = (macroRegisterPending && !wasTyping) || macroSyncPending;
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
            throw e; // macroSyncPending stays: the next key still syncs
        }
        if (mode != null && mode.blocking()) {
            // Waiting for the rest of the command (see the method comment): nothing to sync yet, and
            // a buffer request would not be answered until the next key arrives. Buffer events go
            // through meanwhile (a timer may edit another buffer); a macro's edits so far are synced
            // after the key that ends the wait.
            state.setSuppressBufferEvents(false);
            macroSyncPending = startsMacro;
            macroRegisterPending = "@".equals(keys) && !wasTyping;
            return; // Neovim sends the pending command itself (msg_showcmd "2f")
        }
        macroSyncPending = false;
        if (startsMacro) {
            syncBufferAfterMacro();
            state.publishState();
            return;
        }
        ModeTransition transition = ModeTransition.after(modeBefore, state.mode(), neovimDrivenInsert,
                !status.recordingRegister().isEmpty(), keys);
        switch (transition) {
            case ENTER_NEOVIM_INSERT -> {
                neovimDrivenInsert = true;
                neovimOwnsInsert = true; // to the end: never handed back, even if a gap key asked for it
            }
            case LEAVE_NEOVIM_INSERT -> {
                neovimDrivenInsert = false;
                neovimOwnsInsert = false;
            }
            // ENTER_INSERT leaves the flag alone, never clears it: the key listener may already have
            // made this insert Neovim-driven (a key typed before the sync below reached the editor).
            case ENTER_INSERT, TEXT_CHANGED, NONE -> { }
        }
        if (transition == ModeTransition.ENTER_INSERT) {
            state.syncBuffer(noKeyWaitsAfter(keysTaken)); // the insert-start handshake
        } else if (transition.syncsBuffer()) {
            state.syncBuffer();
        }
        state.publishState();
        if (mayHandBack()) {
            handBackToEclipse();
        }
    }

    /**
     * The check, on the UI thread once the editor has applied an insert's first full sync, that lets
     * Eclipse type only if no key waits for Neovim behind the one that entered the insert. If keys
     * wait, they reach Neovim's insert after the sync was read, so their line events would land
     * among Eclipse's typing: the insert stays Neovim's ({@link #handBackPending}), and the last of
     * those keys hands it back ({@link #handBackToEclipse}), as for a key typed before the sync.
     *
     * <p>Example: {@code ihello<Esc>ibye} typed fast. The keys after Esc go to Neovim, queued behind
     * the leave (NeovimSession.leaveInsertModeAsync). The second {@code i} enters insert mode while
     * {@code b}, {@code y} and {@code e} still wait in the queue; the mode change has ended the
     * Neovim-driven insert they were sent in, so without this check Eclipse typed the next key, and
     * Neovim's events for {@code bye} could then overwrite it. The same holds for {@code ibye} typed
     * fast in Normal mode: {@code b}, {@code y} and {@code e} were routed as Normal-mode keys before
     * Neovim reported the insert.
     *
     * @param taken the keys taken from the queue when the sync was read ({@link #keysTaken})
     */
    private BooleanSupplier noKeyWaitsAfter(long taken) {
        return () -> {
            if (keysQueued.get() == taken) {
                return true;
            }
            if (state.isTypingMode() && !neovimDrivenInsert) {
                neovimDrivenInsert = true;
                handBackPending = true;
            }
            return false;
        };
    }

    /** Whether the insert Neovim drives now may go back to Eclipse ({@link #handBackPending}). */
    private boolean mayHandBack() {
        return handBackPending && neovimDrivenInsert && !neovimOwnsInsert && state.isTypingMode()
                && status.recordingRegister().isEmpty();
    }

    /**
     * Gives the rest of an insert back to Eclipse once Neovim has the keys it was given meanwhile
     * (docs/design/INSERT_CTRL_KEYS.md, section 5). Example: Eclipse typed {@code foo}, the user
     * pressed {@code Ctrl-R} (Neovim drives, since it waits for a register name), then {@code a}:
     * Neovim inserts register {@code a} and is in plain insert mode again. Or a letter typed in the
     * millisecond after {@code Ctrl-W}, before Eclipse had Neovim's text, went to Neovim. The same
     * handshake as at the start of an insert follows: a full sync, and once the editor has applied
     * it, Eclipse types. {@code .} still repeats the whole insert, since Neovim's insert never ended.
     *
     * <p><b>Keys in flight.</b> Keys typed while Neovim drives go to Neovim, queued in order. A key
     * queued before the editor applied the sync, but typed into Neovim after the read, would arrive
     * as a buffer event while Eclipse already types, and could land among Eclipse's own typing. So
     * the hand-back starts only when no key waits, and is called off when the editor applies the
     * sync if one was queued since. The insert then stays Neovim's, and the next key retries.
     */
    private void handBackToEclipse() {
        long taken = keysTaken;
        if (keysQueued.get() != taken) {
            return; // keys wait: the last of them retries
        }
        state.syncBuffer(() -> {
            // On the UI thread, where keys are routed: nothing can be queued during this check.
            if (!mayHandBack() || keysQueued.get() != taken) {
                return false;
            }
            handBackPending = false;
            neovimDrivenInsert = false;
            return true;
        });
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
        neovimOwnsInsert = false;
        handBackPending = false;
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
            if (!typeAtBase(plan)) {
                LOG.warning("Neovim was no longer in insert mode, so the typed text was not replayed "
                        + "(it would have run as commands); dot-repeat will not include it");
                state.setSuppressBufferEvents(false);
                return false;
            }
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
     * (docs/design/INSERT_CTRL_KEYS.md, section 3). Example: with {@code inoremap jk <Esc>}, Eclipse's
     * typed {@code jk} is re-typed as two letters and does not end the insert half way.
     * {@link Neovim#awaitKeysConsumed()} follows every chunk, so the text is processed before the
     * next step.
     *
     * @return {@code false} if Neovim was not in an insert (any more), so the rest was not typed:
     *         the text must not become Normal-mode commands ({@link LuaScripts#TYPE_TEXT})
     */
    private boolean typeAtBase(InsertReplay.Plan plan) throws Exception {
        nvim.setCursor(plan.baseLine1Based(), plan.baseCol0Based());
        for (String chunk : plan.textChunks()) {
            if (!nvim.typeText(chunk)) {
                return false;
            }
            nvim.awaitKeysConsumed();
        }
        return true;
    }

    /**
     * A <em>sync point</em>: a {@code Ctrl} key Neovim handles in the middle of an insert Eclipse
     * types (docs/design/INSERT_CTRL_KEYS.md, section 2). Example: Eclipse typed {@code foo bar}, the
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
     *   <li>Its change reaches the document as a buffer event. If Neovim is still in insert mode,
     *       Eclipse types again once the editor has applied it: the same handshake as at the start of
     *       an insert ({@link StateSync#isEclipseTypingReady()}), with a full sync for a document of
     *       up to {@link ChangedLines#COMPARE_WHOLE_UP_TO_LINES} lines. After {@code Ctrl-O} Neovim is in
     *       {@code niI} until its one command ends, and entering insert again syncs then.</li>
     * </ol>
     * If the key leaves Neovim waiting for another ({@code Ctrl-R} for a register, {@code Ctrl-V},
     * {@code Ctrl-K}), Neovim drives the insert until it has that key: every key goes to it, and it
     * answers no request until that key arrives, so nothing is read here. Then Eclipse types again
     * ({@link #handBackToEclipse}).
     *
     * <p>Buffer events stay suppressed from the re-typing until the key: Eclipse has that text
     * already, and applying Neovim's events for it would insert it twice. Their cursor reports are
     * dropped by the editor meanwhile ({@link #isLeavingInsertMode()}).
     *
     * @param sync what to hand over, or {@code null} for nothing but the document
     * @param key  the key, in Neovim notation ({@code <C-w>})
     */
    void syncInsertThenInput(InsertReplay.SyncPoint sync, String key) throws Exception {
        boolean inInsert;
        leavingInsert = true;
        try {
            inInsert = handOverTypedText(sync);
        } finally {
            leavingInsert = false;
        }
        if (!inInsert) {
            // Neovim left the insert behind Eclipse's back (a timer's stopinsert): the key is not sent
            // either, since in Normal mode Ctrl-W or Ctrl-R is another command. The editor hears the
            // real mode and ends its insert; the document is already pushed.
            state.publishState();
            return;
        }
        status.clearMessage(); // as a key does
        nvim.type(key);
        Mode mode = state.readMode();
        if (mode != null && mode.blocking()) {
            neovimDrivenInsert = true;
            handBackPending = true; // until Neovim has the key it waits for (inputKey)
            return;
        }
        // The key's buffer events have been dispatched once Neovim has consumed it; the cursor is
        // published after them, so the caret lands on the changed text.
        nvim.awaitKeysConsumed();
        state.publishState();
        if (state.isTypingMode()) {
            // The handshake: Eclipse types again once the document has Neovim's text. The key's line
            // events bring that text; a short document is also compared whole, which repairs any
            // difference (ChangedLines.COMPARE_WHOLE_UP_TO_LINES), a long one only waits for them.
            if (sync == null || ChangedLines.comparesWhole(sync.documentLines())) {
                state.syncBuffer();
            } else {
                state.letEclipseTypeOnceApplied();
            }
        }
    }

    /**
     * Steps 1 to 3 of {@link #syncInsertThenInput}.
     *
     * @return {@code false} if Neovim was found outside an insert, so the typed text was not
     *         re-typed and the key must not be sent
     */
    private boolean handOverTypedText(InsertReplay.SyncPoint sync) throws Exception {
        Runnable push = leaveInsertPush.get();
        boolean inInsert = true;
        state.setSuppressBufferEvents(true);
        try {
            InsertReplay.Plan typed = sync == null ? null : sync.typed();
            if (typed != null) {
                try {
                    inInsert = typeAtBase(typed);
                    if (!inInsert) {
                        LOG.warning("Neovim was no longer in insert mode, so the typed text was not "
                                + "re-typed (it would have run as commands) and the key was not sent");
                    }
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
        return inInsert;
    }
}
