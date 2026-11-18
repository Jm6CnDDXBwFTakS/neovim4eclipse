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
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FloatWindow;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HostCommand;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.KeyMapping;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ScreenInput;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewText;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimModes;

/**
 * A page shown from a window of the editing Neovim, the one behind the text editors: the messages
 * page. The page's text is a buffer there ({@code LuaScripts.PAGE_TEXT}), the pane shows that
 * buffer's window, alone in a hidden tabpage as an editor's window is (one Eclipse editor, one
 * Neovim window), drawn from its grid ({@link NeovimSession#drawPageWindow}). Being the same
 * Neovim, its registers, search history, colors and mappings are the code's: {@code y} in the page
 * and {@code p} in the code just work, as in a terminal Neovim.
 *
 * <h2>The focus</h2>
 * When the pane's canvas gets the keyboard, the page takes the focus as Neovim sees it: Neovim
 * enters the page's window ({@link NeovimSession#focusPageAsync}), Neovim's screen takes the page's
 * size, and the focus events ({@link FocusTarget}: the mode, the {@code :} line, messages, floats,
 * the commands a key asks for) come here, drawn over the page with the same overlays as over code.
 * The text editor it came from stays attached: its document and caret are untouched. Clicking back
 * into the code gives the focus back ({@code EditorAttachManager}). A page in the background keeps
 * its last cells: Neovim does not redraw a window of a tabpage that is not current.
 *
 * <p>UI thread, except the session's callbacks, which hop to it.
 */
final class WindowPane implements PagePane {

    /** What the editor holding the pane does for it. UI thread. */
    interface Host {
        /** The editor's site, for its status bar. */
        IEditorSite site();

        /** A command Neovim handed to Eclipse from the page's window ({@code :q}, {@code :split}, {@code :e}). */
        void command(HostCommand command);

        /** A key the plug-in maps for the IDE, typed in the page ({@code gt}, {@code gT}). */
        void action(IdeAction action);
    }

    private final Host host;
    private final GridCanvas canvas;
    private final NeovimSession session;
    /** The page's Neovim window; {@link EditorWindow#UNKNOWN} until the first page was shown. */
    private int window = EditorWindow.UNKNOWN;
    /** The colors of the editor the page came from, until Neovim's own come with the grid. */
    private int foreground = 0x000000;
    private int background = 0xFFFFFF;
    private Font measuredFont;
    private int measuredHeight = 1;
    /** Whether this page has Neovim's focus ({@link #takeFocus}). */
    private boolean focused;
    private boolean disposed;
    private final MessageLine messageLine;
    private final CommandLineBox commandLine;
    private final FloatingWindows floats;
    private final ModeStatusContribution modeStatus;
    private String shownMessage = "";
    private final Consumer<GridSnapshot> gridListener = this::onGrid;
    /** Eclipse's text font changed: the cells change size, so Neovim is asked for the cells that fit now. */
    private final IPropertyChangeListener fontListener;

