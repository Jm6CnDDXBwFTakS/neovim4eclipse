// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The command line of a page shown from a window of the editing Neovim, as a one-row grid for the
 * page to draw along its bottom, as Neovim draws its command line at the bottom of its screen.
 * Neovim edits the line itself (every key in a page is Neovim's: Ctrl-W, Ctrl-R, Ctrl-U, history,
 * Tab); the page only paints what Neovim's command-line events say ({@link CmdlineState.View}): the
 * whole line as Neovim draws it (its {@code :}, {@code /} or {@code ?}, or a prompt, then the text)
 * and the cursor, and the Tab completion candidates in a row above it ({@link #candidateRow}).
 * Pure functions, so each case is a unit test.
 *
 * <p>A line longer than the page is shown from the part with the cursor in it, as Neovim scrolls a
 * long command line. Example: {@code :echo 'hi'} with the cursor at the end, in a 20-column page:
 * {@code ":echo 'hi'"} and the cursor in column 10.
 */
public final class PageRows {

    private PageRows() {
    }

    /**
     * The command-line row for {@code view} in a page {@code width} cells wide, as a snapshot of window
     * {@code window}; a blank row when the line is closed.
     */
    public static GridSnapshot commandRow(CmdlineState.View view, int width, int window, GridEvent.DefaultColors colors) {
        int cells = Math.max(1, width);
        List<String> line = new ArrayList<>();
        int cursor = -1;
        if (view.open()) {
            String text = view.text(); // the whole line as Neovim draws it: its : or / included, or a prompt
            int caretChar = Math.max(0, Math.min(view.caret(), text.length()));
            for (int i = 0; i < text.length(); ) {
                if (i == caretChar) {
                    cursor = line.size();
                }
                int cp = text.codePointAt(i);
                line.add(Character.toString(cp));
                if (CellWidth.of(cp) == 2) {
                    line.add(""); // the right half of a double-width character
                }
                i += Character.charCount(cp);
            }
            if (cursor < 0) {
                cursor = line.size(); // at the end of the line
            }
        }
        int from = cursor < cells ? 0 : cursor - cells + 1; // keep the cursor on the row
        List<String> row = new ArrayList<>(cells);
        for (int c = 0; c < cells; c++) {
            int at = from + c;
            row.add(at < line.size() ? line.get(at) : " ");
        }
        if (from > 0 && row.get(0).isEmpty()) {
            row.set(0, " "); // a cut double-width character: its right half alone shows nothing
        }
        return new GridSnapshot(window, cells, 1, row, new int[cells], Map.of(), colors, view.open() ? 0 : -1,
                view.open() ? cursor - from : -1);
    }

    /** The highlight of the selected candidate in {@link #candidateRow}: the row's colors swapped. */
    public static final int SELECTED = 1;

    /**
     * The Tab completion candidates of an open line in a page {@code width} cells wide, as a
     * one-row snapshot of window {@code window}, the selected one in {@link #SELECTED} (reverse
     * video); {@code null} while there are none. The UI asks for {@code ext_popupmenu}, so Neovim
     * draws no wildmenu of its own and sends the list instead ({@link CmdlineState}); this lays it
     * out as the text editors' {@code :} box does ({@link CandidateLine#fit}). Example: candidates
     * {@code Readme.md README.txt}, the second selected, 30 cells: {@code "Readme.md  README.txt"},
     * cells 11 to 20 in {@link #SELECTED}.
     */
    public static GridSnapshot candidateRow(CmdlineState.View view, int width, int window, GridEvent.DefaultColors colors) {
        if (!view.open() || view.candidates().isEmpty()) {
            return null;
        }
        int cells = Math.max(1, width);
        CandidateLine.ListLine line = CandidateLine.fit(view.candidates(), view.selected(), cells);
        String text = line.text();
        List<String> row = new ArrayList<>(cells);
        int[] hl = new int[cells];
        int end = line.highlightStart() + line.highlightLength();
        for (int i = 0; i < text.length() && row.size() < cells; ) {
            int cp = text.codePointAt(i);
            int id = i >= line.highlightStart() && i < end ? SELECTED : 0;
            if (CellWidth.of(cp) == 2) {
                if (row.size() + 2 > cells) {
                    break; // no room for both halves
                }
                hl[row.size()] = id;
                row.add(Character.toString(cp));
                hl[row.size()] = id;
                row.add("");
            } else {
                hl[row.size()] = id;
                row.add(Character.toString(cp));
            }
            i += Character.charCount(cp);
        }
        while (row.size() < cells) {
            row.add(" ");
        }
        GridEvent.HlAttr reverse = new GridEvent.HlAttr(SELECTED, GridEvent.NO_COLOR, GridEvent.NO_COLOR,
                true, false, false, false, false);
        return new GridSnapshot(window, cells, 1, row, hl, Map.of(SELECTED, reverse), colors, -1, -1);
    }
}
