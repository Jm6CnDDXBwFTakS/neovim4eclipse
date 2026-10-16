// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.HostExCommand.Kind;

import org.junit.jupiter.api.Test;

class HostExCommandTest {

    private static void expect(String raw, Kind kind, boolean force) {
        HostExCommand cmd = HostExCommand.parse(raw);
        assertEquals(kind, cmd.kind(), raw);
        assertEquals(force, cmd.force(), raw + " (force)");
    }

    @Test
    void writeCommands() {
        for (String c : new String[] { "w", "write", "update", "up", ":w", "  :w  ", ":W", "%w", "w file.txt" }) {
            expect(c, Kind.WRITE, false);
        }
        expect("w!", Kind.WRITE, true);
    }

    @Test
    void quitCommands() {
        for (String c : new String[] { "q", "quit", "qa", "quita", ":q" }) {
            expect(c, Kind.QUIT, false);
        }
        for (String c : new String[] { "q!", "quit!", "qa!", "quita!" }) {
            expect(c, Kind.QUIT, true);
        }
    }

    @Test
    void writeQuitCommands() {
        for (String c : new String[] { "wq", "x", "exit", "xit", "wq file", ":wq" }) {
            expect(c, Kind.WRITE_QUIT, false);
        }
        for (String c : new String[] { "wq!", "x!", "exit!" }) {
            expect(c, Kind.WRITE_QUIT, true);
        }
    }

    @Test
    void everythingElseGoesToNeovim() {
        for (String c : new String[] { "%s/a/b/g", "s/a/b/", "set number", "e foo", "wa", "wqa", "xa",
                "w!x", "q foo", "write2", "g/x/d", "", "   ", ":", null }) {
            assertFalse(HostExCommand.parse(c).isHostCommand(), String.valueOf(c));
        }
    }

    @Test
    void isHostCommandReflectsKind() {
        assertTrue(HostExCommand.parse("w").isHostCommand());
        assertFalse(HostExCommand.parse("echo 1").isHostCommand());
    }
}
