// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.events.TraverseEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CommandWindowView;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.KeyMapping;

/**
 * Neovim's command-line window ({@code q:}, {@code q/}, {@code q?}, {@code Ctrl-F} on the command
 * line), drawn over the bottom rows of the editor, where the {@code :} box opens. It is a window of
 * Neovim's own, with no document behind it, so Eclipse draws it from Neovim's grid ({@link
 * GridCanvas}) rather than showing it in an editor.
 *
 * <h2>Behavior</h2>
 * <ul>
 *   <li>It opens when Neovim opens the window and closes when Neovim closes it ({@link #show}): Enter
 *       runs the line under the cursor, {@code :quit} drops it, and so on, all Neovim's.</li>
 *   <li>While it is open it has the keyboard focus, and every key goes to Neovim
 *       ({@link Commands#type}), in every mode: Eclipse never types into it, not even in Insert
 *       mode, where it types into the editor.</li>
 *   <li>The focus moving elsewhere in Eclipse (a click into the editor or another view) closes the
 *       window without running its line ({@link Commands#close}), as it does for the {@code :} box;
 *       the click goes where it was aimed. A command line Neovim opens on top of it ({@code :} typed
 *       in it) takes the focus into the {@code :} box, which is not elsewhere. The focus leaving
 *       Eclipse altogether keeps it open.</li>
 *   <li>The editor keeps its size: the panel covers its bottom rows.</li>
 * </ul>
 * Example: {@code q:}, {@code k}, {@code Enter} runs the command before the last; {@code q:} and a
 * click into the Package Explorer closes it and runs nothing.
 *
 * <p>UI thread only. A child of the editor's {@code StyledText}, in an {@link OverlayFrame} like the
 * {@code :} box's, repositioned when the editor is resized.
 */
final class NeovimWindowPanel {

    /** What the panel asks of Neovim. */
    interface Commands {

        /** Types keys into Neovim (Neovim notation, e.g. {@code "k"}, {@code "<CR>"}). */
        void type(String keys);

        /**
         * A mouse event on a cell of the window ({@code nvim_input_mouse}): {@code "left"} with
         * {@code "press"}, {@code "drag"} or {@code "release"}, or {@code "wheel"} with {@code "up"}
         * or {@code "down"}; row and column are the cell's, 0-based.
         */
        void mouse(String button, String action, String modifier, int row, int col);

        /** Closes the command-line window without running its line. */
        void close();
    }

    private final StyledText host;
    private final Display display;
    private final Commands commands;
    private final Runnable stateListener;
    private final Listener abandonOnFocusElsewhere = this::onFocusAnywhere;
    private final ControlListener repositionOnResize = ControlListener.controlResizedAdapter(e -> reposition());
    private OverlayFrame<GridCanvas> frame;
    private GridCanvas canvas;
    private boolean insert;
    /**
     * Set when the panel closed the window because the focus went elsewhere ({@link Commands#close}),
     * until Neovim reports it closed: the redraws on the way (the close leaves Insert mode, which
     * redraws the window) must not open the panel again and take the focus back from where the user
     * clicked.
     */
    private boolean abandoned;

    /**
     * @param stateListener told when the panel opens, closes, or gains or loses the focus: the Vim
     *                      Ctrl-key contexts follow ({@code EditorKeyContext})
     */
    NeovimWindowPanel(StyledText host, Commands commands, Runnable stateListener) {
        this.host = host;
        this.display = host.getDisplay();
        this.commands = commands;
        this.stateListener = stateListener;
    }

    /** Opens, redraws or closes the panel for Neovim's command-line window as it is now. */
    void show(CommandWindowView view) {
        if (!view.open()) {
            abandoned = false;
            close(true);
            return;
        }
        if (abandoned) {
            return; // the window the user clicked away from; Neovim's close is on its way
        }
        if (canvas == null) {
            open();
        }
        if (view.grid() != null) {
            canvas.show(view.grid());
            reposition();
        }
    }

