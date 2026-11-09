// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WelcomeNote;

/**
 * The text check that ends every UI test ({@link TextCheck}, run by {@code EndToEndTestBase}): that
 * it finds a difference and names it, and that a test left in insert mode is compared after its
 * {@code Esc}, when Neovim has the typed text.
 */
class TextCheckEndToEndTest extends EndToEndTestBase {

    /**
     * Eclipse mode: Eclipse's edits are not pushed until Neovim mode is back on, so the two copies
     * differ meanwhile, as after a drift. Neovim mode on again pushes the document: they agree.
     */
    @Test
    void aDifferenceIsFoundAndNamed() throws Exception {
        runCommand(WelcomeNote.TOGGLE_COMMAND_ID);
        document.replace(document.getLineOffset(1), 4, "BETA");
        String differences = TextCheck.differences(TIMEOUT_MS);
        assertNotNull(differences, "Eclipse has BETA, Neovim beta");
        assertTrue(differences.contains("line 2 is \"BETA\" in Eclipse, \"beta\" in Neovim"), differences);

        runCommand(WelcomeNote.TOGGLE_COMMAND_ID);
        settle();
        assertNull(TextCheck.differences(TIMEOUT_MS), "Neovim mode on again pushes the document");
    }

    /** Text Eclipse typed in insert mode reaches Neovim at Esc, which the check presses first. */
    @Test
    void aTestLeftInInsertModeIsComparedAfterItsEsc() {
        type(widget, "iX");
        waitUntilInsert();
        assertNull(TextCheck.differences(TIMEOUT_MS));
        assertEquals("n", session.getMode());
        assertEquals("Xalpha", SessionProbe.lua(session, "return vim.api.nvim_get_current_line()"));
    }

    private void waitUntilInsert() {
        UiTestSupport.waitUntil("insert mode", TIMEOUT_MS, () -> "i".equals(session.getMode()));
        expectText("Xalpha\nbeta\ngamma\n");
    }
}
