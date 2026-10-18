// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import java.util.function.Predicate;
import java.util.function.Supplier;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.InsertReplay;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.NvimKeys;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.VimCtrlKeys;

/**
 * Decides, for one key already translated to Neovim notation ({@code "x"}, {@code "<Esc>"},
 * {@code "<C-w>"}), whether Neovim or Eclipse handles it, and queues the work.
 *
 * <ul>
 *   <li><b>Normal / visual / operator-pending:</b> the key goes to Neovim and is consumed.</li>
 *   <li><b>Insert / replace:</b> Eclipse inserts printable characters itself (no flicker, content
 *       assist keeps working). {@code Esc} pushes the document to Neovim and leaves insert mode.
 *       Exception: an insert driven by Neovim (from blockwise visual, while recording a macro, or
 *       started before Eclipse could take over) forwards every key.</li>
 *   <li>{@code :}, {@code /}, {@code ?} open the editor's own prompt; {@code ZZ} and {@code ZQ}
 *       become Eclipse save/close.</li>
 * </ul>
 *
 * <p>Why a class of its own: the SWT listener ({@code editor/KeyInterceptor}) is thin glue that
 * turns a {@code VerifyEvent} into a key and sets {@code event.doit}. Every decision lives here,
 * behind two small interfaces ({@link Neovim}, {@link FileCommands}), so it is unit-tested without
 * SWT, Eclipse or a Neovim process. For example, a test can type {@code f} then {@code :} and check
 * that {@code :} went to Neovim as the argument of {@code f} instead of opening the prompt.
 *
 * <p>Runs on the SWT UI thread and must return quickly: it only queues work.
 */
public final class KeyRouter {

    /**
     * What the router needs from the session: its state, read without RPC, and three queued
     * actions. {@link NeovimSession} implements it; tests use a small fake.
     */
    public interface Neovim {
        boolean isTypingMode();

        boolean isNeovimDrivenInsert();

        boolean isEclipseTypingReady();

        boolean isWaitingForArgument();

        boolean isRecording();

        /** Lets Neovim type the rest of the current insert; see {@link #route}. */
        void driveInsertFromNeovim();

        void inputAsync(String keys);

        void leaveInsertModeAsync(InsertReplay.Plan replay);
    }

    /** Eclipse's save and close, for {@code ZZ} and {@code ZQ}. */
    public interface FileCommands {
        /** {@code ZZ}: write if needed, then close. */
        void saveAndClose();

        /** {@code ZQ}: close without writing. */
        void close();
    }

    private final Neovim neovim;
    private volatile Predicate<String> promptHandler;
    private volatile FileCommands fileCommands;
    /** Produces, at Esc, what to re-type into Neovim so that {@code .} can repeat the typed text. */
    private volatile Supplier<InsertReplay.Plan> insertReplaySupplier;
    /** Whether a {@code Ctrl+letter} goes to Neovim; letters it rejects are left to Eclipse. */
    private volatile Predicate<Character> ctrlKeyFilter = letter -> true;
    /** A {@code Z} was typed and the next key decides between {@code ZZ} and {@code ZQ}. */
    private volatile boolean pendingZ;
    /**
     * The previous key was a command that takes one more character ({@code f}, {@code @}, {@code q}
     * ...), so the next key is that argument: {@code f/}, {@code @:} and {@code qa} must reach Neovim
     * instead of opening the prompt. Tracked here as well as in the session because keys can arrive
     * faster than Neovim's answer about its state.
     */
    private volatile boolean argumentPending;

    public KeyRouter(Neovim neovim) {
        this.neovim = neovim;
    }

    /** Handler for {@code :}, {@code /}, {@code ?}; returns {@code true} if it consumed the key. */
    public void setPromptHandler(Predicate<String> handler) {
        this.promptHandler = handler;
    }

    /** Save and close for {@code ZZ}/{@code ZQ}; without them those keys are forwarded to Neovim. */
    public void setFileCommands(FileCommands commands) {
        this.fileCommands = commands;
    }

