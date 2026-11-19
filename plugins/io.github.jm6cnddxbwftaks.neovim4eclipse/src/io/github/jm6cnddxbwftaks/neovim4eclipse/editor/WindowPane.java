// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.List;
import java.util.function.Consumer;

import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.TraverseEvent;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IEditorSite;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.CommandOutput;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.EditorWindow;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.FocusTarget;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.IdeAction;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.StatusInfo;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CmdlineState;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CommandWindowView;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FloatWindow;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridEvent;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HostCommand;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.KeyMapping;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PageBuffer;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PageRows;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ScreenInput;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewContent;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewText;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimModes;

/**
 * What a page editor ({@link NeovimPageEditor}) shows its page in: a window of the editing Neovim,
 * the one behind the text editors. The page is a buffer there: the messages buffer the plug-in
 * fills ({@code LuaScripts.PAGE_TEXT}), or one Neovim made itself, a help page or a
 * {@code :checkhealth} report ({@code LuaScripts.WINDOWS}' {@code show_page}). The pane shows that
 * buffer's window, alone in a hidden tabpage as an editor's window is (one Eclipse editor, one
 * Neovim window), drawn from its grid ({@link NeovimSession#drawPageWindow}). Being the same
 * Neovim, its registers, search history, colors and mappings are the code's: {@code y} in the page
 * and {@code p} in the code just work, as in a terminal Neovim, and in help {@code Ctrl-]} follows
 * the user's own help pages.
 *
 * <h2>The focus</h2>
 * When the pane's canvas gets the keyboard, the page takes the focus as Neovim sees it: Neovim
 * enters the page's window ({@link NeovimSession#focusPageAsync}), Neovim's screen takes the page's
 * size, and the focus events ({@link FocusTarget}: the mode, the {@code :} line, messages, floats,
 * the commands a key asks for) come here. Below the page's text, as Neovim lays out its screen, the
 * pane draws the window's status line (the screen's row below the window, with its colors) and
 * the command line ({@link PageRows}): every key stays the canvas's, so Neovim edits the line
 * itself, Ctrl-W, Ctrl-R and history included. Messages, output and floats are drawn over the
 * page with the same overlays as over code; the mode and the pending command go to Eclipse's
 * status bar ({@link PageStatus}).
 * The text editor it came from stays attached: its document and caret are untouched. Clicking back
 * into the code gives the focus back ({@code EditorAttachManager}). A page in the background keeps
 * its last cells: Neovim does not redraw a window of a tabpage that is not current.
 *
 * <p>UI thread, except the session's callbacks, which hop to it.
 */
final class WindowPane {

    /** What the editor holding the pane does for it. UI thread. */
    interface Host {
        /** The editor's site, for its status bar. */
        IEditorSite site();

        /** A command Neovim handed to Eclipse from the page's window ({@code :q}, {@code :split}, {@code :e}). */
        void command(HostCommand command);

        /** A key the plug-in maps for the IDE, typed in the page ({@code gt}, {@code gT}). */
        void action(IdeAction action);

        /** Neovim closed the page's window by itself, or it could not be entered: the editor closes. */
        void close();
    }

