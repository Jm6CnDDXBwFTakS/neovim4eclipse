// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.events.TraverseEvent;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.HelpProfile;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.OutputProfile;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.ScreenSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HelpPage;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.KeyMapping;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.OverlayColors;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PluginFolders;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PluginNeovim;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ScreenInput;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewContent;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewText;

/**
 * A second Neovim's whole screen in an SWT pane: a {@link GridCanvas} for the screen, keys and
 * mouse to Neovim, the pane's size as Neovim's screen, and the second Neovim's start and end
 * ({@link ScreenSession}). The part that holds it ({@link NeovimView}) is its {@link Host}: it gives
 * the pane a parent, shows its title, and says what "back to the editor" and "ended" mean for it.
 * Example: {@code new ScreenPane(parent, host).show(ViewText.messages(history), 0x000000,
 * 0xFFFFFF)} starts a second Neovim with the output profile and shows the history in it.
 *
 * <h2>One second Neovim per kind</h2>
 * Text ({@link ViewText}) is shown by a second Neovim with the output profile
 * ({@link OutputProfile}), which loads none of the user's plug-ins; a help page ({@link HelpPage})
 * by one with the help profile ({@link HelpProfile}), given the user's {@code 'runtimepath'}. When
 * the kind changes ({@code :messages} after {@code :help}), the running one is closed and the
 * other started, so text never meets the user's plug-ins, whatever was shown before. The same kind
 * again reuses the running one (about 0.1 s saved; for help, {@code Ctrl-O} keeps the pages seen
 * since the last text).
 *
 * <h2>Its look</h2>
 * The editor's text font, and the colors of the editor the content came from, taken when it
 * arrives ({@link #show}): Neovim's default colorscheme on the editor's background, with its
 * {@code 'background'} set to match ({@link OverlayColors#isDark}).
 *
 * <p>UI thread, except the listener's calls, which come from the second Neovim's threads and are
 * passed on with {@code asyncExec}. Starting and closing the second Neovim blocks, so both run in a
 * background job, never on the UI thread.
 */
final class ScreenPane {

    /** What the part holding the pane does for it. Every call is on the UI thread. */
    interface Host {
        /** The pane's title changed, such as "Neovim: help" ({@link ScreenInput#title}). */
        void titleChanged(String title);

        /**
         * {@code Ctrl-W key} in the second Neovim found no window of its own in that direction
         * ({@link ScreenSession.Listener#window}): Eclipse goes on among its editors.
         *
         * @param count  Neovim's count, 0 for none
         * @param cursor the second Neovim's cursor in display pixels, which {@code Ctrl-W l}
         *               keeps the row of, as from a code editor's caret
         */
        void window(char key, int count, Point cursor);

        /** The second Neovim ended ({@code :q}, {@code q}, a crash); the next content starts a new one. */
        void ended();
    }

    private static final Logger LOG = Logger.getLogger(ScreenPane.class.getName());

    /** Where the second Neovim starts: a folder of the plug-in's own ({@code ScreenSession.start}). */
    private static final Supplier<Path> SCREEN_FOLDER = () -> PluginFolders.pluginFolder().resolve("screen");

    private final Host host;
    private final GridCanvas canvas;
    /** Before any editor's colors arrive: black on white, Neovim's light look. */
    private int foreground = 0x000000;
    private int background = 0xFFFFFF;
    /** The font {@link #lineHeight} was measured for, and that height. */
    private Font measuredFont;
    private int measuredHeight = 1;
    /** The second Neovim; {@code null} before the first content and after it ended. */
    private ScreenSession screen;
    /** Whether {@link #screen} has the help profile ({@link HelpProfile}) rather than the output one. */
    private boolean screenForHelp;
    /** Whether a start is under way: content that arrives meanwhile waits in {@link #pending}. */
    private boolean starting;
    /** The content to show once the second Neovim has started; {@code null} for none. */
    private ViewContent pending;
    /** The screen size last asked for, in cells, so a resize that changes nothing sends nothing. */
    private int columns;
    private int rows;
    private boolean disposed;
    /**
     * Eclipse's text font changed (Preferences, Colors and Fonts, Text Font): the cells change size,
     * so the pane is drawn again and Neovim's screen is asked for the number of cells that now fits.
     * Without it the screen kept its old size until the pane was resized: text cut off at the
     * right, or a strip left empty. Fired on the UI thread; removed in {@link #dispose}.
     */
    private final IPropertyChangeListener fontListener;