    /** Whether Neovim is in Insert mode in the window: its cursor is drawn as a bar then. */
    void setInsertMode(boolean insertMode) {
        insert = insertMode;
        if (canvas != null) {
            canvas.setInsertCursor(insertMode);
        }
    }

    boolean isOpen() {
        return canvas != null;
    }

    boolean hasFocus() {
        return canvas != null && !canvas.isDisposed() && canvas.isFocusControl();
    }

    /** Takes the focus back, after the {@code :} box Neovim opened on top of it closed. */
    void focus() {
        if (canvas != null && !canvas.isDisposed()) {
            canvas.setFocus();
        }
    }

    /** A Vim Ctrl key the workbench delivered through a key binding ({@code SendKeyHandler}): Neovim's. */
    void ctrlKey(String nvimKey) {
        if (isOpen()) {
            commands.type(nvimKey);
        }
    }

    /** The canvas, for tests. */
    GridCanvas canvas() {
        return canvas;
    }

    private void open() {
        frame = new OverlayFrame<>(host, parent -> new GridCanvas(parent, host));
        canvas = frame.content();
        canvas.setInsertCursor(insert);
        canvas.addKeyListener(KeyListener.keyPressedAdapter(this::onKey));
        canvas.addTraverseListener(this::onTraverse);
        canvas.addListener(SWT.MouseDown, this::onMouseDown);
        canvas.addListener(SWT.MouseMove, this::onMouseMove);
        canvas.addListener(SWT.MouseUp, this::onMouseUp);
        canvas.addListener(SWT.MouseVerticalWheel, this::onWheel);
        canvas.addListener(SWT.FocusIn, e -> stateListener.run());
        canvas.addListener(SWT.FocusOut, e -> stateListener.run());
        host.addControlListener(repositionOnResize);
        reposition();
        canvas.setFocus();
        display.addFilter(SWT.FocusIn, abandonOnFocusElsewhere);
        stateListener.run();
    }

    // ---- the mouse: the left button and the wheel are Neovim's, in every mode (no Eclipse text here) ----

    /** The cell the left button last pressed or dragged to, {row, col}; {@code null} while it is up. */
    private int[] pressedAt;

    private void onMouseDown(Event e) {
        int[] cell = canvas.cellAt(e.x, e.y);
        if (e.button != 1 || cell == null) {
            return; // the right and middle buttons do nothing here
        }
        pressedAt = cell;
        commands.mouse("left", "press", modifiers(e.stateMask), cell[0], cell[1]);
    }

    /** A drag sends only a change of cell: moving inside one cell would make Neovim redraw for nothing. */
    private void onMouseMove(Event e) {
        int[] cell = pressedAt == null ? null : canvas.cellAt(e.x, e.y);
        if (cell == null || (cell[0] == pressedAt[0] && cell[1] == pressedAt[1])) {
            return;
        }
        pressedAt = cell;
        commands.mouse("left", "drag", modifiers(e.stateMask), cell[0], cell[1]);
    }

    private void onMouseUp(Event e) {
        int[] cell = canvas.cellAt(e.x, e.y);
        if (e.button != 1 || pressedAt == null || cell == null) {
            return;
        }
        pressedAt = null;
        commands.mouse("left", "release", modifiers(e.stateMask), cell[0], cell[1]);
    }

    /** One wheel notch is one {@code <ScrollWheelUp>} or {@code <ScrollWheelDown>}: Neovim scrolls by its {@code 'mousescroll'}. */
    private void onWheel(Event e) {
        int[] cell = canvas.cellAt(e.x, e.y);
        if (cell == null || e.count == 0) {
            return;
        }
        String direction = e.count > 0 ? "up" : "down";
        for (int i = 0; i < Math.max(1, Math.abs(e.count) / 3); i++) { // SWT counts 3 per notch
            commands.mouse("wheel", direction, modifiers(e.stateMask), cell[0], cell[1]);
        }
        e.doit = false; // the editor under the panel does not scroll too
    }

