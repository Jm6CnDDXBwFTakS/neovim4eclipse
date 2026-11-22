// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IPageLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.PinnedPlugins;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;

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
        SessionProbe.lua(session, PinnedPlugins.CLOSE_TELESCOPE + " pcall(vim.keymap.del, 'n', 'gp')");
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
