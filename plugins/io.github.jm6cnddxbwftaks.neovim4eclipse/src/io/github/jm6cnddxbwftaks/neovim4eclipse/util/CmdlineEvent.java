// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.List;

/**
 * One event of Neovim's UI protocol about its command line, its completion menu, or the window's
 * view while a search is typed. Neovim sends them inside {@code redraw} notifications to a UI
 * attached with {@code ext_cmdline} and {@code ext_popupmenu} (and {@code ext_linegrid} for the
 * view), instead of drawing them into its screen grid. The editor's {@code :} box draws the command
 * line from them ({@link CmdlineState}); it edits nothing itself.
 *
 * <h2>The protocol ({@code :help ui-cmdline}, {@code :help ui-popupmenu}, {@code :help ui-linegrid})</h2>
 * <pre>
 * ["cmdline_show", content, pos, firstc, prompt, indent, level, hl_id]   opened, or its text changed
 * ["cmdline_pos", pos, level]                                           the cursor moved
 * ["cmdline_special_char", c, shift, level]                             after Ctrl-R, Ctrl-V: a mark at the cursor
 * ["cmdline_hide", level, abort]                                        closed (abort: Esc, Ctrl-C)
 * ["popupmenu_show", items, selected, row, col, grid]                   candidates; grid -1 is the command line
 * ["popupmenu_select", selected]
 * ["popupmenu_hide"]
 * ["win_viewport", grid, win, topline, botline, curline, curcol, line_count, scroll_delta]
 * </pre>
 * {@code content} is a list of {@code [attr, text, hl_id]} chunks; {@code pos} is a byte offset into
 * its text; {@code firstc} is {@code ":"}, {@code "/"}, {@code "?"}, or {@code ""} for a prompt
 * ({@code input()}, {@code confirm()}), whose text is {@code prompt}; {@code level} is 1, or more for a
 * command line opened inside another ({@code <C-r>=}). A {@code redraw} batch holds one entry per
 * event name, with one argument list per event: {@code ["cmdline_pos", args1, args2]}.
 *
 * <p>Examples as Neovim 0.12.6 sends them (experiments X3 and X8 of the cleanup plan): {@code :set
 * tabs} gives {@code cmdline_show [[0, "set tabs"]] 8 ":" "" 0 1}; {@code <Left>} then
 * {@code cmdline_pos 7 1}; {@code :e <Tab>} a {@code popupmenu_show} with every file and grid -1;
 * {@code input("Name: ")} a {@code cmdline_show} with firstc {@code ""} and prompt {@code "Name: "}.
 */
public sealed interface CmdlineEvent {

    /** {@code cmdline_show}: a command line opened or changed; {@code pos} is a byte offset in {@code content}. */
    record Show(String firstc, String content, int pos, String prompt, int indent, int level) implements CmdlineEvent {
    }

    /** {@code cmdline_pos}: the cursor of command line {@code level} is at byte {@code pos}. */
    record Pos(int pos, int level) implements CmdlineEvent {
    }

    /**
     * {@code cmdline_special_char}: Neovim waits for the next key ({@code <C-r>} shows {@code "},
     * {@code <C-v>} shows {@code ^}) and shows {@code c} at the cursor, pushing the text after it
     * right ({@code shift}) or over the character there.
     */
    record SpecialChar(String c, boolean shift, int level) implements CmdlineEvent {
    }

    /** {@code cmdline_hide}: command line {@code level} closed; {@code abort} for Esc or Ctrl-C. */
    record Hide(int level, boolean abort) implements CmdlineEvent {
    }

    /** {@code popupmenu_show}: completion candidates; {@code selected} is -1 for none. */
    record MenuShow(List<String> items, int selected, int grid) implements CmdlineEvent {
        /** Grid -1 means the menu belongs to the command line, not to an insert in the text. */
        public boolean forCmdline() {
            return grid == -1;
        }
    }

    /** {@code popupmenu_select}: the selected candidate changed (-1: none, the typed text). */
    record MenuSelect(int selected) implements CmdlineEvent {
    }

    /** {@code popupmenu_hide}. */
    record MenuHide() implements CmdlineEvent {
    }

    /**
     * {@code win_viewport}: where the window's cursor is, 0-based line and byte column. While a
     * search is typed with {@code 'incsearch'} this is the match; no {@code CursorMoved} fires.
     */
    record Viewport(int curline, int curcol) implements CmdlineEvent {
    }

    /**
     * The events in one {@code redraw} notification's arguments, in order. Everything else in it
     * (grid lines, highlights, messages) is skipped, and so are malformed entries: the protocol is
     * Neovim's, and a surprise must not break the plug-in.
     *
     * @param batches the notification's argument list, as the RPC layer decodes it
     */
    static List<CmdlineEvent> fromRedraw(List<?> batches) {
        List<CmdlineEvent> events = new ArrayList<>();
        for (Object batch : batches) {
            if (!(batch instanceof List<?> entry) || entry.isEmpty() || !(entry.get(0) instanceof String name)) {
                continue;
            }
            if ("popupmenu_hide".equals(name)) {
                events.add(new MenuHide());
                continue;
            }
            for (int i = 1; i < entry.size(); i++) {
                if (entry.get(i) instanceof List<?> args) {
                    CmdlineEvent event = switch (name) {
                        case "cmdline_show" -> show(args);
                        case "cmdline_pos" -> args.size() < 2 ? null : new Pos(number(args.get(0), 0), number(args.get(1), 1));
                        case "cmdline_special_char" -> args.size() < 3 ? null
                                : new SpecialChar(string(args.get(0)), Boolean.TRUE.equals(args.get(1)), number(args.get(2), 1));
                        case "cmdline_hide" -> new Hide(args.isEmpty() ? 1 : number(args.get(0), 1),
                                args.size() > 1 && Boolean.TRUE.equals(args.get(1)));
                        case "popupmenu_show" -> menu(args);
                        case "popupmenu_select" -> args.isEmpty() ? null : new MenuSelect(number(args.get(0), -1));
                        case "win_viewport" -> args.size() < 6 ? null
                                : new Viewport(number(args.get(4), 0), number(args.get(5), 0));
                        default -> null;
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
        if (args.size() < 6) {
            return null;
        }
        return new Show(string(args.get(2)), MessageEvent.text(args.get(0)), number(args.get(1), 0),
                string(args.get(3)), number(args.get(4), 0), number(args.get(5), 1));
    }

    /** Each item is {@code [word, kind, menu, info]}; the word is what completion inserts. */
    private static MenuShow menu(List<?> args) {
        if (args.size() < 5 || !(args.get(0) instanceof List<?> items)) {
            return null;
        }
        List<String> words = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof List<?> fields && !fields.isEmpty()) {
                words.add(string(fields.get(0)));
            }
        }
        return new MenuShow(List.copyOf(words), number(args.get(1), -1), number(args.get(4), 0));
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }
}
