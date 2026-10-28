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
 *       {@code .} can repeat it ({@link #leaveInsertMode}).</li>
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
     * Set while {@link #leaveInsertMode} runs. Neovim's views until it ends are of text it does not
     * have yet (the replay types into the old lines), so the editor ignores their top lines
     * ({@code NeovimSession.isLeavingInsertMode}); the editor's own top line is sent with the push.
     */
    private volatile boolean leavingInsert;
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
        neovimDrivenInsert = false;
        state.syncModeAndCursor();
    }

    /**
     * Types the plan's text at its base position in Neovim's insert mode, then {@code <Esc>}.
     * Leaves buffer events suppressed on success (the caller resumes them after pushing the
     * document; applying them would insert the text twice) and resumes them on failure.
     *
     * <p>{@link Neovim#awaitKeysConsumed()} follows every chunk, so the keys are processed before the
     * next step and Neovim's input buffer does not overflow.
     *
     * @return {@code true} if the text was typed and insert mode was left
     */
    private boolean replayTypedText(InsertReplay.Plan plan) {
        state.setSuppressBufferEvents(true);
        try {
            nvim.setCursor(plan.baseLine1Based(), plan.baseCol0Based());
            for (String chunk : plan.keyChunks()) {
                nvim.type(chunk);
                nvim.awaitKeysConsumed();
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
}
