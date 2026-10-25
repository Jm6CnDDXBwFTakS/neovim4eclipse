// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The warning about errors in the user's Neovim configuration. */
class StartupErrorReportTest {

    @Test
    void showsNeovimsMessagesAndHowToTryAgain() {
        String message = StartupErrorReport.message("Error in /home/me/.config/nvim/init.lua:\n"
                + "E5112: Lua chunk: init.lua:2: '=' expected near '<'\n");
        assertTrue(message.contains("E5112: Lua chunk: init.lua:2"), message);
        assertTrue(message.contains("partly loaded"), message);
        assertTrue(message.contains("restart Eclipse"), message);
        assertTrue(message.contains("vim.keymap.set"), message);
    }

    @Test
    void longMessagesAreCut() {
        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= StartupErrorReport.MAX_LINES + 5; i++) {
            many.append("E").append(i).append(": error ").append(i).append('\n');
        }
        String message = StartupErrorReport.message(many.toString());
        assertTrue(message.contains("E" + StartupErrorReport.MAX_LINES + ": "), message);
        assertFalse(message.contains("E" + (StartupErrorReport.MAX_LINES + 1) + ": "), message);
        assertTrue(message.contains("5 more lines: see :messages"), message);
    }
}
