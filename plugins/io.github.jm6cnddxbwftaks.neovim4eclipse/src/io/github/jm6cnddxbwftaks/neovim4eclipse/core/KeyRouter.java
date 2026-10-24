// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.function.Predicate;
import java.util.function.Supplier;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InsertReplay;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.KeyGrammar;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimKeys;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimCtrlKeys;

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
 *   <li>{@code :}, {@code /}, {@code ?} open the editor's own prompt where they start a command or
 *       a search motion; {@code ZZ} and {@code ZQ} become Eclipse save/close. Where they belong to the
 *       command before them ({@code f:}, {@code [/}, {@code g??}, a half-typed mapping) they go to
 *       Neovim. Which is which: Neovim's own answer ({@code nvim_get_mode}) once it has processed
 *       every key sent, else {@link KeyGrammar}'s reading of the keys
 *       (docs/design/KEY_ROUTING.md).</li>
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

        /**
         * Whether Neovim waits for one more key of the current command ({@code nvim_get_mode}'s
         * {@code blocking}): after {@code f}, {@code g}, {@code di}, a half-typed mapping ...
         */
        boolean isWaitingForArgument();

        /** Neovim's mode as {@code nvim_get_mode} last reported it ({@code "n"}, {@code "no"}, {@code "v"} ...). */
        String getMode();

        /**
         * Whether keys sent with {@link #inputAsync} have not been processed by Neovim yet. While
         * they have not, {@link #isWaitingForArgument()} describes an earlier key.
         */
        boolean hasKeysInFlight();

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
    /**
     * Follows Vim's command grammar over the keys sent to Neovim, to know what the next key is while
     * Neovim has not processed them yet; synced to Neovim's own answer whenever it has. Used on the
     * UI thread only, as {@link #route} is.
     */
    private final KeyGrammar grammar = new KeyGrammar();
    /** A {@code Z} was typed as a command and held back: the next key decides {@code ZZ} or {@code ZQ}. */
    private boolean zPending;
    /**
     * Whether a macro is being recorded, as far as the keys routed here tell, or {@code null} once
     * Neovim has caught up ({@link #isRecording()}). The session learns it from Neovim after each key,
     * which can be later than the next key: in {@code qaA;<Esc>jq:} typed quickly, the session may still
     * say "not recording" when the second {@code q} arrives, which would then wait for a register and
     * send the {@code :} to Neovim instead of opening the prompt.
     */
    private volatile Boolean recordingByKeys;

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

    /**
     * A key reached Neovim by another route (the Ctrl-key commands): follow it too, so that
     * {@code <C-v>} (block visual) or {@code <C-w>} (a prefix) count for the next key.
     */
    public void sentElsewhere(String nvimKey) {
        zPending = false;
        grammar.feed(nvimKey, isRecording());
        recordingByKeys = null; // that key may have started or stopped a recording: ask Neovim again
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
            zPending = false;
            return send(nvimKey, false); // text, and the Esc that ends it
        }

        if (neovim.isTypingMode()) {
            if ("<Esc>".equals(nvimKey)) {
                zPending = false;
                grammar.reset();
                Supplier<InsertReplay.Plan> supplier = insertReplaySupplier;
                neovim.leaveInsertModeAsync(supplier == null ? null : supplier.get());
                return true;
            }
            return false; // let SWT/JFace insert it; Neovim learns the text on Esc
        }

        // Neovim's own answer is right about the keys it has processed. When it has processed all
        // keys sent so far, it is current and replaces the grammar's guess (which knows nothing of
        // the user's mappings, for example). While keys are on their way, the guess stands.
        if (!neovim.hasKeysInFlight()) {
            grammar.syncTo(neovim.getMode(), neovim.isWaitingForArgument());
        }

        FileCommands files = fileCommands;
        if (zPending) {
            zPending = false;
            return afterZ(files, nvimKey);
        }

        boolean recording = isRecording();
        KeyGrammar.Expect next = grammar.expect();
        Predicate<String> prompt = promptHandler;

        if (next == KeyGrammar.Expect.ARGUMENT || next == KeyGrammar.Expect.TEXT) {
            // f: r? m' g? [/ di" ... : the key belongs to the command before it, whatever it is, or
            // is text for an insert Neovim has begun. After q it names the register (q: q/ q? open
            // Neovim's command-line window).
            if (grammar.expectsRecordingRegister() && NvimKeys.isRecordingRegister(nvimKey)) {
                recordingByKeys = true;
            }
            return send(nvimKey, recording);
        }

        boolean doublesOperator = "?".equals(nvimKey) && grammar.questionDoublesOperator(); // g??
        if (isPromptKey(nvimKey) && prompt != null && !doublesOperator) {
            grammar.promptOpened();
            if (prompt.test(nvimKey)) {
                return true;
            }
        }

        if (next == KeyGrammar.Expect.COMMAND && files != null && "Z".equals(nvimKey)) {
            zPending = true; // held back until the next key: ZZ and ZQ become Eclipse's save and close
            return true;
        }

        char ctrlLetter = VimCtrlKeys.letterOf(nvimKey);
        if (ctrlLetter != 0 && !ctrlKeyFilter.test(ctrlLetter)) {
            return false; // the user gave this Ctrl key to Eclipse (Window > Preferences > Neovim > Ctrl Keys)
        }

        if ("q".equals(nvimKey) && next == KeyGrammar.Expect.COMMAND && recording) {
            recordingByKeys = false; // this q stops the recording
        }
        return send(nvimKey, recording);
    }

    /** Sends {@code nvimKey} to Neovim and lets the grammar follow it. Always consumes the key. */
    private boolean send(String nvimKey, boolean recording) {
        grammar.feed(nvimKey, recording);
        neovim.inputAsync(nvimKey);
        return true;
    }

    /**
     * Whether a macro is being recorded: what the keys routed here say until the session's answer
     * from Neovim agrees with it, then the session's answer.
     */
    private boolean isRecording() {
        Boolean byKeys = recordingByKeys;
        boolean reported = neovim.isRecording();
        if (byKeys == null || byKeys == reported) {
            recordingByKeys = null; // Neovim has caught up
            return reported;
        }
        return byKeys;
    }

    private static boolean isPromptKey(String nvimKey) {
        return ":".equals(nvimKey) || "/".equals(nvimKey) || "?".equals(nvimKey);
    }

    /**
     * The key after a held-back {@code Z}: {@code ZZ} (save and close) and {@code ZQ} (close) run in
     * Eclipse; any other key is sent to Neovim after the {@code Z}.
     *
     * @return {@code true}: the key is consumed here
     */
    private boolean afterZ(FileCommands files, String nvimKey) {
        switch (nvimKey) {
            case "Z" -> {
                grammar.reset();
                files.saveAndClose();
            }
            case "Q", "q" -> {
                grammar.reset();
                files.close();
            }
            default -> {
                boolean recording = isRecording();
                send("Z", recording);
                send(nvimKey, recording);
            }
        }
        return true;
    }
}
