// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Search highlighting in a real editor (docs/design/SEARCH_HIGHLIGHTING.md): a search typed in
 * Neovim paints its matches behind the editor's text, all in one color (the box around the one
 * under the caret is {@code SearchHighlighterTest}'s);
 * {@code :noh} and Eclipse mode take them away. The document is the base class's
 * {@code alpha\nbeta\ngamma\n}, where {@code a} is at offsets 0, 4, 9, 12 and 15. The painting
 * itself is {@code SearchHighlighterTest}; the matches Neovim finds are {@code search_spec.lua}.
 */
class SearchHighlightingEndToEndTest extends EndToEndTestBase {

    private static final String TOGGLE = "io.github.jm6cnddxbwftaks.neovim4eclipse.commands.toggleMode";

    @AfterEach
    void noHighlights() throws Exception {
        NeovimEditorController controller = EditorAttachManager.getInstance().getActiveController();
        if (controller != null && !controller.isNeovimModeActive()) {
            runCommand(TOGGLE);
        }
        session.inputAsync("<Esc>:nohlsearch<CR>");
        settle();
    }

    /** The background the widget shows at {@code offset}, or null. */
    private Color backgroundAt(int offset) {
        StyleRange range = widget.getStyleRangeAtOffset(offset);
        return range == null ? null : range.background;
    }

    private void waitForBackground(int offset, boolean painted) {
        waitUntil("offset " + offset + (painted ? " painted" : " not painted"), TIMEOUT_MS,
                () -> (backgroundAt(offset) != null) == painted, () -> "; background " + backgroundAt(offset));
    }

    @Test
    void aSearchPaintsItsMatchesAndNohTakesThemAway() {
        session.inputAsync("gg0/a<CR>"); // from the first a, so the cursor goes to the next: offset 4
        settle();
        for (int offset : new int[] { 0, 4, 9, 12, 15 }) {
            waitForBackground(offset, true);
        }
        waitForBackground(1, false);
        waitUntil("the caret on the match after the first", TIMEOUT_MS, () -> widget.getCaretOffset() == 4);
        assertEquals(backgroundAt(9), backgroundAt(12), "the matches share one color");
        assertEquals(backgroundAt(9), backgroundAt(4), "the one under the caret too: its box marks it");

        session.inputAsync("n"); // to offset 9: the matches stay as they are
        settle();
        waitUntil("the caret on the next match", TIMEOUT_MS, () -> widget.getCaretOffset() == 9);
        waitForBackground(4, true);

        session.inputAsync(":nohlsearch<CR>");
        settle();
        for (int offset : new int[] { 0, 4, 9, 12, 15 }) {
            waitForBackground(offset, false);
        }
    }

    /**
     * Typing in insert mode (Eclipse types, Neovim gets the text at Esc) moves the highlights with
     * the text. A scroll to another match meanwhile has Neovim send its matches, of its older lines:
     * on the line typed in, they would land two characters early, and must not be painted. After
     * Esc the matches are Neovim's again, in the same place. A file long enough to scroll, with a
     * second beta on line 150.
     */
    @Test
    void theHighlightsFollowTypingInInsertMode() throws Exception {
        StringBuilder text = new StringBuilder("alpha\nbeta\n");
        for (int i = 3; i <= 200; i++) {
            text.append(i == 150 ? "beta" : "line " + i).append('\n');
        }
        IFile file = createFile("long.txt", text.toString());
        ITextEditor longEditor = (ITextEditor) IDE.openEditor(page(), file, TEXT_EDITOR, true);
        try {
            waitForAttach(longEditor, "long.txt");
            settle();
            StyledText longWidget = widgetOf(longEditor);
            session.inputAsync("/beta<CR>"); // offset 6
            settle();
            waitForBackgroundIn(longWidget, 6, true);
            session.inputAsync("2GI"); // the start of beta's own line
            settle();
            waitUntil("insert mode", TIMEOUT_MS, () -> "i".equals(session.getMode()));
            typeSlowly(longWidget, "xx"); // xxbeta: beta at 8
            waitForBackgroundIn(longWidget, 8, true);
            waitForBackgroundIn(longWidget, 6, false);

            // Eclipse scrolls alone to line 150's beta: the view report has Neovim send its matches,
            // line 2's at its older column 0 (offset 6).
            ITextViewer longViewer = longEditor.getAdapter(ITextViewer.class); // the viewer tells its viewport listeners
            longViewer.setTopIndex(140);
            settle();
            longViewer.setTopIndex(0);
            settle();
            assertEquals(null, backgroundIn(longWidget, 6), "Neovim's older columns are not painted");
            waitForBackgroundIn(longWidget, 8, true);

            UiTestSupport.escape(longWidget);
            settle();
            waitUntil("normal mode", TIMEOUT_MS, () -> "n".equals(session.getMode()));
            waitForBackgroundIn(longWidget, 8, true);
            waitForBackgroundIn(longWidget, 6, false);
        } finally {
            page().closeEditor(longEditor, false);
        }
    }

    private static Color backgroundIn(StyledText target, int offset) {
        StyleRange range = target.getStyleRangeAtOffset(offset);
        return range == null ? null : range.background;
    }

    private static void waitForBackgroundIn(StyledText target, int offset, boolean painted) {
        waitUntil("offset " + offset + (painted ? " painted" : " not painted"), TIMEOUT_MS,
                () -> (backgroundIn(target, offset) != null) == painted, () -> "; background " + backgroundIn(target, offset));
    }

    /**
     * Each editor attached gets the matches of its own window, whether attaching it switched Neovim's
     * window (back from another editor) or not (Detach, then Attach, on the same editor).
     */
    @Test
    void anEditorAttachedAgainGetsItsMatches() throws Exception {
        session.inputAsync("/beta<CR>"); // offset 6 in test.txt
        settle();
        waitForBackground(6, true);
        IFile other = createFile("other.txt", "beta gamma\n");
        ITextEditor second = (ITextEditor) IDE.openEditor(page(), other, TEXT_EDITOR, true);
        try {
            waitForAttach(second, "other.txt");
            settle();
            StyledText secondWidget = widgetOf(second);
            waitUntil("the second editor's match", TIMEOUT_MS, () -> secondWidget.getStyleRangeAtOffset(0) != null
                    && secondWidget.getStyleRangeAtOffset(0).background != null);

            page().activate(editor); // back: Neovim's window switches
            waitForAttach(editor, "test.txt");
            settle();
            waitForBackground(6, true);

            // As Detach and Attach do, without their notes (an information dialog would wait for a click).
            EditorAttachManager.getInstance().detach();
            settle();
            waitForBackground(6, false);
            EditorAttachManager.getInstance().attach(editor, true); // the same window: no switch in Neovim
            waitForAttach(editor, "test.txt again");
            settle();
            waitForBackground(6, true);
        } finally {
            page().closeEditor(second, false); // a failed wait must not leave it open for the next test
        }
    }

    /** Eclipse mode clears them; back in Neovim mode they are asked for again, and painted. */
    @Test
    void eclipseModeClearsThemAndNeovimModeBringsThemBack() throws Exception {
        session.inputAsync("/mm<CR>"); // gamma's mm, at offset 13
        settle();
        waitForBackground(13, true);
        runCommand(TOGGLE);
        settle();
        waitForBackground(13, false);
        runCommand(TOGGLE);
        settle();
        waitForBackground(13, true);
    }
}
