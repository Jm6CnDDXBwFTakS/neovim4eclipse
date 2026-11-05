// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HeadedMessage.Point;

/** The note for a "Neovim program" preference still stored from before 0.2.1. */
class OldNeovimProgramTest {

    @TempDir
    Path tmp;

    @Test
    void nothingStoredOrBlankShowsNothing() {
        assertEquals(Optional.empty(), OldNeovimProgram.message(null, "", false));
        assertEquals(Optional.empty(), OldNeovimProgram.message("", "", false));
        assertEquals(Optional.empty(), OldNeovimProgram.message("   ", "", false), "blank was the dedicated Neovim, which still runs");
    }

    /** The old value to copy, and the startup option to use instead, ready to copy. */
    @Test
    void aStoredProgramIsShownWithTheLineToUseInstead() {
        HeadedMessage m = OldNeovimProgram.message(" /usr/local/bin/nvim ", "", false).orElseThrow();
        assertEquals("The Neovim program preference is gone: Neovim for Eclipse no longer reads it, and runs the "
                + "dedicated Neovim.", m.intro());
        assertEquals(List.of("Was", "Instead", ""), m.points().stream().map(Point::heading).toList());
        assertEquals("/usr/local/bin/nvim", m.point("Was").orElseThrow().copy());
        Point instead = m.point("Instead").orElseThrow();
        assertEquals("If you named that program yourself, start Eclipse with:", instead.text(),
                "the value may come from an imported preferences file, not from the user");
        assertEquals("-vmargs -Dneovim4eclipse.nvim=/usr/local/bin/nvim", instead.copy());
        assertEquals("or add this line after -vmargs in eclipse.ini:", m.points().get(2).text());
        assertEquals("-Dneovim4eclipse.nvim=/usr/local/bin/nvim", m.points().get(2).copy(), "no -vmargs, no quotes");
        assertEquals("This note shows once; the old setting is removed.", m.footer());
        assertTrue(OldNeovimProgram.TITLE.startsWith("Neovim Program"));
        assertEquals(List.of("Close"), OldNeovimProgram.BUTTONS);
    }

    /** Eclipse already runs with the option: no note (it would say the dedicated Neovim runs), only removal. */
    @Test
    void withTheOptionSetTheValueIsOnlyRemoved() {
        assertEquals(Optional.empty(), OldNeovimProgram.message("/usr/local/bin/nvim", "/usr/local/bin/nvim", false));
        assertTrue(OldNeovimProgram.isStored("/usr/local/bin/nvim"), "still removed");
        assertTrue(!OldNeovimProgram.isStored("  ") && !OldNeovimProgram.isStored(null));
    }

    /** A 0.2.0 folder value is offered as the program inside it, which the option takes. */
    @Test
    void aFolderIsOfferedAsItsProgram() throws IOException {
        Path folder = tmp.resolve("nvim-linux-arm64");
        Files.createDirectories(folder.resolve("bin"));
        Files.createFile(folder.resolve("bin/nvim"));
        HeadedMessage m = OldNeovimProgram.message(folder.toString(), "", false).orElseThrow();
        assertEquals(folder.toString(), m.point("Was").orElseThrow().copy(), "the value as it was stored");
        assertEquals("-vmargs -Dneovim4eclipse.nvim=" + folder.resolve("bin").resolve("nvim"),
                m.point("Instead").orElseThrow().copy());
        assertEquals(tmp.resolve("empty").toString(), OldNeovimProgram.programFor(tmp.resolve("empty").toString(), false),
                "a folder without bin/nvim, or a missing path, as stored");
    }
}
