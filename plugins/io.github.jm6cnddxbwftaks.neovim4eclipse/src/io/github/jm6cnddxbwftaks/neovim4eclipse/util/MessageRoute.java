// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Decides where Neovim's messages go in Eclipse: the editor's one-line message line, or the output
 * popup. It collects the messages of one command (from one key or submitted line until the next,
 * {@link #clear}) and says, after each one, what Eclipse should show ({@link #view}).
 *
 * <h2>The rule (as Vim's own screen does it)</h2>
 * <ul>
 *   <li>One message of one line goes to the message line: {@code 3 lines yanked}, {@code E486:
 *       Pattern not found: x} (in the error color), {@code :echo "hi"}, {@code :pwd}.</li>
 *   <li>More than that, in one message or several, goes to the output popup, as Vim would show
 *       them above a "Press ENTER" prompt: a two-line {@code :echo}, a failed save with its
 *       {@code Error in BufWriteCmd Autocommands ...} header, a Lua error with its traceback.</li>
 *   <li>A listing ({@code list_cmd}) follows the same two rules, as on Neovim's own screen: one
 *       line goes to the message line ({@code :set ts?}, {@code :ls} with one buffer, the file info
 *       of a typed {@code :b}), more to the popup ({@code :ls}, {@code :marks}).</li>
 *   <li>Shell output always goes to the popup, even when one line long: the {@code shell_*} kinds,
 *       and {@code :messages}. (Shell output arrives appended to the {@code shell_cmd} message, so
 *       that one counts as output too.)</li>
 *   <li>Not shown: the echo of a search ({@code search_cmd}: Eclipse's box showed it) and of a
 *       shell command (the first line of {@code shell_cmd}: the popup's title is the command), and
 *       empty messages.</li>
 *   <li>Not shown either: a passing note that Neovim's screen overwrites with the next message.
 *       {@code :vimgrep /Bar/ src/**}{@code /*.java} sends its progress line, the file it is
 *       searching ({@code src/Bar.java}, not kept for {@code :messages}, its kind left over from the
 *       message before it: {@code ""}, {@code list_cmd} or {@code undo} were seen), then
 *       {@code (1 of 2): class Bar {}} (kind {@code quickfix}). Neovim's own screen shows only the
 *       second, but the events do not say so ({@code replace_last} is false), so the two would count
 *       as two messages and open the popup, which the jump to the match's editor closes at once.
 *       So a quickfix message drops the messages of its command that Neovim does not keep for
 *       {@code :messages} (found 2026-10-30). The kind cannot tell the note apart, so an
 *       {@code :echo} in the same command line ({@code :echo "x" | cnext}) goes too; errors stay.</li>
 * </ul>
 *
 * <h2>Updates</h2>
 * A message can change one already collected: {@code replace_last} replaces the previous message
 * ({@code search_count} {@code [2/5]} after the search's echo), {@code append} continues it (the
 * chunks of {@code :!make}'s output), and a message whose id is already there updates it in place
 * ({@code :w}'s {@code "a.txt" 5L, 13B written}, id {@code "bufwrite"}, sent twice).
 *
 * <p>Example, {@code :!ls} typed: {@code shell_cmd ":!ls\r\n"} (shown: nothing), then
 * {@code shell_out "a.txt\nb.txt\n"} with {@code append}: the view is the popup with
 * {@code a.txt\nb.txt}, titled {@code :!ls}.
 *
 * <p>Pure and not thread-safe; {@code core/StatusTracker} owns one and synchronizes on it.
 * Unit-tested in {@code MessageRouteTest}.
 */
public final class MessageRoute {

    /** The kind given to {@code :messages}' list, which is its own event ({@link MessageEvent.History}). */
    private static final String HISTORY_KIND = "history";
    /** Kinds that are errors: drawn in the error color on the message line. */
    private static final Set<String> ERROR_KINDS = Set.of("emsg", "echoerr", "lua_error", "rpc_error");
    /** Kinds that are output to read: always the popup. (A listing, {@code list_cmd}, is not: see the class comment.) */
    private static final Set<String> OUTPUT_KINDS = Set.of("shell_cmd", "shell_out", "shell_err",
            "shell_ret", HISTORY_KIND);
    /** The kind of a quickfix command's {@code (1 of 2): ...}, which overwrites a passing note before it. */
    private static final String QUICKFIX_KIND = "quickfix";
    /** Kinds that only echo what was typed. */
    private static final Set<String> ECHO_KINDS = Set.of("search_cmd", "empty", "return_prompt");

    /** What Eclipse should show. Exactly one of {@link #line} and {@link #output} is non-empty, or neither. */
    public record View(String line, boolean error, String title, String output) {
        public static final View NONE = new View("", false, "", "");

        /** Whether the output popup should show {@link #output}. */
        public boolean hasOutput() {
            return !output.isEmpty();
        }
    }

    /** One collected message; {@code text} grows when a later one appends to it. */
    private static final class Message {
        final String kind;
        final String id;
        final String trigger;
        /** Whether Neovim keeps it for {@code :messages}; a passing note is not. */
        final boolean history;
        String text;

        Message(String kind, String text, String id, String trigger, boolean history) {
            this.kind = kind;
            this.text = text;
            this.id = id;
            this.trigger = trigger;
            this.history = history;
        }

        /**
         * A message Neovim does not keep for {@code :messages}, such as {@code :vimgrep}'s progress
         * line. Its kind says nothing: Neovim 0.12.6 sends that line with whatever kind the message
         * before it had, even one of another command ({@code ""}, {@code list_cmd}, {@code undo} were
         * seen), so only the flag counts. Errors are always kept, so they never count.
         */
        boolean isPassingNote() {
            return !history;
        }
    }

    private final List<Message> messages = new ArrayList<>();
    /** The command line last seen in Neovim ({@code :ls}), the popup's title for typed commands. */
    private String commandLine = "";

    /** A new command starts (a key, a line from the box): what was collected is done with. */
    public void clear() {
        messages.clear();
    }

    /**
     * Neovim showed command line {@code text} ({@code ":ls"}): the title of the output that a typed
     * command prints next. Not cleared by {@link #clear}: Enter, the key that runs the line, comes
     * after the line was shown.
     */
    public void commandLine(String text) {
        commandLine = text == null ? "" : text;
    }

    /** Adds a message; see the class comment for {@code replace_last}, {@code append} and the id. */
    public void add(MessageEvent.Show show) {
        String text = show.text().replace("\r", "");
        if ("shell_cmd".equals(show.kind())) {
            int lineEnd = text.indexOf('\n');
            text = lineEnd < 0 ? "" : text.substring(lineEnd + 1); // the rest: "[No write since last change]"
        }
        Message same = byId(show.id());
        if (same != null) {
            same.text = text;
        } else if (show.append() && !messages.isEmpty()) {
            Message last = messages.get(messages.size() - 1);
            last.text = last.text + text;
        } else {
            if (show.replaceLast() && !messages.isEmpty()) {
                messages.remove(messages.size() - 1);
            }
            if (QUICKFIX_KIND.equals(show.kind())) {
                // Not only the message just before: the note can come in an earlier redraw, with
                // others (an empty one) in between.
                messages.removeIf(Message::isPassingNote);
            }
            messages.add(new Message(show.kind(), text, show.id(), show.trigger(), show.history()));
        }
    }

    /** Adds {@code :messages}' list (typed, so the popup is titled with the command line). */
    public void add(MessageEvent.History history) {
        messages.add(new Message(HISTORY_KIND, history.text(), "", "typed_cmd", true));
    }

    /** What to show for the messages collected since {@link #clear}. */
    public View view() {
        List<Message> shown = new ArrayList<>();
        for (Message m : messages) {
            if (!ECHO_KINDS.contains(m.kind) && !m.text.isBlank()) {
                shown.add(m);
            }
        }
        if (shown.isEmpty()) {
            return View.NONE;
        }
        Message first = shown.get(0);
        if (shown.size() == 1 && !OUTPUT_KINDS.contains(first.kind) && !first.text.strip().contains("\n")) {
            return new View(first.text.strip(), ERROR_KINDS.contains(first.kind), "", "");
        }
        StringBuilder output = new StringBuilder();
        for (Message m : shown) {
            if (!output.isEmpty()) {
                output.append('\n');
            }
            output.append(stripLeadingBlankLines(m.text).stripTrailing());
        }
        String title = "typed_cmd".equals(first.trigger) ? commandLine : "";
        return new View("", false, title, output.toString());
    }

    private Message byId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (Message m : messages) {
            if (Objects.equals(m.id, id)) {
                return m;
            }
        }
        return null;
    }

    /** Leading empty lines go; the first line's indentation stays ({@code "  tabstop=8"}). */
    private static String stripLeadingBlankLines(String text) {
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0 || !text.substring(start, end).isBlank()) {
                break;
            }
            start = end + 1;
        }
        return text.substring(start);
    }
}
