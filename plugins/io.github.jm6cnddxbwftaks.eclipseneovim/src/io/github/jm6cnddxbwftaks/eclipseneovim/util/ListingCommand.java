// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import java.util.Map;

/**
 * Recognizes ex commands that only display information ({@code :ls}, {@code :marks}, {@code :set ts?},
 * ...). Their output would be lost in Neovim's message area, which Eclipse does not show, so they
 * are run with output capture and the text is shown in a popup instead. Commands that change
 * anything are not matched: they keep running as typed keys, so that macros record them.
 */
public final class ListingCommand {

    /** Full command name to the shortest abbreviation Vim accepts. */
    private static final Map<String, Integer> LISTING = Map.ofEntries(
            Map.entry("ls", 2),
            Map.entry("buffers", 7),
            Map.entry("files", 5),
            Map.entry("jumps", 2),
            Map.entry("marks", 5),
            Map.entry("registers", 3),
            Map.entry("display", 2),
            Map.entry("changes", 7),
            Map.entry("messages", 3),
            Map.entry("pwd", 2),
            Map.entry("undolist", 5),
            Map.entry("version", 2),
            Map.entry("scriptnames", 3),
            Map.entry("history", 3),
            Map.entry("echo", 2));

    /** {@code :set} and friends list options only without arguments, with "all", or with "option?". */
    private static final Map<String, Integer> SET = Map.of("set", 2, "setlocal", 4, "setglobal", 4);

    private ListingCommand() {
    }

    /** @param body the command line without the leading {@code :} */
    public static boolean isListing(String body) {
        if (body == null) {
            return false;
        }
        String cmd = body.strip();
        int nameEnd = 0;
        while (nameEnd < cmd.length() && Character.isLetter(cmd.charAt(nameEnd))) {
            nameEnd++;
        }
        if (nameEnd == 0) {
            return false; // a range, a count or a non-command such as "!ls"
        }
        String name = cmd.substring(0, nameEnd);
        String rest = cmd.substring(nameEnd);
        if (rest.startsWith("!")) {
            rest = rest.substring(1); // :ls! also lists unlisted buffers
        }
        if (!rest.isEmpty() && !Character.isWhitespace(rest.charAt(0))) {
            return false; // e.g. "lsx", "echo(...)" without a space is not one of these commands
        }
        if (matches(LISTING, name)) {
            return true;
        }
        return matches(SET, name) && onlyShowsOptions(rest.strip());
    }

    private static boolean matches(Map<String, Integer> commands, String name) {
        for (Map.Entry<String, Integer> e : commands.entrySet()) {
            if (name.length() >= e.getValue() && e.getKey().startsWith(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean onlyShowsOptions(String args) {
        if (args.isEmpty() || args.equals("all")) {
            return true;
        }
        for (String arg : args.split("\\s+")) {
            if (!arg.endsWith("?")) {
                return false;
            }
        }
        return true;
    }
}
