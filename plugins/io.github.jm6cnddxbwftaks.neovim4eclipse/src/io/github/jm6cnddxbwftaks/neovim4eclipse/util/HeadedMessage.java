// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A dialog's message in parts, for {@code editor/HeadedMessageDialog} to lay out: an opening
 * paragraph, a few points, each a short heading (shown bold, in a column of its own) and its text,
 * and a closing line. Used by the welcome ({@link WelcomeNote#welcome}) and the Restart Neovim
 * question ({@code editor/RestartNeovimHandler}).
 *
 * <p>Why parts, not one string with dashes: a {@code MessageDialog} draws its message as one
 * wrapping label, so a point's second line went back under its dash, and the groups ran together
 * (seen 2026-10-31 on Windows). Laid out, each point's text wraps beside its heading, and the groups
 * have space between them. Example:
 *
 * <pre>
 *   Stop Neovim and start a new one?            (intro)
 *
 *   Lost   The undo history, and anything set   (a point: heading, text)
 *          by hand since Neovim started ...
 *   Kept   The text of every editor, ...
 *
 *   Use it after changing your init.lua.        (footer, if any)
 * </pre>
 *
 * @param intro  the opening paragraph
 * @param points the points, in order
 * @param footer the closing line, or "" for none
 */
public record HeadedMessage(String intro, List<Point> points, String footer) {

    /** One point: a heading of a word or two and its text. Example: "Lost", "The undo history, ...". */
    public record Point(String heading, String text) {
    }

    /** The points made a list that cannot change, so the record stays a value; no footer is "". */
    public HeadedMessage {
        points = List.copyOf(points);
        footer = footer == null ? "" : footer;
    }

    /** Whether there is a closing line. */
    public boolean hasFooter() {
        return !footer.isBlank();
    }

    /**
     * The whole message as text: the paragraphs apart, one point a line as {@code "heading: text"}.
     * For a test, or anything that shows plain text.
     */
    public String plain() {
        return intro + "\n\n"
                + points.stream().map(p -> p.heading() + ": " + p.text()).collect(Collectors.joining("\n"))
                + (hasFooter() ? "\n\n" + footer : "");
    }
}
