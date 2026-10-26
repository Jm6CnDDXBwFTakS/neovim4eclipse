// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.List;
import java.util.TreeMap;

/**
 * Neovim's command line as the editor's {@code :} box shows it, kept up to date from
 * {@link CmdlineEvent}s. The command line is Neovim's: its text, cursor, history, completion,
 * {@code <C-r>} registers, mappings and abbreviations are all Neovim's own. The box only draws
 * {@link #view} and sends every key back to Neovim.
 *
 * <p>Example: {@code :} then {@code set tabs} then Tab:
 * <pre>
 *   Show ":" ""          view ":"              caret 1
 *   Show ":" "set tabs"  view ":set tabs"      caret 9
 *   Show ":" "set tabstop" (Neovim completed)  caret 12
 *   Hide                 closed
 * </pre>
 * A command line opened inside another ({@code <C-r>=} gives level 2) is shown while it is open,
 * then the outer one again. Prompts show their prompt instead of a type character:
 * {@code input("Name: ")} is {@code "Name: "}, {@code confirm()} {@code "[Y]es, (N)o: "}.
 *
 * <p>Pure and not thread-safe; {@code core/NeovimSession} owns one and feeds it on the RPC thread.
 * Unit-tested in {@code CmdlineStateTest}.
 */
public final class CmdlineState {

    /**
     * What the box shows.
     *
     * @param open       whether Neovim has a command line open
     * @param firstc     its type: {@code ":"}, {@code "/"}, {@code "?"}, or {@code ""} for a prompt
     * @param text       the whole line as drawn: type character or prompt, indent, content, and a
     *                   pending special character ({@code "} after {@code <C-r>})
     * @param caret      the caret, a char index into {@code text}
     * @param candidates completion candidates (Tab), empty when there is no menu
     * @param selected   the selected candidate, -1 for none
     */
    public record View(boolean open, String firstc, String text, int caret, List<String> candidates, int selected) {
        public static final View CLOSED = new View(false, "", "", 0, List.of(), -1);

        public View {
            candidates = List.copyOf(candidates);
        }

        /** A search line ({@code /} or {@code ?}), whose match Neovim previews in the text. */
        public boolean isSearch() {
            return open && ("/".equals(firstc) || "?".equals(firstc));
        }
    }

    /** One open command line. */
    private static final class Line {
        CmdlineEvent.Show show;
        int pos;
        CmdlineEvent.SpecialChar special;

        Line(CmdlineEvent.Show show) {
            this.show = show;
            this.pos = show.pos();
        }
    }

    /** The open command lines by level; the highest is the one shown. */
    private final TreeMap<Integer, Line> lines = new TreeMap<>();
    private List<String> candidates = List.of();
    private int selected = -1;

    /**
     * Applies one event.
     *
     * @return whether {@link #view} may have changed ({@link CmdlineEvent.Viewport} never changes it)
     */
    public boolean apply(CmdlineEvent event) {
        switch (event) {
            case CmdlineEvent.Show show -> lines.put(show.level(), new Line(show));
            case CmdlineEvent.Pos pos -> {
                Line line = lines.get(pos.level());
                if (line == null) {
                    return false;
                }
                line.pos = pos.pos();
                line.special = null;
            }
            case CmdlineEvent.SpecialChar special -> {
                Line line = lines.get(special.level());
                if (line == null) {
                    return false;
                }
                line.special = special;
            }
            case CmdlineEvent.Hide hide -> {
                lines.remove(hide.level());
                if (lines.isEmpty()) {
                    candidates = List.of();
                    selected = -1;
                }
            }
            case CmdlineEvent.MenuShow menu -> {
                if (!menu.forCmdline()) {
                    return false; // an insert's menu (Ctrl-N in a block I): nothing for the box
                }
                candidates = menu.items();
                selected = menu.selected();
            }
            case CmdlineEvent.MenuSelect select -> {
                if (candidates.isEmpty()) {
                    return false;
                }
                selected = select.selected();
            }
            case CmdlineEvent.MenuHide hide -> {
                if (candidates.isEmpty()) {
                    return false;
                }
                candidates = List.of();
                selected = -1;
            }
            case CmdlineEvent.Viewport viewport -> {
                return false;
            }
        }
        return true;
    }

    /** The command line to show, or {@link View#CLOSED}. */
    public View view() {
        if (lines.isEmpty()) {
            return View.CLOSED;
        }
        Line line = lines.lastEntry().getValue();
        CmdlineEvent.Show show = line.show;
        String head = (show.prompt().isEmpty() ? show.firstc() : show.prompt()) + " ".repeat(Math.max(0, show.indent()));
        String content = show.content();
        int cursor = Utf8Columns.byteToCharIndex(content, line.pos);
        if (line.special != null) {
            int after = line.special.shift() || cursor >= content.length() ? cursor
                    : cursor + Character.charCount(content.codePointAt(cursor));
            content = content.substring(0, cursor) + line.special.c() + content.substring(after);
        }
        return new View(true, show.firstc(), head + content, head.length() + cursor, candidates, selected);
    }
}
