// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IFile;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;
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

    /**
     * Get Neovim's swap ({@code NeovimInstaller.SwapGate}) asks the Activator, at the moment of the
     * swap, whether the running Neovim comes from the folder being replaced: then the folder stays,
     * and the new Neovim waits for this restart.
     */
    @Test
    void anInstallLeavesTheFolderTheRunningNeovimComesFrom() {
        Path program = Path.of(session.program());
        assertTrue(program.isAbsolute(), program.toString());
        Path home = program.getParent().getParent(); // <home>/bin/nvim
        AtomicBoolean swapped = new AtomicBoolean();
        assertFalse(Activator.getDefault().swapUnlessRunningFrom(home, () -> {
            swapped.set(true);
            return true;
        }));
        assertFalse(swapped.get(), "the folder Neovim runs from is not swapped");
        assertTrue(Activator.getDefault().swapUnlessRunningFrom(home.resolveSibling("elsewhere"), () -> true),
                "another folder is");
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
     * The new Neovim learns the project folders at its first attach, as the first one did, so
     * {@code gf} onto a closed project file opens its editor instead of Neovim reading the file
     * itself (with its filetype, and the user's language servers). The manual test "After Restart
     * Neovim". Example: test.txt's first line is {@code alpha}, and the project has a file named
     * {@code alpha}; {@code gf} there opens that file's editor.
     */
    @Test
    void aRestartedNeovimKnowsTheProjectFolders() throws Exception {
        IFile alpha = createFile("alpha", "named on line 1\n");
        String path = alpha.getLocation().toOSString().replace("'", "''");
        try {
            Activator.getDefault().restartNeovim();
            NeovimSession fresh = Activator.getDefault().currentSession();
            attachedTo(editor, "test.txt in the new Neovim");
            waitUntil("the new Neovim to know the project", TIMEOUT_MS, () -> "1".equals(eval(fresh,
                    "luaeval(\"require('neovim4eclipse.project_files').inside(_A) and 1 or 0\", '" + path + "')")));

            fresh.inputAsync("gg0gf");

            waitUntil("alpha's editor", TIMEOUT_MS, () -> page().getActiveEditor() != null
                    && page().getActiveEditor().getEditorInput() instanceof IFileEditorInput input
                    && input.getFile().equals(alpha));
            attachedTo((ITextEditor) page().getActiveEditor(), "alpha, opened by the new Neovim");
        } finally {
            IEditorPart opened = page().findEditor(new FileEditorInput(alpha));
            if (opened != null) {
                page().closeEditor(opened, false);
            }
        }
    }

    /**
     * The windows of the old Neovim are gone with it; each editor gets a new one in the new Neovim at
     * its next attach (docs/design/WINDOWS.md, the lazy rebuild): two editors, two windows, each in
     * its own tab besides the home tab.
     */
    /** Waits until Neovim is attached to {@code target} (not with waitForAttach: it settles the old session). */
    private static void attachedTo(ITextEditor target, String what) {
        waitUntil(what, TIMEOUT_MS, () -> {
            NeovimEditorController c = EditorAttachManager.getInstance().getActiveController();
            return c != null && c.getEditor() == target && !c.isClosed();
        });
    }

    @Test
    void afterARestartEachEditorGetsANewWindow() throws Exception {
        ITextEditor other = openOtherFile();
        Activator.getDefault().restartNeovim();
        NeovimSession fresh = Activator.getDefault().currentSession();
        attachedTo(other, "other.txt in the new Neovim");
        String otherWindow = eval(fresh, "win_getid()");
        page().activate(editor);
        attachedTo(editor, "test.txt in the new Neovim");
        waitUntil("test.txt's window current", TIMEOUT_MS, () -> !otherWindow.equals(eval(fresh, "win_getid()")));
        assertNotEquals(otherWindow, eval(fresh, "win_getid()"));
        assertEquals("3", eval(fresh, "tabpagenr('$')"), "the home tab and one per editor");
        page().closeEditor(other, false);
    }

    /**
     * The editor does not scroll. The new Neovim's buffer reported its cursor on line 1 before the
     * cursor was placed at the caret; the caret went there and back, and Eclipse left the caret's
     * line at the bottom of the editor (DocumentPush, "The caret stays put meanwhile").
     *
     * <p>The old Neovim must agree on the view before the restart: the caret's move makes it jump to
     * line 151 and centre it (top line 140, Eclipse's 139), and the editor's scroll then makes it 141.
     * Waiting for the UI alone sometimes left the first answer applied last, so the editor was at 139
     * before the restart, which kept it there (found 2026-10-31: about 1 run in 40 on two cores).
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
        waitUntil("the old Neovim's cursor on the caret's line", TIMEOUT_MS,
                () -> "151".equals(eval(session, "line('.')")));
        UiTestSupport.settle(session, TIMEOUT_MS); // its jump's top line applied before the scroll below
        widget.setTopIndex(140);
        waitUntil("the old Neovim and the editor at the same top line", TIMEOUT_MS,
                () -> widget.getTopIndex() == 140 && "141".equals(eval(session, "line('w0')")),
                () -> "; the editor's " + widget.getTopIndex() + ", Neovim's " + eval(session, "line('w0')"));
        UiTestSupport.settle(session, TIMEOUT_MS);
        assertEquals(140, widget.getTopIndex(), "the view before the restart");
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
