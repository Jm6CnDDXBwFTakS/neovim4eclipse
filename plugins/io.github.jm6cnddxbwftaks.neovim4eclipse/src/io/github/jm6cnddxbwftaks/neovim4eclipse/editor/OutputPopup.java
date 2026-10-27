// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.FocusListener;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Listener;

/**
 * Shows output along the bottom of the editor, where Vim shows its message area and where the
 * command line box sits: what a listing printed ({@code :ls}, {@code :marks}, ...), shell output
 * ({@code :!make}), or messages of more than one line ({@code util/MessageRoute} decides).
 *
 * <p>Read-only, in the editor's font, with the same border and tinted background as the command line
 * ({@link OverlayFrame}). Arrow keys, Page Up/Down, Home/End, {@code j}/{@code k},
 * Space/{@code b} and {@code g}/{@code G} scroll; Ctrl+C (Cmd+C) copies a selection; any other key,
 * or clicking elsewhere, closes it and returns to the editor.
 *
 * <p>{@code :} starts the next command as in Vim: the command line opens below the popup, which
 * stays visible until that command is run or canceled ({@link #makeRoomBelow}). UI thread only.
 */
final class OutputPopup {

    /** The popup never covers more than this share of the editor; longer output scrolls. */
    private static final double MAX_HEIGHT_FRACTION = 0.6;

    private final StyledText host;
    private final Runnable colonHandler;
    private OverlayFrame<StyledText> frame;
    /** The text inside {@link #frame}; {@code null} while closed. */
    private StyledText view;
    /** Space kept free below the popup for the command line box. */
    private int bottomInset;
    /** True while the command line box has the focus: losing it then does not close the popup. */
    private boolean heldForCommandLine;
    /**
     * True while the popup is shown without the focus ({@link #showOverEditor}): it opened while the
     * user typed elsewhere in Eclipse, and the next key or click in the editor closes it.
     */
    private boolean closesWithEditorInput;
    /** Closes the popup on a key or click in the editor; the key itself goes on to Neovim as usual. */
    private final Listener closeOnEditorInput = event -> close(false);

    private final PaintListener repositionOnPaint = event -> reposition();
    private final ControlListener repositionOnResize = new ControlListener() {
        @Override
        public void controlMoved(ControlEvent e) {
            reposition();
        }

        @Override
        public void controlResized(ControlEvent e) {
            reposition();
        }
    };

    /** @param colonHandler opens the command line; called when {@code :} is typed in the popup */
    OutputPopup(StyledText host, Runnable colonHandler) {
        this.host = host;
        this.colonHandler = colonHandler;
    }

    boolean isOpen() {
        return view != null && !view.isDisposed();
    }

    /**
     * Shows {@code output}. While the popup is open, its text is replaced and it keeps the focus and
     * its scroll position: output that grows while a command runs ({@code :!make}) arrives again,
     * whole.
     *
     * @param title  the command, shown as the first line (as Vim echoes it); "" for none
     * @param output what the command printed; nothing is shown when it is blank
     */
    void show(String title, String output) {
        show(title, output, true);
    }

    /**
     * Shows {@code output} over the editor, taking the focus only from the editor itself. With the
     * editor focused it takes the focus at once, as Vim's "Press ENTER" prompt takes the next key.
     * Output that comes while the user types elsewhere in Eclipse (a view, a dialog, another editor)
     * does not take the focus from there: Vim has one place to type, Eclipse many. The popup then
     * opens without the focus and behaves like the message line: the next key or click in the
     * editor closes it, and the key goes on to Neovim. A click into the popup gives it the focus,
     * for scrolling. Nothing here moves the focus later, which would race with the command line box
     * a {@code :} opens (focus events come in a different order on each system). Neovim's
     * {@code :messages} keeps its history either way.
     *
     * @param editorHasFocus whether the focus is in the editor now
     */
    void showOverEditor(String title, String output, boolean editorHasFocus) {
        if (editorHasFocus || isOpen()) {
            show(title, output, true);
            return;
        }
        show(title, output, false, false);
        if (isOpen() && !host.isDisposed()) {
            closesWithEditorInput = true;
            host.addListener(SWT.KeyDown, closeOnEditorInput);
            host.addListener(SWT.MouseDown, closeOnEditorInput);
        }
    }

    private void stopClosingWithEditorInput() {
        if (closesWithEditorInput) {
            closesWithEditorInput = false;
            if (!host.isDisposed()) {
                host.removeListener(SWT.KeyDown, closeOnEditorInput);
                host.removeListener(SWT.MouseDown, closeOnEditorInput);
            }
        }
    }

    /**
     * As {@link #show(String, String)}; with {@code takeFocus} false the popup opens without taking
     * the focus and stays while the command line box keeps it, as {@link #makeRoomBelow} does. Used
     * for output that arrives while the user types a command line (a timer's message): taking the
     * focus would close that line.
     */
    void show(String title, String output, boolean takeFocus) {
        show(title, output, !takeFocus, takeFocus);
    }

