// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPageLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.PinnedPlugins;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;

/**
 * snacks.nvim's picker in a real editor, at the commit pinned and vetted in
 * {@code releng/test-plugins.properties}: a picker that draws itself, not Telescope's, in several
 * floats at once (an input, a list, a preview, the box around them, a backdrop). Its
 * {@code lines} picker (pure Lua) is drawn over the text, its input with Neovim's cursor and the
 * editor's caret hidden; the letters typed go to the input; Enter jumps to the line found; leaving
 * the editor for a view moves Neovim out of it. Its {@code files} picker, run with {@code rg}
 * ({@link PinnedPlugins#RG_HOW} when missing), opens the file chosen in an editor of its own. The
 * same through the session alone is {@code SnacksPickerIntegrationTest}. Skipped when the plug-in
 * is not fetched ({@link PinnedPlugins#HOW}).
 *
 * <p>The UI tests share one Neovim, so snacks stays on its {@code 'runtimepath'}, its modules
 * loaded; its {@code setup()} is never called, so nothing of it acts by itself, and the mappings
 * go.
 */
class SnacksPickerEndToEndTest extends EndToEndTestBase {

    @BeforeEach
    void loadSnacks() {
        Optional<Path> snacks = PinnedPlugins.find("snacks");
        assumeTrue(snacks.isPresent(), "snacks is not fetched at its pinned commit: " + PinnedPlugins.HOW);
        SessionProbe.lua(session, PinnedPlugins.loadSnacks(snacks.get()));
    }

    @AfterEach
    void closeSnacks() {
        SessionProbe.lua(session, PinnedPlugins.CLOSE_SNACKS + " pcall(vim.keymap.del, 'n', 'gs') pcall(vim.keymap.del, 'n', 'gS')");
        waitUntil("no picker drawn, no rg left running", TIMEOUT_MS, () -> {
            settle();
            return pluginFloats().isEmpty() && snacksWindows(null) == 0 && "".equals(SessionProbe.lua(session, PickersEndToEndTest.PICKER_PROGRAMS));
        }, () -> "; drawn:\n" + drawn());
    }

    /** The canvases of plug-ins' floats over the text (not ui2's windows, the popup menu or a status line). */
    private List<GridCanvas> pluginFloats() {
        List<GridCanvas> floats = new ArrayList<>();
        for (Control child : widget.getChildren()) {
            if (child instanceof GridCanvas canvas && !canvas.isDisposed() && !StatusLineRow.isRow(canvas)
                    && !isNeovimsOwnFloat(canvas)) {
                floats.add(canvas);
            }
        }
        return floats;
    }

    private static String rowsOf(GridSnapshot grid) {
        StringBuilder rows = new StringBuilder();
        for (int r = 0; grid != null && r < grid.height(); r++) {
            rows.append(grid.row(r).stripTrailing()).append('\n');
        }
        return rows.toString();
    }

    private String drawn() {
        StringBuilder all = new StringBuilder();
        for (GridCanvas canvas : pluginFloats()) {
            all.append(rowsOf(canvas.grid())).append("---\n");
        }
        return all.toString();
    }

    /** {@link PinnedPlugins#SNACKS_WINDOWS}' answer, with {@code word} (a plain word of the test's own) or none. */
    private long snacksWindows(String word) {
        Object n = SessionProbe.lua(session, "return (function(...)\n" + PinnedPlugins.SNACKS_WINDOWS + "\nend)("
                + (word == null ? "" : "'" + word + "'") + ")");
        return ((Number) n).longValue();
    }

    /** Neovim's windows with their filetypes, its last error and messages: for a failure's message. */
    private String neovim() {
        return String.valueOf(SessionProbe.lua(session, "local w = {} for _, x in ipairs(vim.api.nvim_list_wins()) do"
                + " table.insert(w, x .. ':' .. vim.bo[vim.api.nvim_win_get_buf(x)].filetype) end"
                + " return 'windows ' .. table.concat(w, ' ') .. ', errmsg ' .. vim.v.errmsg .. ', messages '"
                + " .. vim.fn.execute('messages'):sub(-600)"));
    }

    /** {@link PinnedPlugins#SNACKS_LIST_HAS}' answer for {@code word}, a plain word of the test's own. */
    private long listHas(String word) {
        Object n = SessionProbe.lua(session, "return (function(...)\n" + PinnedPlugins.SNACKS_LIST_HAS + "\nend)('" + word + "')");
        return ((Number) n).longValue();
    }

    /**
     * Whether a drawn float shows {@code text}, Neovim's cursor is in a drawn float (the input)
     * and the editor's caret is hidden.
     */
    private boolean pickerShows(String text) {
        settle();
        boolean shown = pluginFloats().stream().anyMatch(c -> rowsOf(c.grid()).contains(text));
        boolean cursor = pluginFloats().stream().anyMatch(c -> c.grid().cursorRow() >= 0);
        return shown && cursor && widget.getCaret() == null;
    }

