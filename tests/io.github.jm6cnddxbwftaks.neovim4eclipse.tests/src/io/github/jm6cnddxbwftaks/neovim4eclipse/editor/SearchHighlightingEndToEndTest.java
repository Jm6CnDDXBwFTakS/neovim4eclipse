// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Color;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Search highlighting in a real editor (docs/design/SEARCH_HIGHLIGHTING.md): a search typed in
 * Neovim paints its matches behind the editor's text, the one under the caret a shade stronger;
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
        assertEquals(backgroundAt(9), backgroundAt(12), "two matches, one color");
        waitUntil("the match under the caret", TIMEOUT_MS, () -> widget.getCaretOffset() == 4);
        assertNotEquals(backgroundAt(9), backgroundAt(4), "the current match is stronger");

        session.inputAsync("n"); // to offset 9: the current match moves with the caret
        settle();
        waitUntil("the caret on the next match", TIMEOUT_MS, () -> widget.getCaretOffset() == 9);
        waitUntil("the current match on offset 9", TIMEOUT_MS, () -> backgroundAt(9).equals(backgroundAt(4)) == false
                && backgroundAt(4).equals(backgroundAt(12)));

        session.inputAsync(":nohlsearch<CR>");
        settle();
        for (int offset : new int[] { 0, 4, 9, 12, 15 }) {
            waitForBackground(offset, false);
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
