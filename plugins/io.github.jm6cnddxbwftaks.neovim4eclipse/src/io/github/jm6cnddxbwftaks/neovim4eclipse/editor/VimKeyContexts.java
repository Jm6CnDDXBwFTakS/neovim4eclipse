// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <h2>Two sets of letters</h2>
 * The Ctrl Keys preference page has a Normal and an Insert choice per letter, so a mode change
 * between insert and normal mode switches the letters whose two choices differ, and only those:
 * {@link #apply} compares the letters wanted now with the ones active and changes the difference.
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

    /** The active contexts by id: the normal-mode parent and the letters'. */
    private final Map<String, IContextActivation> activations = new HashMap<>();

    boolean isActive() {
        return !activations.isEmpty();
    }

    /**
     * Makes the active contexts the normal-mode parent and those of the letters {@code toNeovim}
     * accepts, changing only the difference to what is active now, in one batch.
     *
     * @param toNeovim whether a letter (lower case) is sent to Neovim
     */
    void apply(IContextService service, Predicate<Character> toNeovim) {
        Set<String> wanted = new HashSet<>();
        wanted.add(VimCtrlKeys.NORMAL_CONTEXT_ID);
        for (char letter : VimCtrlKeys.LETTERS.toCharArray()) {
            if (toNeovim.test(letter)) {
                wanted.add(VimCtrlKeys.contextId(letter));
            }
        }
        if (wanted.equals(activations.keySet())) {
            return;
        }
        service.deferUpdates(true); // one recomputation of the bindings for the whole batch
        try {
            List<IContextActivation> off = new ArrayList<>();
            activations.entrySet().removeIf(e -> !wanted.contains(e.getKey()) && off.add(e.getValue()));
            if (!off.isEmpty()) {
                service.deactivateContexts(off);
            }
            for (String id : wanted) {
                if (!activations.containsKey(id)) {
                    activations.put(id, service.activateContext(id));
                }
            }
        } finally {
            service.deferUpdates(false);
        }
    }

    /** Deactivates every context {@link #apply} activated. */
    void deactivate(IContextService service) {
        if (isActive()) {
            service.deferUpdates(true);
            try {
                service.deactivateContexts(List.copyOf(activations.values()));
                activations.clear();
            } finally {
                service.deferUpdates(false);
            }
        }
    }
}
