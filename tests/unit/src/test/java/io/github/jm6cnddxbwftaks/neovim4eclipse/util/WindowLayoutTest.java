// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WindowLayout.Group;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WindowLayout.Metrics;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WindowLayout.Rect;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WindowLayout.Sizes;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WindowLayout.Window;

/**
 * Vim's window rules on a picture of the editor area. Most tests use this layout, with a 4-pixel
 * sash between the editors, as Eclipse draws one:
 *
 * <pre>
 *   x: 0    100 104  300
 *      +-----+  +-----+  y 0
 *      |     |  |  B  |
 *      |  A  |  +-----+  y 100 / 104
 *      |     |  |  C  |
 *      +-----+  +-----+  y 300
 * </pre>
 *
 * Group 0 is the row, group 2 the column; the windows are A = 1, B = 3, C = 4.
 */
class WindowLayoutTest {

    private static final int A = 1;
    private static final int B = 3;
    private static final int C = 4;
    private static final Metrics METRICS = new Metrics(10, 5, 80, 180);

    private static Group layout() {
        return new Group(0, true, List.of(
                new Window(A, new Rect(0, 0, 100, 300)),
                new Group(2, false, List.of(
                        new Window(B, new Rect(104, 0, 196, 100)),
                        new Window(C, new Rect(104, 104, 196, 196))))));
    }

    private static int focus(int current, char key, int count, int caretY) {
        return WindowLayout.focus(layout(), current, -1, key, count, 50, caretY);
    }

    @Test
    void theOrderIsVimsDepthFirst() {
        assertEquals(List.of(A, B, C), WindowLayout.windows(layout()).stream().map(Window::id).toList());
    }

    @Test
    void hjklGoToTheNeighborLevelWithTheCaret() {
        assertEquals(B, focus(A, 'l', 0, 50), "the caret is level with B");
        assertEquals(C, focus(A, 'l', 0, 250), "and here with C");
        assertEquals(A, focus(B, 'h', 0, 50));
        assertEquals(A, focus(C, 'h', 0, 250));
        assertEquals(C, focus(B, 'j', 0, 50));
        assertEquals(B, focus(C, 'k', 0, 150));
    }

    @Test
    void hjklStayWhenThereIsNothingThatWay() {
        assertEquals(A, focus(A, 'h', 0, 50));
        assertEquals(A, focus(A, 'k', 0, 50));
        assertEquals(B, focus(B, 'l', 0, 50));
        assertEquals(B, focus(B, 'k', 0, 50));
        assertEquals(C, focus(C, 'j', 0, 250));
    }

    @Test
    void aCountGoesThatManyWindowsOnAndStopsAtTheEdge() {
        Group three = new Group(0, true, List.of(
                new Window(1, new Rect(0, 0, 100, 100)),
                new Window(2, new Rect(104, 0, 100, 100)),
                new Window(3, new Rect(208, 0, 100, 100))));
        assertEquals(3, WindowLayout.focus(three, 1, -1, 'l', 2, 0, 50));
        assertEquals(3, WindowLayout.focus(three, 1, -1, 'l', 9, 0, 50), "no further than the last");
    }

    /** A window above, not beside, is not to the left, even if it reaches further left. */
    @Test
    void onlyWindowsBesideTheCurrentOneCount() {
        Group layout = new Group(0, false, List.of(
                new Window(1, new Rect(0, 0, 300, 100)),
                new Group(2, true, List.of(
                        new Window(3, new Rect(0, 104, 150, 100)),
                        new Window(4, new Rect(154, 104, 146, 100))))));
        assertEquals(3, WindowLayout.focus(layout, 4, -1, 'h', 0, 200, 150));
        assertEquals(3, WindowLayout.focus(layout, 3, -1, 'h', 0, 50, 150), "nothing left of 3");
        assertEquals(1, WindowLayout.focus(layout, 4, -1, 'k', 0, 200, 150));
        assertEquals(4, WindowLayout.focus(layout, 1, -1, 'j', 0, 250, 50), "below the caret's column");
        assertEquals(3, WindowLayout.focus(layout, 1, -1, 'j', 0, 20, 50));
    }

    @Test
    void wAndWGoRoundInOrderAndACountNamesTheWindow() {
        assertEquals(B, focus(A, 'w', 0, 0));
        assertEquals(A, focus(C, 'w', 0, 0), "wrapping");
        assertEquals(C, focus(A, 'W', 0, 0), "back, wrapping");
        assertEquals(B, focus(C, 'w', 2, 0), "window 2");
        assertEquals(C, focus(A, 'W', 7, 0), "the last when there are fewer");
        assertEquals(A, focus(C, 't', 0, 0));
        assertEquals(C, focus(A, 'b', 0, 0));
    }

    @Test
    void pGoesToThePreviousWindowWhileItIsOnScreen() {
        assertEquals(C, WindowLayout.focus(layout(), A, C, 'p', 0, 0, 0));
        assertEquals(A, WindowLayout.focus(layout(), A, 99, 'p', 0, 0, 0), "closed since");
        assertEquals(A, WindowLayout.focus(layout(), A, -1, 'p', 0, 0, 0), "none yet");
    }