    /**
     * @param holdForCommandLine stay open while the command line box has the focus
     * @param takeFocus          take the keyboard focus now
     */
    private void show(String title, String output, boolean holdForCommandLine, boolean takeFocus) {
        if (host.isDisposed() || output == null || output.isBlank()) {
            return;
        }
        String text = (title == null || title.isEmpty() ? "" : title + "\n") + output.stripTrailing() + "\n"
                + "Press any key to continue";
        if (isOpen() && !heldForCommandLine) {
            int top = view.getTopIndex();
            view.setText(text);
            view.setTopIndex(top);
            reposition();
            return;
        }
        close(false);
        // Shown without the focus and not for the command line (showOverEditor): NO_FOCUS, so that the
        // editor getting the focus back does not hand it to the popup (Composite.setFocus offers it to
        // its children first, the popup's text among them). A click into the popup takes it anyway.
        boolean refusesFocus = !takeFocus && !holdForCommandLine;
        frame = new OverlayFrame<>(host, parent -> new StyledText(parent,
                SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL | (refusesFocus ? SWT.NO_FOCUS : SWT.NONE)));
        view = frame.content();
        view.setText(text);
        if (refusesFocus) {
            view.addListener(SWT.MouseDown, event -> {
                stopClosingWithEditorInput(); // focused, it closes as any popup does
                view.forceFocus(); // forceFocus, unlike setFocus, ignores NO_FOCUS
            });
        }
        view.addKeyListener(KeyListener.keyPressedAdapter(this::onKey));
        view.addFocusListener(FocusListener.focusLostAdapter(e -> {
            if (!heldForCommandLine) {
                close(false);
            }
        }));
        host.addPaintListener(repositionOnPaint);
        host.addControlListener(repositionOnResize);
        heldForCommandLine = holdForCommandLine;
        reposition();
        if (takeFocus) {
            view.setFocus();
        }
    }

    private void onKey(KeyEvent e) {
        if ((e.keyCode & SWT.MODIFIER_MASK) != 0 && e.character == 0) {
            return; // Ctrl, Shift, ... pressed on their own
        }
        if (isNativeScrollOrCopy(e)) {
            return; // StyledText scrolls or copies by itself
        }
        e.doit = false;
        if (e.character == ':') {
            heldForCommandLine = true; // the command line takes the focus; this listing stays until it is done
            colonHandler.run();
            return;
        }
        int page = Math.max(1, view.getClientArea().height / Math.max(1, view.getLineHeight()) - 1);
        switch (e.character) {
            case 'j' -> view.setTopIndex(view.getTopIndex() + 1);
            case 'k' -> view.setTopIndex(Math.max(0, view.getTopIndex() - 1));
            case ' ', 'f' -> view.setTopIndex(view.getTopIndex() + page);
            case 'b' -> view.setTopIndex(Math.max(0, view.getTopIndex() - page));
            case 'g' -> view.setTopIndex(0);
            case 'G' -> view.setTopIndex(view.getLineCount() - 1);
            default -> close(true);
        }
    }

    private static boolean isNativeScrollOrCopy(KeyEvent e) {
        boolean copy = (e.stateMask & SWT.MOD1) != 0 && (e.keyCode == 'c' || e.keyCode == 'C');
        return copy || switch (e.keyCode) {
            case SWT.ARROW_UP, SWT.ARROW_DOWN, SWT.ARROW_LEFT, SWT.ARROW_RIGHT, SWT.PAGE_UP, SWT.PAGE_DOWN,
                    SWT.HOME, SWT.END -> true;
            default -> false;
        };
    }

    /**
     * Moves the popup up by {@code pixels}, so that the command line box fits underneath, and keeps it
     * open while the box has the focus. It closes with the box ({@link #close}).
     */
    void makeRoomBelow(int pixels) {
        if (isOpen()) {
            heldForCommandLine = true;
            bottomInset = pixels;
            reposition();
        }
    }

    /** Fits the text up to {@link #MAX_HEIGHT_FRACTION} of the editor, near its bottom edge. */
    private void reposition() {
        if (!isOpen() || host.isDisposed()) {
            return;
        }
        Rectangle area = host.getClientArea();
        int width = Math.max(10, area.width - 2 * OverlayFrame.SIDE_PADDING);
        int wanted = OverlayFrame.outerHeight(view.computeSize(width - 2 * OverlayFrame.BORDER, SWT.DEFAULT).y);
        int height = Math.max(1, Math.min(wanted, (int) (area.height * MAX_HEIGHT_FRACTION) - bottomInset));
        int x = area.x + OverlayFrame.SIDE_PADDING;
        int y = area.y + area.height - height - OverlayFrame.bottomGap(host) - bottomInset;
        Rectangle current = frame.control().getBounds();
        if (current.x != x || current.y != y || current.width != width || current.height != height) {
            frame.control().setBounds(x, y, width, height);
        }
    }

    /** @param refocusEditor whether to give focus back to the editor */
    void close(boolean refocusEditor) {
        if (view == null) {
            return;
        }
        OverlayFrame<StyledText> old = frame;
        frame = null;
        view = null;
        bottomInset = 0;
        heldForCommandLine = false;
        stopClosingWithEditorInput();
        if (!host.isDisposed()) {
            host.removePaintListener(repositionOnPaint);
            host.removeControlListener(repositionOnResize);
        }
        old.dispose(); // disposes the text too
        if (refocusEditor && !host.isDisposed()) {
            host.setFocus();
        }
    }
}
