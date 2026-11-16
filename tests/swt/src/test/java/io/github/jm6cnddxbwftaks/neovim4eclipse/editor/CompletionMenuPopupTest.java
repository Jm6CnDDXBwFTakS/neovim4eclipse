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
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CompletionMenu;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CompletionMenu.Item;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CompletionMenu.View;

/**
 * {@link CompletionMenuPopup} on a real {@code StyledText}: the list at the word being completed,
 * below its line or above it, never with the focus, and gone when the list is. What the cells
 * hold is {@code CompletionMenuTest}.
 */
class CompletionMenuPopupTest {

    private Shell shell;
    private StyledText host;
    private CompletionMenuPopup popup;
    /** Where the test says the word starts; -1 as the editor answers for a line that is folded away. */
    private int wordStart;

    @BeforeEach
    void create() {
        shell = new Shell(Display.getDefault());
        host = new StyledText(shell, SWT.MULTI);
        host.setSize(600, 300);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            text.append("line ").append(i).append(" alpha\n");
        }
        host.setText(text.toString());
        popup = new CompletionMenuPopup(host, view -> wordStart);
    }

    @AfterEach
    void dispose() {
        shell.dispose();
    }

    private static View list(int selected, int count) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(new Item("alpha" + i, "", ""));
        }
        return new View(items, selected, 1, 0, 0, 0, CompletionMenu.Colors.PLAIN);
    }

    /** The offset of the word on line {@code line} (0-based): after "line N ". */
    private int wordOn(int line) {
        return host.getOffsetAtLine(line) + ("line " + line + " ").length();
    }

    @Test
    void theListHangsUnderTheWordOneCellToItsLeft() {
        wordStart = wordOn(2);
        popup.show(list(0, 3));
        GridCanvas canvas = popup.canvas();
        assertNotNull(canvas);
        assertTrue(popup.isShowing());
        assertSame(host, popup.control().getParent(), "a child of the editor's text: it moves with the text");
        Point word = host.getLocationAtOffset(wordStart);
        Rectangle bounds = popup.control().getBounds();
        assertEquals(word.x - canvas.cellWidth() - CompletionMenuPopup.BORDER, bounds.x, "the entries' text lines up with the word");
        assertEquals(word.y + host.getLineHeight(wordStart), bounds.y, "under the word's line");
        assertEquals(3 * canvas.cellHeight() + 2 * CompletionMenuPopup.BORDER, bounds.height);
        assertEquals(" alpha0 ", canvas.grid().row(0));
        assertTrue((canvas.getStyle() & SWT.NO_FOCUS) != 0, "it only shows: the keys stay the editor's");
    }

    /**
     * A frame around the cells, in a color made from the editor's: a list whose background
     * is nearly the editor's (Neovim's default {@code Pmenu} over a dark theme) still stands out.
     */
    @Test
    void theListHasAFrameThatDiffersFromTheEditorsBackground() {
        wordStart = wordOn(2);
        popup.show(list(0, 3));
        GridCanvas canvas = popup.canvas();
        assertSame(popup.control(), canvas.getParent(), "the canvas is the frame's content");
        Rectangle frame = popup.control().getBounds();
        Rectangle cells = canvas.getBounds();
        assertEquals(new Rectangle(CompletionMenuPopup.BORDER, CompletionMenuPopup.BORDER, frame.width - 2 * CompletionMenuPopup.BORDER,
                frame.height - 2 * CompletionMenuPopup.BORDER), cells, "the frame on each side");
        assertEquals(canvas.grid().width() * canvas.cellWidth(), cells.width, "no cell is cut by the frame");
        org.eclipse.swt.graphics.RGB border = popup.control().getBackground().getRGB();
        assertFalse(border.equals(host.getBackground().getRGB()), "the border shows on the editor: " + border);
        assertTrue((popup.control().getStyle() & SWT.NO_FOCUS) != 0, "the frame takes no focus either");
    }

    /** No room under the last visible lines: the list goes above the word's line and ends on it. */
    @Test
    void nearTheBottomTheListGoesAboveTheLine() {
        int lastVisible = host.getClientArea().height / host.getLineHeight() - 1;
        wordStart = wordOn(lastVisible);
        popup.show(list(0, 5));
        Rectangle bounds = popup.control().getBounds();
        Point word = host.getLocationAtOffset(wordStart);
        assertEquals(word.y, bounds.y + bounds.height, "its frame sits on the line");
        assertEquals(5 * popup.canvas().cellHeight() + 2 * CompletionMenuPopup.BORDER, bounds.height);
    }

    /** More entries than rows fit: the list is cut, gets a scrollbar column, and scrolls to the selection. */
    @Test
    void aListLongerThanTheRoomIsCutAndScrollsToTheSelection() {
        wordStart = wordOn(0);
        int room = (host.getClientArea().height - host.getLineHeight() - 2 * CompletionMenuPopup.BORDER) / host.getLineHeight();
        popup.show(list(0, room + 20));
        GridCanvas canvas = popup.canvas();
        assertEquals(room, canvas.grid().height());
        assertEquals(" alpha0   ", canvas.grid().row(0), "a scrollbar cell after the padding; the width fits the longest entry");
        popup.show(list(room + 5, room + 20));
        assertEquals(room, canvas.grid().height());
        assertTrue(canvas.grid().row(room - 1).startsWith(" alpha" + (room + 5)), "the selection is the last row shown: "
                + canvas.grid().row(room - 1));
        assertEquals(CompletionMenu.PMENU_SEL, canvas.grid().hlAt(room - 1, 1));
    }

    /** 'pumheight' limits the rows though more would fit. */
    @Test
    void pumheightLimitsTheRows() {
        wordStart = wordOn(0);
        View view = list(0, 8);
        popup.show(new View(view.items(), 0, 1, 0, 0, 4, CompletionMenu.Colors.PLAIN));
        assertEquals(4, popup.canvas().grid().height());
    }

    @Test
    void aMoveOfTheSelectionRedrawsInTheSamePlace() {
        wordStart = wordOn(2);
        popup.show(list(0, 3));
        GridCanvas canvas = popup.canvas();
        Rectangle before = popup.control().getBounds();
        popup.show(list(1, 3));
        assertSame(canvas, popup.canvas(), "the same canvas");
        assertEquals(before, popup.control().getBounds());
        assertEquals(CompletionMenu.PMENU_SEL, canvas.grid().hlAt(1, 1));
        assertEquals(CompletionMenu.PMENU, canvas.grid().hlAt(0, 1));
    }

    @Test
    void aWordAtTheRightEdgeKeepsTheListInsideTheEditor() {
        host.setText("x".repeat(400));
        wordStart = 390;
        host.setTopIndex(0);
        host.setHorizontalPixel(Math.max(0, host.getLocationAtOffset(390).x - host.getClientArea().width + 20));
        popup.show(list(0, 2));
        Rectangle bounds = popup.control().getBounds();
        Rectangle area = host.getClientArea();
        assertEquals(area.x + area.width, bounds.x + bounds.width, "moved left so that it ends at the edge");
    }

    /** The word's line is folded away, or the document changed under the list: nothing is drawn until the word is back. */
    @Test
    void aWordTheEditorCannotPlaceHidesTheList() {
        wordStart = -1;
        popup.show(list(0, 3));
        assertFalse(popup.isShowing());
        wordStart = wordOn(1);
        host.notifyListeners(SWT.Paint, paintEvent());
        assertTrue(popup.isShowing(), "placed at the editor's next paint");
    }

    /** The editor is resized while the list shows: the room below changes, and the list is laid out again. */
    @Test
    void aResizeLaysTheListOutAgain() {
        wordStart = wordOn(3);
        popup.show(list(0, 30));
        int before = popup.canvas().grid().height();
        host.setSize(600, 600);
        assertTrue(popup.canvas().grid().height() > before, "more rows in a taller editor");
    }

    @Test
    void aHiddenViewRemovesTheList() {
        wordStart = wordOn(2);
        popup.show(list(0, 3));
        GridCanvas canvas = popup.canvas();
        popup.show(View.HIDDEN);
        assertTrue(canvas.isDisposed());
        assertNull(popup.canvas());
        assertFalse(popup.isShowing());
        popup.hide(); // again: nothing to do
    }

    /** A list that comes after another starts at its top, whatever the one before was scrolled to. */
    @Test
    void aNewListStartsAtItsTop() {
        wordStart = wordOn(0);
        int room = (host.getClientArea().height - host.getLineHeight() - 2 * CompletionMenuPopup.BORDER) / host.getLineHeight();
        popup.show(list(room + 10, room + 20));
        popup.hide();
        popup.show(list(-1, room + 20));
        assertTrue(popup.canvas().grid().row(0).startsWith(" alpha0 "), popup.canvas().grid().row(0));
    }

    @Test
    void theWheelOverTheListDoesNothing() {
        wordStart = wordOn(2);
        popup.show(list(0, 3));
        Event wheel = new Event();
        wheel.count = -3;
        popup.canvas().notifyListeners(SWT.MouseVerticalWheel, wheel);
        assertFalse(wheel.doit, "not passed on to the editor under it");
    }

    @Test
    void disposingTheEditorTakesTheListWithIt() {
        wordStart = wordOn(2);
        popup.show(list(0, 3));
        GridCanvas canvas = popup.canvas();
        host.dispose();
        assertTrue(canvas.isDisposed());
        popup.dispose(); // after its editor: must not touch the disposed widget
        popup.show(list(0, 3));
    }

    private Event paintEvent() {
        Event event = new Event();
        event.gc = new org.eclipse.swt.graphics.GC(host);
        event.width = host.getSize().x;
        event.height = host.getSize().y;
        return event;
    }
}
