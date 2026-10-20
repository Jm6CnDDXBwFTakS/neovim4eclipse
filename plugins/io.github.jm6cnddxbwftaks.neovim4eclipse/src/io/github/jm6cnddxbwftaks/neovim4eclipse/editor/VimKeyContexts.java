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
 * {@link VimCtrlKeys} for the context tree. UI thread only.
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
        activations.add(service.activateContext(VimCtrlKeys.NORMAL_CONTEXT_ID));
        for (char letter : VimCtrlKeys.LETTERS.toCharArray()) {
            if (toNeovim.test(letter)) {
                activations.add(service.activateContext(VimCtrlKeys.contextId(letter)));
            }
        }
    }

    /** Deactivates everything {@link #activate} activated. */
    void deactivate(IContextService service) {
        if (isActive()) {
            service.deactivateContexts(activations);
            activations.clear();
        }
    }
}
