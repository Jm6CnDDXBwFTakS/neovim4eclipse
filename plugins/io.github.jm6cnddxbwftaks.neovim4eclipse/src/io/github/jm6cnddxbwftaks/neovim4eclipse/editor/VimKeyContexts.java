// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.Collection;
import java.util.function.Function;
import java.util.function.Predicate;

import org.eclipse.ui.contexts.IContextActivation;
import org.eclipse.ui.contexts.IContextService;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.KeyContextBatch;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimCtrlKeys;

/**
 * Turns the plug-in's key bindings on and off by activating <em>contexts</em>.
 *
 * <h2>Contexts in Eclipse</h2>
 * A context (extension point {@code org.eclipse.ui.contexts}) is a named situation such as "editing
 * text" or "editing Java". Every key binding belongs to one context and only works while that
 * context is active. Code activates a context through the workbench's {@link IContextService} and
 * gets back an {@link IContextActivation}, a token that it later hands back to deactivate it.
 *
 * <p>Activating {@link VimCtrlKeys#NORMAL_CONTEXT_ID} says "Neovim owns the keys now"; activating a
 * letter's context as well ({@link VimCtrlKeys#contextId}) switches on that letter's binding. See
 * {@link VimCtrlKeys} for the context tree.
 *
 * <h2>Two sets of letters</h2>
 * The Ctrl Keys preference page has a Normal and an Insert choice per letter, so a mode change
 * between insert and normal mode switches the letters whose two choices differ, and only those:
 * {@link #apply} compares the letters wanted now with the ones active and changes the difference
 * ({@link KeyContextBatch}, which holds that decision and the batching without Eclipse).
 * Example: Ctrl+W ticked for insert mode only, Ctrl+R ticked for both: at {@code i}, Ctrl+W's
 * context is activated and Ctrl+R's stays as it is.
 *
 * <h2>One switch, one update</h2>
 * Each activation or deactivation makes the workbench recompute which key bindings apply, and the
 * editor switches up to 27 contexts at once (all of them at focus changes). One at a time, turning
 * them on took 5 to 9 ms on Linux.
 * {@link IContextService#deferUpdates(boolean)} holds the recomputing back until the batch ends, so
 * the bindings are worked out once: 0.3 ms (measured 2026-11-01 in the UI tests' workbench, median
 * of 50 switches). The batch is always ended, even when an activation fails, or every later
 * context change would stay held back.
 *
 * <p>UI thread only.
 */
final class VimKeyContexts {

    /** Which contexts are active, and the batching (tested in tests/unit, KeyContextBatchTest). */
    private final KeyContextBatch<IContextActivation> batch = new KeyContextBatch<>();

    boolean isActive() {
        return batch.isActive();
    }

    /**
     * Makes the active contexts the normal-mode parent and the letter contexts of the letters
     * {@code toNeovim} accepts ({@link VimCtrlKeys#contextId}).
     *
     * @param toNeovim whether a letter (lower case) is sent to Neovim
     */
    void apply(IContextService service, Predicate<Character> toNeovim) {
        apply(service, toNeovim, VimCtrlKeys::contextId);
    }

    /**
     * Makes the active contexts the normal-mode parent and, for each letter {@code toNeovim} accepts,
     * the context {@code contextOf} names (its letter context, or in an insert Eclipse types the
     * Word Completion context of {@code Ctrl-N}, {@code Ctrl-P}), changing only the difference to what
     * is active now, in one batch ({@link KeyContextBatch#apply}).
     */
    void apply(IContextService service, Predicate<Character> toNeovim, Function<Character, String> contextOf) {
        batch.apply(switchOf(service), KeyContextBatch.wanted(toNeovim, contextOf));
    }

    /** Deactivates every context {@link #apply} activated. */
    void deactivate(IContextService service) {
        batch.deactivate(switchOf(service));
    }

    /** Eclipse's context service as the batch's {@link KeyContextBatch.Switch}: a thin adapter. */
    private static KeyContextBatch.Switch<IContextActivation> switchOf(IContextService service) {
        return new KeyContextBatch.Switch<>() {
            @Override
            public void deferUpdates(boolean defer) {
                service.deferUpdates(defer);
            }

            @Override
            public IContextActivation activate(String contextId) {
                return service.activateContext(contextId);
            }

            @Override
            public void deactivate(Collection<IContextActivation> tokens) {
                service.deactivateContexts(tokens);
            }
        };
    }
}
