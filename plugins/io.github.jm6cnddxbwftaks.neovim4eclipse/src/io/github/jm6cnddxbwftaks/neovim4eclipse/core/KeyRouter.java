// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InsertReplay;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimCtrlKeys;

/**
 * Decides, for one key already translated to Neovim notation ({@code "x"}, {@code "<Esc>"},
 * {@code "<C-w>"}), whether Neovim or Eclipse handles it, and queues the work.
 *
 * <ul>
 *   <li><b>Normal / visual / operator-pending / command line:</b> the key goes to Neovim and is
 *       consumed. That includes {@code :}, {@code /}, {@code ?}, {@code q} and {@code Z}: Neovim
 *       knows whether a key starts a command ({@code :} opens its command line, which the editor
 *       shows when Neovim sends {@code cmdline_show}) or belongs to the one before it ({@code f:},
 *       {@code r/}, {@code d/foo}, a half-typed mapping). {@code ZZ} and {@code ZQ} are mapped in
 *       Neovim to Eclipse's save and close ({@link IdeAction}).</li>
 *   <li><b>Insert / replace:</b> Eclipse inserts printable characters itself (no flicker, content
 *       assist keeps working). {@code Esc} pushes the document to Neovim and leaves insert mode.
 *       A {@code Ctrl+letter} the user gave to Neovim for insert mode ({@code Ctrl-W},
 *       {@code Ctrl-U}, ...) is a <em>sync point</em>: the text Eclipse typed so far goes to Neovim
 *       first, then the key ({@link Neovim#syncInsertThenInputAsync}). Four letters are Eclipse's
 *       work instead ({@link #insertCtrlKey}): {@code Ctrl-N} and {@code Ctrl-P} run Eclipse's Word
 *       Completion, {@code Ctrl-X Ctrl-O} its content assist, {@code Ctrl-C} ends the insert as
 *       {@code Esc} does.
 *       Exception: an insert driven by Neovim (from blockwise visual, while recording a macro, or
 *       started before Eclipse could take over) forwards every key.</li>
 *   <li>A {@code Ctrl+letter} the user gave to Eclipse (the Ctrl Keys preference page) is left to
 *       Eclipse.</li>
 * </ul>
 *
 * <p>Why so little: this class once followed Vim's command grammar key by key ({@code KeyGrammar},
 * removed in 0.1.7), to guess on the UI thread, before Neovim had processed the keys, whether
 * {@code :} would open a command line and whether {@code q} started a recording. Since the
 * {@code :} box became a view of Neovim's own command line, nothing here needs that guess: Neovim
 * answers by what it does. Example: {@code f:} typed fast sends {@code f} and {@code :}; Neovim
 * takes the {@code :} as the argument of {@code f} and opens no command line, so no box opens.
 *
 * <p>Why a class of its own: the SWT listener ({@code editor/KeyInterceptor}) is thin glue that
 * turns a {@code VerifyEvent} into a key and sets {@code event.doit}. The decisions live here,
 * behind a small interface ({@link Neovim}), so they are unit-tested without SWT, Eclipse or a
 * Neovim process.
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

        /** Lets Neovim type the rest of the current insert; see {@link #route}. */
        void driveInsertFromNeovim();

        void inputAsync(String keys);

        void leaveInsertModeAsync(InsertReplay.Plan replay);

        /**
         * A sync point: hands Neovim the text Eclipse typed, then sends {@code key} into the same
         * insert. Eclipse stops typing at once, so keys that follow go to Neovim, in order.
         *
         * @param sync what to hand over, or {@code null} for nothing but the document
         */
        void syncInsertThenInputAsync(InsertReplay.SyncPoint sync, String key);
    }

    /**
     * Eclipse's completions, which some of Vim's insert-mode keys run instead of Neovim's popup (the
     * plug-in does not draw that popup, docs/design/INSERT_CTRL_KEYS.md, phase D).
     */
    public enum Completion {
        /** <i>Word Completion</i> (Alt+/): the words of the open documents. For {@code Ctrl-N}, {@code Ctrl-P}. */
        WORD,
        /** <i>Content Assist</i> (Ctrl+Space). For {@code Ctrl-X Ctrl-O}, Vim's omni completion. */
        CONTENT_ASSIST
    }

    private final Neovim neovim;
    /** Runs one of Eclipse's completions; {@code null} until the editor sets it. */
    private volatile Consumer<Completion> completion;
    /**
     * Set by {@code Ctrl-X} in an insert Eclipse types: the next key chooses the completion
     * ({@code Ctrl-O} content assist, {@code Ctrl-N} or {@code Ctrl-P} Word Completion), as in Vim's
     * {@code Ctrl-X} mode. Any other key is handled as usual. UI thread.
     */
    private boolean ctrlXPending;
    /** Produces, at Esc, what to re-type into Neovim so that {@code .} can repeat the typed text. */
    private volatile Supplier<InsertReplay.Plan> insertReplaySupplier;
    /** Whether a {@code Ctrl+letter} goes to Neovim; letters it rejects are left to Eclipse. */
    private volatile Predicate<Character> ctrlKeyFilter = letter -> true;
    /** Whether a {@code Ctrl+letter} goes to Neovim in an insert Eclipse types (a sync point). */
    private volatile Predicate<Character> insertCtrlKeyFilter = letter -> false;
    /** Produces, at a sync point, what to hand to Neovim before the key. */
    private volatile Supplier<InsertReplay.SyncPoint> syncPointSupplier;

    public KeyRouter(Neovim neovim) {
        this.neovim = neovim;
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
     * Chooses which {@code Ctrl+letter} keys go to Neovim in an insert Eclipse types, each through
     * a sync point; a rejected letter is left to Eclipse. None by default.
     */
    public void setInsertCtrlKeyFilter(Predicate<Character> toNeovim) {
        this.insertCtrlKeyFilter = toNeovim;
    }

    /** Runs Eclipse's completions for {@code Ctrl-N}, {@code Ctrl-P} and {@code Ctrl-X Ctrl-O}. */
    public void setCompletion(Consumer<Completion> completion) {
        this.completion = completion;
    }

    /** Supplier of the sync point for a {@code Ctrl} key in an insert Eclipse types. */
    public void setSyncPointSupplier(Supplier<InsertReplay.SyncPoint> supplier) {
        this.syncPointSupplier = supplier;
    }

    /**
     * Routes one key.
     *
     * @param nvimKey the key in Neovim notation, not {@code null}
     * @return {@code true} if the key was handled here and Eclipse must not see it
     *         (the caller sets {@code event.doit = false}); {@code false} to let Eclipse handle it
     */
    public boolean route(String nvimKey) {
        return route(nvimKey, false);
    }

    /**
     * Routes a key that one of the plug-in's key bindings delivered ({@code SendKeyHandler}): the
     * binding was active, so the key is Neovim's in every mode, and Eclipse has already consumed
     * it. In an insert Eclipse types it is a sync point, whatever the insert filter says now (the
     * filter may have changed since the binding was activated; leaving the key to Eclipse then would
     * lose it).
     */
    public void routeBound(String nvimKey) {
        route(nvimKey, true);
    }

    private boolean route(String nvimKey, boolean bound) {
        boolean afterCtrlX = ctrlXPending;
        ctrlXPending = false; // it chooses only the very next key
        if (neovim.isTypingMode() && !neovim.isNeovimDrivenInsert() && !neovim.isEclipseTypingReady()) {
            // Insert mode has begun but the editor has not yet received Neovim's text for it (a fast
            // typist, or a test): Eclipse typing now could be overwritten. Let Neovim type the rest of
            // this insert instead, in order with this key. This also covers a fast qaihello<Esc>q:
            // the insert starts before Eclipse could take over, so Neovim types it and records it.
            neovim.driveInsertFromNeovim();
        }

        if (neovim.isNeovimDrivenInsert()) {
            return send(nvimKey); // text, and the Esc that ends it
        }

        if (neovim.isTypingMode()) {
            if ("<Esc>".equals(nvimKey)) {
                return leaveInsert();
            }
            char letter = VimCtrlKeys.letterOf(nvimKey);
            if (afterCtrlX && (letter == 'o' || letter == 'n' || letter == 'p')) {
                return complete(letter == 'o' ? Completion.CONTENT_ASSIST : Completion.WORD);
            }
            if (bound || (letter != 0 && insertCtrlKeyFilter.test(letter))) {
                return insertCtrlKey(letter, nvimKey);
            }
            return false; // let SWT/JFace insert it; Neovim learns the text on Esc
        }

        char ctrlLetter = VimCtrlKeys.letterOf(nvimKey);
        if (!bound && ctrlLetter != 0 && !ctrlKeyFilter.test(ctrlLetter)) {
            return false; // the user gave this Ctrl key to Eclipse (Window > Preferences > Neovim > Ctrl Keys)
        }
        return send(nvimKey);
    }

    /**
     * A {@code Ctrl} key given to Neovim for insert mode, in an insert Eclipse types. Most are a sync
     * point: Neovim gets the typed text, then the key. Example: {@code Ctrl-W} after {@code foo bar};
     * Neovim's own {@code Ctrl-W} deletes {@code bar} as Vim does, and {@code .} later repeats the
     * whole insert. Four are not, because their Vim work is a popup the plug-in does not draw, or is
     * {@code Esc}'s:
     * <ul>
     *   <li>{@code Ctrl-N}, {@code Ctrl-P}: Eclipse's Word Completion, which offers the words of the
     *       open documents, the nearest before the caret first; pressed again, the next one. It has
     *       no way back, so both keys do the same.</li>
     *   <li>{@code Ctrl-X}: waits for the next key ({@link #ctrlXPending}).</li>
     *   <li>{@code Ctrl-C}: ends the insert as {@code Esc} does, typed text handed over for {@code .}.
     *       (Vim's own {@code Ctrl-C} skips the {@code InsertLeave} autocommands and abbreviations;
     *       here it is simply {@code Esc}.)</li>
     * </ul>
     */
    private boolean insertCtrlKey(char letter, String nvimKey) {
        switch (letter) {
            case 'n', 'p' -> {
                return complete(Completion.WORD);
            }
            case 'x' -> {
                ctrlXPending = true;
                return true;
            }
            case 'c' -> {
                return leaveInsert();
            }
            default -> {
                Supplier<InsertReplay.SyncPoint> supplier = syncPointSupplier;
                neovim.syncInsertThenInputAsync(supplier == null ? null : supplier.get(), nvimKey);
                return true;
            }
        }
    }

    /** Leaves an insert Eclipse types, the typed text replayed into Neovim for {@code .}. */
    private boolean leaveInsert() {
        Supplier<InsertReplay.Plan> supplier = insertReplaySupplier;
        neovim.leaveInsertModeAsync(supplier == null ? null : supplier.get());
        return true;
    }

    /** Runs one of Eclipse's completions; Eclipse types its result, as it types any text. */
    private boolean complete(Completion which) {
        Consumer<Completion> run = completion;
        if (run != null) {
            run.accept(which);
        }
        return true;
    }

    /** Sends {@code nvimKey} to Neovim. Always consumes the key. */
    private boolean send(String nvimKey) {
        neovim.inputAsync(nvimKey);
        return true;
    }
}
