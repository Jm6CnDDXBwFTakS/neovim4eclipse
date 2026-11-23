// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.ctrlKey;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.core.resources.IFile;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.contexts.IContextService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.PinnedPlugins;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimCtrlKeys;

/**
 * A real picker in a real editor: Telescope's {@code current_buffer_fuzzy_find} (pure Lua, no
 * {@code rg} or {@code fd}), at the commit pinned and vetted in {@code releng/test-plugins.properties},
 * opened from a mapping as a user's {@code <leader>/} would. Its windows are drawn over the text,
 * its prompt with Neovim's cursor and the editor's caret hidden; the letters typed go to the
 * prompt; Enter jumps to the line found; leaving the editor for a view closes every window of it.
 * The keys' routing alone, without a workbench, is {@code FloatInsertIntegrationTest}; the
 * drawing with a stand-in prompt, {@link FloatingWindowsEndToEndTest}. Skipped when the
 * plug-ins are not fetched ({@link PinnedPlugins#HOW}).
 *
 * <p>The UI tests share one Neovim, so Telescope and plenary stay on its {@code 'runtimepath'},
 * their modules loaded, for the tests after this one; nothing of theirs acts by itself (their
 * {@code plugin/} files, read only at Neovim's start, are never read here), and the mapping goes.
 */
class PickersEndToEndTest extends EndToEndTestBase {

    @BeforeEach
    void loadTelescope() {
        Optional<Path> telescope = PinnedPlugins.find("telescope");
        Optional<Path> plenary = PinnedPlugins.find("plenary");
        assumeTrue(telescope.isPresent() && plenary.isPresent(),
                "telescope and plenary are not fetched at their pinned commits: " + PinnedPlugins.HOW);
        SessionProbe.lua(session, PinnedPlugins.loadTelescope(plenary.get(), telescope.get()));
    }

