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
 * and the cursor. A pure function,
 * so each case is a unit test.
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
}
