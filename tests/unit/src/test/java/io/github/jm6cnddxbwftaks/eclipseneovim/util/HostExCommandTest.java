// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.HostExCommand.Kind;

import org.junit.jupiter.api.Test;

class HostExCommandTest {

    private static void expect(String raw, Kind kind, boolean force) {
        HostExCommand cmd = HostExCommand.parse(raw);
        assertEquals(kind, cmd.kind(), raw);
        assertEquals(force, cmd.force(), raw + " (force)");
    }

    private static void expectAll(Kind kind, String... commands) {
        for (String c : commands) {
            expect(c, kind, false);
            expect(c + "!", kind, true);
        }
    }

    @Test
    void writeCommands() {
        for (String c : new String[] { "w", "wr", "write", "up", "update", ":w", "  :w  ", ":W", "%w", "w file.txt" }) {
            expect(c, Kind.WRITE, false);
        }
        expect("w!", Kind.WRITE, true);
    }

    @Test
    void writeAllCommands() {
        expectAll(Kind.WRITE_ALL, "wa", "wal", "wall", ":wa", "%wa");
        expect("  :WA ", Kind.WRITE_ALL, false);
    }

    @Test
    void quitCommands() {
        expectAll(Kind.QUIT, "q", "qu", "quit", ":q");
    }

    @Test
    void quitAllCommands() {
        expectAll(Kind.QUIT_ALL, "qa", "qal", "qall", "quita", "quitall", ":qa");
    }

    @Test
    void writeQuitCommands() {
        expectAll(Kind.WRITE_QUIT, "wq", "x", "xi", "xit", "exi", "exit", ":wq");
        expect("wq file", Kind.WRITE_QUIT, false);
    }

    @Test
    void writeQuitAllCommands() {
        expectAll(Kind.WRITE_QUIT_ALL, "wqa", "wqal", "wqall", "xa", "xal", "xall", ":xa");
    }

    @Test
    void editKeepsTheArgumentAsTyped() {
        HostExCommand edit = HostExCommand.parse(":e  src/Main.java ");
        assertEquals(Kind.EDIT, edit.kind());
        assertEquals("src/Main.java", edit.argument());
        assertFalse(edit.force());

        HostExCommand revert = HostExCommand.parse("e!");
        assertEquals(Kind.EDIT, revert.kind());
        assertTrue(revert.force());
        assertEquals("", revert.argument());

        assertEquals("~/My File.txt", HostExCommand.parse("edit! ~/My File.txt").argument());
    }

    @Test
    void tabCommandsTakeAnOptionalCount() {
        expect("tabn", Kind.TAB_NEXT, false);
        expect("tabnext", Kind.TAB_NEXT, false);
        expect("tabp", Kind.TAB_PREVIOUS, false);
        expect("tabprevious", Kind.TAB_PREVIOUS, false);
        expect("tabN", Kind.TAB_PREVIOUS, false); // capital N: previous, unlike tabn
        expect("tabNext", Kind.TAB_PREVIOUS, false);
        expect("tabfir", Kind.TAB_FIRST, false);
        expect("tabrewind", Kind.TAB_FIRST, false);
        expect("tabl", Kind.TAB_LAST, false);

        assertEquals(3, HostExCommand.parse("tabn 3").count());
        assertEquals(0, HostExCommand.parse("tabn").count());
        assertEquals(2, HostExCommand.parse("tabp 2").count());
    }

    @Test
    void everythingElseGoesToNeovim() {
        for (String c : new String[] { "%s/a/b/g", "s/a/b/", "set number", "w!x", "q foo", "qa foo", "write2",
                "g/x/d", "tabnew", "tabn x", "tabn -1", "tabl 2", "u", "ec 1", "", "   ", ":", "!", null }) {
            assertFalse(HostExCommand.parse(c).isHostCommand(), String.valueOf(c));
        }
    }

    @Test
    void abbreviationsRunFromTheShortestToTheFullName() {
        assertEquals(Set.of("wa", "wal", "wall"), HostExCommand.abbrev("wa", "wall"));
        assertThrows(IllegalArgumentException.class, () -> HostExCommand.abbrev("x", "wall"));
    }

    @Test
    void partialEditLinesAreEditsForTabCompletion() {
        // Tab in the : box completes file names in :e's own folders for these.
        for (String partial : new String[] { "e", "e ", "e al", "edit sub/" }) {
            expect(partial, Kind.EDIT, false);
        }
        expect("edit! x", Kind.EDIT, true);
        assertEquals(Kind.NONE, HostExCommand.parse("ec").kind(), ":echo, not :e");
    }

    @Test
    void plusPlusPCreatesFoldersForWritesOnly() {
        for (String raw : new String[] { "w ++p", ":wq ++p", "x ++p", "up ++p", "w! ++p", "w ++p other.txt" }) {
            assertTrue(HostExCommand.parse(raw).createFolders(), raw);
        }
        for (String raw : new String[] { "w", "w foo", "w ++pfoo", "wa", "e ++p", "q", "w foo ++p" }) {
            assertFalse(HostExCommand.parse(raw).createFolders(), raw);
        }
    }

    @Test
    void isHostCommandReflectsKind() {
        assertTrue(HostExCommand.parse("w").isHostCommand());
        assertFalse(HostExCommand.parse("echo 1").isHostCommand());
    }
}