    @AfterEach
    void closeTelescope() {
        SessionProbe.lua(session, PinnedPlugins.CLOSE_TELESCOPE + " pcall(vim.keymap.del, 'n', 'gp')"
                + " pcall(vim.keymap.del, 'n', 'gO')");
        waitUntil("no picker drawn", TIMEOUT_MS, () -> {
            settle();
            return pluginFloats().isEmpty();
        });
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

    /** {@link PinnedPlugins#TELESCOPE_WINDOWS}' answer, with {@code word} (a plain word of the test's own) or none. */
    private long telescopeWindows(String word) {
        Object n = SessionProbe.lua(session, "return (function(...)\n" + PinnedPlugins.TELESCOPE_WINDOWS + "\nend)("
                + (word == null ? "" : "'" + word + "'") + ")");
        return ((Number) n).longValue();
    }

    /** The drawn floats' rows, one float after the other: for a failure's message. */
    private String drawn() {
        StringBuilder all = new StringBuilder();
        for (GridCanvas canvas : pluginFloats()) {
            all.append(rowsOf(canvas.grid())).append("---\n");
        }
        return all.toString();
    }

    /** Whether a drawn float shows {@code text}, and the one with Neovim's cursor is its prompt. */
    private boolean pickerShows(String text) {
        settle();
        boolean shown = pluginFloats().stream().anyMatch(c -> rowsOf(c.grid()).contains(text));
        boolean prompt = pluginFloats().stream().anyMatch(c -> c.grid().cursorRow() >= 0 && rowsOf(c.grid()).contains(">"));
        return shown && prompt && widget.getCaret() == null;
    }

    /**
     * The picker drawn, the prompt with the cursor and the caret hidden, the letters typed in the
     * prompt and not the document, Enter jumping to the line found, the caret back on it.
     */
    @Test
    void telescopesPromptTakesTheLettersAndEnterJumpsToTheLineFound() {
        String text = document.get();
        type(widget, "gp");
        waitUntil("Telescope drawn, its prompt with Neovim's cursor, the editor's caret hidden", TIMEOUT_MS,
                () -> pickerShows("beta"), () -> "; drawn:\n" + drawn() + "caret " + widget.getCaret());
        type(widget, "gam");
        // Narrowed, as Neovim has it: the prompt with the letters, the results only gamma (a
        // preview, shown on a wide editor, still has every line, so the drawing alone cannot tell).
        waitUntil("the prompt with the letters, the results narrowed to gamma", TIMEOUT_MS,
                () -> pickerShows("gam") && telescopeWindows("gam") >= 2 && telescopeWindows("alpha") == 0
                        && telescopeWindows("beta") == 0,
                () -> "; drawn:\n" + drawn());
        assertEquals(text, document.get(), "the letters went to the prompt, not the document");
        type(widget, "\r");
        waitUntil("the picker gone, the caret back on gamma's line", TIMEOUT_MS, () -> {
            settle();
            return pluginFloats().isEmpty() && widget.getCaret() != null
                    && widget.getLineAtOffset(widget.getCaretOffset()) == 2;
        }, () -> "; drawn:\n" + drawn() + "caret on line " + widget.getLineAtOffset(widget.getCaretOffset()));
        assertEquals(text, document.get(), "the document keeps its text");
    }

    /** Telescope's results window as drawn, or {@code null}. */
    private GridCanvas resultsCanvas() {
        long win = ((Number) SessionProbe.lua(session, """
                for _, w in ipairs(vim.api.nvim_list_wins()) do
                  if vim.bo[vim.api.nvim_win_get_buf(w)].filetype == 'TelescopeResults' then return w end
                end
                return -1""")).longValue();
        return pluginFloats().stream().filter(c -> c.grid() != null && c.grid().window() == win).findFirst().orElse(null);
    }

    /** The line number of the entry selected in the open picker, 0 when none. */
    private long selectedLine() {
        Object line = SessionProbe.lua(session, """
                local ok, entry = pcall(function() return require('telescope.actions.state').get_selected_entry() end)
                return ok and entry and entry.lnum or 0""");
        return ((Number) line).longValue();
    }

    /** A left click (press and release) on cell ({@code row}, {@code col}) of {@code canvas}. */
    private static void click(GridCanvas canvas, int row, int col) {
        for (int type : new int[] { SWT.MouseDown, SWT.MouseUp }) {
            Event e = new Event();
            e.button = 1;
            e.x = col * canvas.cellWidth() + 1;
            e.y = row * canvas.cellHeight() + 1;
            e.stateMask = type == SWT.MouseUp ? SWT.BUTTON1 : 0;
            canvas.notifyListeners(type, e);
        }
    }

    /**
     * A click on a row of the results selects it, Telescope's own mapping, and the picker stays
     * with the keyboard in its prompt; a second click on the row, a double click to Neovim, opens
     * it. {@code 'mousetime'} is raised so that a slow machine still counts the two as one.
     */
    @Test
    void aClickOnTheResultsSelectsTheRowAndADoubleClickOpensIt() {
        Object mousetime = SessionProbe.lua(session, "local t = vim.o.mousetime vim.o.mousetime = 60000 return t");
        try {
            String text = document.get();
            type(widget, "gp");
            waitUntil("Telescope drawn", TIMEOUT_MS, () -> pickerShows("gamma") && resultsCanvas() != null,
                    () -> "; drawn:\n" + drawn());
            UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS);
            GridCanvas results = resultsCanvas();
            assertNotNull(results);
            int row = -1;
            for (int r = 0; r < results.grid().height() && row < 0; r++) {
                row = results.grid().row(r).contains("gamma") ? r : -1;
            }
            assertTrue(row >= 0, "gamma's row in the results:\n" + rowsOf(results.grid()));
            int col = results.grid().row(row).indexOf("gamma");
            assertFalse(selectedLine() == 3, "another entry is selected first");

            click(results, row, col);
            waitUntil("gamma selected by the click", TIMEOUT_MS, () -> selectedLine() == 3,
                    () -> "; selected line " + selectedLine());
            assertTrue(pickerShows("gamma"), "the picker stays, its prompt with the cursor");
            assertEquals("i", session.getMode(), "the prompt keeps its insert");

            click(results, row, col);
            waitUntil("the picker gone, the caret on gamma's line", TIMEOUT_MS, () -> {
                settle();
                return pluginFloats().isEmpty() && widget.getCaret() != null
                        && widget.getLineAtOffset(widget.getCaretOffset()) == 2;
            }, () -> "; drawn:\n" + drawn());
            assertEquals(text, document.get(), "the document keeps its text");
        } finally {
            SessionProbe.lua(session, "vim.o.mousetime = " + mousetime);
        }
    }

