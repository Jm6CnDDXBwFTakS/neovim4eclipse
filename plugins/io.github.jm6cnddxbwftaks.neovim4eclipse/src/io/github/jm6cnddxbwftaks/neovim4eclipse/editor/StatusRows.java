// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Listener;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.UiExecutor;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusLines;

/**
 * Every editor's {@link StatusLineRow}, kept for as long as the editor's text widget lives, not
 * only while the editor has Neovim. Neovim with {@code 'laststatus'} 2 draws a status line under
 * every window, the focused one's in {@code StatusLine} and the others' in {@code StatusLineNC}; here
 * each editor is one of Neovim's windows, so each editor shows its row, the ones in the background
 * too. Example: {@code notes.txt} and {@code ideas.txt} side by side, the keyboard in
 * {@code notes.txt}: both show their file's line, {@code ideas.txt}'s dimmer.
 *
 * <h2>Why a registry beside the controller</h2>
 * Only the editor with the keyboard has a {@code NeovimEditorController} (the attach manager closes
 * the old one when another editor is attached), and a row that lived in the controller went with it:
 * the background editor lost its line, and its text moved down by the row's height. The rows live
 * here instead: the next controller of the same editor takes the row that is already there
 * ({@link #rowFor}), and the lines Neovim sends for every window ({@code eclipse_status_lines})
 * reach every row, whether its editor is attached or not.
 *
 * <h2>Which line, and when a row goes</h2>
 * Each row shows its own window's line ({@link StatusLineRow#show} leaves out the others), read
 * through the editor's {@code EditorWindow} at each line, so a window that travelled to another
 * editor is followed. Neovim decides whether a window has a line ({@code 'laststatus'} 0, 1, 3 send
 * a line of no rows for a background window: {@code LuaScripts.STATUS_LINE}), and a closed window's
 * line is sent as one of no rows too, and a window that closed or travelled away leaves its token
 * with no id ({@link #NO_WINDOW}): the row hides. A row goes with its widget, when Neovim leaves
 * its editor for good ({@link #release(StyledText)}: Detach Neovim, a form page, a failed attach),
 * and every row goes when its Neovim ends ({@link #release(Source)}): the window ids mean nothing
 * any more. When another Neovim serves the editors (a restart), the editor it attaches to keeps its
 * row, its height and its last line until the new Neovim sends one ({@link #rowFor}), and the other
 * editors' rows go.
 *
 * <p>UI thread, but for the listeners, which the session calls on its own threads. Tested in
 * {@code tests/swt} ({@code StatusRowsTest}) and, with two editors side by side, in
 * {@code StatusBarEndToEndTest}.
 */
final class StatusRows {

    /**
     * Where the lines come from: the session ({@code NeovimEditorController}'s adapter). Two sources
     * are the same Neovim when they are {@code equals}.
     */
    interface Source {
        void addStatusLineListener(Consumer<GridSnapshot> listener);

        void removeStatusLineListener(Consumer<GridSnapshot> listener);

        void addCommandLineRowsListener(Consumer<Integer> listener);

        void removeCommandLineRowsListener(Consumer<Integer> listener);

        /** The command line's rows now (0 or 1; {@code StatusLineRow#commandLineRows}). */
        int commandLineRows();

        /** Whether {@link #commandLineRows} is the Neovim's report, not its starting guess. */
        boolean commandLineRowsReported();

        /** Window {@code window}'s line last sent, or {@code null}. */
        GridSnapshot statusLine(int window);

        /** Fits window {@code window}'s line to {@code columns}, its editor's width. */
        void setStatusLineWidthAsync(int window, int columns);
    }

    /** One editor's row: its widget, the token of its window ({@code EditorWindow}) and the id read from it. */
    private record Entry(StyledText text, Object windowToken, IntSupplier window, StatusLineRow row, Listener onDispose) {
    }

    /** The id a window token reads while it has no window ({@code EditorWindow.UNKNOWN}). */
    static final int NO_WINDOW = -1;

    private final UiExecutor ui;
    /** Read on the session's threads ({@link #onLine}), changed on the UI thread. */
    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final Consumer<GridSnapshot> lineListener = this::onLine;
    private final Consumer<Integer> rowsListener = this::onCommandLineRows;
    /** The Neovim whose lines the rows show; {@code null} before the first row. */
    private Source source;

    StatusRows(UiExecutor ui) {
        this.ui = ui;
    }