    private final Host host;
    /** The page's text: its window's grid. It alone takes the keyboard. */
    private final GridCanvas canvas;
    /** Neovim's status line for the page's window, a row below the text, as Neovim draws it. */
    private final GridCanvas statusRow;
    /** Neovim's command line, a row along the bottom, while a {@code :}, {@code /} or {@code ?} line is open. */
    private final GridCanvas commandRow;
    /** The three, one above the other. */
    private final Composite rows;
    private final NeovimSession session;
    /** The page's Neovim window; {@link EditorWindow#UNKNOWN} until the first page was shown. */
    private volatile int window = EditorWindow.UNKNOWN;
    /** The colors of the editor the page came from, until Neovim's own come with the grid. */
    private int foreground = 0x000000;
    private int background = 0xFFFFFF;
    private Font measuredFont;
    private int measuredHeight = 1;
    /** Whether this page has Neovim's focus ({@link #takeFocus}). */
    private boolean focused;
    private boolean disposed;
    private final MessageLine messageLine;
    /**
     * The Tab completion candidates of an open line ({@link PageRows#candidateRow}), over the
     * window's last row, as Neovim's own wildmenu covers a row: hidden while there are none. An
     * overlay, not a row of the layout, so that Tab does not change the window's height.
     */
    private final GridCanvas candidateRow;
    /** The command line as last said, drawn again when the row changes size. */
    private CmdlineState.View lastView = CmdlineState.View.CLOSED;
    /** Output of several lines (a listing, a long message), over the page. */
    private final OutputPopup output;
    private final FloatingWindows floats;
    /** Neovim's command-line window ({@code q:} typed in the page), drawn over the page's bottom. */
    private final NeovimWindowPanel commandWindowPanel;
    private final Consumer<Integer> closedListener = this::onPageClosed;
    /** The mode and pending command in Eclipse's status bar, one item for all messages editors ({@link PageStatus}). */
    private final PageStatus status;
    private String shownMessage = "";
    private final Consumer<GridSnapshot> gridListener = this::onGrid;
    private final Consumer<GridSnapshot> statusListener = this::onStatusRow;
    /** Eclipse's text font changed: the cells change size, so Neovim is asked for the cells that fit now. */
    private final IPropertyChangeListener fontListener;

    /** The focus events, while this page has the focus; each hops to the UI thread. */
    private final FocusTarget target = new FocusTarget() {
        @Override
        public void onMode(String mode) {
            onUi(() -> status.setMode(mode));
        }

        @Override
        public void onRecording(String register) {
            onUi(() -> status.setRecording(register));
        }

        @Override
        public void onStatus(StatusInfo info) {
            StatusInfo status = info;
            onUi(() -> WindowPane.this.status.setStatusInfo(status));
            onUi(() -> {
                if (!status.message().equals(shownMessage)) {
                    shownMessage = status.message();
                    messageLine.show(status.message(), status.error());
                }
            });
        }

        @Override
        public void onOutput(CommandOutput shown) {
            onUi(() -> output.showOverEditor(shown.title(), shown.text(), false)); // the keys stay Neovim's
        }

        @Override
        public void onCmdline(CmdlineState.View view) {
            onUi(() -> showCommandLine(view));
        }

        @Override
        public void onFloatingWindows(List<FloatWindow> windows) {
            onUi(() -> floats.show(windows));
        }

        @Override
        public void onCommandWindow(CommandWindowView view) {
            onUi(() -> {
                commandWindowPanel.setInsertMode(VimModes.isTyping(session.getMode()));
                commandWindowPanel.show(view);
            });
        }

        @Override
        public void onHostCommand(HostCommand command) {
            onUi(() -> host.command(command));
        }

        @Override
        public void onIdeAction(IdeAction action) {
            onUi(() -> host.action(action));
        }

        @Override
        public int pageWindow() {
            return window; // read on the RPC thread for a host command: hence volatile, written on the UI thread only
        }

        @Override
        public String toString() {
            return "page window " + window;
        }
    };

