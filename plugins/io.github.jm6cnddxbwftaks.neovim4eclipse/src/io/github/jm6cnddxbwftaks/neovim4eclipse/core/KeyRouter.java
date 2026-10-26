// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

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
    }

    private final Neovim neovim;
    /** Produces, at Esc, what to re-type into Neovim so that {@code .} can repeat the typed text. */
    private volatile Supplier<InsertReplay.Plan> insertReplaySupplier;
    /** Whether a {@code Ctrl+letter} goes to Neovim; letters it rejects are left to Eclipse. */
    private volatile Predicate<Character> ctrlKeyFilter = letter -> true;

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
            // this insert instead, in order with this key. This also covers a fast qaihello<Esc>q:
            // the insert starts before Eclipse could take over, so Neovim types it and records it.
            neovim.driveInsertFromNeovim();
        }

        if (neovim.isNeovimDrivenInsert()) {
            return send(nvimKey); // text, and the Esc that ends it
        }

        if (neovim.isTypingMode()) {
            if ("<Esc>".equals(nvimKey)) {
                Supplier<InsertReplay.Plan> supplier = insertReplaySupplier;
                neovim.leaveInsertModeAsync(supplier == null ? null : supplier.get());
                return true;
            }
            return false; // let SWT/JFace insert it; Neovim learns the text on Esc
        }

        char ctrlLetter = VimCtrlKeys.letterOf(nvimKey);
        if (ctrlLetter != 0 && !ctrlKeyFilter.test(ctrlLetter)) {
            return false; // the user gave this Ctrl key to Eclipse (Window > Preferences > Neovim > Ctrl Keys)
        }
        return send(nvimKey);
    }

    /** Sends {@code nvimKey} to Neovim. Always consumes the key. */
    private boolean send(String nvimKey) {
        neovim.inputAsync(nvimKey);
        return true;
    }
}
