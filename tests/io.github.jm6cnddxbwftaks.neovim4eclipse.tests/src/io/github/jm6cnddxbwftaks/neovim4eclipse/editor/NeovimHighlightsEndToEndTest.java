// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;

import java.util.Objects;

import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Color;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Neovim's own highlights in a real editor (docs/design/SEARCH_HIGHLIGHTING.md, "The yank flash and
 * window matches"): a {@code :match} and the flash of a yank are painted in their group's colors,
 * and go when Neovim takes them away. The document is the base class's {@code alpha\nbeta\ngamma\n}.
 * What Neovim reports is {@code highlights_spec.lua}; the painting is {@code NeovimHighlighterTest}.
 */
class NeovimHighlightsEndToEndTest extends EndToEndTestBase {

    /** The test's own group, so that no test depends on Neovim's default colorscheme. */
    private static final String GROUP = ":highlight Mine guibg=#112233 guifg=#aabbcc<CR>";
    private static final Color BACKGROUND = new Color(0x11, 0x22, 0x33);
    private static final Color FOREGROUND = new Color(0xaa, 0xbb, 0xcc);

    @AfterEach
    void noHighlights() {
        session.inputAsync("<Esc>:match none<CR>:lua pcall(vim.api.nvim_del_augroup_by_name, 'flash_test')<CR>");
        settle();
    }

    private Color backgroundAt(int offset) {
        StyleRange range = widget.getStyleRangeAtOffset(offset);
        return range == null ? null : range.background;
    }

    private void waitForColors(int offset, Color background, Color foreground) {
        waitUntil("offset " + offset + " in " + background, TIMEOUT_MS, () -> {
            StyleRange range = widget.getStyleRangeAtOffset(offset);
            return range != null && Objects.equals(background, range.background) && Objects.equals(foreground, range.foreground);
        }, () -> "; style " + widget.getStyleRangeAtOffset(offset));
    }

    /**
     * Waits until {@code offset} has no highlight of the test's group. Another background may be
     * there: an earlier test's search, which {@code 'hlsearch'} keeps showing, is not this test's.
     */
    private void waitForNone(int offset) {
        waitUntil("offset " + offset + " without the group's background", TIMEOUT_MS,
                () -> !BACKGROUND.equals(backgroundAt(offset)), () -> "; background " + backgroundAt(offset));
    }

    @Test
    void aMatchIsPaintedInItsGroupsColorsAndMatchNoneTakesItAway() {
        session.inputAsync(GROUP + ":match Mine /mm/<CR>"); // gamma's mm, at offset 13
        settle();
        waitForColors(13, BACKGROUND, FOREGROUND);
        waitForColors(14, BACKGROUND, FOREGROUND);
        waitForNone(12);
        session.inputAsync(":match none<CR>");
        settle();
        waitForNone(13);
    }

    /**
     * The flash a user's {@code TextYankPost} autocommand asks for ({@code vim.hl.on_yank}), here
     * kept until the test takes it away: a flash that ends on its own (150 ms by default) could end
     * before a loaded machine paints it, and its timer is Neovim's.
     */
    @Test
    void theFlashOfAYankIsPaintedAndGoesWithIt() {
        session.inputAsync(GROUP + ":lua vim.api.nvim_create_autocmd('TextYankPost', { group = vim.api.nvim_create_augroup('flash_test', {}),"
                + " callback = function() vim.hl.on_yank({ higroup = 'Mine', timeout = 600000 }) end })<CR>");
        session.inputAsync("ggjyy"); // beta, offsets 6 to 9
        settle();
        waitForColors(6, BACKGROUND, FOREGROUND);
        waitForColors(9, BACKGROUND, FOREGROUND);
        waitForNone(0);
        session.inputAsync(":lua vim.api.nvim_buf_clear_namespace(0, vim.api.nvim_create_namespace('nvim.hlyank'), 0, -1)<CR>");
        settle();
        waitForNone(6);
    }
}
