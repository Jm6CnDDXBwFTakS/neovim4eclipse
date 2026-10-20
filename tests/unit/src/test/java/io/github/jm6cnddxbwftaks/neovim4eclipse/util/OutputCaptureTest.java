// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.OutputCapture.Redir;

import org.junit.jupiter.api.Test;

class OutputCaptureTest {

    @Test
    void redirectionsThatStart() {
        for (String c : new String[] { "redir @a", "redi @a", "redir! > out.txt", "redir => g:x", "redir>>file",
                "silent redir @a", "sil! redir @a", ":redir @a", "  REDIR @a" }) {
            assertEquals(Redir.START, OutputCapture.redirOf(c), c);
        }
    }

    @Test
    void redirectionsThatEnd() {
        for (String c : new String[] { "redir END", "redi END", ":redir  END ", "silent redir END" }) {
            assertEquals(Redir.END, OutputCapture.redirOf(c), c);
        }
    }

    @Test
    void otherCommands() {
        for (String c : new String[] { "echo 1", "red", "redraw", "redrawstatus", "!ls", "s/redir/x/", "silent echo 1", "", null }) {
            assertEquals(Redir.NONE, OutputCapture.redirOf(c), String.valueOf(c));
        }
    }
}
