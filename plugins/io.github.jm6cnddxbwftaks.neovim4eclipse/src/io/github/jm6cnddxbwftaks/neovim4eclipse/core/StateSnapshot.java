// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.ArrayList;
import java.util.List;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.BlockSelection;

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
    static final int RECORDING = 7;
    static final int STATUSLINE = 8;
    static final int BUFNR = 9;
    /** A block's lines, flat: line, first byte, last byte per line (see {@code util/BlockSelection}). */
    static final int BLOCK = 10;
    /** {@code nvim_get_mode().mode} when the snapshot was taken; see {@link #mode}. */
    static final int MODE = 11;
    /** The outermost closed folds' indexes, when folding is on; see {@link #closedFolds}. */
    static final int FOLDS = 12;

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

    /**
     * Neovim's mode when the snapshot was taken, or {@code null} if missing or malformed. Fresher
     * than any mode read before the request: Neovim answers the request only after the keys sent
     * before it ({@code StateSync.publishState}).
     */
    static String mode(Object raw) {
        if (raw instanceof List<?> list && list.size() > MODE && list.get(MODE) instanceof String mode
                && !mode.isEmpty()) {
            return mode;
        }
        return null;
    }

    /**
     * The indexes of the outermost closed folds ({@link FoldState#closed()}), or {@code null} when
     * the element is missing or is not a list. {@code null} and an empty list differ: empty means
     * "no fold is closed" and expands every fold in Eclipse, so an answer that is not understood must
     * not look like it. Entries that are not numbers are skipped.
     */
    static List<Integer> closedFolds(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() <= FOLDS || !(list.get(FOLDS) instanceof List<?> folds)) {
            return null;
        }
        List<Integer> closed = new ArrayList<>(folds.size());
        for (Object id : folds) {
            int index = toInt(id, -1);
            if (index >= 0) {
                closed.add(index);
            }
        }
        return closed;
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

    /** The visual selection, with the lines of a block ({@link #blockLines}). */
    static VisualSelection selection(Object raw, String fallbackMode) {
        if (!(raw instanceof List<?> list) || list.size() < VISUAL_BOUNDS + 4) {
            return visualBounds(null, fallbackMode);
        }
        VisualSelection base = visualBounds(
                list.subList(VISUAL_BOUNDS, Math.min(list.size(), VISUAL_BOUNDS + 5)), fallbackMode);
        return new VisualSelection(base.active(), base.mode(), base.startLine1Based(), base.startCol0Based(),
                base.endLine1Based(), base.endCol0Based(), list.size() > BLOCK ? blockLines(list.get(BLOCK)) : List.of());
    }

    /**
     * The block's lines from the flat {@code {line, first byte, last byte, ...}} list; an incomplete
     * or malformed triple ends the list (the lines read so far are kept).
     */
    static List<BlockSelection.ByteRange> blockLines(Object raw) {
        List<BlockSelection.ByteRange> lines = new ArrayList<>();
        if (raw instanceof List<?> flat) {
            for (int i = 0; i + 2 < flat.size(); i += 3) {
                int line = toInt(flat.get(i), -1);
                int first = toInt(flat.get(i + 1), -1);
                int last = toInt(flat.get(i + 2), -1);
                if (line < 1 || first < 1 || last < 1) {
                    break;
                }
                lines.add(new BlockSelection.ByteRange(line, first, last));
            }
        }
        return lines;
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