    /** The pane, in {@code parent} (which lays its one child out to fill it), on the editing session. UI thread. */
    WindowPane(Composite parent, Host host, NeovimSession session) {
        this.host = host;
        this.session = session;
        status = new PageStatus(host.site());
        rows = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 0;
        rows.setLayout(layout);
        GridCanvas.Look look = new GridCanvas.Look() {
            @Override
            public Font font() {
                return JFaceResources.getTextFont();
            }

            @Override
            public int foreground() {
                return foreground;
            }

            @Override
            public int background() {
                return background;
            }

            @Override
            public int lineHeight() {
                return WindowPane.this.lineHeight();
            }
        };
        canvas = new GridCanvas(rows, look, SWT.NONE);
        canvas.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        candidateRow = new GridCanvas(canvas, look, SWT.NO_FOCUS);
        candidateRow.setVisible(false);
        statusRow = new GridCanvas(rows, look, SWT.NO_FOCUS);
        statusRow.setLayoutData(rowData(false));
        commandRow = new GridCanvas(rows, look, SWT.NO_FOCUS);
        commandRow.setLayoutData(rowData(true));
        commandRow.setInsertCursor(true); // the command line's cursor is a bar, as Neovim's
        commandRow.addControlListener(new ControlAdapter() {
            @Override
            public void controlResized(ControlEvent e) {
                showCommandLine(lastView); // the row's cells changed: the open line is fitted again
            }
        });
        canvas.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                onKey(e);
            }
        });
        canvas.addTraverseListener(this::onTraverse);
        new GridMouse(canvas, (button, action, modifier, row, col) -> {
            if (window != EditorWindow.UNKNOWN) {
                session.pageMouseAsync(window, button, action, modifier, row, col);
            }
        });
        canvas.addListener(SWT.FocusIn, e -> takeFocus());
        canvas.addControlListener(new ControlAdapter() {
            @Override
            public void controlResized(ControlEvent e) {
                sendSize();
                showCommandLine(lastView); // the candidates lie over the canvas's last row
            }
        });
        fontListener = event -> {
            if (JFaceResources.TEXT_FONT.equals(event.getProperty()) && !canvas.isDisposed()) {
                ((GridData) commandRow.getLayoutData()).heightHint = lineHeight();
                if (((GridData) statusRow.getLayoutData()).heightHint > 0) {
                    ((GridData) statusRow.getLayoutData()).heightHint = lineHeight();
                }
                rows.layout(true);
                canvas.redraw();
                sendSize();
            }
        };
        JFaceResources.getFontRegistry().addListener(fontListener);
        messageLine = new MessageLine(canvas, () -> false);
        output = new OutputPopup(canvas, () -> session.inputAsync(":"));
        floats = new FloatingWindows(canvas);
        commandWindowPanel = new NeovimWindowPanel(canvas, new NeovimEditorController.PanelCommands(session), () -> {
            // nothing to update: a page has no Ctrl-key context of its own
        });
        session.addPageGridListener(gridListener);
        session.addPageStatusListener(statusListener);
        session.addPageClosedListener(closedListener);
    }

    /** A row's layout: one text line high; the status row starts hidden, until Neovim draws a status line. */
    private GridData rowData(boolean shown) {
        GridData data = new GridData(SWT.FILL, SWT.BOTTOM, true, false);
        data.heightHint = shown ? lineHeight() : 0;
        return data;
    }

    /**
     * Neovim's command line, drawn along the bottom ({@link PageRows#commandRow}): Neovim edits the
     * line, every key being Neovim's here; this only paints what it says. Blank while closed.
     */
    private void showCommandLine(CmdlineState.View view) {
        if (commandRow.isDisposed()) {
            return;
        }
        lastView = view;
        GridSnapshot grid = canvas.grid();
        GridEvent.DefaultColors colors = grid == null ? new GridEvent.DefaultColors(GridEvent.NO_COLOR, GridEvent.NO_COLOR)
                : grid.defaultColors();
        int columns = ScreenInput.cells(commandRow.getSize().x, commandRow.cellWidth());
        commandRow.show(PageRows.commandRow(view, columns, window, colors));
        showCandidates(PageRows.candidateRow(view, columns, window, colors));
        // The line takes the row, as Neovim's : takes its message's. Not in Ex mode (gQ), where the
        // line opens again after each command, below that command's output: there a one-line
        // :echo stays, as Neovim scrolls it up instead of covering it.
        if (view.open() && !VimModes.isExMode(session.getMode())) {
            messageLine.hide();
        }
    }

    /** The candidates over the canvas's last row, or nothing ({@code row} null). */
    private void showCandidates(GridSnapshot row) {
        if (row == null) {
            candidateRow.setVisible(false);
            return;
        }
        int height = lineHeight();
        Rectangle area = canvas.getClientArea();
        candidateRow.setBounds(0, Math.max(0, area.height - height), area.width, height);
        candidateRow.show(row);
        candidateRow.moveAbove(null);
        candidateRow.setVisible(true);
    }

    /** The message line's text while it shows, else {@code ""}; for tests. */
    String messageTextForTests() {
        return messageLine.isShowing() ? messageLine.text() : "";
    }

    /** The drawn Tab candidates row, for tests. */
    GridCanvas candidateRowForTests() {
        return candidateRow;
    }

    /**
     * The status line row of a page window ({@code util/Grids.pageStatusIfChanged}); this page's is
     * drawn. A row of no height says the window has none ({@code 'laststatus'} 0): the row goes.
     * Each time the row comes or goes, the grid canvas changes height and Neovim's screen follows
     * it ({@link #sendSize}), so the window's rows stay the canvas's rows.
     */
    private void onStatusRow(GridSnapshot row) {
        onUi(() -> {
            if (row.window() != window) {
                return;
            }
            GridData data = (GridData) statusRow.getLayoutData();
            boolean shown = row.height() > 0;
            int height = shown ? lineHeight() : 0;
            if (data.heightHint != height) {
                data.heightHint = height; // the row appears, or goes
                rows.layout(true);
                sendSize();
            }
            if (shown) {
                statusRow.show(row);
            }
        });
    }

    public GridCanvas canvas() {
        return canvas;
    }

    /** The drawn command-line row, for tests. */
    GridCanvas commandRowForTests() {
        return commandRow;
    }

    /** The drawn status line row, for tests. */
    GridCanvas statusRowForTests() {
        return statusRow;
    }

    /** The editing session this page is a window of; after a crash and a new start, no longer the current one. */
    NeovimSession session() {
        return session;
    }

    /** The colors of the editor the page came from, {@code {foreground, background}}, for a split's second half. */
    int[] colors() {
        return new int[] { foreground, background };
    }

    /** The page's Neovim window, {@link EditorWindow#UNKNOWN} before the first page was shown. */
    int window() {
        return window;
    }

    /**
     * Shows {@code content}, in the colors of the editor it came from until Neovim's own arrive:
     * the message history fills the messages buffer ({@link NeovimSession#showPageTextAsync}); a
     * buffer Neovim made (a help page, a {@code :checkhealth} report) goes into this pane's window
     * ({@link NeovimSession#showPageBufferAsync}). Either opens the pane's window the first time.
     */
    void show(ViewContent content, int fg, int bg) {
        foreground = fg;
        background = bg;
        switch (content) {
            case ViewText text -> session.showPageTextAsync(text, window, this::shown);
            case PageBuffer page -> session.showPageBufferAsync(page, window, this::shown);
        }
    }

    /**
     * Shows the same page as the pane of window {@code other}, in a window of its own: the second
     * half of a {@code :split} of the page ({@link NeovimSession#openSecondPageAsync}).
     */
    void showSameAs(int other, int fg, int bg) {
        foreground = fg;
        background = bg;
        session.openSecondPageAsync(other, this::shown);
    }

    /**
     * The page's window is open (input thread): kept, and given the focus if the pane has the
     * keyboard. A window that opens after the editor closed (a {@code :checkhealth} whose editor
     * closed meanwhile) is closed again, so no page window is left in a hidden tabpage.
     */
    private void shown(int win) {
        if (disposed || canvas.isDisposed()) {
            session.closePageWindowAsync(win);
            return;
        }
        canvas.getDisplay().asyncExec(() -> {
            if (disposed || canvas.isDisposed()) {
                session.closePageWindowAsync(win);
                return;
            }
            window = win;
            if (canvas.isFocusControl()) {
                takeFocus();
            }
        });
    }

    public void setFocus() {
        if (!canvas.isDisposed()) {
            canvas.setFocus();
        }
        takeFocus();
    }

    /**
     * The page takes the focus as Neovim sees it: the focus events come here, Neovim enters the
     * page's window, and Neovim's screen takes the page's size. Again on each FocusIn; Neovim is
     * told only once per stay ({@link #focused}), until the code takes the focus back
     * ({@link #focusLost}).
     */
    private void takeFocus() {
        if (disposed || window == EditorWindow.UNKNOWN || (focused && session.hasFocus(target))) {
            return;
        }
        focused = true;
        session.focus(target);
        session.focusPageAsync(window);
        sendSize();
    }

    /**
     * The canvas lost the keyboard: unless it went to the page's own overlays (children of the
     * canvas: the command-line window's panel takes the keyboard when it opens), the page no
     * longer has the focus. Checked once the focus has settled ({@code asyncExec}).
     */
    void focusOut() {
        onUi(() -> {
            Control focus = canvas.getDisplay().getFocusControl();
            for (Control c = focus; c != null; c = c.getParent()) {
                if (c == canvas) {
                    return; // the : box or another overlay of this page
                }
            }
            focusLost();
        });
    }

    /** The code (or another page) took the focus: this page no longer hears the focus events. */
    void focusLost() {
        if (focused) {
            focused = false;
            session.releaseFocus(target);
            showCommandLine(CmdlineState.View.CLOSED);
            floats.show(List.of());
        }
    }

    /**
     * Neovim's screen is the page's size in cells, while the page has the focus (the code's
     * otherwise). The same check as the code editor's: the router's target, one rule for both
     * ({@code NeovimSession.hasFocus}), not this pane's own {@link #focused} flag.
     */
    private void sendSize() {
        if (!session.hasFocus(target) || canvas.isDisposed()) {
            return;
        }
        Point size = canvas.getSize();
        session.setEditorSizeAsync(ScreenInput.cells(size.x, canvas.cellWidth()), ScreenInput.cells(size.y, canvas.cellHeight()));
    }

    /** Neovim closed a page's window by itself, or this one could not be entered: if it is this page's, the editor closes. */
    private void onPageClosed(int win) {
        onUi(() -> {
            if (win == window) { // compared here, on the UI thread, which alone writes window
                host.close();
            }
        });
    }

    private void onGrid(GridSnapshot grid) {
        onUi(() -> {
            if (grid.window() == window) { // compared on the UI thread, which writes window
                canvas.show(grid);
            }
        });
    }

    private void onKey(KeyEvent e) {
        e.doit = false;
        if (e.keyCode == SWT.TAB || window == EditorWindow.UNKNOWN) {
            return; // Tab is sent by onTraverse
        }
        if (KeyMapping.dropsKey(e.stateMask)) {
            return;
        }
        String key = KeyMapping.toNeovimKey(e.keyCode, e.stateMask, e.character);
        if (key != null) {
            takeFocus(); // a key typed before the FocusIn came (a test's, a fast typist's)
            session.inputAsync(key);
        }
    }

    /** Tab, Shift+Tab, Esc and Enter come as traversals: kept here, and Tab sent, as the {@code q:} panel does. */
    private void onTraverse(TraverseEvent e) {
        e.doit = false;
        if (window != EditorWindow.UNKNOWN && (e.detail == SWT.TRAVERSE_TAB_NEXT || e.detail == SWT.TRAVERSE_TAB_PREVIOUS)) {
            session.inputAsync(e.detail == SWT.TRAVERSE_TAB_PREVIOUS ? "<S-Tab>" : "<Tab>");
        }
    }

    public void sendKey(String key) {
        if (window != EditorWindow.UNKNOWN) {
            takeFocus();
            session.inputAsync(key);
        }
    }

    /** The cursor's cell, its middle, in display pixels: {@code Ctrl-W h j k l} keep its row or column. */
    Point cursorOnScreen() {
        GridSnapshot grid = canvas.grid();
        if (grid == null || grid.cursorRow() < 0) {
            return canvas.toDisplay(0, 0);
        }
        return canvas.toDisplay(grid.cursorCol() * canvas.cellWidth() + canvas.cellWidth() / 2,
                grid.cursorRow() * canvas.cellHeight() + canvas.cellHeight() / 2);
    }

    /** Shows a message of the plug-in's own over the page (an error of a refused command). */
    void showMessage(String message, boolean error) {
        if (!disposed) {
            messageLine.show(message, error);
        }
    }

    private int lineHeight() {
        Font font = JFaceResources.getTextFont();
        if (font != measuredFont && !canvas.isDisposed()) {
            GC gc = new GC(canvas);
            try {
                gc.setFont(font);
                measuredHeight = Math.max(1, gc.getFontMetrics().getHeight());
                measuredFont = font;
            } finally {
                gc.dispose();
            }
        }
        return measuredHeight;
    }

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        JFaceResources.getFontRegistry().removeListener(fontListener);
        session.removePageGridListener(gridListener);
        session.removePageStatusListener(statusListener);
        session.removePageClosedListener(closedListener);
        session.releaseFocus(target);
        commandWindowPanel.dispose();
        status.release();
        output.close(false);
        messageLine.dispose();
        floats.dispose();
        if (window != EditorWindow.UNKNOWN) {
            session.closePageWindowAsync(window);
        }
    }

    private void onUi(Runnable work) {
        if (canvas.isDisposed()) {
            return;
        }
        canvas.getDisplay().asyncExec(() -> {
            if (!disposed && !canvas.isDisposed()) {
                work.run();
            }
        });
    }
}