    /**
     * The row of the editor whose widget is {@code text}: the one it has, or a new one. A row made
     * for another window token (the widget now shows another editor's window, which does not happen
     * today) is made anew. The row shows the window's last line at once, so an editor that comes
     * back to the front keeps what it showed.
     *
     * <p>Another Neovim (a restart): the other editors' rows go (their window ids are the old
     * Neovim's, and the new one's may be the same numbers), but this editor keeps its row, its
     * height and its last line until the new Neovim sends its own. Made anew, the row would start
     * without a line, one row lower, and grow back when the line came: Neovim's window would be
     * resized twice and could scroll by a line (seen at most heights of a small editor). UI thread.
     *
     * @param windowToken the editor's {@code EditorWindow}, the same object for the editor's lifetime
     * @param window      reads the window's id from it, at each line
     */
    StatusLineRow rowFor(StyledText text, Object windowToken, IntSupplier window, Source from) {
        boolean newNeovim = !from.equals(source);
        if (newNeovim) {
            useSource(from, text);
        }
        for (Entry entry : entries) {
            if (entry.text() == text) {
                if (entry.windowToken() == windowToken) {
                    if (newNeovim) {
                        // Kept as it is: its line stays until the new Neovim sends one, and so do
                        // the command line's rows until it reports its own (its first report is
                        // always passed on): taken now, the new Neovim's guess of 1 would make a
                        // user's 'cmdheight' 0 grow a row and shrink back, and Neovim's window could
                        // scroll. The report may have come already (during the start, before this
                        // listened): then it is taken now; listening came first, so none is lost.
                        // Its width goes to the new Neovim, which knows none.
                        entry.row().forgetWidth();
                        if (from.commandLineRowsReported()) {
                            entry.row().commandLineRows(from.commandLineRows());
                        }
                    } else {
                        showLast(entry);
                    }
                    return entry.row();
                }
                drop(entry);
            }
        }
        StatusLineRow row = new StatusLineRow(text, window, this::setWidth);
        Listener onDispose = e -> entries.removeIf(entry -> entry.text() == text);
        Entry entry = new Entry(text, windowToken, window, row, onDispose);
        text.addListener(SWT.Dispose, onDispose);
        entries.add(entry);
        row.commandLineRows(from.commandLineRows());
        showLast(entry);
        return row;
    }

    /** Shows each row its window's last line: after a window travelled, its new one's. UI thread. */
    void showLastLines() {
        entries.forEach(this::showLast);
    }

    /**
     * Takes away the row of the editor whose widget is {@code text}, and the margin it added: Neovim
     * leaves that editor for good ({@code NeovimEditorController#releaseStatusRow}). UI thread.
     */
    void release(StyledText text) {
        for (Entry entry : entries) {
            if (entry.text() == text) {
                drop(entry);
            }
        }
    }

    /**
     * Takes away every row and stops listening, if the rows show {@code ended}'s lines: that Neovim
     * is gone. A newer Neovim's rows stay. UI thread.
     */
    void release(Source ended) {
        if (ended.equals(source)) {
            source.removeStatusLineListener(lineListener);
            source.removeCommandLineRowsListener(rowsListener);
            source = null;
            entries.forEach(this::drop);
        }
    }

    /** The rows kept, for tests. */
    int size() {
        return entries.size();
    }

    /** Fits window {@code window}'s line to {@code columns} in the Neovim the rows show now. */
    private void setWidth(int window, int columns) {
        Source now = source;
        if (now != null) {
            now.setStatusLineWidthAsync(window, columns);
        }
    }

    /**
     * Listens to {@code from} instead of the Neovim before, whose rows go, but {@code keep}'s (see
     * {@link #rowFor}). UI thread.
     */
    private void useSource(Source from, StyledText keep) {
        if (source != null) {
            source.removeStatusLineListener(lineListener);
            source.removeCommandLineRowsListener(rowsListener);
        }
        for (Entry entry : entries) {
            if (entry.text() != keep) {
                drop(entry);
            }
        }
        source = from;
        from.addStatusLineListener(lineListener);
        from.addCommandLineRowsListener(rowsListener);
    }

    private void showLast(Entry entry) {
        int window = entry.window().getAsInt();
        if (window == NO_WINDOW) {
            // The editor's window closed, or travelled to another editor: no line to show.
            entry.row().show(StatusLines.none(NO_WINDOW));
            return;
        }
        // No Neovim: release(Source) drops every row before it forgets the source, so none is left
        // here then; checked anyway, as a task queued before that may still run after it.
        GridSnapshot line = source == null ? null : source.statusLine(window);
        if (line != null) {
            entry.row().show(line);
        }
    }

    private void drop(Entry entry) {
        entries.remove(entry);
        if (!entry.text().isDisposed()) {
            entry.text().removeListener(SWT.Dispose, entry.onDispose());
        }
        entry.row().dispose();
    }

    /** A window's line changed (any thread): the row of that window shows it. */
    private void onLine(GridSnapshot line) {
        if (line.height() == 0) {
            // A closed window's line. Eclipse forgets the window (its editor's token then reads
            // NO_WINDOW) on another thread, before or after this: the line hides a row whose token
            // still reads the window, showLastLines one whose token reads none already.
            ui.asyncExec(() -> {
                entries.forEach(entry -> entry.row().show(line));
                showLastLines();
            });
            return;
        }
        for (Entry entry : entries) {
            if (entry.window().getAsInt() == line.window()) {
                // The window's line when the row is drawn, not this one: a newer one may have been
                // passed on between this one's and this task (core/StatusTracker), and the tasks
                // then run in that order; each draws the newest.
                ui.asyncExec(() -> {
                    Source from = source;
                    GridSnapshot newest = from == null ? null : from.statusLine(line.window());
                    GridSnapshot shown = newest != null ? newest : line;
                    entries.forEach(each -> each.row().show(shown));
                });
                return;
            }
        }
        // Another window's (a page's): not a task on the UI thread.
    }

    /** The command line's rows changed ({@code :set ch=0}; any thread): every editor keeps that many. */
    private void onCommandLineRows(int rows) {
        ui.asyncExec(() -> entries.forEach(entry -> entry.row().commandLineRows(rows)));
    }
}
