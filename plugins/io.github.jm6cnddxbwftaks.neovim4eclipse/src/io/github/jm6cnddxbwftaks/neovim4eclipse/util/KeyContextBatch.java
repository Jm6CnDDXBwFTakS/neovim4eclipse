// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Which of the plug-in's key contexts are active, and how a change of them is made: only the
 * difference, in one batch. The decisions of {@code editor/VimKeyContexts}, without Eclipse, so
 * that {@code tests/unit} tests them with a fake {@link Switch}; {@code VimKeyContexts} passes
 * Eclipse's {@code IContextService} in its place. Why contexts, and why a batch: see that class.
 *
 * <p>Example: Ctrl+V and Ctrl+R on in normal mode, Ctrl+W and Ctrl+R in insert mode. At {@code i},
 * {@link #apply} deactivates V's context and activates W's, in one batch; R's context and the
 * normal-mode parent stay as they are. Applying the same letters again changes nothing and starts
 * no batch.
 *
 * <p>UI thread only, as the contexts it switches.
 *
 * @param <T> the token an activation returns, which a deactivation hands back
 *            ({@code IContextActivation} in Eclipse)
 */
public final class KeyContextBatch<T> {

    /**
     * What {@link KeyContextBatch} needs of Eclipse's context service: hold back the key-binding
     * update while a batch runs, activate a context, deactivate several.
     */
    public interface Switch<T> {
        /** {@code true} holds back the update until a {@code false}, which makes it once. */
        void deferUpdates(boolean defer);

        T activate(String contextId);

        void deactivate(Collection<T> tokens);
    }

    /** The active contexts by id: the normal-mode parent and the letters'. */
    private final Map<String, T> activations = new HashMap<>();

    public boolean isActive() {
        return !activations.isEmpty();
    }

    /** The ids of the active contexts (a copy), for a test or a log line to show. */
    public Set<String> activeIds() {
        return Set.copyOf(activations.keySet());
    }

    /**
     * The contexts that should be active: the normal-mode parent ({@link VimCtrlKeys#NORMAL_CONTEXT_ID})
     * and, for each letter {@code toNeovim} accepts, the context {@code contextOf} names (its letter
     * context, {@link VimCtrlKeys#contextId}; in an insert Eclipse types, the Word Completion
     * context for {@code Ctrl-N} and {@code Ctrl-P}).
     */
    public static Set<String> wanted(Predicate<Character> toNeovim, Function<Character, String> contextOf) {
        Set<String> wanted = new HashSet<>();
        wanted.add(VimCtrlKeys.NORMAL_CONTEXT_ID);
        for (char letter : VimCtrlKeys.LETTERS.toCharArray()) {
            if (toNeovim.test(letter)) {
                wanted.add(contextOf.apply(letter));
            }
        }
        return wanted;
    }

    /**
     * Makes {@code wanted} the active contexts, changing only the difference to what is active now,
     * in one batch; nothing at all when they are active already. The batch is ended even when an
     * activation fails: otherwise every later context change would stay held back.
     */
    public void apply(Switch<T> service, Set<String> wanted) {
        if (wanted.equals(activations.keySet())) {
            return;
        }
        service.deferUpdates(true); // one recomputation of the bindings for the whole batch
        try {
            List<T> off = new ArrayList<>();
            activations.entrySet().removeIf(e -> !wanted.contains(e.getKey()) && off.add(e.getValue()));
            if (!off.isEmpty()) {
                service.deactivate(off);
            }
            for (String id : wanted) {
                if (!activations.containsKey(id)) {
                    activations.put(id, service.activate(id));
                }
            }
        } finally {
            service.deferUpdates(false);
        }
    }

    /** Deactivates every context {@link #apply} activated, in one batch. */
    public void deactivate(Switch<T> service) {
        if (isActive()) {
            service.deferUpdates(true);
            try {
                service.deactivate(List.copyOf(activations.values()));
                activations.clear();
            } finally {
                service.deferUpdates(false);
            }
        }
    }
}
