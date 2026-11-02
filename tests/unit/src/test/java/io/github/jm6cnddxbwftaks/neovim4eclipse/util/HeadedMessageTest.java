// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** A dialog's message in parts ({@link HeadedMessage}), and the same as plain text. */
class HeadedMessageTest {

    private static final HeadedMessage RESTART = new HeadedMessage("Stop Neovim and start a new one?",
            List.of(new HeadedMessage.Point("Lost", "The undo history."),
                    new HeadedMessage.Point("Kept", "The text of every editor.")),
            "Use it after changing your init.lua.");

    @Test
    void plainTextHasTheParagraphsApartAndOnePointALine() {
        assertEquals("""
                Stop Neovim and start a new one?

                Lost: The undo history.
                Kept: The text of every editor.

                Use it after changing your init.lua.""", RESTART.plain());
    }

    @Test
    void withoutAFooterThePlainTextEndsAfterThePoints() {
        HeadedMessage message = new HeadedMessage("Question?", List.of(new HeadedMessage.Point("Lost", "x.")), "");
        assertEquals("Question?\n\nLost: x.", message.plain());
        assertFalse(message.hasFooter());
        assertFalse(new HeadedMessage("Q?", List.of(), null).hasFooter(), "null counts as none");
    }

    @Test
    void thePointsCannotChangeAfterwards() {
        List<HeadedMessage.Point> points = new ArrayList<>(List.of(new HeadedMessage.Point("A", "a")));
        HeadedMessage message = new HeadedMessage("intro", points, "footer");
        points.add(new HeadedMessage.Point("B", "b"));
        assertEquals(1, message.points().size(), "a copy, not the caller's list");
        assertThrows(UnsupportedOperationException.class, () -> message.points().add(new HeadedMessage.Point("C", "c")));
    }

    @Test
    void aPointsValueAndLinksShowInItsPlainLine() {
        HeadedMessage.Point point = new HeadedMessage.Point("SHA-256", "Checked.").withCopy("abc")
                .withLinks(new HeadedMessage.Link("Page", URI.create("https://example.org/")))
                .withLinks(new HeadedMessage.Link("Report", URI.create("https://example.org/r")));
        HeadedMessage message = new HeadedMessage("Q?", List.of(point, new HeadedMessage.Point("Folder", "")
                .withCopy("/a")), "");
        assertEquals("Q?\n\nSHA-256: Checked. abc (Page, Report)\nFolder: /a", message.plain());
        assertEquals(2, point.links().size(), "withLinks adds to the links there are");
        assertFalse(new HeadedMessage.Point("A", "a").hasCopy());
        assertEquals(point, message.point("SHA-256").orElseThrow());
        assertTrue(message.point("None").isEmpty());
    }
}
