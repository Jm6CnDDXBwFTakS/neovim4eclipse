// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.texteditor.ITextEditorActionDefinitionIds;
import org.eclipse.ui.texteditor.ITextEditorExtension6;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;

/**
 * Neovim's window follows the editor (docs/design/VIEWPORT.md, commit 1): its size is the text
 * area's, so {@code L} is the editor's last line, and {@code 'wrap'} is the editor's word wrap, both
 * ways. The pieces are tested alone in {@code ViewportFollowerTest}, {@code viewport_spec.lua} and
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
