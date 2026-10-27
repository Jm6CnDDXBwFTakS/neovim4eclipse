// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An ex command that Eclipse, not Neovim, carries out, as Neovim parsed it.
 *
 * <p>Neovim's buffers are not the files on disk; the IDE owns files, editors and tabs. So commands
 * about those are carried out by Eclipse. Everything else ({@code :%s/a/b/g}) stays Neovim's.
 *
 * <h2>Who parses: Neovim</h2>
 * The plug-in no longer reads what was typed after {@code :}. When a command line is about to run,
 * Neovim's {@code CmdlineLeavePre} autocommand parses it with {@code nvim_parse_cmd()} (so
 * abbreviations, {@code !}, ranges and {@code |} are Neovim's own rules) and, for a command in
 * {@link #TABLE}, replaces the line with {@code :Eclipse[!] {name} {args}}, a user command that
 * sends {@code eclipse_command} to Eclipse ({@code LuaScripts.HOST_COMMANDS}). Example:
 * {@code :tabn 3} runs as {@code :Eclipse tabnext 3}, and {@link #of} turns that into
 * {@code TAB_NEXT}, count 3. Because it happens on Neovim's command line, it works the same for a
 * typed line, a mapping's {@code :q<CR>}, a macro and {@code :normal}, and {@code @:} repeats it.
 *
 * <table>
 *   <caption>Host commands (Neovim's full names)</caption>
 *   <tr><th>Command</th><th>Kind</th><th>Eclipse action</th></tr>
 *   <tr><td>{@code wall}</td><td>{@link Kind#WRITE_ALL}</td><td>File &gt; Save All</td></tr>
 *   <tr><td>{@code quit}, {@code bdelete}, {@code bwipeout}, {@code bunload}</td><td>{@link Kind#QUIT}</td><td>close the editor</td></tr>
 *   <tr><td>{@code bdelete 3}, {@code bdelete other.txt}, {@code %bdelete}</td><td>{@link Kind#BUFFER_DELETE}</td><td>close those buffers' editors</td></tr>
 *   <tr><td>{@code qall}, {@code quitall}</td><td>{@link Kind#QUIT_ALL}</td><td>close all editors</td></tr>
 *   <tr><td>{@code wq}, {@code xit}, {@code exit}</td><td>{@link Kind#WRITE_QUIT}</td><td>save and close</td></tr>
 *   <tr><td>{@code wqall}, {@code xall}</td><td>{@link Kind#WRITE_QUIT_ALL}</td><td>save all, close all</td></tr>
 *   <tr><td>{@code edit file}, {@code edit!}</td><td>{@link Kind#EDIT}</td><td>open a file; revert</td></tr>
 *   <tr><td>{@code tabnext}, {@code tabprevious}, ...</td><td>{@code TAB_*}</td><td>switch editor tabs</td></tr>
 * </table>
 *
 * <p>Writes ({@code :w}, {@code :update}) are not in the table: Neovim runs them, and its write
 * hook asks Eclipse to save ({@code core/DocumentWrite}, the {@code BufWriteCmd} of
 * {@code LuaScripts.DOCUMENT_BUFFER}), so they behave as Neovim's own in every respect: {@code :update}
 * skips an unchanged file, {@code :w !cmd} pipes the text to a command, {@code :w other.txt} and {@code :5,10w part.txt} write to
 * another file.
 *
 * <p>What does not come here, and stays Neovim's: a command with a range it does not take
 * ({@code :5,10w}), arguments it does not take ({@code :q foo}, which Neovim reports), a command
 * {@code :bufdo} runs, the part after {@code |} ({@code :echo 1 | q}), and {@code <Cmd>} or Lua
 * ({@code vim.cmd.quit()}). Neovim's autocommands for buffers being deleted then tell Eclipse.
 *
 * @param kind            what Eclipse does
 * @param force           whether the command had {@code !}
 * @param args            the arguments, as Neovim split them: {@code :e my\ file.txt} gives
 *                        {@code [my file.txt]}; for {@link Kind#BUFFER_DELETE} buffer numbers or names
 * @param writesUnchanged whether the command writes even when nothing changed, as Vim's
 *                        {@code :wq} does; {@code :x} and {@code :exit} write only changes. Only a new file's editor uses it (an untouched one still creates
 *                        the empty file), since Eclipse saves only modified editors
 */
public record HostCommand(Kind kind, boolean force, List<String> args, boolean writesUnchanged) {

    public enum Kind {
        /** {@code :wa}: save every modified editor. */
        WRITE_ALL,
        /**
         * {@code :q}, and the buffer commands {@code :bd}, {@code :bw}, {@code :bun} without a buffer.
         * In Vim these remove the buffer from view; here each buffer is an Eclipse editor, so they
         * close it. Left to Neovim, they would delete the buffer and put an empty one in its place,
         * whose (empty) text the editor would then show.
         */
        QUIT,
        /**
         * {@code :bd 3}, {@code :bd other.txt}, {@code :3,5bw}, {@code :%bd}: close the editors of
         * the buffers named by number or by name ({@link #args()}). A name is matched as {@code :b}
         * does (Vim's {@code bufnr()}: a unique part is enough). Neovim's range is turned into the
         * listed buffers it covers before it gets here, so {@code :%bd} names every listed buffer.
         */
        BUFFER_DELETE,
        /** {@code :qa}: close every editor. */
        QUIT_ALL,
        /** {@code :wq}, {@code :x}, {@code :exit}. */
        WRITE_QUIT,
        /** {@code :wqa}, {@code :xa}: save every editor, then close them all. */
        WRITE_QUIT_ALL,
        /** {@code :e [file]}, {@code :e!}: open a file, or reload/revert the current one. */
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

    /**
     * What a command may have after its name for Eclipse to take it; anything else is left to
     * Neovim, which runs or reports it. Neovim's side checks the same rule before rewriting a line
     * ({@link #rulesForNeovim()}), so a command Eclipse would refuse is never taken from Neovim.
     */
    public enum Arguments {
        /** Nothing: {@code :q foo} stays Neovim's (E488). */
        NONE,
        /** Any arguments: {@code :w ++p}, {@code :e file}. */
        ANY,
        /** At most one number: {@code :tabn 3}; a count in front ({@code :3tabn}) is turned into it. */
        COUNT,
        /** Buffer numbers or names; a range ({@code :3,5bd}, {@code :%bd}) is turned into the buffers it covers. */
        BUFFERS;

        /** The name the Lua side compares with ({@code "none"}, {@code "count"}, ...). */
        public String luaName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** One row of {@link #TABLE}. */
    record Entry(Kind kind, Arguments arguments, boolean writesUnchanged) {
    }

    /**
     * Every command Eclipse handles, by Neovim's full name ({@code nvim_parse_cmd()}'s {@code cmd}):
     * {@code :wa} arrives as {@code wall}, {@code :x} as {@code xit}, {@code :tabN} as
     * {@code tabNext}. No abbreviations are listed: Neovim has already expanded them.
     */
    static final Map<String, Entry> TABLE = buildTable();

    public HostCommand {
        args = args == null ? List.of() : List.copyOf(args);
    }

    /**
     * The command Neovim sent ({@code :Eclipse[!] name args}), or {@code null} if Eclipse does not
     * handle {@code name} with these arguments (a mapping of {@code :Eclipse} by hand, say, with a
     * typo); the caller then shows an error.
     *
     * @param name Neovim's full command name, e.g. {@code quit}
     * @param bang whether it had {@code !}
     * @param args its arguments, as Neovim split them
     */
    public static HostCommand of(String name, boolean bang, List<String> args) {
        Entry entry = name == null ? null : TABLE.get(name);
        List<String> list = args == null ? List.of() : args;
        if (entry == null || !accepts(entry.arguments(), list)) {
            return null;
        }
        Kind kind = entry.kind() == Kind.QUIT && !list.isEmpty() ? Kind.BUFFER_DELETE : entry.kind();
        return new HostCommand(kind, bang, list, entry.writesUnchanged());
    }

    /**
     * The rule for each command, by Neovim's full name, for {@code LuaScripts.HOST_COMMANDS}, as one
     * string of {@code name=rule} words (script arguments are strings):
     * {@code "bdelete=buffers edit=any quit=none tabnext=count ..."}, sorted.
     */
    public static String rulesForNeovim() {
        StringBuilder rules = new StringBuilder();
        new TreeMap<>(TABLE).forEach((name, entry) -> rules.append(rules.isEmpty() ? "" : " ")
                .append(name).append('=').append(entry.arguments().luaName()));
        return rules.toString();
    }

    /**
     * Whether a write may create the missing folders of a new file: {@code :wq ++p}, {@code :x ++p},
     * as in Neovim ({@code :help ++p}). Any other argument is ignored. ({@code :w ++p} is Neovim's
     * write, which passes {@code ++p} to Eclipse itself; see {@code core/DocumentWrite}.)
     */
    public boolean createFolders() {
        return kind == Kind.WRITE_QUIT && !args.isEmpty() && args.get(0).equals("++p");
    }

    /** The file of {@code :e file}, or "" for {@code :e} alone (reload). */
    public String file() {
        return String.join(" ", args);
    }

    /** The tab count ({@code :tabn 3}): the number, or 0 when there is none. */
    public int count() {
        return args.isEmpty() ? 0 : Integer.parseInt(args.get(0));
    }

    private static boolean accepts(Arguments rule, List<String> args) {
        return switch (rule) {
            case NONE -> args.isEmpty();
            case ANY, BUFFERS -> true;
            case COUNT -> args.isEmpty() || (args.size() == 1 && args.get(0).matches("\\d{1,6}"));
        };
    }

    private static Map<String, Entry> buildTable() {
        Map<String, Entry> table = new LinkedHashMap<>();
        table.put("wall", new Entry(Kind.WRITE_ALL, Arguments.NONE, false));
        table.put("quit", new Entry(Kind.QUIT, Arguments.NONE, false));
        // Without a buffer they close this editor (QUIT); with buffers, those editors (BUFFER_DELETE).
        table.put("bdelete", new Entry(Kind.QUIT, Arguments.BUFFERS, false));
        table.put("bwipeout", new Entry(Kind.QUIT, Arguments.BUFFERS, false));
        table.put("bunload", new Entry(Kind.QUIT, Arguments.BUFFERS, false));
        table.put("qall", new Entry(Kind.QUIT_ALL, Arguments.NONE, false));
        table.put("quitall", new Entry(Kind.QUIT_ALL, Arguments.NONE, false));
        table.put("wq", new Entry(Kind.WRITE_QUIT, Arguments.ANY, true));
        table.put("xit", new Entry(Kind.WRITE_QUIT, Arguments.ANY, false));
        table.put("exit", new Entry(Kind.WRITE_QUIT, Arguments.ANY, false));
        table.put("wqall", new Entry(Kind.WRITE_QUIT_ALL, Arguments.NONE, false));
        table.put("xall", new Entry(Kind.WRITE_QUIT_ALL, Arguments.NONE, false));
        table.put("edit", new Entry(Kind.EDIT, Arguments.ANY, false));
        table.put("tabnext", new Entry(Kind.TAB_NEXT, Arguments.COUNT, false));
        table.put("tabprevious", new Entry(Kind.TAB_PREVIOUS, Arguments.COUNT, false));
        table.put("tabNext", new Entry(Kind.TAB_PREVIOUS, Arguments.COUNT, false));
        table.put("tabfirst", new Entry(Kind.TAB_FIRST, Arguments.NONE, false));
        table.put("tabrewind", new Entry(Kind.TAB_FIRST, Arguments.NONE, false));
        table.put("tablast", new Entry(Kind.TAB_LAST, Arguments.NONE, false));
        return Map.copyOf(table);
    }
}