    /** Whether the binding context of {@code Ctrl+letter} is active now. */
    private static boolean ctrlContextActive(char letter) {
        return PlatformUI.getWorkbench().getService(IContextService.class).getActiveContextIds()
                .contains(VimCtrlKeys.contextId(letter));
    }

    /**
     * In the prompt, letters left to Eclipse reach Neovim: {@code Ctrl+A} does not select the
     * document behind it, {@code Ctrl+F} opens no Find/Replace, {@code Ctrl+B} builds nothing,
     * {@code Ctrl+S} saves nothing. Maps of the test's own say that Neovim got each. Once the
     * picker is closed, the preferences apply again ({@code Ctrl+S} is Eclipse's by default).
     */
    @Test
    void everyCtrlLetterInThePromptGoesToNeovim() {
        Shell shell = widget.getShell();
        type(widget, "gp");
        waitUntil("Telescope drawn", TIMEOUT_MS, () -> pickerShows("beta"), () -> "; drawn:\n" + drawn());
        assertTrue(ctrlContextActive('s'), "Ctrl+S, Eclipse's by default, is Neovim's in the prompt");
        SessionProbe.lua(session, """
                vim.g.n4e_ctrl = ''
                for _, key in ipairs({ 'a', 'b', 'f', 's' }) do
                  vim.keymap.set('i', '<C-' .. key .. '>', function() vim.g.n4e_ctrl = vim.g.n4e_ctrl .. key end,
                    { buffer = 0 })
                end""");
        for (char letter : "abfs".toCharArray()) {
            ctrlKey(widget, letter);
        }
        waitUntil("Neovim got every letter", TIMEOUT_MS,
                () -> "abfs".equals(SessionProbe.lua(session, "return vim.g.n4e_ctrl")),
                () -> "; got " + SessionProbe.lua(session, "return vim.g.n4e_ctrl"));
        assertEquals(0, widget.getSelectionCount(), "Eclipse's Select All did not run");
        assertSame(shell, widget.getDisplay().getActiveShell(), "no Find/Replace dialog");
        assertFalse(editor.isDirty(), "the document is untouched");

        SessionProbe.lua(session, PinnedPlugins.CLOSE_TELESCOPE);
        waitUntil("the picker gone, Ctrl+S Eclipse's again", TIMEOUT_MS, () -> {
            settle();
            return pluginFloats().isEmpty() && !ctrlContextActive('s');
        }, () -> "; drawn:\n" + drawn() + "mode " + session.getMode());
    }

    /**
     * A picker of its own on one other file, line 3, mapped to {@code gO}: Telescope's Ctrl-X,
     * Ctrl-V and Ctrl-T then act on it as on any file picker's entry, on every system (no
     * {@code find}, {@code fd} or {@code rg}).
     */
    private void mapPickerOf(IFile file) {
        SessionProbe.lua(session, """
                vim.keymap.set('n', 'gO', function()
                  local conf = require('telescope.config').values
                  require('telescope.pickers').new({}, {
                    prompt_title = 'Files',
                    finder = require('telescope.finders').new_table({
                      results = { [[%s]] },
                      entry_maker = function(path)
                        return { value = path, display = 'other.txt', ordinal = 'other.txt', path = path, lnum = 3 }
                      end,
                    }),
                    sorter = conf.generic_sorter({}),
                  }):find()
                end)
                """.formatted(file.getLocation().toOSString()));
    }