    /** Neovim's modifier letters for a mouse event's keys: {@code "S"} Shift, {@code "C"} Ctrl, {@code "A"} Alt. */
    static String modifiers(int stateMask) {
        return ((stateMask & SWT.SHIFT) != 0 ? "S" : "") + ((stateMask & SWT.CTRL) != 0 ? "C" : "")
                + ((stateMask & SWT.ALT) != 0 ? "A" : "");
    }

    private void onKey(KeyEvent e) {
        e.doit = false;
        if (e.keyCode == SWT.TAB) {
            return; // sent by onTraverse
        }
        String key = KeyMapping.toNeovimKey(e.keyCode, e.stateMask, e.character);
        if (key != null) {
            commands.type(key);
        }
    }

    /**
     * Tab, Shift+Tab, Esc and Enter come as traversals, which would move the focus or close a
     * dialog; {@code doit = false} keeps them here, and they arrive as key presses ({@link #onKey}).
     */
    private void onTraverse(TraverseEvent e) {
        e.doit = false;
        if (e.detail == SWT.TRAVERSE_TAB_NEXT || e.detail == SWT.TRAVERSE_TAB_PREVIOUS) {
            commands.type(e.detail == SWT.TRAVERSE_TAB_PREVIOUS ? "<S-Tab>" : "<Tab>");
        }
    }

    /**
     * The focus went somewhere: unless it is the panel, or the {@code :} box over the same editor
     * (a child of the editor, as the panel is), the window is closed. Checked once the focus has
     * settled ({@code asyncExec}): the {@code :} box going away gives the focus to the editor for a
     * moment before it comes back here ({@link #focus}).
     */
    private void onFocusAnywhere(Event event) {
        if (isOpen()) {
            display.asyncExec(this::closeIfFocusElsewhere);
        }
    }

    private void closeIfFocusElsewhere() {
        if (!isOpen() || canvas.isDisposed() || host.isDisposed()) {
            return;
        }
        Control focus = display.getFocusControl();
        if (focus == null || focus == canvas || isOverlayOfHost(focus)) {
            return; // nothing in Eclipse has it (another application), the panel, or the : box
        }
        abandoned = true;
        commands.close();
        close(false);
    }

    /** Whether {@code control} is inside the editor's widget (an overlay such as the {@code :} box), not the widget itself. */
    private boolean isOverlayOfHost(Control control) {
        for (Control c = control.getParent(); c != null; c = c.getParent()) {
            if (c == host) {
                return true;
            }
        }
        return false;
    }

    /** Covers the editor's bottom rows, as high as the grid ({@code 'cmdwinheight'} rows). */
    private void reposition() {
        if (!isOpen() || host.isDisposed() || frame.isDisposed()) {
            return;
        }
        Rectangle area = host.getClientArea();
        Point grid = canvas.gridSize();
        int width = Math.max(10, area.width - 2 * OverlayFrame.SIDE_PADDING);
        int height = Math.min(OverlayFrame.outerHeight(grid.y), Math.max(10, area.height - OverlayFrame.bottomGap(host)));
        int x = area.x + OverlayFrame.SIDE_PADDING;
        int y = area.y + area.height - height - OverlayFrame.bottomGap(host);
        Rectangle current = frame.control().getBounds();
        if (current.x != x || current.y != y || current.width != width || current.height != height) {
            frame.control().setBounds(x, y, width, height);
        }
    }

    /** @param refocusEditor whether the editor takes the focus back (not when it went elsewhere) */
    private void close(boolean refocusEditor) {
        if (frame == null) {
            return;
        }
        boolean hadFocus = hasFocus();
        display.removeFilter(SWT.FocusIn, abandonOnFocusElsewhere);
        if (!host.isDisposed()) {
            host.removeControlListener(repositionOnResize);
        }
        OverlayFrame<GridCanvas> old = frame;
        frame = null;
        canvas = null;
        old.dispose();
        if (refocusEditor && hadFocus && !host.isDisposed()) {
            host.setFocus();
        }
        stateListener.run();
    }

    /** Called when the editor goes away. */
    void dispose() {
        close(false);
    }
}
