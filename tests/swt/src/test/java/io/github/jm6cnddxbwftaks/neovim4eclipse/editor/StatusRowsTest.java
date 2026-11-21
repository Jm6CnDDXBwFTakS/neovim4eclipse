// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.UiExecutor;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusLines;

/**
 * {@link StatusRows}: an editor's status row outlives the controller that asked for it, so an
 * editor in the background keeps its window's line ({@code 'laststatus'} 2 shows every window's).
 * The row itself is {@code StatusLineRowTest}'s; two editors side by side in a real workbench are
 * {@code StatusBarEndToEndTest}'s.
 */
class StatusRowsTest {

    private Shell shell;
    private StyledText first;
    private StyledText second;
    private final StatusRows rows = new StatusRows(UiExecutor.IMMEDIATE);

    @BeforeEach
    void open() {
        shell = new Shell(Display.getDefault());
        shell.setLayout(new FillLayout());
        first = new StyledText(shell, SWT.MULTI);
        second = new StyledText(shell, SWT.MULTI);
        shell.setSize(600, 300);
        shell.open();
        shell.layout(true, true);
    }

    @AfterEach
    void close() {
        shell.dispose();
    }

    /** A session's lines, as {@code NeovimSession} keeps and sends them. */
    private static final class FakeSource implements StatusRows.Source {
        final List<Consumer<GridSnapshot>> lineListeners = new CopyOnWriteArrayList<>();
        final List<Consumer<Integer>> rowsListeners = new CopyOnWriteArrayList<>();
        final Map<Integer, GridSnapshot> last = new HashMap<>();
        int commandLineRows = 1;

        void send(GridSnapshot line) {
            last.put(line.window(), line);
            lineListeners.forEach(l -> l.accept(line));
        }

        @Override public void addStatusLineListener(Consumer<GridSnapshot> l) { lineListeners.add(l); }
        @Override public void removeStatusLineListener(Consumer<GridSnapshot> l) { lineListeners.remove(l); }
        @Override public void addCommandLineRowsListener(Consumer<Integer> l) { rowsListeners.add(l); }
        @Override public void removeCommandLineRowsListener(Consumer<Integer> l) { rowsListeners.remove(l); }
        @Override public int commandLineRows() { return commandLineRows; }
        @Override public GridSnapshot statusLine(int window) { return last.get(window); }
        final List<String> widths = new CopyOnWriteArrayList<>();
        @Override public void setStatusLineWidthAsync(int window, int columns) { widths.add(window + "x" + columns); }
    }

    /** A line of {@code window} as {@code LuaScripts.STATUS_LINE} answers it. */
    private static GridSnapshot line(int window, String content) {
        List<String> cells = content.codePoints().mapToObj(Character::toString).toList();
        List<Integer> hl = cells.stream().map(c -> 1).toList();
        return StatusLines.of(window, List.of(true, cells, hl, List.of(List.of(0xFFFFFF, 0x333333, false, false, false, false, false))));
    }

    private static String shown(StatusLineRow row) {
        GridCanvas canvas = row.canvasForTests();
        return canvas.isVisible() && canvas.grid() != null ? canvas.grid().row(0).strip() : null;
    }

    /**
     * Two editors, each with its window: a line reaches its own editor's row whichever editor last
     * asked (the one with the keyboard), and the next controller of an editor gets the same row.
     */
    @Test
    void eachEditorKeepsItsRowAndShowsItsWindowsLine() {
        FakeSource session = new FakeSource();
        Object firstWindow = new Object();
        Object secondWindow = new Object();
        StatusLineRow firstRow = rows.rowFor(first, firstWindow, () -> 1000, session);
        StatusLineRow secondRow = rows.rowFor(second, secondWindow, () -> 1001, session); // the keyboard moved
        session.send(line(1000, "notes.txt"));
        session.send(line(1001, "ideas.txt"));
        assertEquals("notes.txt", shown(firstRow), "the editor in the background shows its line");
        assertEquals("ideas.txt", shown(secondRow));
        assertSame(firstRow, rows.rowFor(first, firstWindow, () -> 1000, session), "back to the first editor: the same row");
        assertEquals(1, session.lineListeners.size(), "one listener for every row");
        int margin = first.getBottomMargin();
        session.rowsListeners.forEach(l -> l.accept(0)); // :set ch=0
        assertTrue(first.getBottomMargin() < margin, "the background editor's command line row goes too");
    }

    /** A row made for an editor shows its window's last line at once, before Neovim sends again. */
    @Test
    void aNewRowShowsTheLastLineAtOnce() {
        FakeSource session = new FakeSource();
        session.send(line(1000, "notes.txt"));
        assertEquals("notes.txt", shown(rows.rowFor(first, new Object(), () -> 1000, session)));
    }

