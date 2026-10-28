// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.escape;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.ITextEditorActionDefinitionIds;
import org.eclipse.ui.texteditor.ITextEditorExtension5;
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

    /**
     * Each editor has its own Neovim window (docs/design/WINDOWS.md), which keeps its view: back
     * from another editor, the editor and Neovim agree on the same top line and cursor as before.
     */
    @Test
    void anEditorSwitchKeepsEachEditorsView() throws Exception {
        longDocument();
        session.inputAsync("1");
        session.inputAsync("5");
        session.inputAsync("0");
        session.inputAsync("G");
        waitUntil("the cursor on line 150", TIMEOUT_MS, () -> cursorLine() == 150);
        waitUntil("both agree", TIMEOUT_MS, this::topsAgree, this::tops);
        int top = editorTop();
        ITextEditor other = openOtherFile();
        page().activate(editor);
        waitForAttach(editor, "test.txt again");
        settle();
        assertEquals(top, editorTop(), "the editor's view" + tops());
        assertEquals(top, neovim("w0"), "its window's view" + tops());
        assertEquals(150, cursorLine());
        page().closeEditor(other, false);
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

    /** One notch of the mouse wheel as SWT reports it on Linux: {@code count} −3 is three lines down. */
    private void wheelDown() {
        Event event = new Event();
        event.count = -3;
        event.detail = SWT.SCROLL_LINE;
        event.doit = true;
        widget.notifyListeners(SWT.MouseVerticalWheel, event);
    }

    /**
     * Decisions D1 and D9: the wheel scrolls Neovim's window by the lines SWT reports, drags the
     * cursor along as {@code Ctrl-E} does, and the editor follows Neovim's top line.
     */
    @Test
    void theWheelScrollsNeovimAndTheEditorFollows() {
        longDocument();
        session.inputAsync("g");
        session.inputAsync("g");
        settle();
        wheelDown();
        wheelDown();
        waitUntil("Neovim's window six lines down", TIMEOUT_MS, () -> neovim("w0") == 7, this::tops);
        waitUntil("the editor with it", TIMEOUT_MS, () -> editorTop() == 7, this::tops);
        assertEquals(7, cursorLine(), "the cursor dragged to the top line ('scrolloff' 0)");
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
     * A manual test found {@code L} pressed again and again scrolling the editor down like Page Down.
     * In Vim it stays on the last line. Here the editor is scrolled half a line, as a touchpad or the
     * scroll bar leaves it.
     */
    @Test
    void lPressedAgainStaysPutAfterAScrollByPixels() {
        longDocument();
        widget.setTopPixel(widget.getLineHeight() * 20 + widget.getLineHeight() / 2);
        waitUntil("Neovim's window scrolled with the editor", TIMEOUT_MS, this::topsAgree, this::tops);
        settle();
        session.inputAsync("L");
        settle();
        int line = cursorLine();
        int top = editorTop();
        for (int i = 0; i < 3; i++) {
            session.inputAsync("L");
            settle();
        }
        assertEquals(line, cursorLine(), "L again stays on the same line" + tops());
        assertEquals(top, editorTop(), "and the editor does not scroll" + tops());
    }

    /**
     * Decision D6: in insert mode Eclipse scrolls alone (the wheel, the scroll bar), and Neovim does
     * not hear of it until Esc. Without the top line sent at Esc, the cursor report of the Esc carried
     * Neovim's old top line, and the editor jumped back there.
     */
    /**
     * The manual test of commit 3: the wheel in insert mode scrolled the caret off the screen, and
     * Esc scrolled back to it. In Neovim the wheel would have dragged the cursor along, so at Esc the
     * cursor is dragged into the editor's view instead, and the view stays.
     */
    @Test
    void escDragsACursorTheEditorScrolledAwayFromIntoItsView() {
        longDocument();
        session.inputAsync("g");
        session.inputAsync("g");
        settle();
        type(widget, 'i');
        waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
        type(widget, 'x');
        editor.getAdapter(ITextViewer.class).setTopIndex(100); // as the wheel in insert mode: line 101 on top
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        settle();
        waitUntil("Neovim's window at the editor's view", TIMEOUT_MS, this::topsAgree, this::tops);
        assertEquals(101, editorTop(), "the view stays where it was scrolled");
        assertEquals(101, cursorLine(), "the cursor dragged to its top line ('scrolloff' 0)");
        assertEquals("x" + "line 1", document.get().substring(0, 7), "the typed text is there");
    }

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

    /**
     * Eclipse ends block selection to wrap ({@code AbstractTextEditor.setWordWrap}), so {@code :set
     * wrap} still wraps. A manual test found the toolbar's block selection button still pressed
     * afterward: Eclipse updates its buttons when its own key or button runs, not on
     * {@code setWordWrap} from code.
     */
    @Test
    void setWrapEndsBlockSelectionAndItsToolbarButton() {
        ToolItem button = toolItem("Toggle Block Selection");
        button.setSelection(true); // a click: SWT presses a check item, then tells its listeners
        button.notifyListeners(SWT.Selection, new Event());
        ITextEditorExtension5 block = (ITextEditorExtension5) editor;
        waitUntil("block selection on", TIMEOUT_MS, block::isBlockSelectionModeEnabled);
        session.commandAsync("set wrap");
        waitUntil("the editor's word wrap on", TIMEOUT_MS, () -> ((ITextEditorExtension6) editor).isWordWrapEnabled());
        assertTrue(!block.isBlockSelectionModeEnabled(), "block selection ended");
        waitUntil("the block selection button released", TIMEOUT_MS, () -> !button.getSelection());
        assertTrue(Boolean.TRUE.equals(SessionProbe.lua(session, "return vim.wo.wrap")), "Neovim keeps 'wrap'");
    }

    /** The workbench window's toolbar item whose tooltip starts with {@code tooltip}. */
    private static ToolItem toolItem(String tooltip) {
        List<ToolItem> found = new ArrayList<>();
        waitUntil("the toolbar button " + tooltip, TIMEOUT_MS, () -> {
            found.clear();
            collect(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(), tooltip, found);
            return !found.isEmpty();
        });
        return found.get(0);
    }

    private static void collect(Control control, String tooltip, List<ToolItem> into) {
        if (control instanceof ToolBar bar) {
            for (ToolItem item : bar.getItems()) {
                if (item.getToolTipText() != null && item.getToolTipText().startsWith(tooltip)) {
                    into.add(item);
                }
            }
        }
        if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                collect(child, tooltip, into);
            }
        }
    }

    /**
     * The key itself, not the command: Eclipse's binding must win over the plug-in's key handling.
     * Alt+Shift+Y, except on macOS, where Eclipse unbinds it and uses Cmd+Option+Y (its
     * {@code org.eclipse.ui.workbench.texteditor} plugin.xml, platform "carbon"); the test sent
     * Alt+Shift+Y there at first and failed.
     */
    @Test
    void theWordWrapKeyTogglesWordWrap() {
        boolean mac = org.eclipse.jface.util.Util.isMac();
        Event event = new Event();
        event.keyCode = 'y';
        event.character = mac ? 'y' : 'Y';
        event.stateMask = mac ? SWT.COMMAND | SWT.ALT : SWT.ALT | SWT.SHIFT;
        event.doit = true;
        widget.notifyListeners(SWT.KeyDown, event);
        waitUntil("word wrap on", TIMEOUT_MS, () -> ((ITextEditorExtension6) editor).isWordWrapEnabled());
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
