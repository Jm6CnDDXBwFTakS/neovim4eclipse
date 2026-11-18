// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CommandWindowView;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridEvent;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;

/** {@link NeovimWindowPanel} on a real editor widget, with Neovim replaced by a recorder. */
class NeovimWindowPanelTest {

    private static final long TIMEOUT_MS = TestWait.millis();

    /** Records what the panel asks of Neovim: {@code "type k"}, {@code "close"}. */
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private int stateChanges;
    private Shell shell;
    private StyledText host;
    private NeovimWindowPanel panel;

    @BeforeEach
    void create() {
        shell = new Shell(Display.getDefault());
        shell.setSize(400, 300);
        host = new StyledText(shell, SWT.MULTI);
        host.setBounds(0, 0, 400, 300);
        shell.open();
        KeyboardFocus.require(host, TIMEOUT_MS);
        panel = new NeovimWindowPanel(host, new NeovimWindowPanel.Commands() {
            @Override
            public void type(String keys) {
                calls.add("type " + keys);
            }

            @Override
            public void mouse(String button, String action, String modifier, int row, int col) {
                calls.add("mouse " + button + " " + action + " " + modifier + " " + row + " " + col);
            }

            @Override
            public void close() {
                calls.add("close");
            }
        }, () -> stateChanges++);
    }

    @AfterEach
    void dispose() {
        panel.dispose();
        shell.dispose();
    }

    /** The command-line window, 20 columns by 7 rows, its cursor on the last row. */
    private static CommandWindowView window() {
        List<String> text = new ArrayList<>(Collections.nCopies(20 * 7, " "));
        text.set(0, "e");
        GridSnapshot grid = new GridSnapshot(1004, 20, 7, text, new int[20 * 7], Map.of(),
                new GridEvent.DefaultColors(GridEvent.NO_COLOR, GridEvent.NO_COLOR), 6, 0);
        return new CommandWindowView(true, ":", grid);
    }

    private void settle() {
        while (Display.getDefault().readAndDispatch()) {
            // the posted focus check and the focus events
        }
    }

    @Test
    void itOpensOverTheEditorsBottomWithTheFocusAndClosesWithNeovim() {
        panel.show(window());
        assertTrue(panel.isOpen());
        KeyboardFocus.require(panel.canvas(), TIMEOUT_MS);
        assertTrue(panel.hasFocus());
        Rectangle bounds = panel.canvas().getParent().getBounds();
        Rectangle area = host.getClientArea();
        assertEquals(OverlayFrame.outerHeight(7 * host.getLineHeight()), bounds.height, "as high as the window's 7 rows");
        assertEquals(area.y + area.height - OverlayFrame.bottomGap(host), bounds.y + bounds.height, "at the bottom, as the : box");
        assertTrue(stateChanges > 0);

        panel.show(CommandWindowView.CLOSED);
        assertFalse(panel.isOpen());
        assertTrue(calls.isEmpty(), "Neovim closed it: nothing to ask: " + calls);
        settle();
        assertTrue(host.isFocusControl(), "the editor has the focus back");
    }

    @Test
    void everyKeyGoesToNeovim() {
        panel.show(window());
        Event k = new Event();
        k.keyCode = 'k';
        k.character = 'k';
        panel.canvas().notifyListeners(SWT.KeyDown, k);
        Event enter = new Event();
        enter.keyCode = SWT.CR;
        enter.character = SWT.CR;
        panel.canvas().notifyListeners(SWT.KeyDown, enter);
        panel.ctrlKey("<C-u>");
        assertEquals(List.of("type k", "type <CR>", "type <C-u>"), calls);
    }

    /**
     * The left button and the wheel reach Neovim as cells of the window: a click on row 2, column 3,
     * a drag that sends only a change of cell, and the release; the right button does nothing.
     */
    @Test
    void theLeftButtonAndTheWheelGoToNeovimAsCells() {
        panel.show(window());
        GridCanvas canvas = panel.canvas();
        int w = canvas.cellWidth();
        int h = canvas.cellHeight();
        canvas.notifyListeners(SWT.MouseDown, mouse(1, 3 * w + 1, 2 * h + 1, SWT.SHIFT));
        canvas.notifyListeners(SWT.MouseMove, mouse(0, 3 * w + 2, 2 * h + 2, SWT.BUTTON1)); // the same cell
        canvas.notifyListeners(SWT.MouseMove, mouse(0, 5 * w + 1, 2 * h + 1, SWT.BUTTON1));
        canvas.notifyListeners(SWT.MouseMove, mouse(0, 100 * w, 100 * h, SWT.BUTTON1)); // past the edge: the last cell
        canvas.notifyListeners(SWT.MouseUp, mouse(1, 100 * w, 100 * h, 0));
        canvas.notifyListeners(SWT.MouseDown, mouse(3, w, h, 0)); // the right button
        Event wheel = mouse(0, w, h, 0);
        wheel.count = -3; // one notch down, as SWT counts it
        canvas.notifyListeners(SWT.MouseVerticalWheel, wheel);
        assertEquals(List.of("mouse left press S 2 3", "mouse left drag  2 5", "mouse left drag  6 19",
                "mouse left release  6 19", "mouse wheel down  1 1"), calls);
    }

