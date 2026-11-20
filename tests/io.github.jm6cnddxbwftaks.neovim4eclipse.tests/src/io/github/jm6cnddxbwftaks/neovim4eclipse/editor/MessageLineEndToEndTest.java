// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Neovim's messages and errors along the bottom of the editor, from a real key to what Neovim
 * shows: ui2 ({@code LuaScripts.UI2}) draws them in its own windows over the editor's last row
 * ({@code editor/FloatingWindows}), and nothing is left in Eclipse's status bar. The text is read
 * from ui2's buffers ({@link #shownText}); how the cells are drawn is tested with the floats
 * ({@code FloatingWindowsEndToEndTest}).
 */
class MessageLineEndToEndTest extends EndToEndTestBase {

    /** An error and a plain message both show in Neovim's line; neither in the status bar. */
    @Test
    void anErrorAndAMessageFromNeovimShowInItsLine() {
        runCommandLine(widget, "s/zzz/y/");

        expectShown("E486: Pattern not found: zzz");
        assertTrue(statusLineTexts(editor).stream().noneMatch(text -> text.contains("E486")),
                "not in the status bar: " + statusLineTexts(editor));

        typeSlowly("3yy"); // a message of another kind than an error
        expectShown("3 lines yanked");
    }

    /**
     * The plug-in's own messages go through Neovim too ({@code NeovimSession.echoAsync}, Neovim's
     * {@code nvim_echo}), so they show where Neovim's do and are kept in {@code :messages}.
     */
    @Test
    void aMessageOfThePluginsShowsInNeovimsLine() {
        session.echoAsync("neovim4eclipse says hello", false);
        expectShown("neovim4eclipse says hello");
    }
}
