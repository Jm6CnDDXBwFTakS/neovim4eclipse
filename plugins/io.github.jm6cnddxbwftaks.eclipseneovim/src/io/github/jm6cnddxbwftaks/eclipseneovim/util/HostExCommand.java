// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Classifies an ex command (text typed after {@code :}) that Eclipse, not Neovim, must execute.
 *
 * <p>Neovim's buffers are not the files on disk; the IDE owns files, editors and tabs. So commands
 * about those are carried out by Eclipse. Everything else (e.g. {@code :%s/a/b/g}) is left to Neovim.
 *
 * <table>
 *   <caption>Host commands</caption>
 *   <tr><th>Typed</th><th>Kind</th><th>Eclipse action</th></tr>
 *   <tr><td>{@code :w}, {@code :update}</td><td>{@link Kind#WRITE}</td><td>save the editor</td></tr>
 *   <tr><td>{@code :wa}</td><td>{@link Kind#WRITE_ALL}</td><td>File &gt; Save All</td></tr>
 *   <tr><td>{@code :q}</td><td>{@link Kind#QUIT}</td><td>close the editor</td></tr>
 *   <tr><td>{@code :qa}</td><td>{@link Kind#QUIT_ALL}</td><td>close all editors</td></tr>
 *   <tr><td>{@code :wq}, {@code :x}</td><td>{@link Kind#WRITE_QUIT}</td><td>save and close</td></tr>
 *   <tr><td>{@code :wqa}, {@code :xa}</td><td>{@link Kind#WRITE_QUIT_ALL}</td><td>save all, close all</td></tr>
 *   <tr><td>{@code :e file}, {@code :e!}</td><td>{@link Kind#EDIT}</td><td>open a file; revert</td></tr>
 *   <tr><td>{@code :tabn}, {@code :tabp}, ...</td><td>{@code TAB_*}</td><td>switch editor tabs</td></tr>
 * </table>
 *
 * <p>Names may be abbreviated as in Vim: {@code :wal} is {@code :wall}, {@code :tabp} is
 * {@code :tabprevious}. A {@code !} sets {@link #force()}. Not handled (goes to Neovim):
 * {@code :w otherfile} saves the current editor and ignores the path; ranges other than
 * {@code %} make a command not a host command.
 *
 * @param argument        the text after the command name, as typed (case kept, trimmed); "" if none
 * @param writesUnchanged whether the command writes even when nothing changed, as Vim's {@code :w}
 *                        and {@code :wq} do; {@code :update}, {@code :x} and {@code :exit} write only
 *                        changes. Only a new file's editor uses it (an untouched one still creates
 *                        the empty file), since Eclipse saves only modified editors
 */
public record HostExCommand(Kind kind, boolean force, String argument, boolean writesUnchanged) {

    public enum Kind {
        /** Not a host command: forward to Neovim. */
        NONE,
        /** {@code :w}, {@code :write}, {@code :update}. */
        WRITE,
        /** {@code :wa}, {@code :wall}: save every modified editor. */
        WRITE_ALL,
        /** {@code :q}, {@code :quit}. */
        QUIT,
        /** {@code :qa}, {@code :qall}, {@code :quitall}: close every editor. */
        QUIT_ALL,
        /** {@code :wq}, {@code :x}, {@code :exit}. */
        WRITE_QUIT,
        /** {@code :wqa}, {@code :xa}: save every editor, then close them all. */
        WRITE_QUIT_ALL,
        /** {@code :e [file]}, {@code :edit[!] [file]}: open a file, or reload/revert the current one. */
        EDIT,
        /** {@code :tabn [N]}: next tab, or tab N (1-based). */
        TAB_NEXT,
        /** {@code :tabp [N]}, {@code :tabN [N]}: previous tab, or N tabs back. */
        TAB_PREVIOUS,
        /** {@code :tabfirst}, {@code :tabrewind}. */
        TAB_FIRST,
        /** {@code :tablast}. */
        TAB_LAST
    }

    private static final HostExCommand NOT_HOST = new HostExCommand(Kind.NONE, false, "", false);

    /**
     * Every name Eclipse handles, lowercase, with its kind. Each command can be shortened down to
     * the part Vim's documentation shows outside brackets: ":wa[ll]" is {@code abbrev("wa", "wall")}.
     * Building the map fails on a name listed twice, so overlapping abbreviations cannot slip in.
     */
    private static final Map<String, Kind> KINDS = buildKinds();
    /** {@code :tabN[ext]}: matched before lowercasing, because {@code :tabn} means the opposite. */
    private static final Set<String> TAB_PREVIOUS_CAPITAL_N = abbrev("tabN", "tabNext");
    /** {@code :w[rite]} and {@code :wq}: see {@link #writesUnchanged()}. */
    private static final Set<String> WRITES_UNCHANGED = union(abbrev("w", "write"), Set.of("wq"));

    public HostExCommand {
        argument = argument == null ? "" : argument;
    }

    /** @return {@code true} if Eclipse should handle the command instead of Neovim */
    public boolean isHostCommand() {
        return kind != Kind.NONE;
    }

    /**
     * Whether a write may create the missing folders of a new file: {@code :w ++p}, {@code :wq ++p},
     * {@code :x ++p}, as in Neovim ({@code :help ++p}). Any other argument is ignored, as before.
     */
    public boolean createFolders() {
        if (kind != Kind.WRITE && kind != Kind.WRITE_QUIT) {
            return false;
        }
        int space = indexOfWhitespace(argument);
        return (space >= 0 ? argument.substring(0, space) : argument).equals("++p");
    }

    /**
     * The tab count argument ({@code :tabn 3}): the number, or 0 when there is none.
     * {@link #parse} only accepts tab commands whose argument is empty or a number.
     */
    public int count() {
        return argument.isEmpty() ? 0 : Integer.parseInt(argument);
    }

    /**
     * @param raw command text, with or without a leading {@code :}; may be {@code null}
     * @return the classification; never {@code null}
     */
    public static HostExCommand parse(String raw) {
        if (raw == null) {
            return NOT_HOST;
        }
        String cmd = stripPrefix(raw);
        if (cmd.isEmpty()) {
            return NOT_HOST;
        }
        int space = indexOfWhitespace(cmd);
        String typedHead = space >= 0 ? cmd.substring(0, space) : cmd;
        String argument = space >= 0 ? cmd.substring(space).strip() : "";
        boolean force = typedHead.endsWith("!");
        String name = force ? typedHead.substring(0, typedHead.length() - 1) : typedHead;
        if (name.isEmpty()) {
            return NOT_HOST;
        }
        Kind kind = TAB_PREVIOUS_CAPITAL_N.contains(name) ? Kind.TAB_PREVIOUS
                : KINDS.getOrDefault(name.toLowerCase(Locale.ROOT), Kind.NONE);
        if (!acceptsArgument(kind, argument)) {
            return NOT_HOST;
        }
        boolean writesUnchanged = WRITES_UNCHANGED.contains(name.toLowerCase(Locale.ROOT));
        return kind == Kind.NONE ? NOT_HOST : new HostExCommand(kind, force, argument, writesUnchanged);
    }

    /**
     * Which commands may have text after them. {@code :w file} and {@code :wq file} are accepted
     * (the path is ignored and the editor saved), {@code :e file} needs it, tab commands take a
     * number; anything else with an argument ({@code :q foo}) is left to Neovim, which reports it.
     */
    private static boolean acceptsArgument(Kind kind, String argument) {
        if (argument.isEmpty()) {
            return true;
        }
        return switch (kind) {
            case WRITE, WRITE_QUIT, EDIT -> true;
            case TAB_NEXT, TAB_PREVIOUS -> argument.chars().allMatch(Character::isDigit) && argument.length() <= 6;
            default -> false;
        };
    }

    /** Removes the leading {@code :} and a {@code %} range ({@code :%w} is {@code :w}). */
    private static String stripPrefix(String raw) {
        String s = raw.strip();
        if (s.startsWith(":")) {
            s = s.substring(1).strip();
        }
        if (s.startsWith("%")) {
            s = s.substring(1).strip();
        }
        return s;
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Every abbreviation Vim accepts for a command: {@code abbrev("wa", "wall")} is
     * {@code {wa, wal, wall}}.
     */
    static Set<String> abbrev(String shortest, String full) {
        if (!full.startsWith(shortest)) {
            throw new IllegalArgumentException(shortest + " is not a prefix of " + full);
        }
        Set<String> names = new HashSet<>();
        for (int length = shortest.length(); length <= full.length(); length++) {
            names.add(full.substring(0, length));
        }
        return Set.copyOf(names);
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return Set.copyOf(all);
    }

    private static Map<String, Kind> buildKinds() {
        Map<String, Kind> kinds = new HashMap<>();
        add(kinds, Kind.WRITE, abbrev("w", "write"), abbrev("up", "update"));
        add(kinds, Kind.WRITE_ALL, abbrev("wa", "wall"));
        add(kinds, Kind.QUIT, abbrev("q", "quit"));
        add(kinds, Kind.QUIT_ALL, abbrev("qa", "qall"), abbrev("quita", "quitall"));
        add(kinds, Kind.WRITE_QUIT, Set.of("wq"), abbrev("x", "xit"), abbrev("exi", "exit"));
        add(kinds, Kind.WRITE_QUIT_ALL, abbrev("wqa", "wqall"), abbrev("xa", "xall"));
        add(kinds, Kind.EDIT, abbrev("e", "edit"));
        add(kinds, Kind.TAB_NEXT, abbrev("tabn", "tabnext"));
        add(kinds, Kind.TAB_PREVIOUS, abbrev("tabp", "tabprevious"));
        add(kinds, Kind.TAB_FIRST, abbrev("tabfir", "tabfirst"), abbrev("tabr", "tabrewind"));
        add(kinds, Kind.TAB_LAST, abbrev("tabl", "tablast"));
        return Map.copyOf(kinds);
    }

    @SafeVarargs
    private static void add(Map<String, Kind> kinds, Kind kind, Set<String>... nameSets) {
        for (Set<String> names : nameSets) {
            for (String name : names) {
                Kind previous = kinds.put(name, kind);
                if (previous != null) {
                    throw new IllegalStateException(":" + name + " is both " + previous + " and " + kind);
                }
            }
        }
    }
}
