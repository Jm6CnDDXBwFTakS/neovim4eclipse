// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Caret;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The caret's shape for Neovim's mode ({@link CursorStyleController}) on a real {@link StyledText}:
 * a block in normal mode, thin in insert mode, and Eclipse's own caret again once Neovim leaves the
 * editor (Detach Neovim, a restart, another page of a multi-page editor).
 */
class CursorStyleControllerTest {

    private Shell shell;
    private StyledText text;
    private Caret eclipses;
    private int eclipsesWidth;

    @BeforeEach
    void create() {
        shell = new Shell(Display.getDefault());
        text = new StyledText(shell, SWT.MULTI);
        text.setText("alpha\n");
        eclipses = text.getCaret();
        eclipsesWidth = eclipses.getSize().x;
    }

    @AfterEach
    void dispose() {
        shell.dispose();
    }

    @Test
    void normalModeGivesABlockAndInsertModeAThinCaret() {
        CursorStyleController style = new CursorStyleController(text);
        int block = text.getCaret().getSize().x;
        assertTrue(block > eclipsesWidth, "a block, wider than Eclipse's caret: " + block);

        style.updateForMode("i");
        assertTrue(text.getCaret().getSize().x < block, "thin in insert mode");
        style.dispose();
    }

    @Test
    void disposeGivesEclipsesCaretBack() {
        CursorStyleController style = new CursorStyleController(text);
        style.updateForMode("n");
        style.dispose();
        assertEquals(eclipses, text.getCaret());
        assertEquals(eclipsesWidth, text.getCaret().getSize().x, "Eclipse's own width, not the block");

        style.updateForMode("n");
        assertEquals(eclipsesWidth, text.getCaret().getSize().x, "no change after dispose");
    }

    @Test
    void disposeFromAnotherThreadGivesItBackOnTheUiThread() throws InterruptedException {
        CursorStyleController style = new CursorStyleController(text);
        Thread other = new Thread(style::dispose, "neovim-input");
        other.start();
        other.join();
        Display display = Display.getDefault();
        while (display.readAndDispatch()) {
            // run the queued restore
        }
        assertEquals(eclipsesWidth, text.getCaret().getSize().x);
    }
}
