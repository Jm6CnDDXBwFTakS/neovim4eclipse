// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link EnewEditors}: which untitled editors {@code :enew} opened. Inputs are plain objects here. */
class EnewEditorsTest {

    private final EnewEditors editors = new EnewEditors();
    private final Object enewInput = new Object();
    private final Object menuInput = new Object();

    @Test
    void anEditorNotOpenedByEnewIsNotListed() {
        assertFalse(editors.isOpenedByEnew(menuInput));
        assertFalse(editors.isOpenedByEnew(null));
    }

    /** The editor attached while the wizard ran: the first one asking is it, and only that one. */
    @Test
    void theFirstEditorAskingWhileTheWizardRunsIsTheNewOne() {
        editors.opening();
        assertTrue(editors.isOpenedByEnew(enewInput));
        assertFalse(editors.isOpenedByEnew(menuInput), "only the first one asking");
        editors.opened();
        assertTrue(editors.isOpenedByEnew(enewInput), "still listed");
    }

    /** The editor attaches after the wizard returned: {@code :enew} adds it first. */
    @Test
    void anEditorAddedAfterTheWizardIsListed() {
        editors.opening();
        editors.opened();
        assertFalse(editors.isOpenedByEnew(menuInput), "nothing taken once the wizard returned");
        editors.add(enewInput);
        assertTrue(editors.isOpenedByEnew(enewInput));
        assertFalse(editors.isOpenedByEnew(menuInput));
    }

    /** Adding the one already taken while the wizard ran changes nothing. */
    @Test
    void addingTwiceIsHarmless() {
        editors.opening();
        assertTrue(editors.isOpenedByEnew(enewInput));
        editors.opened();
        editors.add(enewInput);
        assertTrue(editors.isOpenedByEnew(enewInput));
    }
}
