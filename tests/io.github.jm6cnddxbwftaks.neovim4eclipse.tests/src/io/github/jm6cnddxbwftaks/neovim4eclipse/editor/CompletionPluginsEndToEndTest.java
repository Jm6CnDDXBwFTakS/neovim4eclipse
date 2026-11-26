// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.ctrlKey;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.escape;
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

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.swt.widgets.Control;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.PinnedPlugins;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;

/**
 * Real completion plug-ins in a real editor, at the commits pinned and vetted in
 * {@code releng/test-plugins.properties}: blink.cmp (its pure-Lua matcher, nothing downloaded) and
 * nvim-cmp with cmp-buffer. docs/USER_GUIDE.md, "Completion", "A completion plug-in's keys": the
 * plug-in's {@code Ctrl-N}, after letters Eclipse typed, leaves the rest of the insert to Neovim.
 * Here, the keys pressed on the editor: the plug-in's menu is drawn over the text, the letters typed
 * next reach Neovim and narrow it, the caret follows the text the plug-in puts in (it stayed behind
 * with nvim-cmp, which puts its match in a moment after the key), its {@code Ctrl-E} and
 * {@code Ctrl-Y} cancel and accept, Eclipse's text and Neovim's agree (the text check after each
 * test), and after {@code Esc} the next insert is Eclipse's. The same through the session alone is
 * {@code CompletionPluginsIntegrationTest}. Skipped without the plug-ins ({@link PinnedPlugins#HOW}).
 *
 * <p>The UI tests share one Neovim, and a plug-in's {@code setup()} acts on all of it
 * (autocommands, mappings in every buffer) with no way to undo it. So each test that set one up
 * ends with <em>Restart Neovim</em> ({@code restartNeovimAfterThisTest}): the next test gets a Neovim
 * that never loaded it.
 */
class CompletionPluginsEndToEndTest extends EndToEndTestBase {

    private static final String WORDS = "alpha\nalphabet\nalpine\n";

    /** Whether this test set a plug-in up, so that its Neovim is replaced afterwards. */
    private boolean loaded;

    @AfterEach
    void restartNeovim() {
        if (!loaded) {
            return;
        }
        NeovimSession fresh = restartNeovimAfterThisTest();
        assertEquals(true, SessionProbe.lua(fresh,
                "return package.loaded['blink.cmp'] == nil and package.loaded['cmp'] == nil"),
                "the next test's Neovim never loaded the plug-in");
    }

    @Test
    void blinkCmpsMenuFollowsTheTypingAfterItsCtrlN() throws Exception {
        Optional<Path> blink = PinnedPlugins.find("blink.cmp");
        assumeTrue(blink.isPresent(), "blink.cmp is not fetched at its pinned commit: " + PinnedPlugins.HOW);
        loaded = true;
        SessionProbe.lua(session, PinnedPlugins.loadBlinkCmp(blink.get()) + " return 0");
        waitUntil("blink.cmp set up with its Lua matcher, no program asked for", TIMEOUT_MS,
                () -> "lua".equals(SessionProbe.lua(session, PinnedPlugins.BLINK_READY)),
                () -> "; " + SessionProbe.lua(session, PinnedPlugins.BLINK_READY));
        completesAndHandsBack();
        assertEquals("lua", SessionProbe.lua(session, PinnedPlugins.BLINK_READY), "still no program asked for");
    }

    @Test
    void nvimCmpsMenuFollowsTheTypingAfterItsCtrlN() throws Exception {
        Optional<Path> cmp = PinnedPlugins.find("nvim-cmp");
        Optional<Path> buffer = PinnedPlugins.find("cmp-buffer");
        assumeTrue(cmp.isPresent() && buffer.isPresent(),
                "nvim-cmp and cmp-buffer are not fetched at their pinned commits: " + PinnedPlugins.HOW);
        loaded = true;
        SessionProbe.lua(session, PinnedPlugins.loadNvimCmp(cmp.get(), buffer.get()) + " return 0");
        completesAndHandsBack();
    }