    private IFile otherFile() throws Exception {
        IFile file = project.getFile("other.txt");
        if (!file.exists()) {
            file.create(new java.io.ByteArrayInputStream("one\ntwo\nthree\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    true, null);
        }
        return file;
    }

    /** Closes every editor but the test's own, and gives it the keyboard back. */
    private void closeOtherEditors() {
        for (IEditorReference reference : page().getEditorReferences()) {
            IEditorPart other = reference.getEditor(false);
            if (other != null && other != editor) {
                page().closeEditor(other, false);
            }
        }
        page().activate(editor);
        UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS);
    }

    /** The picker's action on the other file; it opens in a new editor, split or not, on line 3. */
    private void pickerActionOpensTheFile(char key, boolean split) throws Exception {
        String text = document.get();
        IFile other = otherFile();
        mapPickerOf(other);
        try {
            UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS);
            type(widget, "gO");
            waitUntil("the picker drawn", TIMEOUT_MS, () -> pickerShows("other.txt"), () -> "; drawn:\n" + drawn());
            ctrlKey(widget, key);
            waitUntil("other.txt in a new editor, its caret on line 3", TIMEOUT_MS, () -> {
                settle();
                IEditorPart active = page().getActiveEditor();
                return active != editor && "other.txt".equals(active.getTitle())
                        && active.getAdapter(Control.class) instanceof StyledText opened
                        && opened.getLineAtOffset(opened.getCaretOffset()) == 2 && pluginFloats().isEmpty();
            }, () -> "; active " + page().getActiveEditor().getTitle() + ", drawn:\n" + drawn());
            assertEquals(split, EditorSplits.isSplit(page().getActiveEditor()), "a split, or a tab of this stack");
            // Normal mode in the new editor: in Insert these keys would be typed into a file.
            type((StyledText) page().getActiveEditor().getAdapter(Control.class), "zz");
            settle();
            assertFalse(page().getActiveEditor().isDirty(), "the keys after the action change no file");
            assertEquals(text, document.get(), "the document keeps its text");
        } finally {
            closeOtherEditors();
        }
    }

    @Test
    void ctrlXInThePickerOpensTheFileInASplit() throws Exception {
        pickerActionOpensTheFile('x', true);
    }

    @Test
    void ctrlVInThePickerOpensTheFileInASplit() throws Exception {
        pickerActionOpensTheFile('v', true);
    }

    @Test
    void ctrlTInThePickerOpensTheFileInAnEditorTab() throws Exception {
        pickerActionOpensTheFile('t', false);
    }

    /** Ctrl-V on a line of the file itself: a clone of the editor on that line, the first editor where it was. */
    @Test
    void ctrlVOnALineOfThisFileClonesTheEditorThere() {
        int line = widget.getLineAtOffset(widget.getCaretOffset());
        try {
            type(widget, "gp");
            waitUntil("Telescope drawn", TIMEOUT_MS, () -> pickerShows("beta"), () -> "; drawn:\n" + drawn());
            type(widget, "gam");
            waitUntil("narrowed to gamma", TIMEOUT_MS, () -> pickerShows("gam") && telescopeWindows("beta") == 0,
                    () -> "; drawn:\n" + drawn());
            ctrlKey(widget, 'v');
            waitUntil("a clone on gamma's line", TIMEOUT_MS, () -> {
                settle();
                IEditorPart active = page().getActiveEditor();
                return active != editor && active.getAdapter(Control.class) instanceof StyledText clone
                        && clone.getLineAtOffset(clone.getCaretOffset()) == 2;
            }, () -> "; active " + page().getActiveEditor().getTitle());
            assertEquals(line, widget.getLineAtOffset(widget.getCaretOffset()), "the first editor stays where it was");
        } finally {
            closeOtherEditors();
        }
    }

    /** A right click stays Eclipse's, whose menu acts on the document: the picker closes first. */
    @Test
    void aRightClickInTheTextClosesThePicker() {
        String text = document.get();
        type(widget, "gp");
        waitUntil("Telescope drawn", TIMEOUT_MS, () -> pickerShows("beta"), () -> "; drawn:\n" + drawn());
        Event press = new Event();
        press.button = 3;
        press.x = 1;
        press.y = 1;
        widget.notifyListeners(SWT.MouseDown, press);
        waitUntil("every window of the picker closed, its insert ended", TIMEOUT_MS, () -> {
            settle();
            return telescopeWindows(null) == 0 && !"i".equals(session.getMode());
        }, () -> "; mode " + session.getMode());
        assertEquals(text, document.get());
    }

