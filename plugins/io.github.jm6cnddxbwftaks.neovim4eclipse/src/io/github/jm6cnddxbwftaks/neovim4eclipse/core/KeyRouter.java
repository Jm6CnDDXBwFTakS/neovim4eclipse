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
 *       first, then the key ({@link Neovim#syncInsertThenInputAsync}). {@code Ctrl-N},
 *       {@code Ctrl-P} and {@code Ctrl-X} with the key after it start Neovim's completion that
 *       way ({@link Neovim#completeInNeovimAsync}); Neovim then types until the completion ends.
 *       Three are Eclipse's work instead ({@link #insertCtrlKey}): {@code Ctrl-X Ctrl-O} runs
 *       its content assist, {@code Ctrl-C} ends the insert as {@code Esc} does, and with
 *       Neovim's completion list turned off {@code Ctrl-N} and {@code Ctrl-P} run its Word
 *       Completion.
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

        /**
         * Whether {@code Ctrl-N}, {@code Ctrl-P} and the {@code Ctrl-X} modes are Neovim's own
         * completion, whose list the editor draws; {@code false} when the user turned that off
         * ({@code vim.g.neovim4eclipse_completion_menu = 0}): then Eclipse's Word Completion.
         */
        boolean completesInNeovim();

        /**
         * A sync point whose keys start Neovim's completion ({@code <C-n>}, {@code <C-x><C-l>}).
         * As {@link #syncInsertThenInputAsync}; Neovim then types until the completion has ended.
         */
        void completeInNeovimAsync(InsertReplay.SyncPoint sync, String keys);

        /**
         * Whether Neovim is completing in an insert it will give back to Eclipse (not one it
         * types to the end, such as a recorded one), or is about to.
         */
        boolean isCompleting();

        /**
         * Ends Neovim's completion, the text kept, behind the keys queued before; Eclipse types
         * again, and then {@code after} runs (on Neovim's input thread). It runs also when there
         * was no completion to end any more.
         */
        void endCompletionThenAsync(Runnable after);
    }

    /**
     * Eclipse's completions, which some of Vim's insert-mode keys run instead of Neovim's
     * (docs/design/INSERT_CTRL_KEYS.md, section 6): content assist always, Word Completion only
     * with Neovim's completion list turned off.
     */
    public enum Completion {
        /**
         * <i>Word Completion</i> (Alt+/): the words of the open documents. For {@code Ctrl-N},
         * {@code Ctrl-P} when Neovim's completion list is off ({@link Neovim#completesInNeovim}).
         */
        WORD,
        /** <i>Content Assist</i> (Ctrl+Space). For {@code Ctrl-X Ctrl-O}, Vim's omni completion. */
        CONTENT_ASSIST
    }

    private final Neovim neovim;
    /** Runs one of Eclipse's completions; {@code null} until the editor sets it. */
    private volatile Consumer<Completion> completion;
    /**
     * Set by {@code Ctrl-X} in an insert Eclipse types: the next key chooses the completion, as in
     * Vim's {@code Ctrl-X} mode ({@link #afterCtrlX}). {@code Ctrl-O} is Eclipse's content assist;
     * another {@code Ctrl} letter goes to Neovim together with the {@code Ctrl-X} it follows. Any
     * other key is handled as usual. Why Neovim does not get the {@code Ctrl-X} at once: it would
     * then be in its {@code Ctrl-X} mode, and content assist would have to wait for it to leave
     * that mode and give the insert back. UI thread, but read from any ({@link #isCtrlXPending}).
     */
    private volatile boolean ctrlXPending;
    /** Produces, at Esc, what to re-type into Neovim so that {@code .} can repeat the typed text. */
    private volatile Supplier<InsertReplay.Plan> insertReplaySupplier;
    /** Whether a {@code Ctrl+letter} goes to Neovim; letters it rejects are left to Eclipse. */
    private volatile Predicate<Character> ctrlKeyFilter = letter -> true;
    /** Whether a {@code Ctrl+letter} goes to Neovim in an insert Eclipse types (a sync point). */
    private volatile Predicate<Character> insertCtrlKeyFilter = letter -> false;
    /** Produces, at a sync point, what to hand to Neovim before the key. */
    private volatile Supplier<InsertReplay.SyncPoint> syncPointSupplier;
    /** Runs the command Eclipse binds to a {@code Ctrl} key (see {@link #routeBound}); {@code null} for none. */
    private volatile Consumer<String> eclipseBinding;

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
     * Runs Eclipse's own command for a {@code Ctrl} key the plug-in's binding took although the
     * letter is Eclipse's in insert mode ({@link #routeBound}). Gets the key in Neovim notation.
     */
    public void setEclipseBinding(Consumer<String> eclipseBinding) {
        this.eclipseBinding = eclipseBinding;
    }

    /**
     * Forgets a pending {@code Ctrl-X} ({@link #ctrlXPending}): a key Eclipse handled without this
     * router came next. Example: {@code Ctrl-X}, then {@code Ctrl-N}, whose binding runs Word
     * Completion directly; without this, a {@code Ctrl-O} typed much later would still run Content
     * Assist. The editor calls it for every command other than the plug-in's own key commands.
     * UI thread.
     */
    public void cancelCtrlX() {
        ctrlXPending = false;
    }

    /**
     * Whether {@code Ctrl-X} was pressed and the key that chooses the completion is still to come.
     * The editor then gives Neovim every {@code Ctrl} letter ({@code util/KeyContextRule}), so that
     * {@code Ctrl-L} after it is Vim's line completion, not the command Eclipse binds to it.
     */
    public boolean isCtrlXPending() {
        return ctrlXPending;
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
     * binding was active, and Eclipse has already consumed the key. In an insert Eclipse types it is
     * a sync point if the letter is Neovim's in insert mode. If it is not, the binding was a
     * normal-mode one not switched off yet: the key contexts follow the mode a moment later than
     * the keys. Example: {@code Ctrl+W} ticked for Normal only, {@code i} then {@code Ctrl+W} typed
     * fast. Eclipse's own command for the key runs then (Close), as if the contexts had been on
     * time ({@link #setEclipseBinding}), not Vim's insert-mode {@code Ctrl-W}.
     */
    public void routeBound(String nvimKey) {
        route(nvimKey, true);
    }

    private boolean route(String nvimKey, boolean bound) {
        boolean afterCtrlX = ctrlXPending;
        ctrlXPending = false; // it chooses only the very next key
        if (neovim.isTypingMode() && !neovim.isNeovimDrivenInsert() && !neovim.isEclipseTypingReady()) {
            // Insert mode has begun but the editor has not yet received Neovim's text for it (a fast
            // typist, or a test): Eclipse typing now could be overwritten. Let Neovim type this key
            // instead, in order, and the ones after it until Neovim has them all; then Eclipse types
            // again (KeyInput.handBackToEclipse). A fast qaihello<Esc>q stays Neovim's to the end:
            // the insert is recorded, so Neovim types it and records it.
            neovim.driveInsertFromNeovim();
        }

        if (neovim.isNeovimDrivenInsert()) {
            if (neovim.isCompleting()) {
                Boolean handled = whileCompleting(afterCtrlX, nvimKey);
                if (handled != null) {
                    return handled;
                }
            }
            return send(nvimKey); // text, and the Esc that ends it
        }

        if (neovim.isTypingMode()) {
            if ("<Esc>".equals(nvimKey)) {
                return leaveInsert();
            }
            char letter = VimCtrlKeys.letterOf(nvimKey);
            if (afterCtrlX && letter != 0) {
                Boolean handled = afterCtrlX(letter, nvimKey);
                if (handled != null) {
                    return handled;
                }
            }
            if (letter != 0 && insertCtrlKeyFilter.test(letter)) {
                return insertCtrlKey(letter, nvimKey);
            }
            if (bound) {
                Consumer<String> eclipse = eclipseBinding;
                if (eclipse != null) {
                    eclipse.accept(nvimKey); // the letter is Eclipse's here (see routeBound)
                }
                return true;
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
     * The {@code Ctrl} letter after {@code Ctrl-X} in an insert Eclipse types.
     * <ul>
     *   <li>{@code Ctrl-O}: Eclipse's content assist, in place of Vim's omni completion.</li>
     *   <li>{@code Ctrl-C}: a key on its own, which ends the insert as {@code Esc} does
     *       ({@link #insertCtrlKey}). Sent to Neovim after {@code <C-x>} it would be Vim's raw
     *       {@code Ctrl-C}, which skips {@code InsertLeave} and abbreviations.</li>
     *   <li>With Neovim's completion ({@link Neovim#completesInNeovim}), any other letter: Neovim
     *       gets {@code <C-x>} and the key in one sync point, and does what Vim does: {@code Ctrl-L}
     *       completes whole lines, {@code Ctrl-F} file names, {@code Ctrl-N} words of this file.</li>
     *   <li>Without it, {@code Ctrl-N} and {@code Ctrl-P}: Eclipse's Word Completion.</li>
     * </ul>
     *
     * @return whether the key was consumed, or {@code null} to route it as a key on its own
     */
    private Boolean afterCtrlX(char letter, String nvimKey) {
        if (letter == 'o') {
            return complete(Completion.CONTENT_ASSIST);
        }
        if (letter == 'c') {
            return null; // Ctrl-C ends the insert as Esc does, also after a Ctrl-X pressed by mistake
        }
        if (letter == 'v' || letter == 'q') {
            // Vim's command-line completion, which runs what a file's line says in backticks.
            // Neovim refuses it by itself for every sender (LuaScripts.CTRL_X_GUARD); here the key
            // is only kept from starting a completion that would be refused at once.
            return true;
        }
        if (neovim.completesInNeovim()) {
            return completeInNeovim("<C-x>" + nvimKey);
        }
        return letter == 'n' || letter == 'p' ? complete(Completion.WORD) : null;
    }

    /**
     * A key while Neovim completes in an insert it will give back: {@code Ctrl-X} and the key
     * after it are decided here, as in an insert Eclipse types ({@link #afterCtrlX}), so that
     * {@code Ctrl-X Ctrl-O} is Eclipse's content assist in both.
     * <ul>
     *   <li>{@code Ctrl-X}: kept back until the next key ({@link #ctrlXPending}). Sent at once,
     *       Neovim would be in its {@code Ctrl-X} mode before the router knows whether the
     *       second key is one for Eclipse.</li>
     *   <li>then {@code Ctrl-O}: the completion is ended with its text kept, Eclipse types again,
     *       and content assist opens. In that order, unless a key typed right behind keeps
     *       Neovim typing a moment longer; a proposal accepted then is an edit of Eclipse's own
     *       during a Neovim-driven insert, which the editor pushes to Neovim
     *       ({@code NeovimEditorController}, the push gate).</li>
     *   <li>then {@code Ctrl-V} or {@code Ctrl-Q}: swallowed, as in {@link #afterCtrlX}.</li>
     *   <li>then any other key but {@code Ctrl-C}: sent with its {@code Ctrl-X}, so
     *       {@code Ctrl-N}, then {@code Ctrl-X Ctrl-L} goes on with whole lines. {@code Ctrl-C}
     *       goes alone and ends the insert.</li>
     * </ul>
     * Not in an insert Neovim types to its end (while recording): the keys go to Neovim as they
     * are, a proposal Eclipse inserted would be missing from the recording, and Neovim itself
     * refuses the three completions that must not run ({@code LuaScripts.CTRL_X_GUARD}).
     *
     * @return whether the key was consumed, or {@code null} to send it to Neovim as it is
     */
    private Boolean whileCompleting(boolean afterCtrlX, String nvimKey) {
        if ("<C-x>".equals(nvimKey)) {
            ctrlXPending = true;
            return true;
        }
        char letter = afterCtrlX ? VimCtrlKeys.letterOf(nvimKey) : 0;
        if (letter == 'o') {
            neovim.endCompletionThenAsync(() -> complete(Completion.CONTENT_ASSIST));
            return true;
        }
        if (letter == 'v' || letter == 'q') {
            return true;
        }
        if (afterCtrlX && letter != 'c') {
            // Also a key that is no Ctrl letter: Ctrl-X Ctrl-] (tags) and Ctrl-X s (spelling) are
            // completions too, and after any other key Neovim leaves its Ctrl-X mode by itself.
            return send("<C-x>" + nvimKey);
        }
        return null;
    }

    /** Starts Neovim's completion with {@code keys}, the typed text handed over first. */
    private boolean completeInNeovim(String keys) {
        Supplier<InsertReplay.SyncPoint> supplier = syncPointSupplier;
        neovim.completeInNeovimAsync(supplier == null ? null : supplier.get(), keys);
        return true;
    }

    /**
     * A {@code Ctrl} key given to Neovim for insert mode, in an insert Eclipse types. Most are a sync
     * point: Neovim gets the typed text, then the key. Example: {@code Ctrl-W} after {@code foo bar};
     * Neovim's own {@code Ctrl-W} deletes {@code bar} as Vim does, and {@code .} later repeats the
     * whole insert. Four differ:
     * <ul>
     *   <li>{@code Ctrl-N}, {@code Ctrl-P}: Neovim's completion, a sync point after which Neovim
     *       types until the completion ends ({@link #completeInNeovim}). With its list turned off
     *       ({@link Neovim#completesInNeovim}), Eclipse's Word Completion instead, which offers the
     *       words of the open documents, the nearest before the caret first; pressed again, the
     *       next one. That one has no way back, so both keys do the same.</li>
     *   <li>{@code Ctrl-X}: waits for the next key ({@link #ctrlXPending}).</li>
     *   <li>{@code Ctrl-C}: ends the insert as {@code Esc} does, typed text handed over for {@code .}.
     *       (Vim's own {@code Ctrl-C} skips the {@code InsertLeave} autocommands and abbreviations;
     *       here it is simply {@code Esc}.)</li>
     * </ul>
     */
    private boolean insertCtrlKey(char letter, String nvimKey) {
        switch (letter) {
            case 'n', 'p' -> {
                return neovim.completesInNeovim() ? completeInNeovim(nvimKey) : complete(Completion.WORD);
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
