// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Neovim's answer for a window's status line ({@code LuaScripts.STATUS_LINE}'s {@code line})
 * into a one-row {@link GridSnapshot}, which the editor's status row draws as the pages draw theirs
 * ({@code editor/GridCanvas}).
 *
 * <h2>The answer</h2>
 * {@code {shown, cells, hl, attrs}}: whether the window has a status line ({@code 'laststatus'}),
 * one string per screen cell (a wide character's second cell {@code ""}), each cell's highlight as
 * a 1-based index into {@code attrs}, and per highlight
 * {@code {fg, bg, reverse, bold, italic, underline, strikethrough}} with colors as
 * {@code 0xRRGGBB} or -1 for the default. Example: {@code {true, {"a", " "}, {1, 1},
 * {{0xFFFFFF, 0x333333, false, true, false, false, false}}}} is a row of two cells, {@code "a "},
 * white on dark gray, bold.
 *
 * <p>A window without a status line, and an answer that is not understood, give a snapshot of no
 * rows ({@link GridSnapshot#height()} 0): the row is hidden. Never throws: a parsing exception on
 * the session's thread would lose the rest of the key's update. Pure; {@code StatusLinesTest}.
 */
public final class StatusLines {

    /** A line's number when Neovim gave none (an older answer, a test's fake): it orders nothing. */
    public static final long NO_STAMP = -1;

    /**
     * A window's line and the number Neovim gave its evaluation ({@code LuaScripts.STATUS_LINE},
     * "Which line is newer"): of two lines of one window, the one with the higher number is newer,
     * whichever arrives last. {@link #NO_STAMP} when it had none.
     */
    public record Stamped(GridSnapshot line, long stamp) {
    }

    private static final GridEvent.DefaultColors NO_DEFAULTS =
            new GridEvent.DefaultColors(GridEvent.NO_COLOR, GridEvent.NO_COLOR);

    private StatusLines() {
    }

    /** No status line for window {@code window}: a snapshot of no rows. */
    public static GridSnapshot none(int window) {
        return new GridSnapshot(window, 0, 0, List.of(), new int[0], Map.of(), NO_DEFAULTS, -1, -1);
    }

    /** Window {@code window}'s status line from {@code line}, Neovim's answer (see the class comment). */
    public static GridSnapshot of(int window, Object line) {
        if (!(line instanceof List<?> parts) || parts.size() < 4 || !Boolean.TRUE.equals(parts.get(0))
                || !(parts.get(1) instanceof List<?> cells) || !(parts.get(2) instanceof List<?> hls)
                || !(parts.get(3) instanceof List<?> attrList) || cells.isEmpty() || cells.size() != hls.size()) {
            return none(window);
        }
        List<String> text = new ArrayList<>(cells.size());
        int[] hl = new int[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            text.add(cells.get(i) instanceof String s ? s : " ");
            hl[i] = hls.get(i) instanceof Number n ? n.intValue() : 0;
        }
        Map<Integer, GridEvent.HlAttr> attrs = new HashMap<>();
        for (int i = 0; i < attrList.size(); i++) {
            if (attrList.get(i) instanceof List<?> a && a.size() >= 7) {
                int id = i + 1; // Lua's 1-based index, as the cells give it
                attrs.put(id, new GridEvent.HlAttr(id, color(a.get(0)), color(a.get(1)), flag(a.get(2)),
                        flag(a.get(3)), flag(a.get(4)), flag(a.get(5)), flag(a.get(6))));
            }
        }
        return new GridSnapshot(window, text.size(), 1, text, hl, attrs, NO_DEFAULTS, -1, -1);
    }

    /**
     * Every window's line from an {@code eclipse_status_lines} notification's argument,
     * {@code {{win, line, stamp}, ...}}; entries that are not understood are left out, a missing or
     * malformed stamp is {@link #NO_STAMP}.
     */
    public static List<Stamped> all(Object lines) {
        List<Stamped> result = new ArrayList<>();
        if (lines instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof List<?> pair && pair.size() >= 2 && pair.get(0) instanceof Number win) {
                    long stamp = pair.size() >= 3 && pair.get(2) instanceof Number n && n.longValue() >= 0
                            ? n.longValue() : NO_STAMP;
                    result.add(new Stamped(of(win.intValue(), pair.get(1)), stamp));
                }
            }
        }
        return result;
    }

    private static int color(Object value) {
        return value instanceof Number n && n.longValue() >= 0 ? (int) (n.longValue() & 0xFFFFFF) : GridEvent.NO_COLOR;
    }

    private static boolean flag(Object value) {
        return Boolean.TRUE.equals(value);
    }
}