    /**
     * A {@code :} line opened from the picker goes with it, whichever button clicks the text. The
     * cursor is on Neovim's command line then, so only Neovim can tell that a float is behind it.
     */
    private void clickWithALineOpenFromThePickerClosesBoth(int button) {
        String text = document.get();
        type(widget, "gp");
        waitUntil("Telescope drawn", TIMEOUT_MS, () -> pickerShows("beta"), () -> "; drawn:\n" + drawn());
        session.inputAsync("<Esc>:");
        waitUntil("the : line open from the prompt", TIMEOUT_MS, () -> {
            settle();
            return "c".equals(session.getMode());
        }, () -> "; mode " + session.getMode());
        Event press = new Event();
        press.button = button;
        press.x = 1;
        press.y = 1;
        widget.notifyListeners(SWT.MouseDown, press);
        if (button == 1) {
            Event release = new Event();
            release.button = 1;
            release.x = 1;
            release.y = 1;
            release.stateMask = SWT.BUTTON1;
            widget.notifyListeners(SWT.MouseUp, release);
        }
        waitUntil("the line and every window of the picker gone", TIMEOUT_MS, () -> {
            settle();
            return telescopeWindows(null) == 0 && "n".equals(session.getMode());
        }, () -> "; mode " + session.getMode() + ", picker windows " + telescopeWindows(null));
        assertEquals(text, document.get());
    }

    @Test
    void aLeftClickWithALineOpenFromThePickerClosesBoth() {
        clickWithALineOpenFromThePickerClosesBoth(1);
    }

    @Test
    void aRightClickWithALineOpenFromThePickerClosesBoth() {
        clickWithALineOpenFromThePickerClosesBoth(3);
    }

    /** With no picker behind it, a {@code :} line outlives a right click, whose menu is Eclipse's. */
    @Test
    void aRightClickKeepsALineOpenedFromTheEditor() {
        session.inputAsync(":");
        waitUntil("the : line open", TIMEOUT_MS, () -> {
            settle();
            return "c".equals(session.getMode());
        }, () -> "; mode " + session.getMode());
        Event press = new Event();
        press.button = 3;
        press.x = 1;
        press.y = 1;
        widget.notifyListeners(SWT.MouseDown, press);
        settle();
        assertEquals("c", session.getMode());
        session.inputAsync("<C-c>");
        waitUntil("the line closed", TIMEOUT_MS, () -> {
            settle();
            return "n".equals(session.getMode());
        }, () -> "; mode " + session.getMode());
    }

    /** Leaving the editor for a view closes every window of the picker and ends its insert. */
    @Test
    void leavingTheEditorClosesThePicker() throws Exception {
        String text = document.get();
        type(widget, "gp");
        waitUntil("Telescope drawn", TIMEOUT_MS, () -> pickerShows("beta"), () -> "; drawn:\n" + drawn());
        type(widget, "al");
        waitUntil("the letters in the prompt", TIMEOUT_MS, () -> pickerShows("al"), () -> "; drawn:\n" + drawn());
        page().showView(IPageLayout.ID_TASK_LIST);
        waitUntil("every window of the picker closed, its insert ended", TIMEOUT_MS, () -> {
            settle();
            return telescopeWindows(null) == 0 && !"i".equals(session.getMode());
        }, () -> "; mode " + session.getMode());
        page().activate(editor);
        UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS);
        waitUntil("nothing of the picker drawn, the caret back", TIMEOUT_MS, () -> {
            settle();
            return pluginFloats().isEmpty() && widget.getCaret() != null;
        }, () -> "; drawn:\n" + drawn());
        assertEquals(text, document.get(), "the letters went to the prompt, not the document");
    }
}
