// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.ctrlKey;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * A real fzf-lua in a real editor, at the commit pinned and vetted in
 * {@code releng/test-plugins.properties}, with the fzf program on {@code PATH}: its list, a
 * terminal running fzf in a float, is drawn over the text with Neovim's cursor in fzf's prompt and
 * the editor's caret hidden; the letters typed go to fzf; Enter jumps to the line chosen;
 * {@code Ctrl-V} on a file opens it in an Eclipse split, as a picker's split action does (USER_GUIDE.md,
 * "Floating windows"); leaving the editor for a view moves Neovim out of the list, which stays
 * open, as fzf-lua's does in a terminal. Telescope's picker, a prompt
 * in Insert mode rather than a terminal, is {@link PickersEndToEndTest}; the keys and the mode
 * without a workbench, {@code FzfLuaIntegrationTest}. Skipped when fzf-lua is not fetched
 * ({@link PinnedPlugins#HOW}), no fzf is on {@code PATH} ({@link PinnedPlugins#FZF_HOW}) or the one
 * there is too old for it ({@link PinnedPlugins#FZF_TOO_OLD}): fzf is not pinned, so a machine
 * without one leaves these out.
 *
 * <p>The UI tests share one Neovim, so fzf-lua stays on its {@code 'runtimepath'}, its modules
 * loaded, for the tests after this one, with what loading it makes (the server it listens on,
 * {@code vim.g.fzf_lua_server}, {@code _G.FzfLua}, its {@code FzfLua*} highlight groups) and what
 * its first list adds (an {@code ExitPre} autocommand, group {@code FzfLuaExitPre}). Its {@code plugin/} file, read only at Neovim's start, is never
 * read here, and the mappings go. fzf-lua keeps fzf running in a hidden buffer after Enter
 * or Esc (its default "hide" profile, for {@code :FzfLua resume}), so each test ends with
 * {@link PinnedPlugins#CLOSE_FZF_LUA}, which ends those too.
 */
class FzfLuaEndToEndTest extends EndToEndTestBase {

    @BeforeEach
    void loadFzfLua() {
        Optional<Path> fzfLua = PinnedPlugins.find("fzf-lua");
        assumeTrue(fzfLua.isPresent(), "fzf-lua is not fetched at its pinned commit: " + PinnedPlugins.HOW);
        Optional<Path> fzf = PinnedPlugins.fzf();
        assumeTrue(fzf.isPresent(), PinnedPlugins.FZF_HOW);
        SessionProbe.lua(session, PinnedPlugins.loadFzfLua(fzfLua.get(), fzf.get()));
        String check = String.valueOf(SessionProbe.lua(session, PinnedPlugins.FZF_CHECK));
        assumeTrue(!PinnedPlugins.FZF_IS_TOO_OLD.equals(check), PinnedPlugins.FZF_TOO_OLD);
        assertEquals(PinnedPlugins.FZF_IS_ACCEPTED, check, "fzf-lua takes the fzf on PATH");
    }

    @AfterEach
    void closeFzfLua() {
        SessionProbe.lua(session, PinnedPlugins.CLOSE_FZF_LUA + " pcall(vim.keymap.del, 'n', 'gz')"
                + " pcall(vim.keymap.del, 'n', 'gZ')");
        waitUntil("no list drawn, no buffer of one left", TIMEOUT_MS, () -> {
            settle();
            return pluginFloats().isEmpty() && ((Number) SessionProbe.lua(session, PinnedPlugins.FZF_BUFFERS)).intValue() == 0;
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

    /** The drawn floats' rows, one float after the other: for a failure's message. */
    private String drawn() {
        StringBuilder all = new StringBuilder();
        for (GridCanvas canvas : pluginFloats()) {
            all.append(rowsOf(canvas.grid())).append("---\n");
        }
        return all.toString();
    }

    /** {@link PinnedPlugins#FZF_LUA_WINDOWS}' answer, with {@code word} (a plain word of the test's own) or none. */
    private long fzfWindows(String word) {
        Object n = SessionProbe.lua(session, "return (function(...)\n" + PinnedPlugins.FZF_LUA_WINDOWS + "\nend)("
                + (word == null ? "" : "'" + word + "'") + ")");
        return ((Number) n).longValue();
    }

    /** Whether a drawn float shows {@code text} with Neovim's cursor in it, the editor's caret hidden. */
    private boolean listShows(String text) {
        settle();
        return pluginFloats().stream().anyMatch(c -> c.grid() != null && c.grid().cursorRow() >= 0
                && rowsOf(c.grid()).contains(text)) && widget.getCaret() == null;
    }

    /**
     * The list drawn with the cursor in fzf's prompt and the caret hidden, the letters narrowing
     * fzf's list and not typed into the document, Enter jumping to the line chosen, the caret back on it.
     */
    @Test
    void fzfsListTakesTheLettersAndEnterJumpsToTheLineChosen() {
        String text = document.get();
        type(widget, "gz");
        waitUntil("fzf-lua's list drawn with Neovim's cursor, the editor's caret hidden", TIMEOUT_MS,
                () -> listShows("beta") && "t".equals(session.getMode()),
                () -> "; drawn:\n" + drawn() + "caret " + widget.getCaret() + ", mode " + session.getMode());
        type(widget, "gam");
        waitUntil("fzf's list narrowed to gamma", TIMEOUT_MS,
                () -> listShows("gam") && fzfWindows("gamma") == 1 && fzfWindows("beta") == 0,
                () -> "; drawn:\n" + drawn());
        assertEquals(text, document.get(), "the letters went to fzf, not the document");
        type(widget, "\r");
        waitUntil("the list gone, the caret back on gamma's line, the mode Normal", TIMEOUT_MS, () -> {
            settle();
            return pluginFloats().isEmpty() && widget.getCaret() != null
                    && widget.getLineAtOffset(widget.getCaretOffset()) == 2 && "n".equals(session.getMode());
        }, () -> "; drawn:\n" + drawn() + "caret on line " + widget.getLineAtOffset(widget.getCaretOffset())
                + ", mode " + session.getMode());
        assertEquals(text, document.get(), "the document keeps its text");
    }

    /**
     * {@code Ctrl-V} on a file in an fzf-lua list: fzf-lua's own action for it ({@code file_vsplit},
     * which its {@code files} binds to {@code Ctrl-V}) closes the list and makes a vertical split on
     * the file, which the editor turns into an Eclipse split on it (a window Eclipse does not draw
     * would take keys unseen). The list is one of the test's own ({@code fzf_exec} on two names in
     * the project's folder: {@code other.txt}, a file there, and {@code notes.md}, which the
     * letters typed leave out), so that it is the same on every system: {@code files} lists with
     * whatever the machine has ({@code fd}, {@code rg}, {@code find}), and on Windows Git Bash's
     * {@code find} gives full paths, cut at the window's width, so no row would show the name.
     */
    @Test
    void ctrlVOnAFileOpensItInASplit() throws Exception {
        String text = document.get();
        createFile("other.txt", "one\ntwo\nthree\n");
        SessionProbe.lua(session, "vim.keymap.set('n', 'gZ', function() require('fzf-lua').fzf_exec("
                + "{ 'other.txt', 'notes.md' }, { cwd = " + PinnedPlugins.longString(project.getLocation().toFile().toPath())
                + ", actions = { ['ctrl-v'] = require('fzf-lua.actions').file_vsplit } }) end)");
        try {
            UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS);
            type(widget, "gZ");
            waitUntil("the list drawn, other.txt and notes.md in it", TIMEOUT_MS,
                    () -> listShows("other.txt") && fzfWindows("other.txt") == 1 && fzfWindows("notes.md") == 1,
                    () -> "; drawn:\n" + drawn());
            type(widget, "other");
            waitUntil("narrowed to other.txt", TIMEOUT_MS, () -> fzfWindows("other.txt") == 1 && fzfWindows("notes.md") == 0,
                    () -> "; drawn:\n" + drawn());
            ctrlKey(widget, 'v');
            waitUntil("other.txt in a new editor, a split", TIMEOUT_MS, () -> {
                settle();
                IEditorPart active = page().getActiveEditor();
                return active != null && active != editor && "other.txt".equals(active.getTitle()) && pluginFloats().isEmpty();
            }, () -> {
                IEditorPart active = page().getActiveEditor();
                return "; active " + (active == null ? "none" : active.getTitle()) + ", drawn:\n" + drawn();
            });
            assertTrue(EditorSplits.isSplit(page().getActiveEditor()), "a split");
            // Normal mode in the new editor: in Insert or Terminal mode these keys would go elsewhere.
            type((StyledText) page().getActiveEditor().getAdapter(Control.class), "zz");
            settle();
            assertFalse(page().getActiveEditor().isDirty(), "the keys after the action change no file");
            assertEquals(text, document.get(), "the document keeps its text");
        } finally {
            for (IEditorReference reference : page().getEditorReferences()) {
                IEditorPart opened = reference.getEditor(false);
                if (opened != null && opened != editor) {
                    page().closeEditor(opened, false);
                }
            }
            page().activate(editor);
            UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS);
        }
    }

    /**
     * Leaving the editor for a view moves Neovim out of the list, Terminal mode over, as clicking
     * away from any window that takes the keyboard does (USER_GUIDE.md, "Floating windows"). The
     * plug-in decides whether its window survives that: Telescope closes itself, fzf-lua does not
     * (nor in a terminal, left with {@code Ctrl-\ Ctrl-N} and {@code Ctrl-W w}), so its list stays, drawn without the cursor.
     */
    @Test
    void leavingTheEditorMovesNeovimOutOfTheListWhichStays() throws Exception {
        String text = document.get();
        type(widget, "gz");
        waitUntil("fzf-lua's list drawn", TIMEOUT_MS, () -> listShows("beta"), () -> "; drawn:\n" + drawn());
        type(widget, "al");
        waitUntil("the letters in fzf's prompt", TIMEOUT_MS, () -> listShows("al") && fzfWindows("beta") == 0,
                () -> "; drawn:\n" + drawn());
        page().showView(IPageLayout.ID_TASK_LIST);
        waitUntil("Neovim out of the list, Terminal mode over, the list still open", TIMEOUT_MS, () -> {
            settle();
            return !session.inPluginFloat() && "n".equals(session.getMode()) && fzfWindows("al") == 1;
        }, () -> "; mode " + session.getMode() + ", lists " + fzfWindows(null));
        page().activate(editor);
        UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS);
        waitUntil("the list drawn without the cursor, the caret back", TIMEOUT_MS, () -> {
            settle();
            return fzfWindows("al") == 1 && !pluginFloats().isEmpty()
                    && pluginFloats().stream().allMatch(c -> c.grid() == null || c.grid().cursorRow() < 0)
                    && widget.getCaret() != null;
        }, () -> "; drawn:\n" + drawn() + "caret " + widget.getCaret());
        assertEquals(text, document.get(), "the letters went to fzf, not the document");
    }
}
