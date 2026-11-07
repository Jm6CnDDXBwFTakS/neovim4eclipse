// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    /**
     * Eclipse replaces the caret while Neovim has the editor (AbstractTextEditor.updateCaret, on an
     * insert/overwrite toggle): dispose leaves that caret, alive, instead of putting the stale first
     * one back and disposing Eclipse's current one.
     */
    @Test
    void disposeLeavesACaretEclipseSetAfterTheLastChange() {
        CursorStyleController style = new CursorStyleController(text);
        Caret replaced = new Caret(text, SWT.NONE);
        replaced.setSize(3, text.getLineHeight());
        text.setCaret(replaced);

        style.dispose();
        assertSame(replaced, text.getCaret(), "Eclipse's current caret stays");
        assertFalse(replaced.isDisposed());
        assertEquals(3, replaced.getSize().x, "with Eclipse's width");
    }

    /** A caret Eclipse set, then changed by Neovim's mode: dispose gives it back its own width. */
    @Test
    void disposeGivesAReplacedCaretBackItsWidth() {
        CursorStyleController style = new CursorStyleController(text);
        Caret replaced = new Caret(text, SWT.NONE);
        replaced.setSize(3, text.getLineHeight());
        text.setCaret(replaced);
        style.updateForMode("n");
        assertTrue(replaced.getSize().x > 3, "the block");

        style.dispose();
        assertSame(replaced, text.getCaret());
        assertFalse(replaced.isDisposed());
        assertEquals(3, replaced.getSize().x);
    }

    /**
     * A widget without a caret: the controller makes one for Neovim's block. Dispose leaves that
     * one (a StyledText without a caret shows none) but thin, as Eclipse's caret is, not the block.
     */
    @Test
    void disposeMakesTheCaretItCreatedThinAgain() {
        text.setCaret(null);
        CursorStyleController style = new CursorStyleController(text);
        Caret own = text.getCaret();
        assertTrue(own != null && own.getSize().x > eclipsesWidth, "a block made by the controller");

        style.dispose();
        assertSame(own, text.getCaret(), "still a caret to see");
        assertFalse(own.isDisposed());
        assertEquals(eclipsesWidth, own.getSize().x, "as thin as StyledText's own caret");
        assertEquals(CursorStyleController.ECLIPSE_THIN_WIDTH, own.getSize().x);
    }

    /** Eclipse's caret disposed while Neovim had the editor: the controller's own is thin again too. */
    @Test
    void disposeMakesItsCaretEclipsesWidthWhenEclipsesIsGone() {
        CursorStyleController style = new CursorStyleController(text);
        text.setCaret(null);
        eclipses.dispose();
        style.updateForMode("n"); // makes a caret of its own
        Caret own = text.getCaret();
        assertTrue(own.getSize().x > eclipsesWidth);

        style.dispose();
        assertSame(own, text.getCaret());
        assertEquals(eclipsesWidth, own.getSize().x, "the width Eclipse's caret had");
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
