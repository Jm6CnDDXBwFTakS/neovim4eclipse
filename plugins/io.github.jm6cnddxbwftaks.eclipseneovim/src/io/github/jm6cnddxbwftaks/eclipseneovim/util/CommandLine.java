// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

/**
 * The Vim command line as typed into the editor's inline command box: its three kinds, what the
 * box is pre-filled with, and how submitted text is turned into a command.
 *
 * <p>Pure Java; the SWT widget lives in {@code CommandLineBox}.
 */
public final class CommandLine {

    /** What the box was opened for. */
    public enum Kind {
        /** {@code :} an ex command. */
        EX(':'),
        /** {@code /} forward search. */
        SEARCH_FORWARD('/'),
        /** {@code ?} backward search. */
        SEARCH_BACKWARD('?');

        private final char prefix;

        Kind(char prefix) {
            this.prefix = prefix;
        }

        /** The character shown at the start of the box. */
        public char prefix() {
            return prefix;
        }

        public boolean isSearch() {
            return this != EX;
        }

        /** The kind opened by a key, or {@code null} if the key does not open a command line. */
        public static Kind forKey(String nvimKey) {
            if (nvimKey == null || nvimKey.length() != 1) {
                return null;
            }
            for (Kind kind : values()) {
                if (kind.prefix == nvimKey.charAt(0)) {
                    return kind;
                }
            }
            return null;
        }
    }

    /** A submitted command line: what to run, without the prefix. */
    public record Submission(Kind kind, String body) {
    }

    private CommandLine() {
    }

    /**
     * Initial text of the box. After {@code :} typed in visual mode Vim pre-fills the range of the
     * selection ({@code :'<,'>}), so commands such as {@code s/a/b/} apply to the selected lines.
     */
    public static String prefill(Kind kind, boolean fromVisualMode) {
        String prefix = String.valueOf(kind.prefix());
        return kind == Kind.EX && fromVisualMode ? prefix + "'<,'>" : prefix;
    }

    /**
     * Turns the box text into a command.
     *
     * @param kind the kind the box was opened with
     * @param text the whole box text, including the prefix; may be {@code null}
     * @return the submission, or {@code null} if nothing but the prefix (and whitespace) was entered
     */
    public static Submission parse(Kind kind, String text) {
        if (text == null) {
            return null;
        }
        String body = text.strip();
        if (!body.isEmpty() && body.charAt(0) == kind.prefix()) {
            body = body.substring(1);
        }
        body = kind == Kind.EX ? stripLeadingColons(body) : body.strip();
        return body.isEmpty() ? null : new Submission(kind, body);
    }

    /** Vim accepts any number of leading colons and spaces: {@code ::  w}. */
    private static String stripLeadingColons(String body) {
        int i = 0;
        while (i < body.length() && (body.charAt(i) == ':' || Character.isWhitespace(body.charAt(i)))) {
            i++;
        }
        return body.substring(i).stripTrailing();
    }
}
