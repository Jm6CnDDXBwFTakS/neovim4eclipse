// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The popup that shows what a listing command printed ({@code :ls}, {@code :marks}), on a real
 * {@link StyledText}: what it shows, where it sits, which keys scroll it, which close it, and how
 * {@code :} keeps it open for the next command. No workbench or Neovim is needed.
 *
 * <p>Keys are sent as synthetic SWT events straight to the popup's text
 * ({@code notifyListeners(SWT.KeyDown, ...)}), so they reach the popup's own key listener without
 * a real keyboard. Whether a key was handled by the popup shows in {@code event.doit}: the popup
 * sets it to {@code false} for the keys it acts on, and leaves it {@code true} for the ones
 * {@code StyledText} handles itself (arrows, Page Up/Down, Ctrl+C).
 */
class OutputPopupTest {

    private static final int HOST_WIDTH = 400;
    private static final int HOST_HEIGHT = 300;

    private Shell shell;
    private StyledText host;
    /** How often {@code :} asked for the command line. */
    private int colonPresses;
    private OutputPopup popup;

    @BeforeEach
    void createHost() {
        shell = new Shell(Display.getDefault());
        shell.setSize(HOST_WIDTH, HOST_HEIGHT);
        host = new StyledText(shell, SWT.MULTI);
        host.setBounds(0, 0, HOST_WIDTH, HOST_HEIGHT);
        shell.open();
        popup = new OutputPopup(host, () -> colonPresses++);
    }

    @AfterEach
    void dispose() {
        popup.close(false);
        shell.dispose();
    }

    /** The popup's text: the one {@link StyledText} inside the host (the host is one itself). */
    private StyledText view() {
        List<StyledText> found = new ArrayList<>();
        collect(host, found);
        found.remove(host);
        assertTrue(found.size() <= 1, "at most one popup at a time: " + found.size());
        return found.isEmpty() ? null : found.get(0);
    }