    /** The focus events, while this page has the focus; each hops to the UI thread. */
    private final FocusTarget target = new FocusTarget() {
        @Override
        public void onMode(String mode) {
            modeStatus.setMode(mode);
        }

        @Override
        public void onRecording(String register) {
            modeStatus.setRecording(register);
        }

        @Override
        public void onStatus(StatusInfo status) {
            modeStatus.setStatusInfo(status);
            onUi(() -> {
                if (!status.message().equals(shownMessage)) {
                    shownMessage = status.message();
                    messageLine.show(status.message(), status.error());
                }
            });
        }

        @Override
        public void onOutput(CommandOutput output) {
            onUi(() -> commandLine.showOutput(output.title(), output.text()));
        }

        @Override
        public void onCmdline(CmdlineState.View view) {
            onUi(() -> commandLine.show(view));
        }

        @Override
        public void onFloatingWindows(List<FloatWindow> windows) {
            onUi(() -> floats.show(windows));
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
        public String toString() {
            return "page window " + window;
        }
    };

    /** The pane, in {@code parent} (which lays its one child out to fill it), on the editing session. UI thread. */
    WindowPane(Composite parent, Host host, NeovimSession session) {
        this.host = host;
        this.session = session;
        modeStatus = new ModeStatusContribution(session::setStatuslineColumns);
        canvas = new GridCanvas(parent, new GridCanvas.Look() {
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
        }, SWT.NONE);
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
            }
        });
        fontListener = event -> {
            if (JFaceResources.TEXT_FONT.equals(event.getProperty()) && !canvas.isDisposed()) {
                canvas.redraw();
                sendSize();
            }
        };
        JFaceResources.getFontRegistry().addListener(fontListener);
        messageLine = new MessageLine(canvas, () -> false);
        commandLine = new CommandLineBox(canvas, new CommandLineBox.Commands() {
            @Override
            public void type(String keys) {
                session.inputAsync(keys);
            }

            @Override
            public void paste(String text) {
                session.pasteAsync(text);
            }

            @Override
            public boolean cancellable() {
                return !VimModes.isExMode(session.getMode());
            }
        }, messageLine, () -> {
            // nothing: a page has no Copy and Paste handlers or Ctrl-key context of its own to update
        });
        floats = new FloatingWindows(canvas);
        session.addPageGridListener(gridListener);
    }

    @Override
    public GridCanvas canvas() {
        return canvas;
    }

    /** The page's Neovim window, {@link EditorWindow#UNKNOWN} before the first page was shown. */
    int window() {
        return window;
    }

    /**
     * Shows {@code text} (the message history), in the colors of the editor it came from until
     * Neovim's own arrive: fills the page's buffer, and opens this pane's window on it the first
     * time ({@link NeovimSession#showPageTextAsync}).
     */
    void show(ViewText text, int fg, int bg) {
        foreground = fg;
        background = bg;
        session.showPageTextAsync(text, window, this::shown);
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

    /** The page's window is open (input thread): kept, and given the focus if the pane has the keyboard. */
    private void shown(int win) {
        onUi(() -> {
            window = win;
            if (canvas.isFocusControl()) {
                takeFocus();
            }
        });
    }

    @Override
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
        if (disposed || window == EditorWindow.UNKNOWN || (focused && session.focusedPage() == window)) {
            return;
        }
        focused = true;
        session.focus(target);
        session.focusPageAsync(window);
        modeStatus.addTo(host.site());
        sendSize();
    }

    /**
     * The canvas lost the keyboard: unless it went to the page's own overlays (the {@code :} box
     * over the page is a child of the canvas, and takes the keyboard when it opens), the page no
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
            commandLine.show(CmdlineState.View.CLOSED);
            floats.show(List.of());
        }
    }

    /** Neovim's screen is the page's size in cells, while the page has the focus (the code's otherwise). */
    private void sendSize() {
        if (!focused || canvas.isDisposed()) {
            return;
        }
        Point size = canvas.getSize();
        session.setEditorSizeAsync(ScreenInput.cells(size.x, canvas.cellWidth()), ScreenInput.cells(size.y, canvas.cellHeight()));
    }

    private void onGrid(GridSnapshot grid) {
        if (grid.window() == window) {
            onUi(() -> canvas.show(grid));
        }
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

    /** Tab, Shift+Tab, Esc and Enter come as traversals: kept here, and Tab sent ({@link ScreenPane} does the same). */
    private void onTraverse(TraverseEvent e) {
        e.doit = false;
        if (window != EditorWindow.UNKNOWN && (e.detail == SWT.TRAVERSE_TAB_NEXT || e.detail == SWT.TRAVERSE_TAB_PREVIOUS)) {
            session.inputAsync(e.detail == SWT.TRAVERSE_TAB_PREVIOUS ? "<S-Tab>" : "<Tab>");
        }
    }

    @Override
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

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        JFaceResources.getFontRegistry().removeListener(fontListener);
        session.removePageGridListener(gridListener);
        session.releaseFocus(target);
        modeStatus.removeFrom(host.site());
        commandLine.dispose();
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
