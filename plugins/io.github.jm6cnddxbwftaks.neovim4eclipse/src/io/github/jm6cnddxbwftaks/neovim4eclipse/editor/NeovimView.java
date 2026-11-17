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
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.events.TraverseEvent;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.contexts.IContextActivation;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.part.ViewPart;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.OutputProfile;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.ScreenSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.KeyMapping;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.OverlayColors;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PluginFolders;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ScreenInput;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewText;

/**
 * The Neovim view: a second Neovim's whole screen in an Eclipse view, first for {@code :messages}
 * and {@code :checkhealth} ({@link NeovimViewOpener} opens it with their text). The second Neovim
 * ({@link ScreenSession}, output profile {@link OutputProfile}) shows the text and never writes a
 * file; this class is the thin SWT part: a {@link GridCanvas} for the screen, keys and mouse to
 * Neovim, the view's size as Neovim's screen, and the way back to the editor.
 *
 * <h2>What the user sees</h2>
 * <ul>
 *   <li>The title says what the view holds: "Neovim: messages", "Neovim: checkhealth"
 *       ({@link ScreenInput#title}). New text replaces the old.</li>
 *   <li>Every key goes to the second Neovim ({@code /} searches, {@code y} yanks into the editing
 *       Neovim's register, {@code q} or {@code :q} closes the view), except the shortcuts Eclipse
 *       binds, which Eclipse keeps as in any view. {@code Ctrl-W w}, {@code p} and {@code k} give
 *       the keyboard back to the editor the text came from.</li>
 *   <li>The left button and the wheel are Neovim's ({@link GridMouse}).</li>
 *   <li>When the second Neovim ends ({@code :q}, a crash), the view closes; the next
 *       {@code :messages} opens it with a new one.</li>
 * </ul>
 *
 * <h2>Its look</h2>
 * The editor's text font, and the colors of the editor the text came from, taken when the text
 * arrives ({@link #show}): Neovim's default colorscheme on the editor's background, with its
 * {@code 'background'} set to match ({@link OverlayColors#isDark}).
 *
 * <p>UI thread, except the listener's calls, which come from the second Neovim's threads and are
 * passed on with {@code asyncExec}. Starting and closing the second Neovim blocks, so both run in a
 * background job, never on the UI thread.
 */
public final class NeovimView extends ViewPart {

    /** The view's id in {@code plugin.xml}. */
    public static final String ID = "io.github.jm6cnddxbwftaks.neovim4eclipse.views.neovim";

    /**
     * The key context active while the view's canvas has the keyboard ({@code plugin.xml}): there
     * Ctrl+W runs the plug-in's Ctrl-W command, which {@link SendKeyHandler} sends to this view,
     * instead of Eclipse's Close, which would close the editor behind the view.
     */
    static final String KEY_CONTEXT_ID = "io.github.jm6cnddxbwftaks.neovim4eclipse.context.view";

    private static final Logger LOG = Logger.getLogger(NeovimView.class.getName());

    /** Where the second Neovim starts: a folder of the plug-in's own ({@code ScreenSession.start}). */
    private static final Supplier<Path> SCREEN_FOLDER = () -> PluginFolders.pluginFolder().resolve("screen");

    /** Before any editor's colors arrive: black on white, Neovim's light look. */
    private int foreground = 0x000000;
    private int background = 0xFFFFFF;
    /** The font {@link #lineHeight} was measured for, and that height. */
    private Font measuredFont;
    private int measuredHeight = 1;

