// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The overlays a text editor shows (the message line, the frame of the {@code :} box) over a
 * page editor's {@link GridCanvas} instead: a page shown from a window of the editing Neovim has
 * no {@code StyledText}, and its messages and command line go over its grid. Sized by the grid's
 * row, not a text line.
 */
class OverlayOverPageTest {

    private static final int CELL_HEIGHT = 17;

    private Shell shell;
    private GridCanvas page;

    @BeforeEach
    void createPage() {
        shell = new Shell(Display.getDefault());
        shell.setSize(400, 300);
        Font font = shell.getFont();
        page = new GridCanvas(shell, new GridCanvas.Look() {
            @Override public Font font() { return font; }
            @Override public int foreground() { return 0x202020; }
            @Override public int background() { return 0xf0f0f0; }
            @Override public int lineHeight() { return CELL_HEIGHT; }
        }, SWT.NONE);
        page.setBounds(0, 0, 400, 300);
        shell.open();
    }

    @AfterEach
    void dispose() {
        shell.dispose();
    }

    @Test
    void anOverlayIsSizedByThePagesRow() {
        assertEquals(page.cellHeight(), OverlayFrame.lineHeight(page));
        assertTrue(OverlayFrame.bottomGap(page) > 0);
    }

    @Test
    void aMessageShowsAlongTheBottomOfThePage() {
        MessageLine line = new MessageLine(page, () -> false);
        try {
            line.show("3 lines yanked", false);
            assertTrue(line.isShowing());
            assertEquals("3 lines yanked", line.frame().content().getText());
            Rectangle bounds = line.frame().control().getBounds();
            Rectangle area = page.getClientArea();
            assertTrue(bounds.y + bounds.height <= area.y + area.height, "inside the page: " + bounds);
            assertTrue(bounds.y > area.height / 2, "along its bottom: " + bounds);
        } finally {
            line.dispose();
        }
    }

    @Test
    void aFloatOverThePageTakesThePagesLook() {
        assertEquals(CELL_HEIGHT, GridCanvas.lookOf(page).lineHeight());
    }
}