    /** A closed window's line has no rows: the row goes, the command line's row stays. */
    @Test
    void aLineOfNoRowsHidesTheRow() {
        FakeSource session = new FakeSource();
        StatusLineRow row = rows.rowFor(first, new Object(), () -> 1000, session);
        session.send(line(1000, "notes.txt"));
        session.send(StatusLines.none(1000));
        assertEquals(null, shown(row));
    }

    /**
     * A restarted Neovim: the editor it attaches to keeps its row, its height and its line (made
     * anew, it would start a row lower and resize Neovim's window twice); the other editors' rows go
     * (their window ids are the old Neovim's); the listeners move.
     */
    @Test
    void anotherNeovimKeepsTheAttachedEditorsRowAndDropsTheOthers() {
        FakeSource before = new FakeSource();
        Object window = new Object();
        StatusLineRow kept = rows.rowFor(first, window, () -> 1000, before);
        rows.rowFor(second, new Object(), () -> 1001, before);
        before.send(line(1000, "notes.txt"));
        int margin = first.getBottomMargin();
        FakeSource after = new FakeSource();
        assertSame(kept, rows.rowFor(first, window, () -> 1000, after));
        assertEquals("notes.txt", shown(kept), "its line until the new Neovim sends one");
        assertEquals(margin, first.getBottomMargin(), "the same height");
        assertEquals(1, rows.size(), "the other editor's old row went");
        assertEquals(0, before.lineListeners.size(), "no longer listening to the old Neovim");
        assertEquals(1, after.lineListeners.size());
        after.send(line(1000, "notes.txt [new]"));
        assertEquals("notes.txt [new]", shown(kept));
        assertTrue(!after.widths.isEmpty() && after.widths.stream().allMatch(w -> w.startsWith("1000x")),
                "the editor's width reported again, to the new Neovim: " + after.widths);
    }

    /**
     * A background editor's window closed: Eclipse hears of it first and the editor's token reads
     * no id, so the line of no rows Neovim sends after finds the row by that and hides it.
     */
    @Test
    void aClosedWindowsRowHidesThoughTheTokenNoLongerNamesIt() {
        FakeSource session = new FakeSource();
        int[] id = { 1000 };
        StatusLineRow row = rows.rowFor(first, new Object(), () -> id[0], session);
        session.send(line(1000, "notes.txt"));
        id[0] = StatusRows.NO_WINDOW;
        session.send(StatusLines.none(1000));
        assertEquals(null, shown(row));
    }

    /**
     * The other order: the line of no rows is sent while the token still names the window, and
     * Eclipse forgets the window before the UI thread shows the line.
     */
    @Test
    void aClosedWindowsRowHidesWhicheverThreadIsFirst() {
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        StatusRows queuedRows = new StatusRows(new UiExecutor() {
            @Override public boolean isAvailable() { return true; }
            @Override public boolean isUiThread() { return true; }
            @Override public void asyncExec(Runnable work) { queued.add(work); }
            @Override public void syncExec(Runnable work) { work.run(); }
            @Override public void timerExec(int delayMs, Runnable work) { work.run(); }
        });
        FakeSource session = new FakeSource();
        int[] id = { 1000 };
        StatusLineRow row = queuedRows.rowFor(first, new Object(), () -> id[0], session);
        session.send(line(1000, "notes.txt"));
        session.send(StatusLines.none(1000));
        id[0] = StatusRows.NO_WINDOW;
        queued.forEach(Runnable::run);
        assertEquals(null, shown(row));
    }

    /** Neovim leaves an editor for good (Detach Neovim): its row and margin go, the other editor's stay. */
    @Test
    void aReleasedEditorLosesItsRow() {
        FakeSource session = new FakeSource();
        int bare = first.getBottomMargin();
        rows.rowFor(first, new Object(), () -> 1000, session);
        rows.rowFor(second, new Object(), () -> 1001, session);
        assertTrue(first.getBottomMargin() > bare, "the command line's row");
        rows.release(first);
        assertEquals(bare, first.getBottomMargin(), "the widget's own margin back");
        assertEquals(1, rows.size());
    }

    /** An ended Neovim's rows go and its listeners are removed; a newer one's are left alone. */
    @Test
    void anEndedNeovimsRowsGo() {
        FakeSource ended = new FakeSource();
        rows.rowFor(first, new Object(), () -> 1000, ended);
        FakeSource newer = new FakeSource();
        rows.rowFor(second, new Object(), () -> 1000, newer);
        rows.release(ended);
        assertEquals(1, rows.size(), "the newer Neovim's row stays");
        assertEquals(1, newer.lineListeners.size());
        rows.release(newer);
        assertEquals(0, rows.size());
        assertEquals(0, newer.lineListeners.size(), "no longer listening");
    }

    /** A disposed editor's row goes with it. */
    @Test
    void aClosedEditorsRowGoes() {
        FakeSource session = new FakeSource();
        rows.rowFor(first, new Object(), () -> 1000, session);
        rows.rowFor(second, new Object(), () -> 1001, session);
        second.dispose();
        assertEquals(1, rows.size());
    }
}
