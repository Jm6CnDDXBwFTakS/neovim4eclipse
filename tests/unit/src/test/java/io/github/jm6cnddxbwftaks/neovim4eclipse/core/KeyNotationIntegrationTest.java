// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.KeyMapping;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

/**
 * The key notation {@link KeyMapping} writes, read by a real Neovim: each key, parsed the way
 * {@code nvim_input} parses it ({@code nvim_replace_termcodes}), and spelled back by
 * {@code keytrans()}, is the key that was pressed. Catches a spelling Neovim reads as something else,
 * such as {@code <A-<>} or a bare {@code <lt>} for Alt+&lt;. The keys chosen are the same on every
 * platform (Option types no other character for them on macOS).
 */
@Tag("integration")
class KeyNotationIntegrationTest {

    private static final int ALT = KeyMapping.SWT_ALT;
    private static final int SHIFT = KeyMapping.SWT_SHIFT;
    private static final int CTRL = KeyMapping.SWT_CTRL;

    @Test
    void neovimReadsEachKeyAsThePressedOne() throws Exception {
        TestNeovim.require();
        try (NeovimSession s = NeovimSession.start(TestNeovim.PATH, List.of("--clean"))) {
            assertEquals("<M-A>", neovimSpelling(s, KeyMapping.toNeovimKey('a', ALT | SHIFT, 'A')));
            assertEquals("<M-a>", neovimSpelling(s, KeyMapping.toNeovimKey('a', ALT, 'a')));
            assertEquals("<M-1>", neovimSpelling(s, KeyMapping.toNeovimKey('1', ALT, '1')));
            assertEquals("<M-lt>", neovimSpelling(s, KeyMapping.toNeovimKey('<', ALT, '<')));
            assertEquals("<M-Space>", neovimSpelling(s, KeyMapping.toNeovimKey(' ', ALT, ' ')));
            assertEquals("<C-lt>", neovimSpelling(s, KeyMapping.toNeovimKey('<', CTRL, '<')));
            assertEquals("<C-->", neovimSpelling(s, KeyMapping.toNeovimKey('-', CTRL, '-')));
            assertEquals("<lt>", neovimSpelling(s, KeyMapping.toNeovimKey('<', 0, '<')));
        }
    }

    /** {@code keys} parsed as {@code nvim_input} parses them, spelled back by {@code keytrans()}. */
    private static String neovimSpelling(NeovimSession s, String keys) throws Exception {
        return String.valueOf(s.lua("return vim.fn.keytrans(vim.api.nvim_replace_termcodes([==[" + keys
                + "]==], true, true, true))"));
    }
}
