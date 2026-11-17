// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.JumpLabels;

/**
 * A jump plug-in's labels in a real editor (docs/design/SEARCH_HIGHLIGHTING.md, "Jump labels").
 * The document is the base class's {@code alpha\nbeta\ngamma\n}.
 *
 * <p>The first test sets the marks itself, as a jump plug-in sets them, so it runs everywhere. The
 * second runs flash, one of the pinned plug-ins ({@code releng/test-plugins.properties}), from
 * {@code .tool-cache/test-plugins/} and is skipped when that is not fetched. What Neovim reports is
 * {@code highlights_spec.lua} and {@code jump_plugins_spec.lua}; the painting is
 * {@code NeovimHighlighterTest}.
 */
class JumpLabelsEndToEndTest extends EndToEndTestBase {

    private static final Color DIM = new Color(0x77, 0x77, 0x77);

    @AfterEach
    void noMarks() {
        session.inputAsync("<Esc>:lua vim.api.nvim_buf_clear_namespace(0, vim.api.nvim_create_namespace('jump_test'), 0, -1)<CR>");
        settle();
    }

    private List<JumpLabelPainter.Painted> labels() {
        NeovimEditorController controller = EditorAttachManager.getInstance().getActiveController();
        return controller == null ? List.of() : controller.jumpLabelsForTests();
    }

    private Color foregroundAt(int offset) {
        StyleRange range = widget.getStyleRangeAtOffset(offset);
        return range == null ? null : range.foreground;
    }

    /**
     * Marks as flash sets them, in groups under the built-in prefix {@code Flash}: the backdrop
     * dims the text, the label is painted over its character, and both go with the marks. The
     * document does not change.
     */
    @Test
    void aLabelIsPaintedOverItsCharacterAndTheBackdropDimsTheText() {
        session.inputAsync(":highlight FlashTestBackdrop guifg=#777777<CR>:highlight FlashTestLabel guibg=#101010 guifg=#f0f0f0<CR>");
        session.inputAsync(":lua local ns = vim.api.nvim_create_namespace('jump_test')"
                + " vim.api.nvim_buf_set_extmark(0, ns, 0, 0, { end_row = 1, end_col = 0, hl_group = 'FlashTestBackdrop' })"
                + " vim.api.nvim_buf_set_extmark(0, ns, 1, 2, { virt_text = { { 'x', 'FlashTestLabel' } }, virt_text_pos = 'overlay' })<CR>");
        settle();
        waitUntil("the label", TIMEOUT_MS, () -> labels().size() == 1, () -> "; labels " + labels());
        JumpLabelPainter.Painted painted = labels().get(0);
        assertEquals(new JumpLabels.Label(2, 2, 0, "x", 0x101010, 0xF0F0F0, false), painted.label());
        Point t = widget.getLocationAtOffset(8); // the t of beta: line 2, column 2
        assertEquals(new Point(t.x, t.y), new Point(painted.cell().x, painted.cell().y), "over the t of beta");
        waitUntil("the dimmed first line", TIMEOUT_MS, () -> Objects.equals(DIM, foregroundAt(0)) && Objects.equals(DIM, foregroundAt(4)),
                () -> "; style " + widget.getStyleRangeAtOffset(0));
        assertEquals("alpha\nbeta\ngamma\n", document.get(), "the text is untouched");

        session.inputAsync(":lua vim.api.nvim_buf_clear_namespace(0, vim.api.nvim_create_namespace('jump_test'), 0, -1)<CR>");
        settle();
        waitUntil("no label", TIMEOUT_MS, () -> labels().isEmpty(), () -> "; labels " + labels());
        waitUntil("the text in its own color", TIMEOUT_MS, () -> !Objects.equals(DIM, foregroundAt(0)),
                () -> "; style " + widget.getStyleRangeAtOffset(0));
    }

    /**
     * The real flash: its jump is started, the character to look for typed, and the label the
     * editor painted is typed as the user would read it off the screen; the caret lands on the
     * match that label stood for. flash puts a label right after its match, so the match is one
     * column before the label.
     */
    @Test
    void flashsLabelTypedMovesTheCaretToItsMatch() throws Exception {
        Optional<Path> flash = PinnedPlugins.find("flash");
        assumeTrue(flash.isPresent(), "flash is not fetched at its pinned commit: " + PinnedPlugins.HOW);
        session.inputAsync("gg0:lua " + PinnedPlugins.loadFlash(flash.get()) + "<CR>");
        settle();
        session.inputAsync(":lua vim.schedule(function() require('flash').jump() end)<CR>");
        session.inputAsync("m"); // the m's of gamma
        waitUntil("flash's labels", TIMEOUT_MS, () -> !labels().isEmpty(), () -> "; labels " + labels());
        JumpLabels.Label label = labels().get(0).label();
        int match = document.getLineOffset(label.line1() - 1) + label.col0() - 1;
        assertEquals('m', document.getChar(match), "the label stands right after an m");
        session.inputAsync(label.text());
        waitUntil("the caret on the match at " + match, TIMEOUT_MS, () -> widget.getCaretOffset() == match,
                () -> "; caret " + widget.getCaretOffset());
        waitUntil("no label left", TIMEOUT_MS, () -> labels().isEmpty(), () -> "; labels " + labels());
    }
}