    /**
     * The steps both plug-ins go through, on a fourth line under three words. {@code o} and
     * {@code al} typed by Eclipse, then the plug-in's {@code Ctrl-N}: its menu is drawn with the
     * words (whether a match is put in at once is a matter of timing: see
     * {@code CompletionPluginsIntegrationTest}). {@code Ctrl-E} takes back what it put in, if
     * anything; {@code phab}, typed by Neovim now, opens the menu again by itself, narrowed to
     * {@code alphabet}, the caret after the letters; {@code Ctrl-Y} accepts it, the caret after the
     * word. {@code Esc} ends the insert, and in the next one Eclipse types {@code al} with no menu.
     * nvim-cmp's late match right after the key (the letters then landed inside it) is the
     * stand-in mapping's test, {@code CompletionMenuEndToEndTest}, which controls the delay.
     */
    private void completesAndHandsBack() throws Exception {
        document.set(WORDS);
        settle();
        type(widget, "G");
        type(widget, "o");
        waitUntil("an insert Eclipse types", TIMEOUT_MS,
                () -> session.isTypingMode() && session.isEclipseTypingReady() && !session.isNeovimDrivenInsert());
        type(widget, "al");
        assertEquals(WORDS + "al\n", document.get(), "Eclipse typed the letters");
        ctrlKey(widget, 'n');
        waitUntil("the plug-in's menu drawn, with the words", TIMEOUT_MS,
                () -> menuShows("alphabet") && menuShows("alpine"), () -> "; " + state());
        assertTrue(session.isNeovimDrivenInsert(), "Neovim types the rest of the insert");

        ctrlKey(widget, 'e');
        waitUntil("the menu closed, the line back to al", TIMEOUT_MS,
                () -> !menuShows("alpha") && (WORDS + "al\n").equals(document.get()), () -> "; " + state());

        type(widget, "phab");
        waitUntil("the letters typed by Neovim, the caret after them, the menu narrowed to alphabet", TIMEOUT_MS,
                () -> (WORDS + "alphab\n").equals(document.get()) && caretAtEndOfLine4()
                        && menuShows("alphabet") && !menuShows("alpine"),
                () -> "; " + state());

        ctrlKey(widget, 'y');
        waitUntil("alphabet accepted, the menu closed, the caret after the word", TIMEOUT_MS,
                () -> (WORDS + "alphabet\n").equals(document.get()) && caretAtEndOfLine4() && !menuShows("alpine"),
                () -> "; " + state());
        assertTrue(session.isNeovimDrivenInsert(), "Neovim types on until Esc");

        escape(widget);
        waitUntil("Normal mode, the insert over", TIMEOUT_MS,
                () -> "n".equals(session.getMode()) && !session.isNeovimDrivenInsert(), () -> "; " + state());
        assertEquals(List.of("alpha", "alphabet", "alpine", "alphabet"), session.getBufferLines());

        type(widget, "o");
        waitUntil("the next insert, typed by Eclipse", TIMEOUT_MS,
                () -> session.isTypingMode() && session.isEclipseTypingReady() && !session.isNeovimDrivenInsert(),
                () -> "; " + state());
        type(widget, "al");
        assertEquals(WORDS + "alphabet\nal\n", document.get(), "Eclipse typed it");
        assertFalse(menuShows("alphabet"), "no menu: Neovim has not seen the letters");
        escape(widget);
        waitUntil("Normal mode", TIMEOUT_MS, () -> "n".equals(session.getMode()));
    }

    /** Whether the caret is at the end of the fourth line, where the typing happens. */
    private boolean caretAtEndOfLine4() {
        try {
            int end = document.getLineOffset(3) + document.getLineLength(3) - document.getLineDelimiter(3).length();
            return widget.getCaretOffset() == end;
        } catch (BadLocationException | NullPointerException e) {
            return false;
        }
    }

    /** Whether a plug-in's float drawn over the text shows {@code word}. */
    private boolean menuShows(String word) {
        settle();
        return pluginFloats().stream().anyMatch(c -> c.isVisible() && rowsOf(c.grid()).contains(word));
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

    /** For a failure's message: the document, the caret, the floats drawn, Neovim's mode, line and menus. */
    private String state() {
        try {
            return describe();
        } catch (AssertionError e) { // Neovim did not answer: the wait's own message still shows
            return "Neovim did not answer: " + e.getMessage();
        }
    }

    private String describe() {
        StringBuilder drawn = new StringBuilder();
        for (GridCanvas canvas : pluginFloats()) {
            drawn.append(canvas.isVisible() ? "" : "(hidden) ").append(rowsOf(canvas.grid()).replace('\n', '/')).append(" | ");
        }
        return "document " + document.get().replace("\n", "|") + ", caret " + widget.getCaretOffset()
                + ", drawn " + drawn + "driven " + session.isNeovimDrivenInsert() + ", Neovim "
                + SessionProbe.lua(session, "return vim.api.nvim_get_mode().mode .. ' ' .. vim.inspect(vim.fn.getline(4))"
                        + " .. ' menus ' .. (function(...)\n" + PinnedPlugins.COMPLETION_MENUS + "\nend)()"
                        + " .. ' errmsg ' .. vim.v.errmsg");
    }
}
