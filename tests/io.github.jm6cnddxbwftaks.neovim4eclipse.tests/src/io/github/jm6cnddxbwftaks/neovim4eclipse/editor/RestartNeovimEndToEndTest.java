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
 * editor's text. What the question says is lost (a {@code :let} variable) is gone, and what it says
 * comes back from ShaDa (a register) is there. The test Neovim's ShaDa file is under
 * {@code target/neovim-home/state} (this module's {@code pom.xml}).
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
        eval(session, "execute('let g:set_by_hand = 1')");

        Activator.getDefault().restartNeovim();

        NeovimSession fresh = Activator.getDefault().currentSession();
        assertNotSame(session, fresh, "a new Neovim");
        assertFalse(session.isAlive(), "the old one is stopped");
        waitUntil("Neovim attached again", TIMEOUT_MS, () -> {
            NeovimEditorController c = EditorAttachManager.getInstance().getActiveController();
            return c != null && c.getEditor() == editor && !c.isClosed();
        });
        assertEquals("0", eval(fresh, "exists('g:set_by_hand')"), "what was set by hand is gone");
        assertEquals("from the old Neovim", eval(fresh, "getreg('a')"), "registers come back from ShaDa");
        waitUntil("the editor's text in the new buffer", TIMEOUT_MS,
                () -> "alpha".equals(eval(fresh, "getline(1)")),
                () -> "; line 1 is " + eval(fresh, "getline(1)"));
        assertEquals("alpha\nbeta\ngamma\n", document.get(), "Eclipse's text is kept");
    }

    /**
     * The editor does not scroll. The new Neovim's buffer reported its cursor on line 1 before the
     * cursor was placed at the caret; the caret went there and back, and Eclipse left the caret's
     * line at the bottom of the editor (DocumentPush, "The caret stays put meanwhile").
     */
    @Test
    void aRestartLeavesTheCaretAndTheScrollPositionAlone() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 300; i++) {
            text.append("line ").append(i).append('\n');
        }
        document.set(text.toString());
        settle();
        widget.setCaretOffset(widget.getOffsetAtLine(150));
        widget.setTopIndex(140);
        settle();
        List<Integer> caretLines = new java.util.concurrent.CopyOnWriteArrayList<>();
        widget.addCaretListener(e -> caretLines.add(widget.getLineAtOffset(e.caretOffset)));

        Activator.getDefault().restartNeovim();
        NeovimSession fresh = Activator.getDefault().currentSession();
        waitUntil("the new Neovim's cursor on the caret's line", TIMEOUT_MS,
                () -> "151".equals(eval(fresh, "line('.')")));
        UiTestSupport.settle(fresh, TIMEOUT_MS);

        assertEquals(150, widget.getLineAtOffset(widget.getCaretOffset()), "the caret's line (0-based)");
        assertEquals(140, widget.getTopIndex(), "the editor did not scroll");
        assertFalse(caretLines.contains(0), "the caret never went to line 1: " + caretLines);
    }
}
