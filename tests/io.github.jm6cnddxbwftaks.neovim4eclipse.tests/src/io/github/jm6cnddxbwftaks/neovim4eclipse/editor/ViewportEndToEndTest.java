// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.escape;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.texteditor.ITextEditorActionDefinitionIds;
import org.eclipse.ui.texteditor.ITextEditorExtension6;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;

/**
 * Neovim's window follows the editor (docs/design/VIEWPORT.md, commits 1 and 2): its size is the
 * text area's, so {@code L} is the editor's last line, {@code 'wrap'} is the editor's word wrap, both
 * ways, and the editor scrolls where Neovim's window scrolls. The pieces are tested alone in {@code ViewportFollowerTest}, {@code viewport_spec.lua} and
 * {@code NeovimViewportIntegrationTest}; this is the wiring.
 */
class ViewportEndToEndTest extends EndToEndTestBase {

    private Point shellSize;

    @AfterEach
    void restore() throws Exception {
        if (shellSize != null) {
            widget.getShell().setSize(shellSize);
        }
        if (((ITextEditorExtension6) editor).isWordWrapEnabled()) {
            ((ITextEditorExtension6) editor).setWordWrap(false);
        }
    }

    /** The editor's text area in cells, {@code [columns, rows]}, as the plug-in measures it. */
    private int[] editorCells() {
        return ViewportFollower.cells(widget, ViewportFollower.averageCharWidth(widget));
    }

    /** Neovim's window text area, {@code [columns, rows]}. */
    private int[] neovimWindow() {
        List<?> size = (List<?>) SessionProbe.lua(session, "local w = vim.api.nvim_get_current_win() "
                + "return { vim.fn.winwidth(w) - vim.fn.getwininfo(w)[1].textoff, vim.fn.winheight(w) }");
        return new int[] { ((Number) size.get(0)).intValue(), ((Number) size.get(1)).intValue() };
    }

    private boolean neovimMatchesTheEditor() {
        int[] cells = editorCells();
        int[] window = neovimWindow();
        return cells[0] == window[0] && cells[1] == window[1];
    }

    /** A document of 300 lines, "line 1" to "line 300", with Neovim's window the editor's size. */
    private void longDocument() {
        document.set(IntStream.rangeClosed(1, 300).mapToObj(i -> "line " + i).collect(Collectors.joining("\n", "", "\n")));
        settle();
        waitUntil("Neovim's window the editor's size", TIMEOUT_MS, this::neovimMatchesTheEditor, this::sizes);
    }

    /** The editor's top line, 1-based, in document lines. */
    private int editorTop() {
        return editor.getAdapter(ITextViewer.class).getTopIndex() + 1;
    }

    private int neovim(String lineExpr) {
        return ((Number) SessionProbe.lua(session, "return vim.fn.line('" + lineExpr + "')")).intValue();
    }

    private boolean topsAgree() {
        return editorTop() == neovim("w0");
    }

    private String tops() {
        return "; editor's top line " + editorTop() + ", Neovim's " + neovim("w0") + ", cursor " + neovim(".");
    }

    private String sizes() {
        int[] cells = editorCells();
        int[] window = neovimWindow();
        return "; editor " + cells[0] + "×" + cells[1] + ", Neovim's window " + window[0] + "×" + window[1];
    }

    @Test
    void neovimsWindowIsTheEditorsSizeAndFollowsAResize() {
        waitUntil("Neovim's window the editor's size", TIMEOUT_MS, this::neovimMatchesTheEditor, this::sizes);

        Shell shell = widget.getShell();
        shellSize = shell.getSize();
        int rowsBefore = editorCells()[1];
        shell.setSize(shellSize.x - 120, shellSize.y - 150);
        shell.layout(true, true);
        waitUntil("the smaller editor", TIMEOUT_MS, () -> editorCells()[1] < rowsBefore);
        waitUntil("Neovim's window resized with it", TIMEOUT_MS, this::neovimMatchesTheEditor, this::sizes);
    }

    @Test
    void lGoesToTheEditorsLastFullLine() {
        String text = IntStream.rangeClosed(1, 300).mapToObj(i -> "line " + i).collect(Collectors.joining("\n", "", "\n"));
        document.set(text);
        settle();
        waitUntil("Neovim's window the editor's size", TIMEOUT_MS, this::neovimMatchesTheEditor, this::sizes);
        int rows = editorCells()[1];
        session.inputAsync("gg");
        session.inputAsync("L");
        int scrolloff = ((Number) SessionProbe.lua(session, "return vim.wo.scrolloff")).intValue();
        int expected = rows - scrolloff;
        waitUntil("L on line " + expected + " (" + rows + " rows, scrolloff " + scrolloff + ")", TIMEOUT_MS,
                () -> ((Number) SessionProbe.lua(session, "return vim.fn.line('.')")).intValue() == expected,
                () -> "; on line " + SessionProbe.lua(session, "return vim.fn.line('.')"));
    }

    @Test
    void aJumpScrollsTheEditorToNeovimsTopLine() {
        longDocument();
        session.inputAsync("1");
        session.inputAsync("5");
        session.inputAsync("0");
        session.inputAsync("G");
        waitUntil("the cursor on line 150", TIMEOUT_MS, () -> cursorLine() == 150);
        waitUntil("the editor scrolled as Neovim's window", TIMEOUT_MS, this::topsAgree, this::tops);
        assertTrue(editorTop() > 1);
    }

