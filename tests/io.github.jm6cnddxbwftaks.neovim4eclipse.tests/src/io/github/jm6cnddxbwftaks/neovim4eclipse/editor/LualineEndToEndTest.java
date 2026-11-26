// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.escape;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;

import org.eclipse.swt.custom.StyledText;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.PinnedPlugins;

/**
 * lualine.nvim in a real editor, at the commit pinned and vetted in
 * {@code releng/test-plugins.properties}: USER_GUIDE says the editor draws your
 * {@code 'statusline'}, lualine's included, under its text. The row drawn there is lualine's line:
 * the mode by lualine's names, following the keys pressed on the editor ({@code i},
 * {@code v}, {@code Esc}), the file's name, and {@code [+]} once the document changed; an editor in
 * the background keeps the line lualine last wrote for it. The same
 * through the session alone is {@code LualineIntegrationTest}. Skipped without the plug-in
 * ({@link PinnedPlugins#HOW}).
 *
 * <p>lualine sets {@code 'statusline'} for the whole Neovim, which the UI tests share, so each
 * test ends with <em>Restart Neovim</em> ({@code restartNeovimAfterThisTest}).
 */
class LualineEndToEndTest extends EndToEndTestBase {

    /** Whether this test loaded the plug-in, so that its Neovim is replaced afterwards. */
    private boolean loaded;

    @BeforeEach
    void loadLualine() {
        Optional<Path> lualine = PinnedPlugins.find("lualine");
        assumeTrue(lualine.isPresent(), "lualine is not fetched at its pinned commit: " + PinnedPlugins.HOW);
        loaded = true;
        SessionProbe.lua(session, PinnedPlugins.loadLualine(lualine.get()) + " return 0");
    }

    @AfterEach
    void restartNeovim() {
        if (!loaded) {
            return; // skipped, or the editor never opened: nothing was loaded
        }
        NeovimSession fresh = restartNeovimAfterThisTest();
        assertEquals(true, SessionProbe.lua(fresh, "return package.loaded['lualine'] == nil"),
                "the next test's Neovim never loaded lualine");
    }

    @Test
    void theEditorDrawsLualinesStatusLineAndItFollowsTheMode() {
        int window = ((Number) SessionProbe.lua(session, "return vim.api.nvim_get_current_win()")).intValue();
        type(widget, "l");
        expectRow(window, row -> row.startsWith(" NORMAL ") && row.contains("test.txt"),
                "lualine's line under the text, Normal mode, the file's name");
        type(widget, "i");
        expectRow(window, row -> row.startsWith(" INSERT "), "INSERT, in the insert Eclipse types");
        escape(widget);
        type(widget, "v");
        expectRow(window, row -> row.startsWith(" VISUAL "), "VISUAL");
        escape(widget);
        type(widget, "x");
        expectRow(window, row -> row.startsWith(" NORMAL ") && row.contains("test.txt [+]"),
                "NORMAL again, and [+] once the document changed");
        assertEquals("", SessionProbe.lua(session, PinnedPlugins.PROGRAMS_REFUSED), "lualine asked to start no program");
    }

    /**
     * Two editors side by side ({@code :vsplit}): the editor in the background shows the line lualine
     * last wrote for its window, from when it had the keyboard, not lualine's inactive sections.
     * lualine writes each window's line as finished text and refreshes the current tabpage's only
     * (its {@code refresh}, scope {@code tabpage}), and each editor's window is in a tabpage of its
     * own (USER_GUIDE, "Status line"). Example: {@code i} in the new editor shows {@code INSERT}
     * there, and the editor beside it still {@code NORMAL}.
     */
    @Test
    void anEditorInTheBackgroundShowsTheLineLualineLastWroteForIt() {
        int first = ((Number) SessionProbe.lua(session, "return vim.api.nvim_get_current_win()")).intValue();
        type(widget, "l");
        expectRow(widget, first, row -> row.startsWith(" NORMAL "), "lualine's line in the first editor");
        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        session.commandAsync("vsplit");
        try {
            waitUntil("the clone of :vsplit", TIMEOUT_MS, () -> page.getActiveEditor() != editor
                    && page.getActiveEditor() instanceof ITextEditor);
            ITextEditor clone = (ITextEditor) page.getActiveEditor();
            waitForAttach(clone, "the clone of :vsplit");
            int second = ((Number) SessionProbe.lua(session, "return vim.api.nvim_get_current_win()")).intValue();
            StyledText cloneText = widgetOf(clone);
            type(cloneText, "i");
            expectRow(cloneText, second, row -> row.startsWith(" INSERT "), "INSERT in the editor with the keyboard");
            expectRow(widget, first, row -> row.startsWith(" NORMAL "),
                    "the editor beside it still shows the line lualine wrote for it");
            escape(cloneText);
            waitUntil("Normal mode", TIMEOUT_MS, () -> "n".equals(session.getMode()));
        } finally {
            for (IEditorReference reference : page.getEditorReferences()) {
                if (reference.getEditor(false) != editor) {
                    page.closeEditors(new IEditorReference[] { reference }, false);
                }
            }
            settle();
        }
    }

    private void expectRow(int window, Predicate<String> check, String what) {
        expectRow(widget, window, check, what);
    }

    private void expectRow(StyledText text, int window, Predicate<String> check, String what) {
        waitUntil(what, TIMEOUT_MS, () -> {
            settle();
            String row = statusRowText(text, window);
            return row != null && check.test(row);
        }, () -> "; the row is '" + statusRowText(text, window) + "'");
    }
}
