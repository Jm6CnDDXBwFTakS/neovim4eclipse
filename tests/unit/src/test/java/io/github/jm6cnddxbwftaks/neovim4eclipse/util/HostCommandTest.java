// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HostCommand.Kind;

/**
 * {@link HostCommand}: the commands Neovim hands to Eclipse, by Neovim's full name. Abbreviations,
 * {@code !} and ranges are Neovim's to parse; that real lines arrive in this form is tested against
 * a real Neovim ({@code NeovimSessionIntegrationTest}).
 */
class HostCommandTest {

    private static Kind kind(String name, String... args) {
        HostCommand cmd = HostCommand.of(name, false, List.of(args));
        return cmd == null ? null : cmd.kind();
    }

    @Test
    void eachFullNameHasItsKind() {
        assertEquals(Kind.WRITE_ALL, kind("wall"));
        assertEquals(Kind.QUIT, kind("quit"));
        assertEquals(Kind.QUIT_ALL, kind("qall"));
        assertEquals(Kind.QUIT_ALL, kind("quitall"));
        assertEquals(Kind.WRITE_QUIT, kind("wq"));
        assertEquals(Kind.WRITE_QUIT, kind("xit"));
        assertEquals(Kind.WRITE_QUIT, kind("exit"));
        assertEquals(Kind.WRITE_QUIT_ALL, kind("wqall"));
        assertEquals(Kind.WRITE_QUIT_ALL, kind("xall"));
        assertEquals(Kind.EDIT, kind("edit", "a.txt"));
        assertEquals(Kind.TAB_NEXT, kind("tabnext"));
        assertEquals(Kind.TAB_PREVIOUS, kind("tabprevious"));
        assertEquals(Kind.TAB_PREVIOUS, kind("tabNext"), ":tabN is backward, whatever its spelling suggests");
        assertEquals(Kind.TAB_FIRST, kind("tabfirst"));
        assertEquals(Kind.TAB_FIRST, kind("tabrewind"));
        assertEquals(Kind.TAB_LAST, kind("tablast"));
    }

    /** {@code :bd} and its relatives close the editor, like {@code :q}; with buffers, those editors. */
    @Test
    void bufferCommandsCloseThisEditorOrTheNamedOnes() {
        for (String name : List.of("bdelete", "bwipeout", "bunload")) {
            assertEquals(Kind.QUIT, kind(name), name);
            assertEquals(Kind.BUFFER_DELETE, kind(name, "3", "other.txt"), name);
        }
        assertEquals(List.of("3", "other.txt"), HostCommand.of("bdelete", true, List.of("3", "other.txt")).args());
    }

    @Test
    void argumentsACommandDoesNotTakeMakeItNeovims() {
        assertNull(kind("quit", "foo"));
        assertNull(kind("wall", "x"));
        assertNull(kind("tablast", "2"));
        assertNull(kind("tabnext", "two"));
        assertNull(kind("tabnext", "1", "2"));
        assertEquals(Kind.TAB_NEXT, kind("tabnext", "3"));
    }

    @Test
    void otherCommandsAreNotEclipses() {
        assertNull(kind("substitute"));
        assertNull(kind("q"), "abbreviations never arrive: Neovim sends full names");
        assertNull(kind("Quit"));
        assertNull(HostCommand.of(null, false, null));
    }

    @Test
    void bangIsForce() {
        assertTrue(HostCommand.of("quit", true, List.of()).force());
        assertFalse(HostCommand.of("quit", false, List.of()).force());
    }

    /** Writes are Neovim's: its write hook asks Eclipse (core/DocumentWrite). */
    @Test
    void writeAndUpdateAreNeovims() {
        assertNull(kind("write"));
        assertNull(kind("update"));
    }

    @Test
    void wqWritesUnchangedFilesXDoesNot() {
        assertTrue(HostCommand.of("wq", false, List.of()).writesUnchanged());
        assertFalse(HostCommand.of("xit", false, List.of()).writesUnchanged());
        assertFalse(HostCommand.of("exit", false, List.of()).writesUnchanged());
    }

    @Test
    void plusPlusPCreatesFoldersOnlyForWrites() {
        assertTrue(HostCommand.of("wq", false, List.of("++p")).createFolders());
        assertTrue(HostCommand.of("xit", false, List.of("++p")).createFolders());
        assertFalse(HostCommand.of("wq", false, List.of("other.txt")).createFolders());
        assertFalse(HostCommand.of("edit", false, List.of("++p")).createFolders());
    }

    @Test
    void theFileAndTheCount() {
        assertEquals("my file.txt", HostCommand.of("edit", false, List.of("my file.txt")).file());
        assertEquals("", HostCommand.of("edit", true, List.of()).file(), ":e! alone reverts");
        assertEquals(3, HostCommand.of("tabnext", false, List.of("3")).count());
        assertEquals(0, HostCommand.of("tabnext", false, List.of()).count());
    }

    /** The table Neovim's side checks before it hands a line over (LuaScripts.HOST_COMMANDS). */
    @Test
    void rulesForNeovimNameEveryCommandAndWhatItTakes() {
        Map<String, String> rules = new HashMap<>();
        for (String word : HostCommand.rulesForNeovim().split(" ")) {
            String[] nameAndRule = word.split("=");
            rules.put(nameAndRule[0], nameAndRule[1]);
        }
        assertEquals("none", rules.get("quit"));
        assertEquals("any", rules.get("edit"));
        assertEquals("any", rules.get("wq"));
        assertEquals("count", rules.get("tabnext"));
        assertEquals("count", rules.get("tabNext"));
        assertEquals("buffers", rules.get("bdelete"));
        assertEquals(HostCommand.TABLE.keySet(), rules.keySet());
    }
}