    /** Supplier of the replay plan for the insert that Esc is ending. */
    public void setInsertReplaySupplier(Supplier<InsertReplay.Plan> supplier) {
        this.insertReplaySupplier = supplier;
    }

    /**
     * Chooses which {@code Ctrl+letter} keys go to Neovim. A rejected letter is left to Eclipse, so
     * the text widget handles it as usual.
     */
    public void setCtrlKeyFilter(Predicate<Character> toNeovim) {
        this.ctrlKeyFilter = toNeovim;
    }

    /** Forget a half-typed command; called when a key reaches Neovim by another route (Ctrl-key bindings). */
    public void reset() {
        argumentPending = false;
        pendingZ = false;
    }

    /**
     * Routes one key.
     *
     * @param nvimKey the key in Neovim notation, not {@code null}
     * @return {@code true} if the key was handled here and Eclipse must not see it
     *         (the caller sets {@code event.doit = false}); {@code false} to let Eclipse handle it
     */
    public boolean route(String nvimKey) {
        if (neovim.isTypingMode() && !neovim.isNeovimDrivenInsert() && !neovim.isEclipseTypingReady()) {
            // Insert mode has begun but the editor has not yet received Neovim's text for it (a fast
            // typist, or a test): Eclipse typing now could be overwritten. Let Neovim type the rest of
            // this insert instead, in order with this key.
            neovim.driveInsertFromNeovim();
        }

        if (neovim.isNeovimDrivenInsert()) {
            pendingZ = false;
            neovim.inputAsync(nvimKey);
            return true;
        }

        if (neovim.isTypingMode()) {
            if ("<Esc>".equals(nvimKey)) {
                pendingZ = false;
                Supplier<InsertReplay.Plan> supplier = insertReplaySupplier;
                neovim.leaveInsertModeAsync(supplier == null ? null : supplier.get());
                return true;
            }
            return false; // let SWT/JFace insert it; Neovim learns the text on Esc
        }

        if (argumentPending || neovim.isWaitingForArgument()) {
            argumentPending = false;
            pendingZ = false;
            neovim.inputAsync(nvimKey); // the argument of f/F/t/T/r/@/m/'/`/"/q, whatever the key is
            return true;
        }

        Predicate<String> prompt = promptHandler;
        if (isPromptKey(nvimKey) && prompt != null) {
            pendingZ = false;
            if (prompt.test(nvimKey)) {
                return true;
            }
        }

        FileCommands files = fileCommands;
        if (files != null && handleZZ(files, nvimKey)) {
            return true;
        }

        char ctrlLetter = VimCtrlKeys.letterOf(nvimKey);
        if (ctrlLetter != 0 && !ctrlKeyFilter.test(ctrlLetter)) {
            pendingZ = false;
            return false; // the user gave this Ctrl key to Eclipse (Window > Preferences > Neovim > Ctrl Keys)
        }

        argumentPending = NvimKeys.takesArgument(nvimKey, neovim.isRecording());
        neovim.inputAsync(nvimKey);
        return true;
    }

    private static boolean isPromptKey(String nvimKey) {
        return ":".equals(nvimKey) || "/".equals(nvimKey) || "?".equals(nvimKey);
    }

    /**
     * Implements {@code ZZ} (save and close) and {@code ZQ} (close). Any other key after {@code Z}
     * replays the {@code Z} to Neovim followed by that key.
     *
     * @return {@code true} if the key was consumed here
     */
    private boolean handleZZ(FileCommands files, String nvimKey) {
        if (pendingZ) {
            pendingZ = false;
            if ("Z".equals(nvimKey)) {
                files.saveAndClose();
            } else if ("Q".equals(nvimKey) || "q".equals(nvimKey)) {
                files.close();
            } else {
                neovim.inputAsync("Z");
                neovim.inputAsync(nvimKey);
            }
            return true;
        }
        if ("Z".equals(nvimKey)) {
            pendingZ = true;
            return true;
        }
        return false;
    }
}
