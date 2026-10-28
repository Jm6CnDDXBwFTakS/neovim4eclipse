// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;

/**
 * <em>Neovim → Restart Neovim...</em> ({@link RestartNeovimHandler}): the question comes first, and
 * Cancel keeps the running Neovim; a restart gives a new Neovim, attached to the editor, with the
 * editor's text and without the old Neovim's registers.
 */
class RestartNeovimEndToEndTest extends EndToEndTestBase {

    /**
     * Asks {@code target} for an expression's value. Waits with the event loop running, not with a
     * blocking {@code get()}: right after a restart the new Neovim's attach still has work for the UI
     * thread queued ahead of the answer, and a blocked UI thread would never let it through.
     */
    private static String eval(NeovimSession target, String expression) {
        CompletableFuture<String> answer = new CompletableFuture<>();
        target.evalAsync(expression, answer::complete);
        waitUntil("Neovim's answer to " + expression, TIMEOUT_MS, answer::isDone);
        return answer.join();
    }

    @Test
    void theCommandAsksFirstAndCancelKeepsNeovim() throws Exception {
        // Closing the question (as Esc would) is Cancel.
        List<String> dialogs = closeModalDialogsWhile(widget);

        runCommand("io.github.jm6cnddxbwftaks.neovim4eclipse.commands.restart");

        assertEquals(List.of(RestartNeovimHandler.TITLE), dialogs);
        assertSame(session, Activator.getDefault().currentSession(), "the same Neovim");
        assertTrue(session.isAlive());
    }

    @Test
    void aRestartStartsANewNeovimAttachedToTheEditor() {
        eval(session, "setreg('a', 'from the old Neovim')");

        Activator.getDefault().restartNeovim();

        NeovimSession fresh = Activator.getDefault().currentSession();
        assertNotSame(session, fresh, "a new Neovim");
        assertFalse(session.isAlive(), "the old one is stopped");
        waitUntil("Neovim attached again", TIMEOUT_MS, () -> {
            NeovimEditorController c = EditorAttachManager.getInstance().getActiveController();
            return c != null && c.getEditor() == editor && !c.isClosed();
        });
        assertEquals("", eval(fresh, "getreg('a')"), "the old registers are gone");
        waitUntil("the editor's text in the new buffer", TIMEOUT_MS,
                () -> "alpha".equals(eval(fresh, "getline(1)")),
                () -> "; line 1 is " + eval(fresh, "getline(1)"));
        assertEquals("alpha\nbeta\ngamma\n", document.get(), "Eclipse's text is kept");
    }
}
