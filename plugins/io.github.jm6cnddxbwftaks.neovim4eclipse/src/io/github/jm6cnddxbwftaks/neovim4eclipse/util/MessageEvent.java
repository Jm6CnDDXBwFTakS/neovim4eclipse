// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.List;

/**
 * One message event of Neovim's UI protocol. The plug-in attaches with {@code ext_messages}
 * ({@code core/NeovimConnection}), so Neovim sends every message as an event, inside {@code redraw}
 * notifications, instead of drawing it into its screen grid, which nobody sees.
 *
 * <h2>The protocol ({@code :help ui-messages})</h2>
 * <pre>
 * ["msg_show", kind, content, replace_last, history, append, id, trigger]
 * ["msg_showcmd", content]                     the pending command ('showcmd'), e.g. "2d"
 * ["msg_history_show", entries, prev_cmd]      :messages; each entry is [kind, content, append]
 * ["flush"]                                    the end of one redraw: the screen is consistent
 * </pre>
 * {@code content} is a list of {@code [attr_id, text, hl_id]} chunks, whose texts together are the
 * message. Examples as Neovim 0.12.6 sends them (experiment X1 of the cleanup plan):
 * <pre>
 *   /nope&lt;CR&gt;   msg_show "search_cmd" "/nope", then msg_show "emsg" "E486: Pattern not found: nope"
 *   :ls          msg_show "list_cmd" with the whole listing, one event
 *   :!seq 3      msg_show "shell_cmd" ":!seq 3\r\n", then "shell_out" "1\n2\n3\n" with append = true
 *   :w           msg_show "progress" twice with id "bufwrite": the second updates the first
 * </pre>
 * The kinds are listed under {@code :help ui-messages}; {@link MessageRoute} decides what each one
 * means for Eclipse. Kinds a newer Neovim adds arrive as they are and are routed by their text.
 */
public sealed interface MessageEvent {

    /**
     * {@code msg_show}: a message.
     *
     * @param kind        e.g. {@code "emsg"}, {@code "echo"}, {@code "list_cmd"}; {@code ""} for
     *                    Neovim's own plain messages ({@code 3 lines yanked})
     * @param text        the chunks' texts joined; may hold line breaks
     * @param replaceLast whether this message replaces the one before it ({@code search_count}
     *                    after {@code search_cmd})
     * @param append      whether it continues the message before it ({@code shell_out} chunks)
     * @param id          identifies the message: a later one with the same id updates it ({@code
     *                    "bufwrite"} for {@code :w}); numbers for messages that are never updated
     * @param trigger     {@code "typed_cmd"} when a command typed on the command line printed it,
     *                    {@code ""} otherwise (a timer, a mapping's {@code <Cmd>}, a macro)
     * @param history     whether Neovim keeps the message for {@code :messages}; a passing note,
     *                    such as {@code :vimgrep}'s progress line (the file being searched), is not
     */
    record Show(String kind, String text, boolean replaceLast, boolean append, String id, String trigger,
            boolean history) implements MessageEvent {
    }

    /** {@code msg_showcmd}: the pending command, {@code ""} when there is none. */
    record Showcmd(String text) implements MessageEvent {
    }

    /** {@code msg_history_show}: what {@code :messages} lists, one entry per line. */
    record History(String text) implements MessageEvent {
    }

    /** {@code flush}: Neovim finished one redraw; a good moment to show what arrived. */
    record Flush() implements MessageEvent {
    }

    /**
     * The message events in one {@code redraw} notification's arguments, in order. Everything else
     * in it (grid lines, cursor moves, highlights, command-line events) is skipped, and so are
     * malformed entries: the protocol is Neovim's, and a surprise must not break the plug-in.
     *
     * @param batches the notification's argument list, as the RPC layer decodes it: one list per
     *                event name, {@code ["msg_show", args1, args2, ...]}
     */
    static List<MessageEvent> fromRedraw(List<?> batches) {
        List<MessageEvent> events = new ArrayList<>();
        for (Object batch : batches) {
            if (!(batch instanceof List<?> entry) || entry.isEmpty() || !(entry.get(0) instanceof String name)) {
                continue;
            }
            if ("flush".equals(name)) {
                events.add(new Flush());
                continue;
            }
            for (int i = 1; i < entry.size(); i++) {
                if (entry.get(i) instanceof List<?> args) {
                    MessageEvent event = switch (name) {
                        case "msg_show" -> show(args);
                        case "msg_showcmd" -> args.isEmpty() ? null : new Showcmd(text(args.get(0)));
                        case "msg_history_show" -> history(args);
                        default -> null; // grid_line, cmdline_show, mode_change ...
                    };
                    if (event != null) {
                        events.add(event);
                    }
                }
            }
        }
        return events;
    }

    private static Show show(List<?> args) {
        if (args.size() < 2) {
            return null;
        }
        return new Show(string(args.get(0)), text(args.get(1)), flag(args, 2), flag(args, 4),
                args.size() > 5 ? string(args.get(5)) : "", args.size() > 6 ? string(args.get(6)) : "",
                args.size() <= 3 || flag(args, 3)); // history; an older Neovim without it: kept
    }

    /** The entries' texts, one per line; an entry with {@code append} continues the line before. */
    private static History history(List<?> args) {
        if (args.isEmpty() || !(args.get(0) instanceof List<?> entries)) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (Object e : entries) {
            if (e instanceof List<?> entry && entry.size() >= 2) {
                boolean append = entry.size() > 2 && Boolean.TRUE.equals(entry.get(2));
                if (!out.isEmpty() && !append) {
                    out.append('\n');
                }
                out.append(text(entry.get(1)));
            }
        }
        return new History(out.toString());
    }

    /** The texts of a {@code content} list of {@code [attr_id, text, hl_id]} chunks, joined. */
    static String text(Object content) {
        StringBuilder text = new StringBuilder();
        if (content instanceof List<?> chunks) {
            for (Object chunk : chunks) {
                if (chunk instanceof List<?> parts && parts.size() >= 2 && parts.get(1) != null) {
                    text.append(parts.get(1));
                }
            }
        }
        return text.toString();
    }

    private static boolean flag(List<?> args, int index) {
        return args.size() > index && Boolean.TRUE.equals(args.get(index));
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }
}
