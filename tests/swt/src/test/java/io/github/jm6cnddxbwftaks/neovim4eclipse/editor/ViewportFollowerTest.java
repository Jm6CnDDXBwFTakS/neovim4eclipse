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
 * {@link ViewportFollower}: the text area's size in cells, and word wrap kept the same both ways,
 * with a real {@link StyledText} and fakes for the session and the editor. Neovim's side (the grid,
 * {@code 'linebreak'}) is {@code ViewportSizeTest} and {@code viewport_spec.lua}.
 */
class ViewportFollowerTest {

    private final List<String> sent = new ArrayList<>();
    private Shell shell;
    private StyledText text;
    /** What the editor allows: {@code false} plays an editor in block selection mode. */
    private boolean editorCanWrap = true;
    private ViewportFollower follower;

    @BeforeEach
    void create() {
        shell = new Shell(Display.getDefault());
        shell.setLayout(new FillLayout());
        text = new StyledText(shell, SWT.V_SCROLL | SWT.H_SCROLL);
        text.setText("one\ntwo\nthree\n");
        shell.setSize(500, 300);
        shell.layout();
        follower = new ViewportFollower(text, (columns, rows) -> sent.add("size " + columns + "x" + rows),
                wrap -> sent.add("wrap " + wrap), wrap -> {
                    if (editorCanWrap) {
                        text.setWordWrap(wrap);
                    }
                });
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
    void startSendsTheEditorsWrapThenItsSize() {
        follower.start();
        assertEquals(List.of("wrap false", size()), sent, "Eclipse's word wrap wins on attach (D2)");
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
        assertEquals(List.of(), sent);
    }
}
