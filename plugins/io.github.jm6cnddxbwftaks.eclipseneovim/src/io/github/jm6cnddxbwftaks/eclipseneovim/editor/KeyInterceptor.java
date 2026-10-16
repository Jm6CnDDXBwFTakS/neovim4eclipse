// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.eclipse.swt.custom.VerifyKeyListener;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.eclipseneovim.core.NeovimSession;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.InsertReplay;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.KeyMapping;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.NvimKeys;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.VimCtrlKeys;

/**
 * Routes key presses between Neovim and Eclipse.
 *
 * <ul>
 *   <li><b>Normal / visual / operator-pending:</b> the key goes to Neovim and is swallowed
 *       ({@code event.doit = false}).</li>
 *   <li><b>Insert / replace:</b> Eclipse inserts printable characters itself (no flicker, content
 *       assist keeps working). {@code Esc} pushes the document to Neovim and leaves insert mode.
 *       Exception: insert mode entered from blockwise visual is driven by Neovim (all keys are
 *       forwarded) so Vim can repeat the text on every line of the block.</li>
 *   <li>{@code :}, {@code /}, {@code ?} open the inline command line ({@code CommandLineBox}) instead of Neovim's own; {@code ZZ}
 *       and {@code ZQ} are mapped to Eclipse save/close.</li>
 * </ul>
 *
 * <p>This runs on the SWT UI thread and must return quickly: it only queues work
 * ({@link NeovimSession#inputAsync}). Translating an SWT event into Neovim notation is done by
 * {@link KeyMapping}.
 */
public final class KeyInterceptor implements VerifyKeyListener {

    private static final Logger LOG = Logger.getLogger(KeyInterceptor.class.getName());

    /** Number of Ctrl chords still to be logged; a small budget so the log shows the first few and then stops. */
    private static final AtomicInteger CTRL_LOG_BUDGET = new AtomicInteger(12);

    private final NeovimSession session;
    private volatile boolean enabled = true;
    private volatile Predicate<String> specialKeyHandler;
    private volatile ITextEditor editor;
    /** Produces, at Esc, what to re-type into Neovim so that {@code .} can repeat the typed text. */
    private volatile Supplier<InsertReplay.Plan> insertReplaySupplier;
    /** A {@code Z} was typed and the next key decides between {@code ZZ} and {@code ZQ}. */
    private volatile boolean pendingZ;
    /**
     * The previous key was a command that takes one more character ({@code f}, {@code @}, {@code q}
     * ...), so the next key is that argument: {@code f/}, {@code @:} and {@code qa} must reach Neovim
     * instead of opening the command line. Tracked here as well as in the session
     * because keys can arrive faster than Neovim's answer about its state.
     */
    private volatile boolean argumentPending;
    /** Whether a {@code Ctrl+letter} goes to Neovim; letters it rejects are left to Eclipse. */
    private volatile Predicate<Character> ctrlKeyFilter = letter -> true;

    public KeyInterceptor(NeovimSession session) {
        this.session = session;
    }

    /** Handler for {@code :}, {@code /}, {@code ?}; returns {@code true} if it consumed the key. */
    public void setSpecialKeyHandler(Predicate<String> handler) {
        this.specialKeyHandler = handler;
    }

    /** Editor used for {@code ZZ}/{@code ZQ}; without one those keys are forwarded to Neovim. */
    public void setEditor(ITextEditor editor) {
        this.editor = editor;
    }

    /** Supplier of the replay plan for the insert that Esc is ending; called on the UI thread. */
    public void setInsertReplaySupplier(Supplier<InsertReplay.Plan> supplier) {
        this.insertReplaySupplier = supplier;
    }

