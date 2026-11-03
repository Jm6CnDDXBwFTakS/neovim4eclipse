// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A dialog's message in parts, for {@code dialogs/HeadedMessageDialog} to lay out: an opening
 * paragraph, a few points, each a short heading (shown bold, in a column of its own) and its text,
 * and a closing line. Used by the welcome ({@link WelcomeNote#welcome}), the Restart Neovim question
 * ({@code editor/RestartNeovimHandler}) and Get Neovim's dialogs ({@link GetNeovimText}).
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
 * <p>A point may also carry a value to copy (a path, a hash, a line for a file), shown under its text
 * in a fixed-width font with a <em>Copy</em> button, and links, shown on one line under that. Example
 * from Get Neovim's confirmation:
 *
 * <pre>
 *   SHA-256   The plug-in checks the archive against this before unpacking.
 *             [2e58d259ffcc6145e5112ea0...] (Copy)            (the value)
 *             GitHub release page · VirusTotal                (the links)
 * </pre>
 *
 * @param intro  the opening paragraph
 * @param points the points, in order
 * @param footer the closing line, or "" for none
 */
public record HeadedMessage(String intro, List<Point> points, String footer) {

    /**
     * A link a point offers: what it says, and where it goes (opened in the user's browser). Only an
     * {@code https} page ({@link HttpsLinks}): the dialog's opener would run a {@code file:} link's
     * program on Windows.
     *
     * @throws IllegalArgumentException if {@code url} is not an {@code https} URL with a host
     */
    public record Link(String label, URI url) {
        public Link {
            HttpsLinks.require(url);
        }
    }

    /**
     * One point: a heading of a word or two and its text, optionally a value to copy and links.
     * Example: {@code new Point("Lost", "The undo history, ...")}, or
     * {@code new Point("SHA-256", "Checked before unpacking.").withCopy(hash).withLinks(page, report)}.
     *
     * @param heading the bold heading
     * @param text    the text beside it; "" for none (a point that is only a value)
     * @param copy    the value shown with <em>Copy</em>, or "" for none
     * @param links   the links, in order; none is an empty list
     */
    public record Point(String heading, String text, String copy, List<Link> links) {

        /** A heading and its text, the common case. */
        public Point(String heading, String text) {
            this(heading, text, "", List.of());
        }

        /** Nulls count as none, and the links become a list that cannot change, so a point stays a value. */
        public Point {
            text = text == null ? "" : text;
            copy = copy == null ? "" : copy;
            links = List.copyOf(links == null ? List.of() : links);
        }

        /** This point with {@code value} to copy. */
        public Point withCopy(String value) {
            return new Point(heading, text, value, links);
        }

        /** This point with {@code more} links after those it has. */
        public Point withLinks(Link... more) {
            List<Link> all = new ArrayList<>(links);
            all.addAll(List.of(more));
            return new Point(heading, text, copy, all);
        }

        /** Whether there is a value to copy. */
        public boolean hasCopy() {
            return !copy.isEmpty();
        }

        /** The point as one line of text: {@code "heading: text value (link, link)"}, leaving out what it lacks. */
        String plain() {
            String linkLabels = links.isEmpty() ? ""
                    : links.stream().map(Link::label).collect(Collectors.joining(", ", "(", ")"));
            return heading + ": " + Stream.of(text, copy, linkLabels).filter(s -> !s.isEmpty())
                    .collect(Collectors.joining(" "));
        }
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

    /** The point with this heading, if there is one (for tests, and for a caller that adds to one). */
    public Optional<Point> point(String heading) {
        return points.stream().filter(p -> p.heading().equals(heading)).findFirst();
    }

    /**
     * The whole message as text: the paragraphs apart, one point a line as {@code "heading: text"}.
     * For a test, or anything that shows plain text.
     */
    public String plain() {
        return intro + "\n\n" + points.stream().map(Point::plain).collect(Collectors.joining("\n"))
                + (hasFooter() ? "\n\n" + footer : "");
    }
}
