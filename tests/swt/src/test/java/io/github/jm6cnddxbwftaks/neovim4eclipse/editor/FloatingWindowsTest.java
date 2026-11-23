// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FloatWindow;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridEvent;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;

/**
 * {@link FloatingWindows} on a real {@code StyledText}: a canvas per window, where Neovim put the
 * window, in Neovim's order under the editor's other children, never with the focus, and gone
 * when the window is. The editor here stands for one whose Neovim window has 40 columns and 10
 * rows.
 */
class FloatingWindowsTest {

    private Shell shell;
    private StyledText host;
    private FloatingWindows floats;
    /** What reached Neovim's mouse: {@code grid button action modifier row col}. */
    private final List<String> sent = new ArrayList<>();

    @BeforeEach
    void create() {
        shell = new Shell(Display.getDefault());
        host = new StyledText(shell, SWT.MULTI);
        host.setSize(600, 300);
        floats = new FloatingWindows(host, (grid, button, action, modifier, row, col) -> sent
                .add(grid + " " + button + " " + action + " " + modifier + " " + row + " " + col));
    }

    @AfterEach
    void dispose() {
        shell.dispose();
    }

    private static FloatWindow window(int win, int columns, int rows, int row, int col, int zindex) {
        GridSnapshot grid = new GridSnapshot(win, columns, rows, Collections.nCopies(columns * rows, "x"),
                new int[columns * rows], Map.of(), new GridEvent.DefaultColors(GridEvent.NO_COLOR, GridEvent.NO_COLOR), -1, -1);
        return new FloatWindow(grid, row, col, 10, 40, zindex);
    }

    /** {@code window(...)} with Neovim's cursor at {@code cursorRow}, {@code cursorCol} of its grid. */
    private static FloatWindow withCursor(int win, int cursorRow, int cursorCol, boolean fromBottom) {
        GridSnapshot grid = new GridSnapshot(win, 6, 2, Collections.nCopies(12, "x"), new int[12], Map.of(),
                new GridEvent.DefaultColors(GridEvent.NO_COLOR, GridEvent.NO_COLOR), cursorRow, cursorCol);
        return new FloatWindow(grid, 3, 4, 10, 40, 50, fromBottom);
    }

    /**
     * A picker's prompt with Neovim's cursor in it: drawn with the cursor, a bar in Insert mode
     * (also on a canvas made after the mode came) and a block otherwise; it still never takes the
     * focus, and only a plug-in's window counts as one the editor hides its caret for.
     */
    @Test
    void aWindowWithTheCursorDrawsItAsABarInInsertModeAndCountsAsFocused() {
        floats.setInsertMode(true);
        floats.show(List.of(window(1002, 6, 2, 0, 0, 40), withCursor(1003, 0, 2, false)));
        GridCanvas prompt = floats.canvas(1003);
        assertTrue(prompt.isInsertCursor(), "a canvas made in Insert mode draws a bar");
        assertTrue((prompt.getStyle() & SWT.NO_FOCUS) != 0, "the keys stay the editor's, which sends them to Neovim");
        assertTrue(floats.hasFocusedFloat());
        assertNotNull(floats.cursorCell());

        floats.setInsertMode(false); // Esc in the prompt
        assertFalse(prompt.isInsertCursor(), "a block in normal mode");
        assertFalse(floats.canvas(1002).isInsertCursor());

        floats.show(List.of(window(1002, 6, 2, 0, 0, 40), window(1003, 6, 2, 3, 4, 50)));
        assertFalse(floats.hasFocusedFloat(), "the cursor left it: the editor shows its caret again");
        floats.show(List.of(withCursor(1004, 0, 4, true)));
        assertFalse(floats.hasFocusedFloat(), "ui2's line with the cursor is Neovim's command line, not a plug-in's window");
        floats.clear();
        assertFalse(floats.hasFocusedFloat());
    }