    /**
     * The picker drawn, the input with the cursor and the caret hidden, the letters typed in the
     * input and not the document, Enter jumping to the line found, the caret back on it.
     */
    @Test
    void theInputTakesTheLettersAndEnterJumpsToTheLineFound() {
        String text = document.get();
        type(widget, "gs");
        waitUntil("snacks drawn, its input with Neovim's cursor, the editor's caret hidden", TIMEOUT_MS,
                () -> pickerShows("beta") && "i".equals(session.getMode()),
                () -> "; drawn:\n" + drawn() + "caret " + widget.getCaret() + ", mode " + session.getMode() + "; " + neovim());
        type(widget, "gam");
        waitUntil("the input with the letters, the list narrowed to gamma", TIMEOUT_MS,
                () -> pickerShows("gam") && listHas("gamma") >= 1 && listHas("beta") == 0,
                () -> "; drawn:\n" + drawn());
        assertEquals(text, document.get(), "the letters went to the input, not the document");
        type(widget, "\r");
        waitUntil("the picker gone, the caret back on gamma's line", TIMEOUT_MS, () -> {
            settle();
            return pluginFloats().isEmpty() && widget.getCaret() != null
                    && widget.getLineAtOffset(widget.getCaretOffset()) == 2;
        }, () -> "; drawn:\n" + drawn() + "caret on line " + widget.getLineAtOffset(widget.getCaretOffset()));
        assertEquals(text, document.get(), "the document keeps its text");
    }

    /**
     * Leaving the editor for a view moves Neovim out of the picker, its insert ended, as in a
     * terminal; coming back draws no caret-less editor, and the document never got the letters.
     */
    @Test
    void leavingTheEditorMovesNeovimOutOfThePicker() throws Exception {
        String text = document.get();
        type(widget, "gs");
        waitUntil("snacks drawn", TIMEOUT_MS, () -> pickerShows("beta"), () -> "; drawn:\n" + drawn() + neovim());
        type(widget, "al");
        waitUntil("the letters in the input", TIMEOUT_MS, () -> pickerShows("al") && listHas("beta") == 0,
                () -> "; drawn:\n" + drawn());
        page().showView(IPageLayout.ID_TASK_LIST);
        waitUntil("Neovim out of the picker, its insert ended", TIMEOUT_MS, () -> {
            settle();
            return !"i".equals(session.getMode()) && !"snacks_picker_input".equals(SessionProbe.lua(session, "return vim.bo.filetype"));
        }, () -> "; mode " + session.getMode());
        page().activate(editor);
        UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS);
        waitUntil("the caret back", TIMEOUT_MS, () -> {
            settle();
            return widget.getCaret() != null;
        }, () -> "; drawn:\n" + drawn());
        assertEquals(text, document.get(), "the letters went to the input, not the document");
    }

    /**
     * snacks' {@code files} in the project's folder, run with {@code rg} (snacks runs it by its
     * name, from Neovim's {@code PATH} kept to its absolute folders, without the developer's
     * ripgrep config: {@link PinnedPlugins#FOR_PROGRAMS}): the letters narrow it to other.txt,
     * and Enter opens it in an
     * editor of its own, its caret on the first line.
     */
    @Test
    void filesOpensTheFileChosenInAnEditor() throws Exception {
        assumeTrue(PinnedPlugins.rg().isPresent(), PinnedPlugins.RG_HOW);
        String text = document.get();
        createFile("other.txt", "one\ntwo\nthree\n");
        SessionProbe.lua(session, PinnedPlugins.FOR_PROGRAMS + " vim.keymap.set('n', 'gS', function()"
                + " require('snacks').picker.files({ cwd = " + PinnedPlugins.longString(project.getLocation().toFile().toPath())
                + ", cmd = 'rg' }) end)");
        try {
            UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS);
            type(widget, "gS");
            waitUntil("the list drawn, other.txt and test.txt in it", TIMEOUT_MS,
                    () -> pickerShows("other.txt") && listHas("other.txt") == 1 && listHas("test.txt") == 1,
                    () -> "; drawn:\n" + drawn());
            type(widget, "other");
            waitUntil("narrowed to other.txt", TIMEOUT_MS, () -> pickerShows("other") && listHas("test.txt") == 0,
                    () -> "; drawn:\n" + drawn());
            type(widget, "\r");
            waitUntil("other.txt in a new editor, the picker gone", TIMEOUT_MS, () -> {
                settle();
                IEditorPart active = page().getActiveEditor();
                return active != editor && "other.txt".equals(active.getTitle())
                        && active.getAdapter(Control.class) instanceof StyledText opened
                        && opened.getLineAtOffset(opened.getCaretOffset()) == 0 && pluginFloats().isEmpty();
            }, () -> "; active " + page().getActiveEditor().getTitle() + ", drawn:\n" + drawn());
            type((StyledText) page().getActiveEditor().getAdapter(Control.class), "zz");
            settle();
            assertFalse(page().getActiveEditor().isDirty(), "the keys after the pick change no file");
            assertEquals(text, document.get(), "the document keeps its text");
        } finally {
            for (IEditorReference reference : page().getEditorReferences()) {
                IEditorPart other = reference.getEditor(false);
                if (other != null && other != editor) {
                    page().closeEditor(other, false);
                }
            }
            page().activate(editor);
            UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS);
        }
    }
}
