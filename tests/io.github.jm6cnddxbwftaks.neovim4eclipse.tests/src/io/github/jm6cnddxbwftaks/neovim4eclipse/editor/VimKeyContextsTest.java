// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.contexts.IContextActivation;
import org.eclipse.ui.contexts.IContextService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimCtrlKeys;

/** The Ctrl-letter contexts are really activated in the workbench, and only for chosen letters. */
class VimKeyContextsTest {

    private final IContextService service = PlatformUI.getWorkbench().getService(IContextService.class);
    private final VimKeyContexts contexts = new VimKeyContexts();

    @org.junit.jupiter.api.BeforeEach
    void detachEditors() {
        // An attached editor activates the same contexts; only this test's activations must count.
        EditorAttachManager.getInstance().detachQuietly();
    }

    @AfterEach
    void deactivate() {
        contexts.deactivate(service);
    }

    private Collection<?> active() {
        return service.getActiveContextIds();
    }

    @Test
    void activatesTheNormalContextAndTheChosenLetters() {
        contexts.apply(service, letter -> letter != 's');
        assertTrue(contexts.isActive());
        assertTrue(active().contains(VimCtrlKeys.NORMAL_CONTEXT_ID));
        assertTrue(active().contains(VimCtrlKeys.contextId('v')));
        assertFalse(active().contains(VimCtrlKeys.contextId('s')));
    }

    @Test
    void deactivateRemovesEverything() {
        contexts.apply(service, letter -> true);
        contexts.deactivate(service);
        assertFalse(contexts.isActive());
        assertFalse(active().contains(VimCtrlKeys.NORMAL_CONTEXT_ID));
        assertFalse(active().contains(VimCtrlKeys.contextId('v')));
    }

    @Test
    void everyLetterContextIsDefinedInPluginXml() {
        for (char letter : VimCtrlKeys.LETTERS.toCharArray()) {
            assertTrue(service.getContext(VimCtrlKeys.contextId(letter)).isDefined(), "context of Ctrl-" + letter);
        }
    }

    /**
     * The whole switch is one batch ({@code deferUpdates}), so the workbench recomputes its key
     * bindings once instead of once per context: 5 to 9 ms at every Esc before.
     */
    @Test
    void activatingIsOneBatch() {
        List<String> calls = new ArrayList<>();
        new VimKeyContexts().apply(recording(calls, -1), letter -> letter == 'v' || letter == 'r');
        assertEquals(List.of("deferUpdates true", "activateContext", "activateContext", "activateContext",
                "deferUpdates false"), calls);
    }

    @Test
    void deactivatingIsOneBatch() {
        List<String> calls = new ArrayList<>();
        IContextService fake = recording(calls, -1);
        VimKeyContexts batch = new VimKeyContexts();
        batch.apply(fake, letter -> true);
        calls.clear();
        batch.deactivate(fake);
        assertEquals(List.of("deferUpdates true", "deactivateContexts", "deferUpdates false"), calls);
        assertFalse(batch.isActive());
    }

    /**
     * Insert mode's letters replace normal mode's: only the letters whose two choices differ change,
     * in one batch. Here {@code v} goes off, {@code w} comes on, {@code r} and the parent stay.
     */
    @Test
    void switchingLettersChangesOnlyTheDifference() {
        List<String> calls = new ArrayList<>();
        IContextService fake = recording(calls, -1);
        VimKeyContexts batch = new VimKeyContexts();
        batch.apply(fake, letter -> letter == 'v' || letter == 'r');
        calls.clear();
        batch.apply(fake, letter -> letter == 'w' || letter == 'r');
        assertEquals(List.of("deferUpdates true", "deactivateContexts", "activateContext", "deferUpdates false"),
                calls);
        calls.clear();
        batch.apply(fake, letter -> letter == 'w' || letter == 'r');
        assertEquals(List.of(), calls, "nothing to change, no batch");
    }

    @Test
    void switchingLettersInTheWorkbench() {
        contexts.apply(service, letter -> letter == 'v');
        contexts.apply(service, letter -> letter == 'w');
        assertTrue(active().contains(VimCtrlKeys.NORMAL_CONTEXT_ID));
        assertTrue(active().contains(VimCtrlKeys.contextId('w')));
        assertFalse(active().contains(VimCtrlKeys.contextId('v')));
    }

    /** A failure must not leave the workbench holding back every later context change. */
    @Test
    void aFailingActivationStillEndsTheBatch() {
        List<String> calls = new ArrayList<>();
        // try/catch, not assertThrows: this fragment imports only org.junit.jupiter.api (MANIFEST.MF),
        // and assertThrows takes a type from org.junit.jupiter.api.function.
        try {
            new VimKeyContexts().apply(recording(calls, 2), letter -> true);
            fail("the third activation should have failed");
        } catch (IllegalStateException expected) {
            assertEquals("deferUpdates false", calls.get(calls.size() - 1));
        }
    }

    /**
     * An {@link IContextService} that only records the calls the batch makes. Its activation tokens
     * are dummies. The call numbered {@code failAt} (0-based, counting {@code activateContext} calls)
     * throws; -1 never does.
     */
    private static IContextService recording(List<String> calls, int failAt) {
        IContextActivation token = (IContextActivation) Proxy.newProxyInstance(
                VimKeyContextsTest.class.getClassLoader(), new Class<?>[] { IContextActivation.class },
                (proxy, method, args) -> null);
        int[] activations = { 0 };
        return (IContextService) Proxy.newProxyInstance(VimKeyContextsTest.class.getClassLoader(),
                new Class<?>[] { IContextService.class }, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "deferUpdates" -> calls.add("deferUpdates " + args[0]);
                        case "activateContext" -> {
                            calls.add("activateContext");
                            if (activations[0]++ == failAt) {
                                throw new IllegalStateException("activation fails");
                            }
                            return token;
                        }
                        case "deactivateContexts" -> calls.add("deactivateContexts");
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                    return null;
                });
    }
}
