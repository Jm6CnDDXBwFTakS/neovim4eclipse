// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import static io.github.jm6cnddxbwftaks.eclipseneovim.core.Rpc.await;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ensarsarajcic.neovim.java.api.NeovimApi;
import com.ensarsarajcic.neovim.java.api.types.api.VimMode;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.InsertReplay;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.ModeTransition;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.NvimKeys;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.VimModes;

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
 *   <li><b>{@code :w} typed in Neovim's own command line</b>: offered to Eclipse on Enter
 *       ({@link #tryHostCommandLine}).</li>
 * </ul>
 *
 * <p>Everything here blocks on RPC and runs on the session's single input thread, which keeps keys
 * in order. State that other parts of the session also use (mode, buffer-event suppression,
 * publishing) stays in {@link NeovimSession}, reached through its package-private methods.
 */
final class KeyInput {

    private static final Logger LOG = Logger.getLogger(KeyInput.class.getName());

    /** A key that takes longer than this end to end is logged as a warning (diagnostics). */
    private static final long SLOW_KEY_WARNING_MS = 250;
    /** Upper bound for waiting on a macro to finish ({@code 1000@a} can run for a while). */
    private static final long MACRO_TIMEOUT_SEC = 60;

    private final NeovimSession session;
    private final NeovimApi api;

    /**
     * True while insert mode was entered from blockwise visual ({@code I}, {@code A}, {@code c}, ...)
     * or while recording a macro. Vim repeats the text typed on the first line onto every line of the
     * block when Esc is pressed, and a macro must contain every typed key; both only work if Neovim
     * itself sees the typing. So in these cases the editor forwards keys to Neovim instead of
     * inserting them itself (see {@code KeyInterceptor}).
     */
    private volatile boolean neovimDrivenInsert;
    /** Set by a lone {@code @}: the next key names the macro register and starts the playback. */
    private boolean macroRegisterPending;

    KeyInput(NeovimSession session, NeovimApi api) {
        this.session = session;
        this.api = api;
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
     * {@code nvim_input}: Neovim does all the editing. The only exception is Enter on a {@code :}
     * command line, which is offered to the host handler first (so {@code :w} saves the Eclipse
     * file).
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
                LOG.warning("input('" + keys + "') took " + ms + " ms (mode=" + session.getMode() + ")");
            }
        }
    }

    private void inputKey(String keys) throws Exception {
        LOG.fine(() -> "input(" + keys + ") mode=" + session.getMode());
        session.status().clearMessage(); // a message stays visible until the next key

        if (NvimKeys.isEnter(keys) && tryHostCommandLine()) {
            return;
        }

        boolean wasTyping = session.isTypingMode();
        String modeBefore = session.getMode();
        // The key after a lone @ names the macro register and starts the playback.
        boolean startsMacro = macroRegisterPending && !wasTyping;
        macroRegisterPending = false;

        if (startsMacro) {
            session.setSuppressBufferEvents(true); // see syncBufferAfterMacro
        }
        VimMode mode;
        try {
            await(api.input(keys));
            mode = session.readMode();
        } catch (Exception e) {
            session.setSuppressBufferEvents(false);
            throw e;
        }
        if (mode != null && mode.isBlocking()) {
            // Waiting for the rest of the command (see the method comment): nothing to sync yet, and
            // a buffer request would not be answered until the next key arrives.
            session.setSuppressBufferEvents(false);
            macroRegisterPending = "@".equals(keys) && !wasTyping;
            session.status().showPendingKey(keys);
            return;
        }
        if (startsMacro) {
            syncBufferAfterMacro();
            session.publishState();
            return;
        }
        ModeTransition transition = ModeTransition.after(modeBefore, session.getMode(), neovimDrivenInsert,
                session.isRecording(), keys);
        switch (transition) {
            case ENTER_NEOVIM_INSERT -> neovimDrivenInsert = true;
            case LEAVE_NEOVIM_INSERT -> neovimDrivenInsert = false;
            // ENTER_INSERT leaves the flag alone, never clears it: the key listener may already have
            // made this insert Neovim-driven (a key typed before the sync below reached the editor).
            case ENTER_INSERT, TEXT_CHANGED, NONE -> { }
        }
        if (transition.syncsBuffer()) {
            session.syncBufferFromNeovim();
        }
        session.publishState();
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
            List<String> lines = session.getBufferLines(MACRO_TIMEOUT_SEC);
            session.setSuppressBufferEvents(false); // before dispatching, or the sync itself would be dropped
            LOG.fine(() -> "buffer sync after macro: " + lines.size() + " lines");
            session.dispatchBufferChange(0, -1, lines);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "buffer sync after macro failed", e);
        } finally {
            session.setSuppressBufferEvents(false);
        }
    }

    /**
     * On Enter, asks Neovim (not the cached mode, which can lag) whether a {@code :} command is
     * being executed and, if the host handles it, cancels it in Neovim.
     *
     * @return {@code true} if the host handled the command and the key must not be forwarded
     */
    private boolean tryHostCommandLine() {
        try {
            VimMode mode = session.readMode();
            // Not on a command line (or Neovim is blocked and would not answer): nothing to intercept.
            if (mode == null || mode.isBlocking() || !VimModes.isCmdline(mode.getMode())) {
                return false;
            }
            String type = cmdline(LuaScripts.GET_CMDTYPE);
            String text = cmdline(LuaScripts.GET_CMDLINE);
            Predicate<String> handler = session.hostExHandler();
            if (handler != null && type.startsWith(":") && !text.isBlank() && handler.test(text.strip())) {
                await(api.input("<Esc>"));
                session.syncBufferFromNeovim();
                session.publishState();
                LOG.info("Host handled cmdline: " + text);
                return true;
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "cmdline host intercept failed", e);
        }
        return false;
    }

    private String cmdline(String lua) throws Exception {
        Object raw = await(api.executeLua(lua, List.of()));
        return raw == null ? "" : String.valueOf(raw);
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
        Runnable push = session.leaveInsertPush();
        boolean replayed = replay != null && replayTypedText(replay);
        if (replayed) {
            try {
                if (push != null) {
                    push.run();
                }
                session.setCursor(replay.finalLine1Based(), replay.finalCol0Based());
            } finally {
                session.setSuppressBufferEvents(false);
            }
        } else {
            if (push != null) {
                push.run();
            }
            await(api.input("<Esc>"));
        }
        neovimDrivenInsert = false;
        session.syncModeAndCursorFromNeovim();
    }

    /**
     * Types the plan's text at its base position in Neovim's insert mode, then {@code <Esc>}.
     * Leaves buffer events suppressed on success (the caller resumes them after pushing the
     * document; applying them would insert the text twice) and resumes them on failure.
     *
     * <p>A cheap request (not {@code nvim_get_mode}, which is answered out of band) follows every
     * chunk. Neovim handles requests only after it has consumed all pending keys, so this guarantees
     * the keys are processed before the next step and keeps its input buffer from overflowing.
     *
     * @return {@code true} if the text was typed and insert mode was left
     */
    private boolean replayTypedText(InsertReplay.Plan plan) {
        session.setSuppressBufferEvents(true);
        try {
            session.setCursor(plan.baseLine1Based(), plan.baseCol0Based());
            for (String chunk : plan.keyChunks()) {
                await(api.input(chunk));
                awaitKeysConsumed();
            }
            await(api.input("<Esc>"));
            awaitKeysConsumed();
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "replaying typed text into Neovim failed; dot-repeat will not include it", e);
            session.setSuppressBufferEvents(false);
            return false;
        }
    }

    /** Returns once Neovim has consumed all pending input: it answers requests only when idle. */
    private void awaitKeysConsumed() throws Exception {
        await(api.executeLua("return 0", List.of()));
    }
}
