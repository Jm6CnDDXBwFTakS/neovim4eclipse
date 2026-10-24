// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.List;

/**
 * One command-line event of Neovim's UI protocol: a command line was shown (opened, or its text
 * changed) or hidden. Neovim sends them to a UI attached with {@code ext_cmdline}, inside
 * {@code redraw} notifications, instead of drawing the command line into its screen grid.
 *
 * <p>The plug-in uses them to notice a command line it did not open itself: a mapping such as
 * {@code nnoremap ; :} or {@code <Space>s :%s/}, a count before {@code :} ({@code 2:} gives
 * {@code :.,.+1}), {@code !} with a motion ({@code :.!}). See docs/design/KEY_ROUTING.md, phase 2.
 *
 * <h2>The protocol ({@code :help ui-cmdline})</h2>
 * <pre>
 * ["redraw", [["cmdline_show", [content, pos, firstc, prompt, indent, level, ...]], ...]]
 * ["redraw", [["cmdline_hide", [level, abort]]]]
 * </pre>
 * {@code content} is a list of {@code [attr, text]} chunks (newer Neovim adds a highlight id as a
 * third element), {@code firstc} is {@code ":"}, {@code "/"}, {@code "?"}, or {@code ""} for a
 * prompt ({@code input()}), and {@code level} is 1, or more for a command line opened inside
 * another ({@code <C-r>=}). A {@code redraw} batch holds one entry per event name, with one
 * argument list per event: {@code ["cmdline_show", args1, args2]}.
 *
 * @param shown   {@code true} for {@code cmdline_show}, {@code false} for {@code cmdline_hide}
 * @param firstc  the command line's type; {@code ""} for a hide
 * @param content the text typed so far, without {@code firstc}; {@code ""} for a hide
 * @param level   1 for a top-level command line
 */
public record CmdlineEvent(boolean shown, String firstc, String content, int level) {

    /**
     * The command-line events in one {@code redraw} notification's arguments, in order. Everything
     * else in it (grid lines, cursor moves, highlights) is skipped. Malformed entries are skipped
     * too: the protocol is Neovim's, and a surprise must not break the plug-in.
     *
     * @param batches the notification's argument list, as the RPC layer decodes it
     */
    public static List<CmdlineEvent> fromRedraw(List<?> batches) {
        List<CmdlineEvent> events = new ArrayList<>();
        for (Object batch : batches) {
            if (!(batch instanceof List<?> entry) || entry.isEmpty()) {
                continue;
            }
            Object name = entry.get(0);
            boolean show = "cmdline_show".equals(name);
            if (!show && !"cmdline_hide".equals(name)) {
                continue; // most batches: grid_line, cursor_goto, flush ...
            }
            for (int i = 1; i < entry.size(); i++) {
                if (entry.get(i) instanceof List<?> args) {
                    CmdlineEvent event = show ? show(args) : hide(args);
                    if (event != null) {
                        events.add(event);
                    }
                }
            }
        }
        return events;
    }

    private static CmdlineEvent show(List<?> args) {
        if (args.size() < 6) {
            return null;
        }
        StringBuilder content = new StringBuilder();
        if (args.get(0) instanceof List<?> chunks) {
            for (Object chunk : chunks) {
                if (chunk instanceof List<?> parts && parts.size() >= 2 && parts.get(1) != null) {
                    content.append(parts.get(1));
                }
            }
        }
        Object firstc = args.get(2);
        return new CmdlineEvent(true, firstc == null ? "" : firstc.toString(), content.toString(), level(args.get(5)));
    }

    private static CmdlineEvent hide(List<?> args) {
        return new CmdlineEvent(false, "", "", args.isEmpty() ? 1 : level(args.get(0)));
    }

    private static int level(Object value) {
        return value instanceof Number number ? number.intValue() : 1;
    }
}