    /** A mouse event at cell ({@code row}, {@code col}) of {@code canvas}; returns it, for its {@code doit}. */
    private static Event mouse(GridCanvas canvas, int type, int row, int col, int count) {
        Event e = new Event();
        e.button = type == SWT.MouseVerticalWheel ? 0 : 1;
        e.x = col * canvas.cellWidth() + 1;
        e.y = row * canvas.cellHeight() + 1;
        e.count = count;
        e.doit = true;
        canvas.notifyListeners(type, e);
        return e;
    }

    /**
     * The left button and the wheel go to the window's own grid, at its cell. A window that takes
     * no mouse ignores them, and neither scrolls the editor below.
     */
    @Test
    void theMouseOnAWindowGoesToItsGridAndOneThatTakesNoneIgnoresIt() {
        FloatWindow plain = window(1002, 6, 2, 3, 4, 50);
        FloatWindow clickable = new FloatWindow(plain.grid(), 3, 4, 10, 40, 50, false, 7);
        floats.show(List.of(clickable, window(1003, 6, 2, 0, 20, 60)));
        GridCanvas canvas = floats.canvas(1002);
        mouse(canvas, SWT.MouseDown, 1, 2, 1);
        mouse(canvas, SWT.MouseUp, 1, 2, 1);
        Event wheel = mouse(canvas, SWT.MouseVerticalWheel, 1, 2, -3);
        assertEquals(List.of("7 left press  1 2", "7 left release  1 2", "7 wheel down  1 2"), sent);
        assertFalse(wheel.doit, "the editor below does not scroll too");

        sent.clear();
        GridCanvas noMouse = floats.canvas(1003);
        mouse(noMouse, SWT.MouseDown, 0, 0, 1);
        mouse(noMouse, SWT.MouseUp, 0, 0, 1);
        assertFalse(mouse(noMouse, SWT.MouseVerticalWheel, 0, 0, 3).doit, "nor under one that takes none");
        assertEquals(List.of(), sent);

        sent.clear();
        for (int held : new int[] { SWT.CTRL, SWT.MOD1 }) { // Eclipse's hyperlink keys, as in the text
            Event press = new Event();
            press.button = 1;
            press.stateMask = held;
            canvas.notifyListeners(SWT.MouseDown, press);
            Event wheelHeld = new Event();
            wheelHeld.count = 3;
            wheelHeld.stateMask = held;
            wheelHeld.doit = true;
            canvas.notifyListeners(SWT.MouseVerticalWheel, wheelHeld);
            assertFalse(wheelHeld.doit);
        }
        assertEquals(List.of(), sent, "Ctrl+click is no Vim tag jump here");

        floats.show(List.of(plain)); // the plug-in turned the window's mouse off
        mouse(canvas, SWT.MouseDown, 0, 0, 1);
        assertEquals(List.of(), sent);
    }

    @Test
    void aWindowGetsACanvasWhereNeovimPutIt() {
        floats.show(List.of(window(1002, 6, 2, 3, 4, 50)));
        GridCanvas canvas = floats.canvas(1002);
        assertNotNull(canvas);
        assertSame(host, canvas.getParent(), "a child of the editor's text: its edges cut the window off");
        Rectangle area = host.getClientArea();
        assertEquals(new Rectangle(area.x + 4 * canvas.cellWidth(), area.y + 3 * canvas.cellHeight(), 6 * canvas.cellWidth(),
                2 * canvas.cellHeight()), canvas.getBounds());
        assertEquals("xxxxxx", canvas.grid().row(0));
        assertTrue((canvas.getStyle() & SWT.NO_FOCUS) != 0, "it only shows: the keys stay the editor's");
    }

    @Test
    void aWindowOnTheLastRowSitsOnTheEditorsBottomEdgeAlsoAfterAResize() {
        floats.show(List.of(window(1002, 40, 2, 8, 0, 50)));
        GridCanvas canvas = floats.canvas(1002);
        Rectangle area = host.getClientArea();
        assertEquals(area.y + area.height, canvas.getBounds().y + canvas.getBounds().height);
        host.setSize(600, 420);
        area = host.getClientArea();
        assertEquals(area.y + area.height, canvas.getBounds().y + canvas.getBounds().height, "placed again at a resize");
    }

