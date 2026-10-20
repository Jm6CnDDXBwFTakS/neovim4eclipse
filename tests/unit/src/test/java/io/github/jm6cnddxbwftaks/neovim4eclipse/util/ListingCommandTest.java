// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ListingCommandTest {

    @Test
    void listingCommandsAndTheirAbbreviations() {
        for (String cmd : new String[] { "ls", "ls!", "buffers", "files", "ju", "jumps", "marks", "marks a",
                "reg", "registers a", "di", "changes", "mes", "messages", "pw", "pwd", "undol", "ve",
                "scr", "his", "echo &tabstop", "ec 1 + 1", "  ls  " }) {
            assertTrue(ListingCommand.isListing(cmd), cmd);
        }
    }

    @Test
    void setOnlyWhenItShowsOptions() {
        for (String cmd : new String[] { "set", "se", "set all", "set ts?", "set ts? sw?", "setl ts?", "setglobal" }) {
            assertTrue(ListingCommand.isListing(cmd), cmd);
        }
        for (String cmd : new String[] { "set ts=4", "set number", "set ts? sw=2", "setl nowrap" }) {
            assertFalse(ListingCommand.isListing(cmd), cmd);
        }
    }

    @Test
    void commandsThatChangeThingsOrAreNotListingAreNotMatched() {
        for (String cmd : new String[] { "", "  ", "s/a/b/", "%d", "3ls", "b 2", "buffer 2", "bd", "e foo", "w",
                "l", "e", "r", "regx", "lsx", "!ls", "normal dd", "echox" }) {
            assertFalse(ListingCommand.isListing(cmd), cmd);
        }
        assertFalse(ListingCommand.isListing(null));
    }
}
