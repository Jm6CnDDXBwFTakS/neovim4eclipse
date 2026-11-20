// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.BiPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CommandWindowView;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FloatWindow;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HostCommand;

/**
 * Hands the session's focus events ({@link FocusTarget}) to the part that has the focus. The
 * session listens once, through the consumers below, and the router passes each event on to the
 * current target, so that a part taking the focus is one call ({@link #focus}) instead of ten
 * listeners moved from one part to the next.
 *
 * <p>Example: a text editor's controller takes the focus when it attaches and gives it up when it
 * closes ({@link #release}). An event that comes while no part has the focus (between one editor's
 * close and the next one's attach) goes nowhere, as it went to no listener before.
 *
 * <p>Thread-safe: the target is read on the session's threads and set on any thread. A target that
 * throws is logged, and the event is not passed on again.
 */
public final class FocusRouter {

    private static final Logger LOG = Logger.getLogger(FocusRouter.class.getName());

    private volatile FocusTarget current;
    /**
     * Whether a host command belongs to the part with the focus, by the window it ran in
     * ({@code util/CommandOrigin}); the session sets it. One that does not is dropped.
     */
    private volatile BiPredicate<HostCommand, FocusTarget> ownCommand = (c, t) -> true;

    /** Gives the focus to {@code target}; the part that had it hears nothing more. */
    public synchronized void focus(FocusTarget target) {
        current = target;
    }

    /** Whether {@code target} has the focus now. Example: the code clicked again, while it has not. */
    public boolean has(FocusTarget target) {
        return target != null && current == target;
    }

    /**
     * Takes the focus from {@code target} if it still has it, and leaves it with no part.
     * A target that no longer has it changes nothing: a page that closed after the code took the
     * focus back does not take it from the code.
     */
    public synchronized void release(FocusTarget target) {
        if (current == target) {
            current = null;
        }
    }

    /** Sets the rule that keeps a command from another part's window from the focus ({@link #hostCommand}). */
    void setCommandRule(BiPredicate<HostCommand, FocusTarget> rule) {
        ownCommand = rule;
    }

    /** The part with the focus, or {@code null}. */
    public FocusTarget current() {
        return current;
    }

    /**
     * Whether a page has the focus events now: the target shows a page window
     * ({@link FocusTarget#pageWindow}). {@code false} with no target at all, as after a page closed
     * and Eclipse activated a view: then nothing holds Neovim's screen. Any thread.
     */
    public boolean pageHasFocus() {
        FocusTarget target = current;
        return target != null && target.pageWindow() != HostCommand.NO_WINDOW;
    }

    final Consumer<String> mode = m -> pass("mode", t -> t.onMode(m));
    final Consumer<String> recording = r -> pass("recording", t -> t.onRecording(r));
    final Consumer<IdeAction> ideAction = a -> pass("IDE action", t -> t.onIdeAction(a));
    /**
     * A host command goes to the target with the focus only if it belongs to that very target,
     * judged with the target read once: a click back into the code between the command's run and
     * its arrival changes the target, and the command from the page is then judged against the code
     * (and dropped), not against the page it was typed in.
     */
    final Consumer<HostCommand> hostCommand = c -> {
        FocusTarget target = current;
        if (target == null) {
            return;
        }
        if (!ownCommand.test(c, target)) {
            LOG.fine(() -> "Dropped " + c + ": it ran in a window of a part without the focus");
            return;
        }
        try {
            target.onHostCommand(c);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "The host command event failed in " + target, e);
        }
    };
    final Consumer<String> cmdlineType = v -> pass("command line", t -> t.onCmdlineType(v));
    final Consumer<CommandWindowView> commandWindow = v -> pass("command-line window", t -> t.onCommandWindow(v));
    final Consumer<List<FloatWindow>> floatingWindows = w -> pass("floating windows", t -> t.onFloatingWindows(w));

    private void pass(String what, Consumer<FocusTarget> event) {
        FocusTarget target = current;
        if (target == null) {
            return;
        }
        try {
            event.accept(target);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "The " + what + " event failed in " + target, e);
        }
    }
}
