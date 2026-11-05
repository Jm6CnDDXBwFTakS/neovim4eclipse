// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The startup option that names a Neovim other than the dedicated one. */
class NeovimOptionTest {

    @Test
    void unsetOrBlankMeansTheDedicatedNeovim() {
        assertEquals("", NeovimOption.of(null));
        assertEquals("", NeovimOption.of(""));
        assertEquals("", NeovimOption.of("  "));
    }

    @Test
    void aProgramIsTakenAsGivenWithoutSurroundingSpace() {
        assertEquals("/usr/local/bin/nvim", NeovimOption.of(" /usr/local/bin/nvim "));
        assertEquals("C:\\Neovim\\bin\\nvim.exe", NeovimOption.of("C:\\Neovim\\bin\\nvim.exe"));
    }

    @Test
    void theLinesToTypeNameTheProperty() {
        assertEquals("neovim4eclipse.nvim", NeovimOption.PROPERTY);
        assertEquals("-Dneovim4eclipse.nvim=/usr/bin/nvim", NeovimOption.iniLine("/usr/bin/nvim"));
        assertEquals("-vmargs -Dneovim4eclipse.nvim=/usr/bin/nvim", NeovimOption.commandLine("/usr/bin/nvim"));
    }

    /** The two lines to copy, each in a field of its own, then the closing sentence. */
    @Test
    void theRowsGiveTheCommandLineAndTheEclipseIniLineToCopy() {
        List<HeadedMessage.Point> rows = NeovimOption.rows("Instead", "Start Eclipse with:", "C:\\A B\\nvim.exe",
                "The end.");
        assertEquals(List.of("Instead", "", ""), rows.stream().map(HeadedMessage.Point::heading).toList());
        assertEquals("-vmargs \"-Dneovim4eclipse.nvim=C:\\A B\\nvim.exe\"", rows.get(0).copy());
        assertEquals("-Dneovim4eclipse.nvim=C:\\A B\\nvim.exe", rows.get(1).copy(), "as it goes into eclipse.ini");
        assertEquals("The end.", rows.get(2).text());
        assertEquals(2, NeovimOption.rows("Instead", "x", "/n", null).size());
    }

    /**
     * The placeholder program in the lines to copy is absolute on its platform: on Windows with a
     * drive letter, since {@code /path/to/nvim} there is refused as not absolute.
     */
    @Test
    void theExampleProgramIsAnAbsolutePathOnEachPlatform() {
        assertEquals("/path/to/nvim", NeovimOption.exampleProgram(false));
        assertEquals("C:\\path\\to\\nvim.exe", NeovimOption.exampleProgram(true));
        assertEquals("-vmargs -Dneovim4eclipse.nvim=C:\\path\\to\\nvim.exe", NeovimOption.commandLine(
                NeovimOption.exampleProgram(true)), "no space, so no quotes");
    }

    /** A path with a space is one argument on the command line, quoted; eclipse.ini takes it as it is. */
    @Test
    void aPathWithASpaceIsQuotedOnTheCommandLineOnly() {
        String program = "C:\\Program Files\\Neovim\\bin\\nvim.exe";
        assertEquals("-vmargs \"-Dneovim4eclipse.nvim=" + program + "\"", NeovimOption.commandLine(program));
        assertEquals("-Dneovim4eclipse.nvim=" + program, NeovimOption.iniLine(program));
    }
}
