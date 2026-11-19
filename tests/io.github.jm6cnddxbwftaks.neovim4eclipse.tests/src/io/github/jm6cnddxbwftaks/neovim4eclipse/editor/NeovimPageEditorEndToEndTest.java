// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.keys.IBindingService;
import org.eclipse.ui.part.FileEditorInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PageBuffer;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PageKind;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PlatformUtil;

/**
 * The page editor in a real workbench: one per kind, showing its content in a window of the
 * editing Neovim, reused for the same kind, refusing a file, closed by {@code q}, and its leftovers
 * closed at the start ({@link NeovimPageEditor#closeLeftovers}).
 */
class NeovimPageEditorEndToEndTest extends EndToEndTestBase {

    @AfterEach
    void closeThePages() {
        List<IEditorReference> pages = new ArrayList<>();
        for (IEditorReference reference : page().getEditorReferences()) {
            if (NeovimPageEditor.ID.equals(reference.getId())) {
                pages.add(reference);
            }
        }
        page().closeEditors(pages.toArray(IEditorReference[]::new), false);
        settle();
    }

    private NeovimPageEditor open(PageKind kind) throws Exception {
        return (NeovimPageEditor) page().openEditor(new NeovimPageInput(kind), NeovimPageEditor.ID, true);
    }

    private static String rowsOf(NeovimPageEditor page) {
        GridSnapshot grid = page.canvasForTests().grid();
        if (grid == null) {
            return "";
        }
        StringBuilder rows = new StringBuilder();
        for (int r = 0; r < grid.height(); r++) {
            rows.append(grid.row(r).stripTrailing()).append('\n');
        }
        return rows.toString();
    }

    /**
     * The help buffer of {@code help-context}, made in the editing Neovim without the window net
     * ({@code :noautocmd}), so that this test hands it to the editor itself.
     */
    private PageBuffer helpContext() {
        Object answer = SessionProbe.lua(session, """
                vim.cmd('noautocmd help help-context')
                local buf, line = vim.api.nvim_get_current_buf(), vim.fn.line('.')
                vim.bo[buf].bufhidden = 'hide'
                vim.cmd('noautocmd helpclose')
                return { buf, line }""");
        List<?> pair = (List<?>) answer;
        return new PageBuffer(PageBuffer.HELP, ((Number) pair.get(0)).intValue(), ((Number) pair.get(1)).intValue());
    }

    @Test
    void aHelpPageShowsInTheHelpEditorWhichTheSameKindReuses() throws Exception {
        NeovimPageEditor help = open(PageKind.HELP);
        assertEquals("Neovim: help", help.getPartName());
        help.show(helpContext(), 0x000000, 0xFFFFFF);
        waitUntil("the page", TIMEOUT_MS, () -> {
            settle();
            return rowsOf(help).contains("help-context");
        }, () -> "; it shows " + rowsOf(help));
        assertSame(help, open(PageKind.HELP), "the same kind: the same editor");
        assertTrue(open(PageKind.MESSAGES) != help, "another kind: another editor");
        assertTrue(EditorAttachManager.textEditorOf(help) == null, "no text editor: Neovim is not attached to it");
    }

    @Test
    void qClosesTheEditor() throws Exception {
        NeovimPageEditor help = open(PageKind.HELP);
        help.show(helpContext(), 0x000000, 0xFFFFFF);
        waitUntil("the page", TIMEOUT_MS, () -> {
            settle();
            return rowsOf(help).contains("help-context");
        });
        UiTestSupport.requireKeyboardFocus(help.canvasForTests(), TIMEOUT_MS);
        Event q = new Event();
        q.keyCode = 'q';
        q.character = 'q';
        q.doit = true;
        help.canvasForTests().notifyListeners(SWT.KeyDown, q);
        waitUntil("the editor closed", TIMEOUT_MS, () -> {
            settle();
            return page().findEditor(new NeovimPageInput(PageKind.HELP)) == null;
        });
    }

    /** Ctrl+W in a page editor is Neovim's, not Eclipse's Close Editor (on macOS Close is Cmd+W). */
    @Test
    void ctrlWInThePageEditorIsNeovims() throws Exception {
        NeovimPageEditor help = open(PageKind.HELP);
        UiTestSupport.requireKeyboardFocus(help.canvasForTests(), TIMEOUT_MS);
        Binding binding = PlatformUI.getWorkbench().getService(IBindingService.class)
                .getPerfectMatch(KeySequence.getInstance(PlatformUtil.isMac() ? "CTRL+W" : "M1+W"));
        assertNotNull(binding);
        assertEquals("io.github.jm6cnddxbwftaks.neovim4eclipse.commands.ctrl.w", binding.getParameterizedCommand().getId());
    }

    /** Opened on a file (Open With, Other), the page editor refuses: Eclipse shows its error editor. */
    @Test
    void openedOnAFileItRefuses() throws Exception {
        IFile file = createFile("page.txt", "text\n");
        IEditorPart opened = IDE.openEditor(page(), new FileEditorInput(file), NeovimPageEditor.ID);
        assertFalse(opened instanceof NeovimPageEditor, "not a page editor: " + opened);
        page().closeEditor(opened, false);
    }

    /**
     * The start's sweep closes every page editor and nothing else. Live editors only: the
     * placeholder tab a real restart brings back is the manual card pg-restart's, since the tests'
     * Eclipse saves its layout differently.
     */
    @Test
    void theStartsSweepClosesThePageEditors() throws Exception {
        open(PageKind.HELP);
        open(PageKind.MESSAGES);
        settle();
        NeovimPageEditor.closeLeftovers(PlatformUI.getWorkbench());
        settle();
        assertEquals(null, page().findEditor(new NeovimPageInput(PageKind.HELP)));
        assertEquals(null, page().findEditor(new NeovimPageInput(PageKind.MESSAGES)));
        assertTrue(page().findEditor(editor.getEditorInput()) == editor, "the code editor stays");
    }

    /**
     * A file Eclipse would open in the page editor (Open With remembers the editor once picked)
     * opens in the Text Editor instead when Neovim opens it ({@code :e}, {@code gf}).
     */
    @Test
    void neovimNeverOpensAFileInThePageEditor() {
        IEditorDescriptor page = PlatformUI.getWorkbench().getEditorRegistry().findEditor(NeovimPageEditor.ID);
        assertNotNull(page);
        assertEquals(NewFiles.TEXT_EDITOR, NewFiles.internalOr(page, "notes.txt"));
    }
}