    /**
     * {@code StyledText} moves its children along when its text scrolls. A float does not belong to
     * the text: the editor's next paint puts it back where Neovim has it.
     */
    @Test
    void aFloatStaysWhereItIsWhenTheTextScrolls() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 200; i++) {
            text.append("line ").append(i).append('\n');
        }
        host.setText(text.toString());
        floats.show(List.of(window(1002, 40, 2, 8, 0, 50)));
        GridCanvas canvas = floats.canvas(1002);
        Rectangle before = canvas.getBounds();
        host.setTopPixel(5 * host.getLineHeight());
        assertEquals(before.y - 5 * host.getLineHeight(), canvas.getBounds().y, "SWT took the float along with the text");
        Event paint = new Event(); // the paint a scroll brings; sent by hand, since this shell is never shown
        paint.gc = new GC(host);
        try {
            host.notifyListeners(SWT.Paint, paint);
        } finally {
            paint.gc.dispose();
        }
        assertEquals(before, canvas.getBounds());
    }

    @Test
    void windowsLieInNeovimsOrderUnderTheEditorsOtherChildren() {
        Label overlay = new Label(host, SWT.NONE); // stands for the : box or the message line
        floats.show(List.of(window(1002, 6, 2, 3, 4, 50), window(1003, 6, 2, 3, 4, 1000)));
        List<Control> topFirst = Arrays.asList(host.getChildren());
        assertEquals(List.of(overlay, floats.canvas(1003), floats.canvas(1002)), topFirst,
                "SWT lists children top first: the overlay, the higher zindex, the lower");
    }

    /**
     * The status line's row lies under every window, as Neovim draws a float over a status line:
     * with {@code 'cmdheight'} 0 ui2's {@code :} line lies on it. Also for windows drawn later.
     */
    @Test
    void windowsLieOverTheStatusLinesRow() {
        Label statusRow = new Label(host, SWT.NONE);
        floats.keepUnder(statusRow);
        floats.show(List.of(window(1002, 6, 2, 3, 4, 50)));
        floats.show(List.of(window(1002, 6, 2, 3, 4, 50), window(1003, 6, 2, 3, 4, 200)));
        assertEquals(List.of(floats.canvas(1003), floats.canvas(1002), statusRow), Arrays.asList(host.getChildren()),
                "top first: the windows, then the status line's row");
    }

    @Test
    void aRedrawnWindowKeepsItsCanvasAndAGoneOneLosesIt() {
        floats.show(List.of(window(1002, 6, 2, 3, 4, 50), window(1003, 6, 2, 5, 4, 60)));
        GridCanvas first = floats.canvas(1002);
        GridCanvas second = floats.canvas(1003);
        floats.show(List.of(window(1002, 8, 2, 3, 4, 50)));
        assertSame(first, floats.canvas(1002));
        assertEquals(8 * first.cellWidth(), first.getBounds().width, "its new size");
        assertNull(floats.canvas(1003));
        assertTrue(second.isDisposed());
        floats.clear();
        assertFalse(floats.isShowing());
        assertTrue(first.isDisposed());
        assertEquals(0, host.getChildren().length);
    }

    @Test
    void disposingLeavesNothingBehindAndIsHarmlessAfterTheEditorWentAway() {
        floats.show(List.of(window(1002, 6, 2, 3, 4, 50)));
        floats.dispose();
        assertEquals(0, host.getChildren().length);
        floats.show(List.of(window(1002, 6, 2, 3, 4, 50)));
        host.dispose();
        floats.dispose(); // the canvases went with the editor
        floats.show(List.of(window(1004, 6, 2, 3, 4, 50))); // a late report: nothing to draw on
        assertFalse(floats.isShowing());
    }
}