    private static void collect(Control control, List<StyledText> found) {
        if (control instanceof StyledText text) {
            found.add(text);
        }
        if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                collect(child, found);
            }
        }
    }

    /** The bounds of the popup's frame (its border), in the host's coordinates. */
    private Rectangle frameBounds() {
        return view().getParent().getBounds();
    }

    /** Sends a key press to the popup; returns the event, whose {@code doit} says who handles it. */
    private Event press(int keyCode, char character, int stateMask) {
        Event event = new Event();
        event.keyCode = keyCode;
        event.character = character;
        event.stateMask = stateMask;
        event.doit = true;
        view().notifyListeners(SWT.KeyDown, event);
        return event;
    }

    private Event type(char character) {
        return press(character, character, 0);
    }

    /** A listing long enough to scroll in the popup: "line 1" ... "line {@code lines}". */
    private static String lines(int lines) {
        return IntStream.rangeClosed(1, lines).mapToObj(i -> "line " + i).collect(Collectors.joining("\n"));
    }

    // What it shows

    @Test
    void showsTheCommandTheOutputAndThePrompt() {
        popup.show(":ls", "  1 %a   \"a.txt\"\n  2      \"b.txt\"\n\n");
        assertTrue(popup.isOpen());
        // Trailing blank lines of the output are dropped; the prompt follows at once, as in Vim.
        assertEquals(":ls\n  1 %a   \"a.txt\"\n  2      \"b.txt\"\nPress any key to continue", view().getText());
    }

    @Test
    void blankOrMissingOutputShowsNothing() {
        popup.show(":echo ''", "   \n");
        popup.show(":echo ''", null);
        assertFalse(popup.isOpen());
        assertNull(view());
    }

    @Test
    void nothingIsShownOnADisposedEditor() {
        host.dispose();
        popup.show(":ls", "output");
        assertFalse(popup.isOpen());
    }

    @Test
    void aSecondListingReplacesTheFirst() {
        popup.show(":ls", "first");
        popup.show(":marks", "second");
        assertTrue(view().getText().startsWith(":marks\nsecond"), view().getText()); // view() checks there is one
    }

    /** Output that no typed command printed (a timer's) has no title line. */
    @Test
    void withoutATitleTheOutputIsTheFirstLine() {
        popup.show("", "a\nb");
        assertEquals("a\nb\nPress any key to continue", view().getText());
    }

    /** {@code :!make}'s output arrives again, whole, as it grows: the open popup is updated in place. */
    @Test
    void growingOutputUpdatesTheOpenPopupAndKeepsWhereItWasScrolled() {
        popup.show(":!make", lines(100));
        StyledText first = view();
        first.setTopIndex(10);

        popup.show(":!make", lines(150));

        assertSame(first, view(), "the same popup, not a new one");
        assertTrue(view().getText().contains("line 150"), view().getText());
        assertEquals(10, view().getTopIndex());
    }

    // Where it sits

    @Test
    void sitsAlongTheBottomOfTheEditorInsideItsSidePadding() {
        popup.show(":ls", "one line");
        Rectangle area = host.getClientArea();
        Rectangle bounds = frameBounds();
        assertEquals(area.x + OverlayFrame.SIDE_PADDING, bounds.x);
        assertEquals(area.width - 2 * OverlayFrame.SIDE_PADDING, bounds.width);
        assertEquals(area.y + area.height - OverlayFrame.bottomGap(host), bounds.y + bounds.height);
    }

    @Test
    void longOutputCoversAtMostSixtyPercentOfTheEditor() {
        popup.show(":marks", lines(200));
        assertTrue(frameBounds().height <= host.getClientArea().height * 0.6, "height " + frameBounds().height);
    }

    @Test
    void followsTheEditorWhenItIsResized() {
        popup.show(":ls", "one line");
        int before = frameBounds().y;
        host.setSize(HOST_WIDTH, HOST_HEIGHT - 100); // the resize listener moves the popup up
        assertEquals(before - 100, frameBounds().y);
    }

    @Test
    void makeRoomBelowMovesThePopupUpForTheCommandLine() {
        popup.show(":ls", "one line");
        int before = frameBounds().y;
        popup.makeRoomBelow(30);
        assertEquals(before - 30, frameBounds().y);
    }

    @Test
    void makeRoomBelowDoesNothingWhileClosed() {
        popup.makeRoomBelow(30);
        assertFalse(popup.isOpen());
    }

    // Keys

    @Test
    void jAndKScrollOneLineAndKStopsAtTheTop() {
        popup.show(":marks", lines(200));
        assertFalse(type('j').doit);
        assertEquals(1, view().getTopIndex());
        type('k');
        type('k');
        assertEquals(0, view().getTopIndex());
        assertTrue(popup.isOpen());
    }

    @Test
    void spaceAndFScrollAPageAndBScrollsBack() {
        popup.show(":marks", lines(200));
        type(' ');
        int page = view().getTopIndex();
        assertTrue(page > 1, "a page is more than one line: " + page);
        type('f');
        assertEquals(2 * page, view().getTopIndex());
        type('b');
        assertEquals(page, view().getTopIndex());
        type('b');
        type('b');
        assertEquals(0, view().getTopIndex());
    }

    @Test
    void capitalGGoesToTheEndAndGToTheStart() {
        popup.show(":marks", lines(200));
        type('G');
        assertNotEquals(0, view().getTopIndex());
        type('g');
        assertEquals(0, view().getTopIndex());
    }

    @Test
    void arrowsPagesAndCopyAreLeftToTheTextWidget() {
        popup.show(":marks", lines(200));
        for (int key : new int[] {SWT.ARROW_UP, SWT.ARROW_DOWN, SWT.ARROW_LEFT, SWT.ARROW_RIGHT, SWT.PAGE_UP,
                SWT.PAGE_DOWN, SWT.HOME, SWT.END}) {
            assertTrue(press(key, (char) 0, 0).doit, "key " + key);
        }
        assertTrue(press('c', (char) 3, SWT.MOD1).doit, "Ctrl+C / Cmd+C copies");
        assertTrue(popup.isOpen());
    }

    @Test
    void aModifierPressedOnItsOwnDoesNotClose() {
        popup.show(":ls", "output");
        press(SWT.CTRL, (char) 0, 0);
        press(SWT.SHIFT, (char) 0, 0);
        assertTrue(popup.isOpen());
    }

    @Test
    void anyOtherKeyClosesThePopup() {
        popup.show(":ls", "output");
        assertFalse(type('x').doit);
        assertFalse(popup.isOpen());
        assertNull(view());
    }

    @Test
    void colonOpensTheCommandLineAndKeepsThePopupWhenItLosesTheFocus() {
        popup.show(":ls", "output");
        assertFalse(type(':').doit);
        assertEquals(1, colonPresses);
        view().notifyListeners(SWT.FocusOut, new Event()); // the command line box takes the focus
        assertTrue(popup.isOpen());
    }

    @Test
    void losingTheFocusOtherwiseClosesIt() {
        popup.show(":ls", "output");
        view().notifyListeners(SWT.FocusOut, new Event()); // a click elsewhere
        assertFalse(popup.isOpen());
    }

    @Test
    void closingTwiceIsHarmless() {
        popup.show(":ls", "output");
        popup.close(true);
        popup.close(true);
        assertFalse(popup.isOpen());
        assertFalse(host.isDisposed()); // the editor itself is untouched
    }
}
