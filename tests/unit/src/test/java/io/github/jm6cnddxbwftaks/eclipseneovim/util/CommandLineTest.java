// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.CommandLine.Kind;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.CommandLine.Submission;

import org.junit.jupiter.api.Test;

class CommandLineTest {

    @Test
    void keysOpenTheMatchingKind() {
        assertEquals(Kind.EX, Kind.forKey(":"));
        assertEquals(Kind.SEARCH_FORWARD, Kind.forKey("/"));
        assertEquals(Kind.SEARCH_BACKWARD, Kind.forKey("?"));
        assertNull(Kind.forKey("j"));
        assertNull(Kind.forKey("::"));
        assertNull(Kind.forKey(null));
    }

    @Test
    void prefixesAndSearchFlag() {
        assertEquals(':', Kind.EX.prefix());
        assertEquals('/', Kind.SEARCH_FORWARD.prefix());
        assertEquals('?', Kind.SEARCH_BACKWARD.prefix());
        assertFalse(Kind.EX.isSearch());
        assertTrue(Kind.SEARCH_BACKWARD.isSearch());
    }

    @Test
    void prefillIsJustThePrefixExceptForExFromVisualMode() {
        assertEquals(":", CommandLine.prefill(Kind.EX, false));
        assertEquals(":'<,'>", CommandLine.prefill(Kind.EX, true));
        assertEquals("/", CommandLine.prefill(Kind.SEARCH_FORWARD, true), "a search from visual mode has no range");
        assertEquals("?", CommandLine.prefill(Kind.SEARCH_BACKWARD, false));
    }

    @Test
    void parseStripsThePrefix() {
        assertEquals(new Submission(Kind.EX, "w"), CommandLine.parse(Kind.EX, ":w"));
        assertEquals(new Submission(Kind.EX, "%s/a/b/g"), CommandLine.parse(Kind.EX, ":%s/a/b/g"));
        assertEquals(new Submission(Kind.SEARCH_FORWARD, "foo"), CommandLine.parse(Kind.SEARCH_FORWARD, "/foo"));
        assertEquals(new Submission(Kind.SEARCH_BACKWARD, "foo"), CommandLine.parse(Kind.SEARCH_BACKWARD, "?foo"));
    }

    @Test
    void exCommandsTolerateExtraColonsAndSpaces() {
        assertEquals("w", CommandLine.parse(Kind.EX, "::  w  ").body());
        assertEquals("'<,'>s/a/b/", CommandLine.parse(Kind.EX, ":'<,'>s/a/b/").body());
    }

    @Test
    void aSearchKeepsItsPatternIncludingLeadingAndInnerPunctuation() {
        assertEquals("a/b", CommandLine.parse(Kind.SEARCH_FORWARD, "/a/b").body());
        assertEquals(":foo", CommandLine.parse(Kind.SEARCH_FORWARD, "/:foo").body());
    }

    @Test
    void parseWithoutThePrefixStillWorks() {
        assertEquals("w", CommandLine.parse(Kind.EX, "w").body());
    }

    @Test
    void emptyInputGivesNoCommand() {
        assertNull(CommandLine.parse(Kind.EX, ":"));
        assertNull(CommandLine.parse(Kind.EX, ":   "));
        assertNull(CommandLine.parse(Kind.EX, "::"));
        assertNull(CommandLine.parse(Kind.SEARCH_FORWARD, "/"));
        assertNull(CommandLine.parse(Kind.EX, null));
    }
}