    /** The pane, in {@code parent} (which should lay its one child out to fill it). UI thread. */
    ScreenPane(Composite parent, Host host) {
        this.host = host;
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
                return ScreenPane.this.lineHeight();
            }
        }, SWT.NONE);
        canvas.addKeyListener(new KeyListener() {
            @Override
            public void keyPressed(KeyEvent e) {
                onKey(e);
            }

            @Override
            public void keyReleased(KeyEvent e) {
                // nothing: Neovim acts on presses
            }
        });
        canvas.addTraverseListener(this::onTraverse);
        new GridMouse(canvas, (button, action, modifier, row, col) -> {
            if (screen != null) {
                screen.mouseAsync(button, action, modifier, row, col);
            }
        });
        canvas.addControlListener(new ControlAdapter() {
            @Override
            public void controlResized(ControlEvent e) {
                resizeScreen();
            }
        });
        fontListener = event -> {
            if (JFaceResources.TEXT_FONT.equals(event.getProperty()) && !canvas.isDisposed()) {
                canvas.redraw();
                resizeScreen();
            }
        };
        JFaceResources.getFontRegistry().addListener(fontListener);
    }

    /** The canvas the screen is drawn on: for the host's focus and key context, and for tests. */
    GridCanvas canvas() {
        return canvas;
    }

    /** Gives the canvas the keyboard. */
    void setFocus() {
        if (!canvas.isDisposed()) {
            canvas.setFocus();
        }
    }

    /**
     * Shows {@code content}, replacing what the pane held, starting a second Neovim of its kind if
     * none is running (see the class comment, "One second Neovim per kind"), in the colors of the
     * editor it came from.
     *
     * @param fg that editor's text color, {@code 0xRRGGBB}
     * @param bg its background
     */
    void show(ViewContent content, int fg, int bg) {
        if (disposed) {
            return;
        }
        foreground = fg;
        background = bg;
        host.titleChanged(ScreenInput.title(content.title()));
        if (screen != null && screenForHelp == isHelp(content)) {
            showOnScreen(content);
            canvas.redraw();
            return;
        }
        if (screen != null) {
            ScreenSession other = screen;
            screen = null; // its end, when it comes, is no longer this pane's (Events.ended)
            closeInBackground(other);
        }
        pending = content;
        if (!starting) {
            start(isHelp(content));
        }
    }

    private static boolean isHelp(ViewContent content) {
        return content instanceof HelpPage;
    }

    /**
     * Starts a second Neovim of the kind asked for in a background job, at the pane's size, then
     * shows {@link #pending}.
     */
    private void start(boolean forHelp) {
        starting = true;
        Point size = canvas.getSize();
        int startColumns = ScreenInput.cells(size.x, canvas.cellWidth());
        int startRows = ScreenInput.cells(size.y, canvas.cellHeight());
        Display display = canvas.getDisplay();
        String program = Activator.getDefault().getNvimExecutable();
        String version = Activator.getDefault().getBundle().getVersion().toString();
        List<String> profile = forHelp ? HelpProfile.ARGS : OutputProfile.ARGS;
        Events events = new Events();
        Job job = Job.create("Starting the Neovim view's Neovim", monitor -> {
            ScreenSession started = null;
            Exception failure = null;
            try {
                // Every start of the dedicated Neovim checks its files in full, this one too: the
                // kept answer notices a reinstall, not a runtime file edited in place
                // (PluginNeovim.verified). About 0.1 s, here in the job.
                PluginNeovim.forget();
                started = ScreenSession.start(program, profile, version, SCREEN_FOLDER.get(), startColumns,
                        startRows, events);
            } catch (Exception e) {
                failure = e;
            }
            ScreenSession session = started;
            Exception error = failure;
            display.asyncExec(() -> onStarted(session, events, forHelp, error, startColumns, startRows));
            return Status.OK_STATUS;
        });
        job.setSystem(true);
        job.schedule();
    }

    private void onStarted(ScreenSession session, Events events, boolean forHelp, Exception failure, int startColumns,
            int startRows) {
        starting = false;
        if (failure != null) {
            LOG.log(Level.WARNING, "The Neovim view could not start its Neovim", failure);
            host.titleChanged(ScreenInput.title("could not start (see the log)"));
            return;
        }
        if (disposed) {
            closeInBackground(session);
            return;
        }
        ViewContent content = pending;
        if (content != null && isHelp(content) != forHelp) {
            // The other kind arrived while this one started: start that one instead.
            closeInBackground(session);
            start(isHelp(content));
            return;
        }
        screen = session;
        screenForHelp = forHelp;
        events.session = session;
        if (events.endedEarly) {
            LOG.fine("The Neovim view's Neovim ended while it started");
            onEnded(); // the next content starts a new one
            return;
        }
        columns = startColumns;
        rows = startRows;
        resizeScreen(); // the pane may have changed size while Neovim started
        pending = null;
        if (content != null) {
            showOnScreen(content);
        }
    }

    /**
     * Puts {@code content} into the second Neovim, by its profile. A failure (it ended a moment ago,
     * after a {@code :q} whose end is still on its way) is logged; the next content starts a new
     * one. A help tag the second Neovim did not find shows Neovim's own error on its message line.
     */
    private void showOnScreen(ViewContent content) {
        boolean dark = OverlayColors.isDark(background);
        var shown = switch (content) {
            case ViewText text -> OutputProfile.show(screen, text, dark);
            case HelpPage page -> HelpProfile.show(screen, page, dark);
        };
        shown.whenComplete((answer, failure) -> {
            if (failure != null) {
                LOG.log(Level.FINE, "The Neovim view could not show " + content.title(), failure);
            } else if (answer instanceof String error && !error.isEmpty()) {
                LOG.fine(() -> "The Neovim view's help: " + error);
            }
        });
    }

    /** One cell's height: the text font's line height, measured once per font. */
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

    /** Neovim's screen is the pane's size in cells; asked again only when that changes. */
    private void resizeScreen() {
        if (screen == null || canvas.isDisposed()) {
            return;
        }
        Point size = canvas.getSize();
        int newColumns = ScreenInput.cells(size.x, canvas.cellWidth());
        int newRows = ScreenInput.cells(size.y, canvas.cellHeight());
        if (newColumns != columns || newRows != rows) {
            columns = newColumns;
            rows = newRows;
            screen.resizeAsync(columns, rows);
        }
    }

    private void onKey(KeyEvent e) {
        e.doit = false;
        if (e.keyCode == SWT.TAB || screen == null) {
            return; // Tab is sent by onTraverse
        }
        if (KeyMapping.dropsKey(e.stateMask)) {
            return; // Super+U on Linux: neither Neovim's nor a letter (KeyMapping.dropsKey)
        }
        String key = KeyMapping.toNeovimKey(e.keyCode, e.stateMask, e.character);
        if (key != null) {
            screen.inputAsync(key);
        }
    }

    /**
     * A Vim key Eclipse would have taken for a shortcut, from the plug-in's key binding
     * ({@link SendKeyHandler}): {@code <C-w>}, typed in the pane. UI thread.
     */
    void sendKey(String key) {
        if (screen != null) {
            screen.inputAsync(key);
        }
    }

    /**
     * Tab, Shift+Tab, Esc and Enter come as traversals, which would move the focus;
     * {@code doit = false} keeps them here, and they arrive as key presses ({@link #onKey}), as in
     * the command-line window's panel.
     */
    private void onTraverse(TraverseEvent e) {
        e.doit = false;
        if (screen != null && (e.detail == SWT.TRAVERSE_TAB_NEXT || e.detail == SWT.TRAVERSE_TAB_PREVIOUS)) {
            screen.inputAsync(e.detail == SWT.TRAVERSE_TAB_PREVIOUS ? "<S-Tab>" : "<Tab>");
        }
    }

    /** The second Neovim's cursor cell, its middle, in display pixels; the canvas's corner without one. */
    private Point cursorOnScreen() {
        GridSnapshot grid = canvas.grid();
        if (grid == null || grid.cursorRow() < 0) {
            return canvas.toDisplay(0, 0);
        }
        return canvas.toDisplay(grid.cursorCol() * canvas.cellWidth() + canvas.cellWidth() / 2,
                grid.cursorRow() * canvas.cellHeight() + canvas.cellHeight() / 2);
    }

    private void onEnded() {
        screen = null;
        if (!disposed) {
            host.ended();
        }
    }

    /** Closes the second Neovim, in the background. UI thread; the host calls it when it is disposed. */
    void dispose() {
        disposed = true;
        JFaceResources.getFontRegistry().removeListener(fontListener);
        ScreenSession running = screen;
        screen = null;
        if (running != null) {
            closeInBackground(running);
        }
    }

    /** {@link ScreenSession#close} waits for the process a moment, so never on the UI thread. */
    private static void closeInBackground(ScreenSession session) {
        Job job = Job.create("Closing the Neovim view's Neovim", monitor -> {
            session.close();
            return Status.OK_STATUS;
        });
        job.setSystem(true);
        job.schedule();
    }

    /**
     * What one second Neovim reports, passed to the UI thread. Each counts only while it is the
     * pane's {@link #screen}: one closed because the kind changed still sends its last screens, a
     * yank or {@code Ctrl-W p} typed a moment before, and its end, which must neither draw, fill a
     * register, move the focus nor close the pane.
     */
    private final class Events implements ScreenSession.Listener {

        /** The session these events are from, once it started; UI thread. */
        private ScreenSession session;
        /** It ended before {@link #onStarted} took it: {@link #onStarted} ends the pane then. UI thread. */
        private boolean endedEarly;

        private boolean current() {
            return session != null && session == screen;
        }

        @Override
        public void screen(GridSnapshot snapshot) {
            onUi(() -> {
                if (current()) {
                    canvas.show(snapshot);
                }
            });
        }

        @Override
        public void yank(String register, String type, List<String> lines) {
            onUi(() -> {
                NeovimSession editing = Activator.getDefault().currentSession();
                if (current() && editing != null) {
                    editing.setRegisterFromViewAsync(register, type, lines);
                }
            });
        }

        @Override
        public void window(char key, int count) {
            onUi(() -> {
                if (current()) {
                    host.window(key, count, cursorOnScreen());
                }
            });
        }

        @Override
        public void ended() {
            onUi(() -> {
                if (session == null) {
                    endedEarly = true;
                } else if (current()) {
                    onEnded();
                }
            });
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
}
