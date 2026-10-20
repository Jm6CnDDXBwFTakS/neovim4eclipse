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

/**
 * Shows what a listing command printed ({@code :ls}, {@code :marks}, ...) along the bottom of the
 * editor, where Vim shows its message area and where the command line box sits.
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
     * @param title  the command, shown as the first line (as Vim echoes it)
     * @param output what the command printed; nothing is shown when it is blank
     */
    void show(String title, String output) {
        if (host.isDisposed() || output == null || output.isBlank()) {
            return;
        }
        close(false);
        frame = new OverlayFrame<>(host,
                parent -> new StyledText(parent, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL));
        view = frame.content();
        view.setText(title + "\n" + output.stripTrailing() + "\n" + "Press any key to continue");
        view.addKeyListener(KeyListener.keyPressedAdapter(this::onKey));
        view.addFocusListener(FocusListener.focusLostAdapter(e -> {
            if (!heldForCommandLine) {
                close(false);
            }
        }));
        host.addPaintListener(repositionOnPaint);
        host.addControlListener(repositionOnResize);
        reposition();
        view.setFocus();
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
