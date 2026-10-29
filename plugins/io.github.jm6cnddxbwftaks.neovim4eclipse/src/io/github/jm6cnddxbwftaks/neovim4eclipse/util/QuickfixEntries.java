// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Diagnostic.Severity;

/**
 * The entries of the quickfix lists {@code :Eproblems} and {@code :Etasks} make from the workspace's
 * problems and tasks (docs/design/DIAGNOSTICS.md, section 5), and what decides them: which severities
 * and which task tags count, the order, and the arguments sent to Neovim. No Eclipse types:
 * {@code editor/QuickfixLists} reads the markers and annotations and hands them over as
 * {@link Entry}s.
 *
 * <h2>The quickfix list in two lines</h2>
 * Vim's quickfix list is a list of places ({@code :help quickfix}): a file, a line, a column, a
 * type ({@code E}rror, {@code W}arning, {@code I}nfo) and a text. {@code :cnext}, {@code ]q} and
 * {@code :cc 3} walk it, opening each place's file. Example entry: {@code app/src/A.java:40 col 9
 * error: Type mismatch}. Neovim keeps up to ten lists (the stack, {@code :colder} / {@code :cnewer}).
 *
 * <h2>Lines and columns</h2>
 * As {@code setqflist()} takes them: lines 1-based, columns 1-based <em>byte</em> columns (Neovim's
 * {@code col} with {@code vcol} 0). 0 means "not known": a line of 0 opens the file and leaves the
 * cursor where it was in it, a column of 0 puts the cursor on the line's first non-blank. The
 * entries of a file without an open editor have the line only (decided, Q11 of the design doc: a
 * column would need the file's text).
 */
public final class QuickfixEntries {

    private QuickfixEntries() {
    }

    /** The two lists Eclipse fills; the name is what {@code context.neovim4eclipse} holds in Neovim. */
    public enum Kind {
        /** {@code :Eproblems}: errors, warnings and infos. */
        PROBLEMS("problems", "Eclipse problems"),
        /** {@code :Etasks}: task markers ({@code TODO}, {@code FIXME}) with the user's tags. */
        TASKS("tasks", "Eclipse tasks");

        private final String luaName;
        private final String title;

        Kind(String luaName, String title) {
            this.luaName = luaName;
            this.title = title;
        }

        /** The name in Neovim ({@code "problems"}), as the list's context and the commands send it. */
        public String luaName() {
            return luaName;
        }

        /** The list's title ({@code :help quickfix-title}), shown by {@code :chistory}. */
        public String title() {
            return title;
        }

        /** The kind named {@code name} ({@code "problems"}), or {@code null}. */
        public static Kind of(String name) {
            for (Kind kind : values()) {
                if (kind.luaName.equals(name)) {
                    return kind;
                }
            }
            return null;
        }
    }

    /**
     * One place in the list.
     *
     * @param bufnr    the document's Neovim buffer, or 0 when it has none (the entry then names
     *                 {@code file}, and Neovim makes an unlisted buffer for it)
     * @param file     the file's full path on disk, as Neovim opens it ({@code /ws/app/src/A.java})
     * @param module   what {@code :clist} shows instead of the path: the workspace path
     *                 ({@code app/src/A.java})
     * @param lnum     1-based line, 0 when not known
     * @param col      1-based byte column, 0 when not known
     * @param severity the problem's severity; {@code null} for a task (no type in the list)
     * @param text     the message; only its first line is sent
     */
    public record Entry(int bufnr, String file, String module, int lnum, int col, Severity severity, String text) {

        public Entry {
            file = file == null ? "" : file;
            module = module == null ? "" : module;
            text = text == null ? "" : text;
            lnum = Math.max(0, lnum);
            col = lnum == 0 ? 0 : Math.max(0, col);
        }

        /** The entry's {@code type} in the list: {@code E}, {@code W}, {@code I}, or "" for a task. */
        public String type() {
            if (severity == null) {
                return "";
            }
            return switch (severity) {
                case ERROR -> "E";
                case WARN -> "W";
                case INFO -> "I";
            };
        }

        /** The message's first line: a quickfix entry shows one line ({@code :clist}, the message after a jump). */
        public String firstLine() {
            int end = text.indexOf('\n');
            String line = end < 0 ? text : text.substring(0, end);
            return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        }
    }

    // ---- which problems ------------------------------------------------------------------------

    /**
     * The least severity {@code :Eproblems arg} lists (decided, Q9: at least that severe, as
     * {@code vim.diagnostic}'s {@code severity = { min = ... }}): {@code ""} or {@code "info"} lists
     * all, {@code "warning"} errors and warnings, {@code "error"} errors. {@code null} for anything
     * else (Neovim's command refuses it first; this guards a hand-written call).
     */
    public static Severity leastSeverity(String arg) {
        String word = arg == null ? "" : arg.trim().toLowerCase(Locale.ROOT);
        return switch (word) {
            case "", "info" -> Severity.INFO;
            case "warning", "warn" -> Severity.WARN;
            case "error" -> Severity.ERROR;
            default -> null;
        };
    }

    /** Whether a problem of {@code severity} belongs in a list of at least {@code least}. Errors are the most severe. */
    public static boolean atLeast(Severity severity, Severity least) {
        return severity != null && severity.compareTo(least) <= 0; // ERROR < WARN < INFO in the enum
    }

    // ---- which tasks ---------------------------------------------------------------------------

