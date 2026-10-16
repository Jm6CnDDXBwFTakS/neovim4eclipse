// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;

import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.contexts.IContextService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.VimCtrlKeys;

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
        contexts.activate(service, letter -> letter != 's');
        assertTrue(contexts.isActive());
        assertTrue(active().contains(VimCtrlKeys.NORMAL_CONTEXT_ID));
        assertTrue(active().contains(VimCtrlKeys.contextId('v')));
        assertFalse(active().contains(VimCtrlKeys.contextId('s')));
    }

    @Test
    void deactivateRemovesEverything() {
        contexts.activate(service, letter -> true);
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
}
