// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.List;

/**
 * Reads the answer of {@link LuaScripts#STATE_SNAPSHOT}, the one request made after each key.
 *
 * <p>The Lua chunk returns a Lua table, which arrives through msgpack-rpc as a Java {@link List}
 * whose elements are numbers or strings. The position of each value in that list is fixed by the
 * Lua code, so the indexes below must match it. Add new values at the end so the existing indexes
 * stay valid.
 *
 * <p>Every method tolerates a short or malformed list and falls back to a neutral value, because a
 * parsing exception on the input thread would turn one odd answer into a lost keystroke. Pure
 * functions, unit-tested in {@code StateSnapshotTest}.
 */
final class StateSnapshot {

    static final int CURSOR_LINE = 0;
    static final int CURSOR_COL = 1;
    /** Visual start line, start column, end line, end column, mode: five consecutive elements. */
    static final int VISUAL_BOUNDS = 2;
    static final int CURSWANT = 7;
    static final int TABSTOP = 8;
    static final int RECORDING = 9;
    static final int STATUSLINE = 10;
    static final int BUFNR = 11;

    private StateSnapshot() {
    }

    /**
     * The evaluated statusline for the status bar; "" when it was not asked for, or for missing or
     * malformed data. (Messages and {@code showcmd} are not in the snapshot: Neovim sends them as
     * UI events, see {@code StatusTracker}.)
     */
    static String statusline(Object raw) {
        if (raw instanceof List<?> list && list.size() > STATUSLINE) {
            return text(list.get(STATUSLINE));
        }
        return "";
    }

    /** The register being recorded into; "" if not recording or not understood. */
    static String recording(Object raw) {
        if (raw instanceof List<?> list && list.size() > RECORDING && list.get(RECORDING) != null) {
            return String.valueOf(list.get(RECORDING));
        }
        return "";
    }

    /** The current buffer number, or {@link NotificationBridge#UNKNOWN_BUFFER}. */
    static int buffer(Object raw) {
        if (raw instanceof List<?> list && list.size() > BUFNR) {
            return toInt(list.get(BUFNR), NotificationBridge.UNKNOWN_BUFFER);
        }
        return NotificationBridge.UNKNOWN_BUFFER;
    }

    /** The cursor, or {@code null} if the payload is not understood. */
    static CursorPos cursor(Object raw) {
        if (raw instanceof List<?> list && list.size() > CURSOR_COL) {
            return new CursorPos(toInt(list.get(CURSOR_LINE), 1), toInt(list.get(CURSOR_COL), 0));
        }
        return null;
    }

    /** The visual selection, including the blockwise extras ({@code $} and the tab width). */
    static VisualSelection selection(Object raw, String fallbackMode) {
        if (!(raw instanceof List<?> list) || list.size() < VISUAL_BOUNDS + 4) {
            return visualBounds(null, fallbackMode);
        }
        VisualSelection base = visualBounds(
                list.subList(VISUAL_BOUNDS, Math.min(list.size(), VISUAL_BOUNDS + 5)), fallbackMode);
        // curswant is 2147483647 (Integer.MAX_VALUE) after "$": the block extends to each line's end.
        boolean toEol = list.size() > CURSWANT && toInt(list.get(CURSWANT), 0) >= Integer.MAX_VALUE;
        int tabstop = list.size() > TABSTOP ? Math.max(1, toInt(list.get(TABSTOP), 8)) : 8;
        return new VisualSelection(base.active(), base.mode(), base.startLine1Based(), base.startCol0Based(),
                base.endLine1Based(), base.endCol0Based(), toEol, tabstop);
    }

    /**
     * Parses the visual bounds {@code {start_line, start_col, end_line, end_col[, mode]}}.
     * {@code getpos()} columns are 1-based byte indices; they are converted to 0-based here.
     */
    static VisualSelection visualBounds(Object raw, String fallbackMode) {
        int startLine = 1;
        int startCol = 1;
        int endLine = 1;
        int endCol = 1;
        String mode = fallbackMode;
        if (raw instanceof List<?> list && list.size() >= 4) {
            startLine = toInt(list.get(0), 1);
            startCol = toInt(list.get(1), 1);
            endLine = toInt(list.get(2), 1);
            endCol = toInt(list.get(3), 1);
            if (list.size() >= 5 && list.get(4) != null) {
                mode = String.valueOf(list.get(4));
            }
        }
        return new VisualSelection(true, mode, startLine, Math.max(0, startCol - 1),
                endLine, Math.max(0, endCol - 1));
    }

    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** A msgpack integer arrives as some {@link Number} type; strings are tolerated too. */
    static int toInt(Object o, int fallback) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(o.toString());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }
}
