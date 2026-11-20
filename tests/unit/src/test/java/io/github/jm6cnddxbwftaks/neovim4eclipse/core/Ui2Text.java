// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

/**
 * What Neovim shows in ui2's windows ({@link LuaScripts#UI2}), for the integration tests: Neovim
 * draws its command line, messages, pager and prompts itself, and the editor copies their cells as
 * floats ({@code util/Grids}), so a test of a message asks Neovim what its windows hold.
 */
final class Ui2Text {

    private static final String SHOWN = """
            local ui2 = require('vim._core.ui2')
            local all = {}
            for _, kind in ipairs({ 'cmd', 'msg', 'pager', 'dialog' }) do
              local buf = ui2.bufs[kind]
              if buf and vim.api.nvim_buf_is_valid(buf) then
                all[#all + 1] = table.concat(vim.api.nvim_buf_get_lines(buf, 0, -1, false), '\\n')
              end
            end
            return table.concat(all, '\\n')
            """;

    private Ui2Text() {
    }

    /**
     * The text of ui2's cmd, msg, pager and dialog buffers, joined with line breaks; {@code ""} if
     * the call fails, so it can be a wait's condition. Example: after {@code :echo 'done'} it
     * contains {@code done}.
     */
    static String shown(NeovimSession s) {
        try {
            Object text = s.lua(SHOWN);
            return text == null ? "" : String.valueOf(text);
        } catch (Exception e) {
            return "";
        }
    }
}
