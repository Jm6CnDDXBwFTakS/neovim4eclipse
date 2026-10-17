// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class EditPathTest {

    private static final Path HOME = Path.of("/home/user").toAbsolutePath();
    private static final Path SRC = Path.of("/ws/app/src").toAbsolutePath();
    private static final Path PROJECT = Path.of("/ws/app").toAbsolutePath();

    @Test
    void relativeNamesTryTheFileDirectoryThenTheProject() {
        assertEquals(List.of(SRC.resolve("Util.java"), PROJECT.resolve("Util.java")),
                EditPath.candidates("Util.java", HOME, List.of(SRC, PROJECT)));
    }

    @Test
    void dotDotIsNormalizedAndDuplicatesDropped() {
        assertEquals(List.of(PROJECT.resolve("pom.xml")),
                EditPath.candidates("../pom.xml", HOME, List.of(SRC, SRC)));
    }

    @Test
    void homeAndAbsolutePathsAreUsedAsTheyAre() {
        assertEquals(List.of(HOME.resolve(".config/nvim/init.lua")),
                EditPath.candidates("~/.config/nvim/init.lua", HOME, List.of(SRC)));
        assertEquals(List.of(HOME), EditPath.candidates("~", HOME, List.of(SRC)));
        Path absolute = PROJECT.resolve("README.md");
        assertEquals(List.of(absolute), EditPath.candidates(absolute.toString(), HOME, List.of(SRC)));
    }

    @Test
    void missingBasesAreSkippedAndBlankNamesGiveNothing() {
        assertEquals(List.of(PROJECT.resolve("a.txt")),
                EditPath.candidates("a.txt", HOME, Arrays.asList(null, PROJECT)));
        assertTrue(EditPath.candidates("  ", HOME, List.of(SRC)).isEmpty());
        assertTrue(EditPath.candidates(null, HOME, List.of(SRC)).isEmpty());
    }
}
