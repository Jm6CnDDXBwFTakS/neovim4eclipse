// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Incoming;

/**
 * The receiver the RPC channel has from its start, which hands everything on to the session's
 * once that is there ({@link #deliverTo}), and keeps every notification that came before it.
 *
 * <p>Why keep them all: Neovim sends its {@code redraw}s from {@code nvim_ui_attach} on, and with
 * {@code --embed} the user's configuration runs right after it, while Eclipse is still building
 * its session. What Neovim draws then it does not send again: a window's size
 * ({@code grid_resize}) comes once, when it changes, and later lines only where they changed.
 * Dropped, a floating window the configuration opened meanwhile (lazy.nvim's installer, as it
 * installs missing plug-ins) had a place but no cells, and was never drawn; the highlights and
 * the first window's place would be missing too. So the rule is one: nothing Neovim sends before
 * the session is ready is lost; it is handed on, in the order it came, before the first
 * notification or request after the session is there, on the same reader thread, so the order
 * holds.
 * Example: a {@code redraw} with lazy.nvim's {@code grid_resize}, kept, then the session set,
 * then the next {@code redraw}: the session gets the first, then the second.
 *
 * <p>A request before then cannot wait (Neovim waits for its answer): it is answered with an
 * error; one after it waits for the kept notifications to be handed on first, so the order holds
 * for requests too. A kept notification whose handler fails is logged and the rest still go on,
 * as the channel does for each notification (one handler's bug must not cost what comes after).
 * The kept notifications cost only the time Eclipse takes to build its session (the
 * configuration may draw meanwhile, as an installer's progress does): the list is handed on and
 * let go at the first notification or request after it.
 *
 * <p>Threads: {@link #notification} and {@link #request} run on the channel's reader thread,
 * one at a time; {@link #deliverTo} on the session's, once (the field is volatile). The kept list
 * is touched on the reader thread only.
 */
final class EarlyIncoming implements Incoming {

    private static final Logger LOG = Logger.getLogger(EarlyIncoming.class.getName());

    /** A notification kept before the session's receiver was there. */
    private record Kept(String method, List<?> params) {
    }

    private volatile Incoming target;
    /** The notifications that came before {@link #target} was set, in order; {@code null} once handed on. */
    private List<Kept> kept = new ArrayList<>();

    /** Hands everything from now on, and what came before, to {@code incoming}. Call once. */
    void deliverTo(Incoming incoming) {
        this.target = incoming;
    }

    @Override
    public void notification(String method, List<?> params) {
        Incoming to = target;
        if (to == null) {
            kept.add(new Kept(method, params));
            return;
        }
        handOn(to);
        to.notification(method, params);
    }

    /** Hands the kept notifications to {@code to}, once, each on its own: one that fails costs only itself. */
    private void handOn(Incoming to) {
        if (kept == null) {
            return;
        }
        List<Kept> before = kept;
        kept = null;
        for (Kept k : before) {
            try {
                to.notification(k.method(), k.params());
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable e) {
                LOG.log(Level.WARNING, "Notification " + k.method() + " from Neovim's start failed", e);
            }
        }
    }

    @Override
    public Object request(String method, List<?> params) throws Exception {
        Incoming to = target;
        if (to == null) {
            throw new IllegalStateException("Eclipse is not ready for " + method + " yet");
        }
        handOn(to);
        return to.request(method, params);
    }
}
