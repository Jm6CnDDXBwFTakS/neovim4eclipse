// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.VerifyKeyListener;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.KeyRouter;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InsertReplay;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.KeyMapping;

/**
 * The SWT end of key routing: turns each key event of the editor's text widget into Neovim
 * notation ({@link KeyMapping}), asks {@link KeyRouter} who handles it, and swallows the event
 * ({@code event.doit = false}) when Neovim does. The decisions themselves (insert mode, Esc, Ctrl keys) are in {@link KeyRouter}, which is
 * unit-tested without SWT.
 *
 * <p>This runs on the SWT UI thread and must return quickly: it only queues work
 * ({@link NeovimSession#inputAsync}).
 */
public final class KeyInterceptor implements VerifyKeyListener {

    private static final Logger LOG = Logger.getLogger(KeyInterceptor.class.getName());

    /** Number of Ctrl chords still to be logged; a small budget so the log shows the first few and then stops. */
    private static final AtomicInteger CTRL_LOG_BUDGET = new AtomicInteger(12);

    private static final String ESCAPE = "<Esc>";

    private final NeovimSession session;
    private final KeyRouter router;
    private volatile boolean enabled = true;
    /** Set when {@link #verifyKey} saw an Esc; read and cleared by {@link #afterKeyDown} for the same key press. UI thread. */
    private boolean escapeVerified;
    private final Listener afterKeyDown = this::afterKeyDown;
    /** Run after each routed key; see {@link #setAfterKey}. */
    private Runnable afterKey = () -> { };

    public KeyInterceptor(NeovimSession session) {
        this.session = session;
        this.router = new KeyRouter(session);
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

    /** Runs after each key this interceptor routed (the editor re-checks its key contexts). */
    public void setAfterKey(Runnable afterKey) {
        this.afterKey = afterKey;
    }

    /**
     * Chooses which {@code Ctrl+letter} keys reach Neovim in an insert Eclipse types, each through a
     * sync point ({@link KeyRouter#setInsertCtrlKeyFilter}).
     */
    public void setInsertCtrlKeyFilter(Predicate<Character> toNeovim) {
        router.setInsertCtrlKeyFilter(toNeovim);
    }

    /** Runs Eclipse's completions for insert-mode {@code Ctrl-N}, {@code Ctrl-P}, {@code Ctrl-X Ctrl-O}; UI thread. */
    public void setCompletion(Consumer<KeyRouter.Completion> completion) {
        router.setCompletion(completion);
    }

    /** Supplier of the sync point for a {@code Ctrl} key in an insert Eclipse types; called on the UI thread. */
    public void setSyncPointSupplier(Supplier<InsertReplay.SyncPoint> supplier) {
        router.setSyncPointSupplier(supplier);
    }

    /** See {@link KeyRouter#setEclipseBinding}. */
    public void setEclipseBinding(Consumer<String> eclipseBinding) {
        router.setEclipseBinding(eclipseBinding);
    }

    /** See {@link KeyRouter#cancelCtrlX}. UI thread. */
    public void cancelCtrlX() {
        router.cancelCtrlX();
    }

    /**
     * Routes a key one of the plug-in's key bindings delivered ({@link KeyRouter#routeBound}): to
     * Neovim, through a sync point in an insert Eclipse types. UI thread.
     */
    public void routeBound(String nvimKey) {
        if (enabled && session.isAlive()) {
            router.routeBound(nvimKey);
            afterKey.run();
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void verifyKey(VerifyEvent event) {
        String nvimKey = KeyMapping.toNeovimKey(event.keyCode, event.stateMask, event.character);
        escapeVerified |= ESCAPE.equals(nvimKey);
        if (!enabled || !session.isAlive()) {
            return;
        }
        if (KeyMapping.dropsKey(event.stateMask)) {
            event.doit = false; // Super+U on Linux: neither Neovim's nor a letter to type (KeyMapping.dropsKey)
            return;
        }
        logCtrlChord(event, nvimKey);
        if (nvimKey != null && router.route(nvimKey)) {
            event.doit = false;
        }
        afterKey.run();
    }

    /**
     * The widget's {@code KeyDown} listener (see {@link #install}): leaves insert mode when Eclipse
     * took the Esc before {@link #verifyKey} saw it.
     *
     * <p>Why: listeners that are added to the text viewer later run before this one, and some
     * stop the key. Eclipse's linked mode (a Java rename with Alt+Shift+R, a template's fields)
     * takes Esc to leave linked mode, the content-assist popup takes it to close. Neovim then stayed
     * in insert mode until a second Esc. As in Vim, where Esc closes the completion menu and ends
     * the insert at once, one Esc now does both: Eclipse's part, then Neovim's.
     *
     * <p>How: StyledText runs the verify-key listeners from its own {@code KeyDown} listener, the
     * first one on the widget, so this listener runs after them for the same key press. Only Esc,
     * and only in insert or replace mode, where an Esc left out leaves the user stuck typing.
     */
    private void afterKeyDown(Event event) {
        boolean verified = escapeVerified;
        escapeVerified = false;
        if (verified || !enabled || !session.isAlive() || !session.isTypingMode()
                || !ESCAPE.equals(KeyMapping.toNeovimKey(event.keyCode, event.stateMask, event.character))) {
            return;
        }
        LOG.fine("Eclipse took Esc (linked mode or a popup) before Neovim saw it; leaving insert mode too");
        router.route(ESCAPE);
    }

    /**
     * Leaves insert mode as an Esc typed in the editor would (Eclipse's typing goes to Neovim first),
     * if Neovim is in insert or replace mode. For a linked edit that ended the insert's reason
     * ({@code LinkedEditWatch}). UI thread.
     *
     * @return whether the Esc was sent
     */
    public boolean leaveInsertMode() {
        if (enabled && session.isAlive() && session.isTypingMode()) {
            router.route(ESCAPE);
            return true;
        }
        return false;
    }

    /** Adds the {@link #afterKeyDown} listener to {@code widget}; undo with {@link #uninstall}. UI thread. */
    public void install(StyledText widget) {
        widget.addListener(SWT.KeyDown, afterKeyDown);
    }

    public void uninstall(StyledText widget) {
        if (!widget.isDisposed()) {
            widget.removeListener(SWT.KeyDown, afterKeyDown);
        }
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