    @Test
    void ctrlEScrollsTheEditorOneLine() {
        longDocument();
        session.inputAsync("<C-e>");
        waitUntil("line 2 at the editor's top", TIMEOUT_MS, () -> editorTop() == 2, this::tops);
        waitUntil("both agree", TIMEOUT_MS, this::topsAgree, this::tops);
    }

    /**
     * What a manual test found before commit 2 (XFCE, a maximized window restored): the window
     * shrinks, Neovim scrolls to keep its cursor on the screen, the editor did not, and {@code L}
     * went to a line below the editor's last one, which then scrolled.
     */
    @Test
    void afterTheEditorShrinksLStillGoesToALineItShows() {
        longDocument();
        session.inputAsync("L"); // the cursor on the last line of the larger editor
        settle();
        Shell shell = widget.getShell();
        shellSize = shell.getSize();
        int rowsBefore = editorCells()[1];
        shell.setSize(shellSize.x, shellSize.y - 150);
        shell.layout(true, true);
        waitUntil("the smaller editor", TIMEOUT_MS, () -> editorCells()[1] < rowsBefore);
        waitUntil("Neovim's window resized with it", TIMEOUT_MS, this::neovimMatchesTheEditor, this::sizes);
        waitUntil("the editor scrolled as Neovim's window did", TIMEOUT_MS, this::topsAgree, this::tops);
        int top = editorTop();
        session.inputAsync("L");
        settle();
        int rows = editorCells()[1];
        waitUntil("L on the editor's last line", TIMEOUT_MS, () -> cursorLine() == top + rows - 1, this::tops);
        assertEquals(top, editorTop(), "the editor did not scroll for L");
    }

    /**
     * The scroll bar (or the wheel, or Eclipse revealing a search hit) scrolls the editor: Neovim's
     * window goes there too and drags its cursor along, so the next key does not scroll the editor
     * back to Neovim's old view, and {@code H} is the editor's top line.
     */
    @Test
    void aScrollInTheEditorIsKeptByTheNextKey() {
        longDocument();
        session.inputAsync("g");
        session.inputAsync("g");
        settle();
        editor.getAdapter(ITextViewer.class).setTopIndex(100); // line 101 at the top, as the scroll bar
        waitUntil("Neovim's window scrolled with the editor", TIMEOUT_MS, () -> neovim("w0") == 101, this::tops);
        assertEquals(101, cursorLine(), "the cursor dragged into the view, as Ctrl-E drags it");
        session.inputAsync("j");
        waitUntil("j", TIMEOUT_MS, () -> cursorLine() == 102);
        settle();
        assertEquals(101, editorTop(), "the editor stays where it was scrolled");
        session.inputAsync("L");
        int rows = editorCells()[1];
        waitUntil("L on the editor's last line", TIMEOUT_MS, () -> cursorLine() == 100 + rows, this::tops);
        assertEquals(101, editorTop());
    }

    /**
     * Decision D6: in insert mode Eclipse scrolls alone (the wheel, the scroll bar), and Neovim does
     * not hear of it until Esc. Without the top line sent at Esc, the cursor report of the Esc carried
     * Neovim's old top line, and the editor jumped back there.
     */
    @Test
    void escKeepsTheViewEclipseScrolledToInInsertMode() {
        longDocument();
        session.inputAsync("M"); // the middle line: the editor can scroll a few lines and still show it
        settle();
        type(widget, 'i');
        waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
        type(widget, 'x');
        ITextViewer viewer = editor.getAdapter(ITextViewer.class);
        viewer.setTopIndex(viewer.getTopIndex() + 3); // as the wheel would
        int top = editorTop();
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        settle();
        waitUntil("Neovim's window scrolled to the editor's view", TIMEOUT_MS, this::topsAgree, this::tops);
        assertEquals(top, editorTop(), "the editor stays where it was scrolled");
    }

    @Test
    void eclipsesWordWrapIsNeovimsWrapWithLinebreak() throws Exception {
        runCommand(ITextEditorActionDefinitionIds.WORD_WRAP); // Alt+Shift+Y
        assertTrue(((ITextEditorExtension6) editor).isWordWrapEnabled());
        waitUntil("wrap and linebreak in Neovim", TIMEOUT_MS,
                () -> Boolean.TRUE.equals(SessionProbe.lua(session, "return vim.wo.wrap and vim.wo.linebreak")));

        runCommand(ITextEditorActionDefinitionIds.WORD_WRAP);
        waitUntil("nowrap in Neovim", TIMEOUT_MS,
                () -> Boolean.FALSE.equals(SessionProbe.lua(session, "return vim.wo.wrap")));
    }

    @Test
    void setWrapInNeovimTurnsOnTheEditorsWordWrap() {
        waitUntil("nowrap at attach, as the editor", TIMEOUT_MS,
                () -> Boolean.FALSE.equals(SessionProbe.lua(session, "return vim.wo.wrap")));
        session.commandAsync("set wrap");
        waitUntil("the editor's word wrap on", TIMEOUT_MS, () -> ((ITextEditorExtension6) editor).isWordWrapEnabled());
        session.commandAsync("set nowrap");
        waitUntil("and off again", TIMEOUT_MS, () -> !((ITextEditorExtension6) editor).isWordWrapEnabled());
    }
}