    /**
     * Chooses which {@code Ctrl+letter} keys reach Neovim through this listener. It matters for
     * letters the user gave to Eclipse that Eclipse has no binding for: the key then arrives here and
     * is left alone, so the text widget handles it as usual.
     */
    public void setCtrlKeyFilter(Predicate<Character> toNeovim) {
        this.ctrlKeyFilter = toNeovim;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void verifyKey(VerifyEvent event) {
        if (!enabled || !session.isAlive()) {
            return;
        }
        String nvimKey = KeyMapping.toNeovimKey(event.keyCode, event.stateMask, event.character);
        logCtrlChord(event, nvimKey);
        if (nvimKey == null) {
            return;
        }

        if (session.isTypingMode() && !session.isNeovimDrivenInsert() && !session.isEclipseTypingReady()) {
            // Insert mode has begun but the editor has not yet received Neovim's text for it (a fast
            // typist, or a test): Eclipse typing now could be overwritten. Let Neovim type the rest of
            // this insert instead, in order with this key.
            session.driveInsertFromNeovim();
        }

        if (session.isNeovimDrivenInsert()) {
            // Neovim types this insert: it started from blockwise visual (Esc repeats the text on every
            // line of the block), a macro is being recorded (it must contain every key), or a key came
            // before Eclipse could take over (see above). Eclipse does not insert anything here.
            event.doit = false;
            pendingZ = false;
            session.inputAsync(nvimKey);
            return;
        }

        if (session.isTypingMode()) {
            if ("<Esc>".equals(nvimKey)) {
                event.doit = false;
                pendingZ = false;
                Supplier<InsertReplay.Plan> supplier = insertReplaySupplier;
                session.leaveInsertModeAsync(supplier == null ? null : supplier.get());
            }
            // Anything else: let SWT/JFace insert it. Neovim learns the text on Esc.
            return;
        }

        if (argumentPending || session.isWaitingForArgument()) {
            argumentPending = false;
            pendingZ = false;
            event.doit = false;
            session.inputAsync(nvimKey); // the argument of f/F/t/T/r/@/m/'/`/"/q, whatever the key is
            return;
        }

        if (isPromptKey(nvimKey) && specialKeyHandler != null) {
            event.doit = false;
            pendingZ = false;
            if (specialKeyHandler.test(nvimKey)) {
                return;
            }
        }

        if (editor != null && handleZZ(event, nvimKey)) {
            return;
        }

        char ctrlLetter = VimCtrlKeys.letterOf(nvimKey);
        if (ctrlLetter != 0 && !ctrlKeyFilter.test(ctrlLetter)) {
            pendingZ = false;
            return; // the user gave this Ctrl key to Eclipse (Window > Preferences > Neovim > Ctrl Keys)
        }

        event.doit = false;
        argumentPending = NvimKeys.takesArgument(nvimKey, session.isRecording());
        session.inputAsync(nvimKey);
    }

    /** Forget a half-typed command; called when a key reaches Neovim by another route (Ctrl-key bindings). */
    public void resetPendingArgument() {
        argumentPending = false;
        pendingZ = false;
    }

    /**
     * Diagnostics: records Ctrl chords that actually reach this listener. If Ctrl-V never shows up
     * here while the editor has focus, the workbench consumed it first (see KeyContextDiagnostics).
     */
    private static void logCtrlChord(VerifyEvent event, String nvimKey) {
        if ((event.stateMask & KeyMapping.SWT_CTRL) != 0 && CTRL_LOG_BUDGET.getAndDecrement() > 0) {
            LOG.info("Ctrl chord reached the editor key listener: keyCode=" + event.keyCode
                    + " stateMask=0x" + Integer.toHexString(event.stateMask)
                    + " char=" + (int) event.character + " -> " + nvimKey);
        }
    }

    private static boolean isPromptKey(String nvimKey) {
        return ":".equals(nvimKey) || "/".equals(nvimKey) || "?".equals(nvimKey);
    }

    /**
     * Implements {@code ZZ} (save and close) and {@code ZQ} (close). Any other key after {@code Z}
     * replays the {@code Z} to Neovim followed by that key.
     *
     * @return {@code true} if the event was consumed here
     */
    private boolean handleZZ(VerifyEvent event, String nvimKey) {
        if (pendingZ) {
            pendingZ = false;
            event.doit = false;
            if ("Z".equals(nvimKey)) {
                EclipseFileCommands.zz(editor);
            } else if ("Q".equals(nvimKey) || "q".equals(nvimKey)) {
                EclipseFileCommands.zq(editor);
            } else {
                session.inputAsync("Z");
                session.inputAsync(nvimKey);
            }
            return true;
        }
        if ("Z".equals(nvimKey)) {
            event.doit = false;
            pendingZ = true;
            return true;
        }
        return false;
    }
}
