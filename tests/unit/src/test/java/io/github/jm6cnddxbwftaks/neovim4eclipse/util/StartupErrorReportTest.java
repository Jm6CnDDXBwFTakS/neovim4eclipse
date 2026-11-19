// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** noice.nvim's three lines, as noice 4.x prints them when it turns itself off under the plug-in's UI. */
    private static final String NOICE = """
            You're using a GUI that uses ext_cmdline. Noice can't work when the GUI has ext_cmdline enabled.
            You're using a GUI that uses ext_popupmenu. Noice can't work when the GUI has ext_popupmenu enabled.
            You're using a GUI that uses ext_messages. Noice can't work when the GUI has ext_messages enabled.
            """;

    @Test
    void noicesLinesAloneOpenNoWarning() {
        assertEquals("", StartupErrorReport.withoutKnownNotices(NOICE));
        // Windows line ends and spaces around a line change nothing.
        assertEquals("", StartupErrorReport.withoutKnownNotices(NOICE.replace("\n", "\r\n")));
        assertEquals("", StartupErrorReport.withoutKnownNotices("  " + NOICE.replace("\n", "  \n")));
    }

    @Test
    void anErrorBesideNoicesLinesIsKept() {
        String kept = StartupErrorReport.withoutKnownNotices("Error in /home/you/.config/nvim/init.lua:\n" + NOICE
                + "E5112: Lua chunk: init.lua:2: '=' expected near '<'\n");
        assertEquals("Error in /home/you/.config/nvim/init.lua:\nE5112: Lua chunk: init.lua:2: '=' expected near '<'",
                kept);
    }

    @Test
    void onlyNoicesExactSentencesAreLeftOut() {
        // A line that only resembles noice's (another extension, other words around it) stays.
        String lookalikes = """
                You're using a GUI that uses ext_tabline. Noice can't work when the GUI has ext_tabline enabled.
                You're using a GUI that uses ext_cmdline. Noice can't work when the GUI has ext_messages enabled.
                E5108: You're using a GUI that uses ext_cmdline. Noice can't work when the GUI has ext_cmdline enabled.
                """;
        assertEquals(lookalikes.strip(), StartupErrorReport.withoutKnownNotices(lookalikes));
    }

    @Test
    void messagesWithoutNoiceAreUnchanged() {
        assertEquals("E492: Not an editor command: nnoremap ; :",
                StartupErrorReport.withoutKnownNotices("E492: Not an editor command: nnoremap ; :\n"));
        assertEquals("", StartupErrorReport.withoutKnownNotices(""));
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
