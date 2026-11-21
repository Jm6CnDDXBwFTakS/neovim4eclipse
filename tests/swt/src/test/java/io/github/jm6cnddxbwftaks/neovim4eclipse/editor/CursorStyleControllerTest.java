// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * Hidden while Neovim's cursor is in a plug-in's window: the widget has no caret then, also
     * after a mode change and a scroll (which would make a merely invisible caret visible again);
     * shown again in the mode's shape, the same caret.
     */
    @Test
    void aHiddenCaretIsOffTheWidgetUntilShownAgainInTheModesShape() {
        CursorStyleController style = new CursorStyleController(text);
        Caret caret = text.getCaret();
        int block = caret.getSize().x;
        style.setHidden(true);
        assertNull(text.getCaret(), "no caret: a StyledText without one draws none");
        assertTrue(style.isHidden());
        style.updateForMode("i"); // the prompt's insert
        text.setText("alpha\n".repeat(50));
        text.setSize(200, 60);
        text.setTopIndex(10); // a scroll, which shows a merely invisible caret again
        text.setSize(220, 80); // and a resize
        assertNull(text.getCaret(), "a mode change does not bring it back");

        style.setHidden(false);
        assertSame(caret, text.getCaret(), "the same caret back");
        assertFalse(style.isHidden());
        assertTrue(caret.getSize().x < block, "thin: the mode Neovim is in now");
        style.dispose();
    }

    /** Detach Neovim while hidden: the caret goes back, with Eclipse's width. */
    @Test
    void disposeWhileHiddenGivesEclipsesCaretBack() {
        CursorStyleController style = new CursorStyleController(text);
        style.setHidden(true);
        style.dispose();
        assertSame(eclipses, text.getCaret());
        assertEquals(eclipsesWidth, eclipses.getSize().x);
    }

    /** Eclipse sets a new caret while hidden, and the float list comes again: the new one is hidden too. */
    @Test
    void aCaretEclipseSetWhileHiddenIsHiddenAtTheNextHide() {
        CursorStyleController style = new CursorStyleController(text);
        style.setHidden(true);
        Caret fresh = new Caret(text, SWT.NONE);
        text.setCaret(fresh);
        style.setHidden(true); // the prompt redrawn
        assertNull(text.getCaret());
        style.setHidden(false);
        assertSame(fresh, text.getCaret(), "the newest caret back, Eclipse's current one");
        style.dispose();
    }

    /** Eclipse sets a new caret while hidden (the Insert key): that one stays, in the mode's shape. */
    @Test
    void aCaretEclipseSetWhileHiddenStays() {
        CursorStyleController style = new CursorStyleController(text);
        style.setHidden(true);
        Caret fresh = new Caret(text, SWT.NONE);
        text.setCaret(fresh);
        style.setHidden(false);
        assertSame(fresh, text.getCaret());
        assertTrue(fresh.getSize().x > eclipsesWidth, "the block of normal mode");
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
