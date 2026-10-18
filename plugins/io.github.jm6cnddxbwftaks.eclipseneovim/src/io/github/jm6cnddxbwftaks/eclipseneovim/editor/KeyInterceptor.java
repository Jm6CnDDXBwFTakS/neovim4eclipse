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

import io.github.jm6cnddxbwftaks.eclipseneovim.core.KeyRouter;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.NeovimSession;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.InsertReplay;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.KeyMapping;

/**
 * The SWT end of key routing: turns each key event of the editor's text widget into Neovim
 * notation ({@link KeyMapping}), asks {@link KeyRouter} who handles it, and swallows the event
 * ({@code event.doit = false}) when Neovim does. The decisions themselves (insert mode, command
 * arguments, {@code :} prompts, {@code ZZ}, Ctrl keys) are in {@link KeyRouter}, which is
 * unit-tested without SWT.
 *
 * <p>This runs on the SWT UI thread and must return quickly: it only queues work
 * ({@link NeovimSession#inputAsync}).
 */
public final class KeyInterceptor implements VerifyKeyListener {

    private static final Logger LOG = Logger.getLogger(KeyInterceptor.class.getName());

    /** Number of Ctrl chords still to be logged; a small budget so the log shows the first few and then stops. */
    private static final AtomicInteger CTRL_LOG_BUDGET = new AtomicInteger(12);

    private final NeovimSession session;
    private final KeyRouter router;
    private volatile boolean enabled = true;

    public KeyInterceptor(NeovimSession session) {
        this.session = session;
        this.router = new KeyRouter(session);
    }

    /** Handler for {@code :}, {@code /}, {@code ?}; returns {@code true} if it consumed the key. */
    public void setSpecialKeyHandler(Predicate<String> handler) {
        router.setPromptHandler(handler);
    }

    /** Editor used for {@code ZZ}/{@code ZQ}; without one those keys are forwarded to Neovim. */
    public void setEditor(ITextEditor editor) {
        router.setFileCommands(editor == null ? null : new KeyRouter.FileCommands() {
            @Override
            public void saveAndClose() {
                EclipseFileCommands.zz(editor);
            }

            @Override
            public void close() {
                EclipseFileCommands.zq(editor);
            }
        });
    }

    /** Supplier of the replay plan for the insert that Esc is ending; called on the UI thread. */
    public void setInsertReplaySupplier(Supplier<InsertReplay.Plan> supplier) {
        router.setInsertReplaySupplier(supplier);
    }

    /**
     * Chooses which {@code Ctrl+letter} keys reach Neovim through this listener. It matters for
     * letters the user gave to Eclipse that Eclipse has no binding for: the key then arrives here and
     * is left alone, so the text widget handles it as usual.
     */
    public void setCtrlKeyFilter(Predicate<Character> toNeovim) {
        router.setCtrlKeyFilter(toNeovim);
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
        if (nvimKey != null && router.route(nvimKey)) {
            event.doit = false;
        }
    }

    /** Forget a half-typed command; called when a key reaches Neovim by another route (Ctrl-key bindings). */
    public void resetPendingArgument() {
        router.reset();
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
}
