// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WheelScroll;

/**
 * {@link NeovimWheel}: the vertical wheel in a real {@link StyledText} goes to Neovim as lines and
 * does not scroll the widget, unless Eclipse keeps it (insert mode, a modifier). The display filter
 * sees {@code notifyListeners} events as it sees real ones.
 */
class NeovimWheelTest {

    private final List<Integer> sent = new ArrayList<>();
    private boolean forward = true;
    private Shell shell;
    private StyledText text;
    private NeovimWheel wheel;

    @BeforeEach
    void create() {
        shell = new Shell(Display.getDefault());
        shell.setLayout(new FillLayout());
        text = new StyledText(shell, SWT.V_SCROLL);
        StringBuilder lines = new StringBuilder();
        for (int i = 1; i <= 200; i++) {
            lines.append("line ").append(i).append('\n');
        }
        text.setText(lines.toString());
        shell.setSize(400, 300);
        shell.layout();
        wheel = new NeovimWheel(text, () -> forward, () -> 40, sent::add);
    }

    @AfterEach
    void dispose() {
        wheel.dispose();
        shell.dispose();
    }

    private Event roll(int count, int detail, int stateMask) {
        Event event = new Event();
        event.count = count;
        event.detail = detail;
        event.stateMask = stateMask;
        event.doit = true;
        text.notifyListeners(SWT.MouseVerticalWheel, event);
        return event;
    }

    @Test
    void theWheelGoesToNeovimAndTheWidgetDoesNotScroll() {
        Event event = roll(-3, SWT.SCROLL_LINE, 0);
        assertEquals(List.of(3), sent, "three lines down");
        assertFalse(event.doit, "swallowed");
        assertEquals(0, text.getTopIndex(), "the editor scrolls only when Neovim answers");
        roll(3, SWT.SCROLL_LINE, 0);
        assertEquals(List.of(3, -3), sent);
    }

    @Test
    void aPageIsTheEditorLessTwoLines() {
        roll(-1, SWT.SCROLL_PAGE, 0);
        assertEquals(List.of(38), sent);
    }

    @Test
    void insertModeAndModifiersStayEclipses() {
        forward = false;
        assertTrue(roll(-3, SWT.SCROLL_LINE, 0).doit, "insert mode: Eclipse scrolls");
        forward = true;
        assertTrue(roll(-3, SWT.SCROLL_LINE, SWT.CTRL).doit, "Ctrl+wheel zooms");
        assertTrue(roll(-3, SWT.SCROLL_LINE, SWT.SHIFT).doit, "Shift+wheel scrolls sideways");
        assertEquals(List.of(), sent);
    }

    @Test
    void afterDisposeNothingIsSent() {
        wheel.dispose();
        roll(-3, SWT.SCROLL_LINE, 0);
        assertEquals(List.of(), sent);
    }

    @Test
    void wheelScrollUsesSwtsOwnDetailValues() {
        assertEquals(SWT.SCROLL_LINE, WheelScroll.SCROLL_LINE);
        assertEquals(SWT.SCROLL_PAGE, WheelScroll.SCROLL_PAGE);
    }
}
