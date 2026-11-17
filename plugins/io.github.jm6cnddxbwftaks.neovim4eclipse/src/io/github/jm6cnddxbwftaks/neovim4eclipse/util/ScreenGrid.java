// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The whole screen of a Neovim attached without {@code ext_multigrid}, as its grid events build
 * it: the second Neovim of the Neovim view ({@code core/ScreenSession}), whose screen is drawn
 * as it is, windows, status line, {@code :} line and messages included. Used on the RPC reader
 * thread only; what leaves it is an immutable {@link GridSnapshot}.
 *
 * <h2>Why a class apart from {@link Grids}</h2>
 * {@link Grids} serves the editing Neovim, attached with {@code ext_multigrid}: a grid per window,
 * with {@code win_pos} saying which window each grid shows, and only some of them drawn. Without
 * {@code ext_multigrid} Neovim draws everything into grid 1 ({@code :help ui-linegrid}) and sends
 * no window events, so there is nothing to choose: the one grid is the screen. Both keep their
 * cells in {@link GridCells}, so the events are read the same way.
 *
 * <h2>Example</h2>
 * <pre>
 *   screen.apply(new Resize(1, 80, 24));        // the screen, 80x24
 *   screen.apply(new Line(1, 0, 0, cells));     // "health: OK" on row 0
 *   screen.apply(new CursorGoto(1, 0, 0));
 *   screen.apply(new Flush());
 *   screen.snapshotIfChanged();                 // the 80x24 grid, the cursor on row 0, column 0
 *   screen.snapshotIfChanged();                 // empty: nothing changed since
 * </pre>
 */
public final class ScreenGrid {

    /** The grid Neovim draws its whole screen into when no grid per window was asked for. */
    public static final int SCREEN = 1;
    /**
     * The window ID a snapshot carries ({@link GridSnapshot#window}): none, the snapshot is the
     * whole screen. Neovim's window IDs start at 1000, so 0 is never one.
     */
    public static final int WHOLE_SCREEN = 0;

    private GridCells cells;
    private final Map<Integer, GridEvent.HlAttr> attrs = new HashMap<>();
    private GridEvent.DefaultColors defaultColors = new GridEvent.DefaultColors(GridEvent.NO_COLOR, GridEvent.NO_COLOR);
    private int cursorRow = -1;
    private int cursorCol = -1;
    private boolean changed;
    private GridSnapshot last;

    /**
     * Applies one event. Events of another grid than {@link #SCREEN} are ignored: without
     * {@code ext_multigrid} there are none, and should a Neovim send one, it is not part of the
     * screen. A {@link GridEvent.Flush} only ends a redraw (see {@link #snapshotIfChanged}).
     */
    public void apply(GridEvent event) {
        switch (event) {
            case GridEvent.Resize e when e.grid() == SCREEN -> {
                if (cells == null) {
                    cells = new GridCells(e.width(), e.height());
                } else {
                    cells.resize(e.width(), e.height());
                }
                changed = true;
            }
            case GridEvent.Clear e when e.grid() == SCREEN -> withCells(GridCells::clear);
            case GridEvent.Line e when e.grid() == SCREEN -> withCells(c -> c.line(e.row(), e.colStart(), e.cells()));
            case GridEvent.Scroll e when e.grid() == SCREEN ->
                withCells(c -> c.scroll(e.top(), e.bot(), e.left(), e.right(), e.rows()));
            case GridEvent.CursorGoto e when e.grid() == SCREEN -> {
                changed |= e.row() != cursorRow || e.col() != cursorCol;
                cursorRow = e.row();
                cursorCol = e.col();
            }
            case GridEvent.Destroy e when e.grid() == SCREEN -> {
                cells = null;
                changed = true;
            }
            case GridEvent.HlAttr e -> {
                attrs.put(e.id(), e);
                changed = true;
            }
            case GridEvent.DefaultColors e -> {
                defaultColors = e;
                changed = true;
            }
            default -> {
                // another grid's event, a window event (none without ext_multigrid), or flush
            }
        }
    }

    /**
     * The screen, if it changed since the last snapshot and its size is known; empty otherwise.
     * Called at each {@code flush}, when the screen is consistent. A redraw that leaves every cell
     * as it was (the cursor blinking back to the same cell) gives no snapshot either.
     */
    public Optional<GridSnapshot> snapshotIfChanged() {
        if (!changed || cells == null) {
            return Optional.empty();
        }
        changed = false;
        GridSnapshot snapshot = snapshot();
        if (snapshot.equals(last)) {
            return Optional.empty();
        }
        last = snapshot;
        return Optional.of(snapshot);
    }

    /** The screen as it stands, with the highlights its cells use. */
    private GridSnapshot snapshot() {
        Set<Integer> used = new HashSet<>();
        for (int id : cells.hl) {
            used.add(id);
        }
        Map<Integer, GridEvent.HlAttr> usedAttrs = new HashMap<>();
        for (int id : used) {
            GridEvent.HlAttr attr = attrs.get(id);
            if (attr != null) {
                usedAttrs.put(id, attr);
            }
        }
        boolean cursorOnScreen = cursorRow >= 0 && cursorRow < cells.height && cursorCol >= 0 && cursorCol < cells.width;
        return new GridSnapshot(WHOLE_SCREEN, cells.width, cells.height, new ArrayList<>(Arrays.asList(cells.text)),
                cells.hl, usedAttrs, defaultColors, cursorOnScreen ? cursorRow : -1, cursorOnScreen ? cursorCol : -1);
    }

    private void withCells(java.util.function.Consumer<GridCells> action) {
        if (cells != null) {
            action.accept(cells);
            changed = true;
        }
    }
}
