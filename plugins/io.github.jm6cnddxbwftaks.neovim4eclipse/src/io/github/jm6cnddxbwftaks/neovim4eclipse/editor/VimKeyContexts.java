// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.eclipse.ui.contexts.IContextActivation;
import org.eclipse.ui.contexts.IContextService;

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
 * <h2>One switch, one update</h2>
 * Each activation or deactivation makes the workbench recompute which key bindings apply, and the
 * editor switches up to 27 contexts at once: all of them off when insert mode begins, back on at
 * {@code Esc}. One at a time, turning them on took 5 to 9 ms on Linux, at every {@code Esc}.
 * {@link IContextService#deferUpdates(boolean)} holds the recomputing back until the batch ends, so
 * the bindings are worked out once: 0.3 ms (measured 2026-11-01 in the UI tests' workbench, median
 * of 50 switches). The batch is always ended, even when an activation fails, or every later
 * context change would stay held back.
 *
 * <p>UI thread only.
 */
final class VimKeyContexts {

    private final List<IContextActivation> activations = new ArrayList<>();

    boolean isActive() {
        return !activations.isEmpty();
    }

    /**
     * Activates the normal-mode context and the contexts of the letters that go to Neovim.
     *
     * @param toNeovim whether a letter (lower case) is sent to Neovim
     */
    void activate(IContextService service, Predicate<Character> toNeovim) {
        if (isActive()) {
            return;
        }
        service.deferUpdates(true); // one recomputation of the bindings for the whole batch
        try {
            activations.add(service.activateContext(VimCtrlKeys.NORMAL_CONTEXT_ID));
            for (char letter : VimCtrlKeys.LETTERS.toCharArray()) {
                if (toNeovim.test(letter)) {
                    activations.add(service.activateContext(VimCtrlKeys.contextId(letter)));
                }
            }
        } finally {
            service.deferUpdates(false);
        }
    }

    /** Deactivates everything {@link #activate} activated. */
    void deactivate(IContextService service) {
        if (isActive()) {
            service.deferUpdates(true);
            try {
                service.deactivateContexts(activations);
                activations.clear();
            } finally {
                service.deferUpdates(false);
            }
        }
    }
}