    /** A touchpad's small counts add up to a notch's scroll, not one scroll each. */
    @Test
    void aTouchpadsSmallWheelCountsAddUpToOneScroll() {
        panel.show(window());
        GridCanvas canvas = panel.canvas();
        for (int i = 0; i < 5; i++) {
            Event wheel = mouse(0, 1, 1, 0);
            wheel.count = 1;
            canvas.notifyListeners(SWT.MouseVerticalWheel, wheel);
        }
        assertEquals(List.of("mouse wheel up  0 0"), calls, "5 counts: one scroll, 2 kept for the next");
    }

    /** The window closed mid-drag (Enter typed with the button held): no hover turns into a drag after. */
    @Test
    void aDragCutOffByTheCloseSendsNothingWhenTheWindowOpensAgain() {
        panel.show(window());
        panel.canvas().notifyListeners(SWT.MouseDown, mouse(1, 1, 1, 0));
        panel.show(CommandWindowView.CLOSED);
        panel.show(window());
        int w = panel.canvas().cellWidth();
        panel.canvas().notifyListeners(SWT.MouseMove, mouse(0, 5 * w, 1, 0));
        assertEquals(List.of("mouse left press  0 0"), calls);
    }

    private static Event mouse(int button, int x, int y, int stateMask) {
        Event e = new Event();
        e.button = button;
        e.x = x;
        e.y = y;
        e.stateMask = stateMask;
        return e;
    }

    @Test
    void theFocusElsewhereClosesTheWindowWithoutRunningItsLine() {
        Text other = new Text(shell, SWT.SINGLE);
        other.setBounds(0, 0, 50, 20);
        panel.show(window());
        KeyboardFocus.require(panel.canvas(), TIMEOUT_MS);
        clickAway(other);
        assertEquals(List.of("close"), calls);
        assertFalse(panel.isOpen());
        assertTrue(other.isFocusControl(), "the focus stays where it went");
    }

    /**
     * A click away from the open panel, into {@code other}: moves the focus from the panel there,
     * again until the panel has closed. The panel closes from an {@code asyncExec} after the focus
     * event, and only if the focus is then in Eclipse; when the test's window was not active at that
     * moment, a single move left the panel open (seen on macOS), so the move is made again.
     */
    private void clickAway(Text other) {
        KeyboardFocus.moveUntil(panel.canvas(), other, () -> !panel.isOpen(),
                () -> "the panel closed by the focus going elsewhere", TIMEOUT_MS);
    }

    /** Redraws on the way to Neovim's close (leaving Insert mode redraws) do not open it again. */
    @Test
    void aWindowClickedAwayFromStaysClosedUntilNeovimClosesIt() {
        Text other = new Text(shell, SWT.SINGLE);
        other.setBounds(0, 0, 50, 20);
        panel.show(window());
        KeyboardFocus.require(panel.canvas(), TIMEOUT_MS);
        clickAway(other);
        panel.show(window()); // a redraw before Neovim's window is gone
        assertFalse(panel.isOpen(), "not opened again");
        assertTrue(other.isFocusControl(), "the focus stays where the user clicked");
        panel.show(CommandWindowView.CLOSED); // Neovim's report
        panel.show(window()); // q: again
        assertTrue(panel.isOpen());
    }

    @Test
    void theColonBoxOverTheSameEditorIsNotElsewhere() {
        panel.show(window());
        KeyboardFocus.require(panel.canvas(), TIMEOUT_MS);
        OverlayFrame<Text> box = new OverlayFrame<>(host, parent -> new Text(parent, SWT.SINGLE)); // as the : box opens
        box.control().setBounds(0, 0, 100, 20);
        KeyboardFocus.require(box.content(), TIMEOUT_MS);
        settle();
        assertTrue(panel.isOpen(), "a : typed in the window opens Neovim's command line on top of it");
        box.dispose(); // the : box closes and gives the focus to the editor for a moment
        host.setFocus();
        panel.focus();
        settle();
        assertTrue(panel.isOpen(), "the focus came back to the panel at once: " + calls);
        assertNotNull(panel.canvas());
        assertTrue(calls.isEmpty(), calls.toString());
    }
}