    @Test
    void aGrowingWindowTakesFromTheOnesAfterItThenBefore() {
        assertArrayEquals(new int[] { 180, 30, 90 }, WindowLayout.resized(new int[] { 100, 100, 100 }, 0, 180, 30));
        assertArrayEquals(new int[] { 30, 240, 30 }, WindowLayout.resized(new int[] { 100, 100, 100 }, 1, 999, 30),
                "as large as it can be: the others keep their minimum");
        assertArrayEquals(new int[] { 70, 30, 200 }, WindowLayout.resized(new int[] { 100, 100, 100 }, 2, 200, 30),
                "the last one takes from those before it, nearest first");
    }

    @Test
    void aShrinkingWindowGivesToTheNextOneOrThePrevious() {
        assertArrayEquals(new int[] { 60, 140, 100 }, WindowLayout.resized(new int[] { 100, 100, 100 }, 0, 60, 30));
        assertArrayEquals(new int[] { 100, 160, 40 }, WindowLayout.resized(new int[] { 100, 100, 100 }, 2, 40, 30));
        assertArrayEquals(new int[] { 170, 30 }, WindowLayout.resized(new int[] { 100, 100 }, 1, 0, 30),
                "never below the minimum");
    }

    @Test
    void plusAndMinusChangeTheHeightInLinesInTheColumn() {
        assertEquals(List.of(new Sizes(2, new int[] { 130, 166 })),
                WindowLayout.resize(layout(), B, '+', 3, METRICS, 20), "3 lines of 10 pixels");
        assertEquals(List.of(new Sizes(2, new int[] { 90, 206 })),
                WindowLayout.resize(layout(), B, '-', 0, METRICS, 20), "one line without a count");
        assertEquals(List.of(), WindowLayout.resize(layout(), A, '+', 1, METRICS, 20), "A has no window above or below");
    }

    /** {@code 10 Ctrl-W _}: ten lines of text; the editor's text is 80 of its 100 pixels now. */
    @Test
    void underscoreAndBarSetTheSizeOrMakeItAsLargeAsItCanBe() {
        assertEquals(List.of(new Sizes(2, new int[] { 120, 176 })),
                WindowLayout.resize(layout(), B, '_', 10, METRICS, 20));
        assertEquals(List.of(new Sizes(2, new int[] { 276, 20 })),
                WindowLayout.resize(layout(), B, '_', 0, METRICS, 20));
        assertEquals(List.of(new Sizes(0, new int[] { 276, 20 })),
                WindowLayout.resize(layout(), A, '|', 0, METRICS, 20), "the width, in the row");
        assertEquals(List.of(new Sizes(0, new int[] { 90, 206 })),
                WindowLayout.resize(layout(), C, '>', 2, METRICS, 20),
                "from C: the row that holds its column; nothing after it, so A gives the space");
    }

    @Test
    void equalMakesEveryRowAndColumnShareEqually() {
        assertEquals(List.of(new Sizes(0, new int[] { 148, 148 }), new Sizes(2, new int[] { 148, 148 })),
                WindowLayout.resize(layout(), B, '=', 0, METRICS, 20));
    }

    @Test
    void xSwapsWithTheNextWindowOrThePreviousOneOrTheNth() {
        assertEquals(C, WindowLayout.exchangeWith(layout(), B, 0));
        assertEquals(B, WindowLayout.exchangeWith(layout(), C, 0), "the last swaps with the one before");
        assertEquals(B, WindowLayout.exchangeWith(layout(), C, 1));
        assertEquals(-1, WindowLayout.exchangeWith(layout(), A, 0), "the next frame is a column: Vim does nothing");
        assertEquals(-1, WindowLayout.exchangeWith(new Window(1, new Rect(0, 0, 1, 1)), 1, 0), "a window alone");
    }

    @Test
    void rOrRMovesTheWindowsOfARowOnePlaceRound() {
        Group three = new Group(0, true, List.of(
                new Window(1, new Rect(0, 0, 100, 100)),
                new Window(2, new Rect(104, 0, 100, 100)),
                new Window(3, new Rect(208, 0, 100, 100))));
        assertEquals(List.of(3, 1, 2), WindowLayout.rotated(three, 1, true, 0));
        assertEquals(List.of(2, 3, 1), WindowLayout.rotated(three, 1, false, 0));
        assertEquals(List.of(2, 3, 1), WindowLayout.rotated(three, 2, true, 2), "twice");
        assertEquals(List.of(C, B), WindowLayout.rotated(layout(), B, true, 1));
    }

    @Test
    void rotatingARowThatHoldsASplitIsVimsE443() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> WindowLayout.rotated(layout(), A, true, 0));
        assertEquals("E443: Cannot rotate when another window is split", e.getMessage());
    }
}
