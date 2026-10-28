// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ViewportFollower}: the text area's size in cells, word wrap kept the same both ways, and
 * the top line taken from Neovim, with a real {@link StyledText} and fakes for the session and the
 * editor (whose lines are the widget's here: nothing is folded). Neovim's side (the grid,
 * {@code 'linebreak'}) is {@code ViewportSizeTest} and {@code viewport_spec.lua}.
 */
class ViewportFollowerTest {

    private final List<String> sent = new ArrayList<>();
    private Shell shell;
    private StyledText text;
    /** What the editor allows: {@code false} plays an editor in block selection mode. */
    private boolean editorCanWrap = true;
    private ViewportFollower follower;
    /** How often the editor was scrolled by the follower. */
    private int scrolls;

    @BeforeEach
    void create() {
        shell = new Shell(Display.getDefault());
        shell.setLayout(new FillLayout());
        text = new StyledText(shell, SWT.V_SCROLL | SWT.H_SCROLL);
        text.setText("one\ntwo\nthree\n");
        shell.setSize(500, 300);
        shell.layout();
        follower = new ViewportFollower(text, new ViewportFollower.Neovim() {
            @Override
            public void sendSize(int columns, int rows) {
                sent.add("size " + columns + "x" + rows);
            }

            @Override
            public void sendWrap(boolean wrap) {
                sent.add("wrap " + wrap);
            }

            @Override
            public void sendTopLine(int line1) {
                sent.add("top " + line1);
            }

            @Override
            public void sendScroll(int line1) {
                sent.add("scroll " + line1);
            }
        }, new ViewportFollower.Editor() {
            @Override
            public void setWordWrap(boolean wrap) {
                if (editorCanWrap) {
                    text.setWordWrap(wrap);
                }
            }

            @Override
            public int topLine() {
                return text.getTopIndex();
            }

            @Override
            public void setTopLine(int line0) {
                scrolls++;
                text.setTopIndex(line0);
            }
        });
    }

    /** A document of {@code count} lines, "line 1" to "line {count}". */
    private void lines(int count) {
        StringBuilder lines = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            lines.append("line ").append(i).append('\n');
        }
        text.setText(lines.toString());
    }

    @AfterEach
    void dispose() {
        follower.close();
        shell.dispose();
    }

    private String size() {
        int[] cells = ViewportFollower.cells(text, ViewportFollower.averageCharWidth(text));
        return "size " + cells[0] + "x" + cells[1];
    }

    private void paint() {
        text.notifyListeners(SWT.Paint, new Event());
    }

    @Test
    void startSendsTheEditorsWrapThenItsSizeThenItsTopLine() {
        lines(200);
        text.setTopIndex(30);
        follower.start();
        assertEquals(List.of("wrap false", size(), "top 31"), sent,
                "Eclipse's word wrap wins on attach (D2); the top line needs the window's height");
    }

    @Test
    void neovimsTopLineScrollsTheEditorOnce() {
        lines(200);
        follower.start();
        follower.onNeovimTopLine(40);
        assertEquals(39, text.getTopIndex(), "line 40 at the top");
        follower.onNeovimTopLine(40); // the cursor report and WinScrolled carry the same line
        assertEquals(1, scrolls, "already there: no second scroll");
    }

    @Test
    void theEditorsOwnScrollIsSentOnceAtThePaint() {
        lines(200);
        follower.start();
        sent.clear();
        text.setTopIndex(70); // the scroll bar, or Eclipse revealing a search hit
        paint();
        paint();
        assertEquals(List.of("scroll 71"), sent, "Neovim drags its cursor into the editor's view");
        assertEquals(71, follower.eclipseTopLine(), "what Esc sends to Neovim");
    }

    @Test
    void neovimsTopLineIsNotSentBackAsAScroll() {
        lines(200);
        follower.start();
        sent.clear();
        follower.onNeovimTopLine(40);
        paint();
        assertEquals(List.of(), sent, "no echo");
    }

    @Test
    void aTopLineBeforeStartIsIgnored() {
        lines(200);
        follower.onNeovimTopLine(40);
        assertEquals(0, text.getTopIndex());
        follower.onNeovimTopLine(0); // not a line
        assertEquals(0, scrolls);
    }

    @Test
    void aLargerEditorIsSentOnceAndTheSameSizeNotAgain() {
        follower.start();
        sent.clear();
        shell.setSize(800, 600);
        shell.layout();
        assertEquals(List.of(size()), sent);
        paint();
        assertEquals(1, sent.size(), "a paint at the same size sends nothing");
    }

    @Test
    void wordWrapToggledInEclipseIsSentAtTheNextPaint() {
        follower.start();
        sent.clear();
        text.setWordWrap(true); // what Alt+Shift+Y ends in
        paint();
        assertEquals("wrap true", sent.get(0));
        sent.clear();
        paint();
        assertEquals(List.of(), sent);
    }

    @Test
    void neovimsWrapBecomesTheEditorsWithoutComingBack() {
        follower.start();
        sent.clear();
        follower.onNeovimWrap(true);
        assertTrue(text.getWordWrap());
        paint();
        assertFalse(sent.contains("wrap true"), "no echo: " + sent);
    }

    @Test
    void anEditorThatCannotWrapTellsNeovimSo() {
        follower.start();
        sent.clear();
        editorCanWrap = false;
        follower.onNeovimWrap(true);
        assertFalse(text.getWordWrap());
        assertEquals("wrap false", sent.get(0), "Eclipse's state goes back to Neovim");
    }

    @Test
    void theCellsAreWholeCharactersAndWholeLinesInsideTheMargins() {
        text.setMargins(10, 0, 10, 0);
        Rectangle area = text.getClientArea();
        double charWidth = (area.width - 20) / 80.0;
        int[] cells = ViewportFollower.cells(text, charWidth);
        assertEquals(80, cells[0]);
        assertEquals(area.height / text.getLineHeight(), cells[1], "a partly visible last line does not count");
        assertEquals(1, ViewportFollower.cells(text, 0)[0], "no character width yet: one column");
        text.setMargins(area.width, area.height, 0, 0); // margins that leave no room (a window manager may not shrink the shell)
        assertArrayEquals(new int[] { 1, 1 }, ViewportFollower.cells(text, charWidth), "never below one cell");
    }

    @Test
    void afterCloseNothingIsSent() {
        follower.start();
        follower.close();
        sent.clear();
        shell.setSize(900, 700);
        shell.layout();
        text.setWordWrap(true);
        paint();
        follower.onNeovimWrap(false);
        follower.onNeovimTopLine(2);
        assertEquals(List.of(), sent);
        assertEquals(0, scrolls);
    }
}