    /**
     * The task tags of {@code vim.g.neovim4eclipse_task_tags}: a comma-separated list
     * ({@code "JM6, FIXME"}), or {@code "*"} for every task. A task counts when its message starts
     * with one of the tags as a whole word, which is how builders write a task's message (JDT gives
     * {@code // JM6 check the offsets} the message {@code JM6 check the offsets}). Case matters, as in
     * JDT's default. Example: with {@code JM6}, {@code "JM6 check"} and {@code "JM6: check"} count,
     * {@code "JM67 check"} and {@code "see JM6"} do not.
     */
    public record TaskTags(boolean all, List<String> tags) {

        public TaskTags {
            tags = List.copyOf(tags);
        }

        /** The tags in {@code text}; empty (no tags at all) for {@code null} or blanks. */
        public static TaskTags parse(String text) {
            List<String> tags = new ArrayList<>();
            boolean all = false;
            for (String part : (text == null ? "" : text).split(",")) {
                String tag = part.trim();
                if (tag.equals("*")) {
                    all = true;
                } else if (!tag.isEmpty() && !tags.contains(tag)) {
                    tags.add(tag);
                }
            }
            return new TaskTags(all, tags);
        }

        /** Whether no tag is set: {@code :Etasks} then says how to set one instead of making a list. */
        public boolean isEmpty() {
            return !all && tags.isEmpty();
        }

        /** Whether a task with this message counts. */
        public boolean matches(String message) {
            if (all) {
                return true;
            }
            String text = message == null ? "" : message.stripLeading();
            for (String tag : tags) {
                if (text.startsWith(tag) && (text.length() == tag.length()
                        || !isWordChar(text.charAt(tag.length())) || !isWordChar(tag.charAt(tag.length() - 1)))) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isWordChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_';
        }
    }

    // ---- the list ------------------------------------------------------------------------------

    /**
     * The Problems view's order, sorted by resource: by workspace path (case ignored, then exact),
     * then line, column, severity (errors first) and text.
     */
    static final Comparator<Entry> ORDER = Comparator.comparing(Entry::module, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Entry::module)
            .thenComparingInt(Entry::lnum)
            .thenComparingInt(Entry::col)
            .thenComparing(Entry::severity, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Entry::text);

    /**
     * {@code entries} in {@link #ORDER}, each place once. A file seen through two projects (a project
     * nested in another's folder has its files twice in the workspace) may carry the same problem
     * twice; the one with the shorter workspace path is kept. Same place means the same file on disk,
     * line, column, type and text.
     */
    public static List<Entry> sorted(List<Entry> entries) {
        List<Entry> byPath = new ArrayList<>(entries);
        byPath.sort(Comparator.comparingInt((Entry e) -> e.module().length()).thenComparing(ORDER));
        Set<List<Object>> seen = new HashSet<>();
        List<Entry> kept = new ArrayList<>(byPath.size());
        for (Entry entry : byPath) {
            if (seen.add(Arrays.asList(entry.file(), entry.lnum(), entry.col(), entry.type(), entry.firstLine()))) {
                kept.add(entry);
            }
        }
        kept.sort(ORDER);
        return kept;
    }

    /**
     * The current quickfix list when it is one of Eclipse's: its kind and argument (the severity
     * word, or the task tags), as {@code LuaScripts.QUICKFIX_CURRENT} answers.
     */
    public record Current(Kind kind, String arg) {

        public Current {
            Objects.requireNonNull(kind, "kind");
            arg = arg == null ? "" : arg;
        }

        /**
         * The answer of {@code QUICKFIX_CURRENT}: {@code ["problems", "warning"]}, or {@code null}
         * (Lua's {@code nil}) when the current list is not ours. {@code null} for anything else.
         */
        public static Current fromLua(Object answer) {
            if (!(answer instanceof List<?> list) || list.isEmpty()) {
                return null;
            }
            Kind kind = Kind.of(String.valueOf(list.get(0)));
            if (kind == null) {
                return null;
            }
            Object arg = list.size() > 1 ? list.get(1) : "";
            return new Current(kind, arg instanceof String text ? text : "");
        }
    }

    /** How many arguments one entry takes in {@link #toLuaArgs}. */
    public static final int LUA_ARGS_PER_ENTRY = 7;

    /** How the list is sent: made by a command, or updated because the workspace changed. */
    public enum Mode {
        /**
         * {@code :Eproblems}: reuse the list of this kind in the stack or make one, make it current,
         * and jump to the first entry unless the command had {@code !}.
         */
        COMMAND,
        /** A change: only when the current list is this kind's, keeping the entry the user is on. */
        UPDATE
    }

    /**
     * The arguments of {@code LuaScripts.SET_QUICKFIX}: the kind ({@code problems}), its argument (the
     * severity word or the task tags, kept in the list's context for later updates), the mode
     * ({@code command} or {@code update}), {@code 1} to jump to the first entry, then seven strings
     * per entry: bufnr, file, module, lnum, col, type, text. Flat strings, as for
     * {@link Diagnostic#toLuaArgs}: no escaping. Example: {@code ["problems", "", "command", "1",
     * "3", "/ws/app/A.java", "app/A.java", "40", "9", "E", "Type mismatch"]}.
     */
    public static List<String> toLuaArgs(Kind kind, String arg, Mode mode, boolean jump, List<Entry> entries) {
        Objects.requireNonNull(kind, "kind");
        List<String> args = new ArrayList<>(4 + LUA_ARGS_PER_ENTRY * entries.size());
        args.add(kind.luaName());
        args.add(arg == null ? "" : arg);
        args.add(mode.name().toLowerCase(Locale.ROOT));
        args.add(jump ? "1" : "0");
        for (Entry e : entries) {
            args.add(Integer.toString(e.bufnr()));
            args.add(e.file());
            args.add(e.module());
            args.add(Integer.toString(e.lnum()));
            args.add(Integer.toString(e.col()));
            args.add(e.type());
            args.add(e.firstLine());
        }
        return args;
    }
}