    private GridCanvas canvas;
    /** The second Neovim; {@code null} before the first text and after it ended. */
    private ScreenSession screen;
    /** Whether a start is under way: text that arrives meanwhile waits in {@link #pending}. */
    private boolean starting;
    /** The text to show once the second Neovim has started; {@code null} for none. */
    private ViewText pending;
    /** The editor the last text came from, for {@code Ctrl-W p}; {@code null} if none. */
    private IEditorPart cameFrom;
    /** The screen size last asked for, in cells, so a resize that changes nothing sends nothing. */
    private int columns;
    private int rows;
    private boolean disposed;
    /** The view's key context while the canvas has the keyboard ({@link #KEY_CONTEXT_ID}); {@code null} otherwise. */
    private IContextActivation keyContext;

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new FillLayout());
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
                return NeovimView.this.lineHeight();
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
        // Bound to the view's site: Eclipse also ends the activation when the view stops being the
        // active part, so the context never outlives the focus.
        IContextService contexts = getSite().getService(IContextService.class);
        if (contexts != null) {
            canvas.addListener(SWT.FocusIn, e -> {
                if (keyContext == null) {
                    keyContext = contexts.activateContext(KEY_CONTEXT_ID);
                }
            });
            canvas.addListener(SWT.FocusOut, e -> {
                if (keyContext != null) {
                    contexts.deactivateContext(keyContext);
                    keyContext = null;
                }
            });
        }
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
        setPartName(ScreenInput.title(""));
    }

    @Override
    public void setFocus() {
        if (canvas != null && !canvas.isDisposed()) {
            canvas.setFocus();
        }
    }

    /**
     * Shows {@code text}, replacing what the view held, starting the second Neovim if it is not
     * running. {@code from} is the editor it came from: its colors are the view's, and
     * {@code Ctrl-W p} goes back to it. UI thread.
     *
     * @param from the editor whose Neovim produced the text, or {@code null} if none is active
     * @param fg   that editor's text color, {@code 0xRRGGBB}
     * @param bg   its background
     */
    void show(ViewText text, IEditorPart from, int fg, int bg) {
        if (disposed) {
            return;
        }
        cameFrom = from;
        foreground = fg;
        background = bg;
        setPartName(ScreenInput.title(text.title()));
        if (screen != null) {
            showOnScreen(text);
            canvas.redraw();
            return;
        }
        pending = text;
        if (!starting) {
            start();
        }
    }

    /** Starts the second Neovim in a background job, at the view's size, then shows {@link #pending}. */
    private void start() {
        starting = true;
        Point size = canvas.getSize();
        int startColumns = ScreenInput.cells(size.x, canvas.cellWidth());
        int startRows = ScreenInput.cells(size.y, canvas.cellHeight());
        Display display = canvas.getDisplay();
        String program = Activator.getDefault().getNvimExecutable();
        String version = Activator.getDefault().getBundle().getVersion().toString();
        Job job = Job.create("Starting the Neovim view's Neovim", monitor -> {
            ScreenSession started = null;
            Exception failure = null;
            try {
                started = ScreenSession.start(program, OutputProfile.ARGS, version, SCREEN_FOLDER.get(), startColumns,
                        startRows, new Events());
            } catch (Exception e) {
                failure = e;
            }
            ScreenSession session = started;
            Exception error = failure;
            display.asyncExec(() -> onStarted(session, error, startColumns, startRows));
            return Status.OK_STATUS;
        });
        job.setSystem(true);
        job.schedule();
    }

    private void onStarted(ScreenSession session, Exception failure, int startColumns, int startRows) {
        starting = false;
        if (failure != null) {
            LOG.log(Level.WARNING, "The Neovim view could not start its Neovim", failure);
            setPartName(ScreenInput.title("could not start (see the log)"));
            return;
        }
        if (disposed) {
            closeInBackground(session);
            return;
        }
        screen = session;
        columns = startColumns;
        rows = startRows;
        resizeScreen(); // the view may have changed size while Neovim started
        ViewText text = pending;
        pending = null;
        if (text != null) {
            showOnScreen(text);
        }
    }

    /**
     * Puts {@code text} into the second Neovim. A failure (it ended a moment ago, after a
     * {@code :q} whose end is still on its way) is logged; the next text starts a new one.
     */
    private void showOnScreen(ViewText text) {
        OutputProfile.show(screen, text, OverlayColors.isDark(background)).whenComplete((count, failure) -> {
            if (failure != null) {
                LOG.log(Level.FINE, "The Neovim view could not show " + text.title(), failure);
            }
        });
    }

    /** One cell's height: the text font's line height, measured once per font. */
    private int lineHeight() {
        Font font = JFaceResources.getTextFont();
        if (font != measuredFont && canvas != null && !canvas.isDisposed()) {
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

    /** Neovim's screen is the view's size in cells; asked again only when that changes. */
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
     * ({@link SendKeyHandler}): {@code <C-w>}, typed in the view. UI thread.
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

    /** {@code Ctrl-W w}, {@code p} or {@code k}: the editor the text came from, or the page's active one. */
    private void toEditor() {
        IWorkbenchPage page = getSite().getPage();
        IEditorPart editor = cameFrom != null && page.findEditor(cameFrom.getEditorInput()) != null ? cameFrom
                : page.getActiveEditor();
        if (editor != null) {
            page.activate(editor);
        }
    }

    /** The second Neovim ended: the view closes ({@code :q}); the next text opens a new one. */
    private void onEnded() {
        screen = null;
        if (!disposed) {
            getSite().getPage().hideView(this);
        }
    }

    /** The canvas the screen is drawn on, for tests. UI thread. */
    GridCanvas canvasForTests() {
        return canvas;
    }

    @Override
    public void dispose() {
        disposed = true;
        ScreenSession running = screen;
        screen = null;
        if (running != null) {
            closeInBackground(running);
        }
        super.dispose();
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

    /** What the second Neovim reports, passed to the UI thread. */
    private final class Events implements ScreenSession.Listener {

        @Override
        public void screen(GridSnapshot snapshot) {
            onUi(() -> canvas.show(snapshot));
        }

        @Override
        public void yank(String register, String type, List<String> lines) {
            NeovimSession editing = Activator.getDefault().currentSession();
            if (editing != null) {
                editing.setRegisterFromViewAsync(register, type, lines);
            }
        }

        @Override
        public void toEditor() {
            onUi(NeovimView.this::toEditor);
        }

        @Override
        public void ended() {
            onUi(NeovimView.this::onEnded);
        }

        private void onUi(Runnable work) {
            if (canvas == null || canvas.isDisposed()) {
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
