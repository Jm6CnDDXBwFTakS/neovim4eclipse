// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.ctrlKey;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.escape;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PreferenceConstants;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FloatWindow;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;

/**
 * Neovim's insert-mode completion in a real editor ({@code KeyRouter}, {@code EditorKeyContext}):
 * Ctrl-N in an insert Eclipse types hands the typed text to Neovim, Neovim completes and its popup
 * menu is drawn at the word, a float of Neovim's own (window id {@code -1}, {@link #popupMenu}); while it completes every key is Neovim's,
 * the Ctrl letters Eclipse binds too; the key that ends the completion gives typing back to
 * Eclipse. The keys go through the workbench's key bindings, as a keyboard's do.
 */
class CompletionMenuEndToEndTest extends EndToEndTestBase {

    private IPreferenceStore store;

    /** The words to complete from, and an insert Eclipse types on a new third line with {@code ap} typed. */
    @BeforeEach
    void typeTheStartOfAWord() {
        store = Activator.getDefault().getPreferenceStore();
        store.setValue(PreferenceConstants.ctrlKeyInsert('n'), true); // the default; said here, since the tests rest on it
        store.setValue(PreferenceConstants.ctrlKeyInsert('p'), true);
        assertTrue(session.completesInNeovim(), "the tests' Neovim has the list on");
        document.set("apple\napricot\n");
        settle();
        typeSlowly("Go");
        waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
        type(widget, "ap");
        expectText("apple\napricot\nap\n");
    }

    @AfterEach
    void leaveTheInsert() {
        if (session.isTypingMode()) {
            escape(widget);
            waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        }
        settle();
        store.setToDefault(PreferenceConstants.ctrlKeyInsert('n'));
        store.setToDefault(PreferenceConstants.ctrlKeyInsert('p'));
        store.setToDefault(PreferenceConstants.ctrlKeyInsert('e'));
        store.setToDefault(PreferenceConstants.ctrlKeyInsert('y'));
    }

    /** The rows of {@code grid}, each without its trailing blanks and ended by a line break. */
    private static String rowsOf(GridSnapshot grid) {
        StringBuilder rows = new StringBuilder();
        for (int r = 0; grid != null && r < grid.height(); r++) {
            rows.append(grid.row(r).stripTrailing()).append('\n');
        }
        return rows.toString();
    }

    /**
     * Waits until Neovim's popup menu is drawn ({@link #popupMenu}: a float of Neovim's own, the
     * grid of window id {@code -1}, whose cells the editor copies, {@code editor/FloatingWindows})
     * with {@code rows}, and returns it.
     */
    private FloatWindow listShowing(String rows) {
        waitUntil("the list showing " + rows.replace("\n", "|"), TIMEOUT_MS, () -> {
            settle();
            FloatWindow menu = popupMenu();
            return menu != null && rows.equals(rowsOf(menu.grid()));
        }, () -> popupMenu() == null ? "; no list" : "; it shows " + rowsOf(popupMenu().grid()).replace("\n", "|"));
        return popupMenu();
    }

    private void noList() {
        waitUntil("the list gone", TIMEOUT_MS, () -> {
            settle();
            return popupMenu() == null;
        });
    }

    /**
     * The index of the selected match in Neovim's completion ({@code complete_info()}), -1 for
     * none: asked from Neovim, since the menu's colors are Neovim's highlight ids.
     */
    private int selectedMatch() {
        Object selected = SessionProbe.lua(session, "return vim.fn.complete_info({ 'selected' }).selected");
        return selected instanceof Number number ? number.intValue() : -2;
    }

    private List<?> neovimLines() {
        return (List<?>) SessionProbe.lua(session, "return vim.api.nvim_buf_get_lines(0, 0, -1, false)");
    }

    /**
     * {@code ap}, Ctrl-N: Neovim completes to the first match and its list hangs under the word.
     * Ctrl-N again takes the next match. A typed bracket ends the completion and is inserted by
     * Neovim; the key after it is Eclipse's again.
     */
    @Test
    void ctrlNCompletesInNeovimWithItsListAndTypingGoesBackToEclipse() {
        ctrlKey(widget, 'n');
        expectText("apple\napricot\napple\n");
        FloatWindow list = listShowing("apple\napricot\n");
        assertEquals(0, selectedMatch(), "the first match is selected");
        assertTrue(widget.isFocusControl(), "the editor keeps the keyboard focus");
        // Neovim places its menu itself, on the screen row under the word's (the third line's, row 2).
        assertEquals(3, list.row(), "under the line of the word");

        ctrlKey(widget, 'n');
        expectText("apple\napricot\napricot\n");
        waitUntil("the second entry selected", TIMEOUT_MS, () -> {
            settle();
            return popupMenu() != null && selectedMatch() == 1;
        }, () -> "; selected " + selectedMatch());

        type(widget, "(");
        expectText("apple\napricot\napricot(\n");
        noList();
        waitUntil("Eclipse types again", TIMEOUT_MS, session::isEclipseTypingReady);
        assertFalse(session.isCompleting());
        type(widget, "x");
        expectText("apple\napricot\napricot(x\n");
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        settle();
        expectText("apple\napricot\napricot(x\n");
        assertEquals(List.of("apple", "apricot", "apricot(x"), neovimLines(), "Neovim has the same text");
    }

    /**
     * With Ctrl-E and Ctrl-Y cleared for insert mode they are Eclipse's (Quick Switch Editor,
     * Redo). With the list open they are Neovim's all the same: Ctrl-E takes the completion back,
     * Ctrl-Y keeps the selected match.
     */
    @Test
    void whileTheListIsOpenCtrlEAndCtrlYAreNeovims() {
        store.setValue(PreferenceConstants.ctrlKeyInsert('e'), false);
        store.setValue(PreferenceConstants.ctrlKeyInsert('y'), false);
        settle();
        ctrlKey(widget, 'n');
        listShowing("apple\napricot\n");
        ctrlKey(widget, 'e');
        expectText("apple\napricot\nap\n");
        noList();
        assertTrue(session.isTypingMode(), "still in the insert");
        assertTrue(widget.isFocusControl(), "no editor switcher opened");

        ctrlKey(widget, 'p');
        expectText("apple\napricot\napricot\n");
        listShowing("apple\napricot\n");
        ctrlKey(widget, 'y');
        noList();
        waitUntil("Eclipse types again", TIMEOUT_MS, session::isEclipseTypingReady);
        expectText("apple\napricot\napricot\n");
        type(widget, "s");
        expectText("apple\napricot\napricots\n");
    }

    /** Esc with the list open leaves insert mode at once, as in Vim, with the match that was inserted. */
    @Test
    void escWithTheListOpenLeavesInsertModeAndKeepsTheMatch() {
        ctrlKey(widget, 'n');
        listShowing("apple\napricot\n");
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        noList();
        settle();
        expectText("apple\napricot\napple\n");
        assertEquals(List.of("apple", "apricot", "apple"), neovimLines());
    }

    /**
     * After Esc, {@code .} repeats the insert as one: what Eclipse typed and what Neovim completed.
     */
    @Test
    void dotRepeatsTheTypedTextAndItsCompletion() {
        ctrlKey(widget, 'n');
        listShowing("apple\napricot\n");
        ctrlKey(widget, 'y');
        noList();
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        settle();
        typeSlowly(".");
        expectText("apple\napricot\napple\napple\n");
    }

    /** A click into the text ends the completion: the list goes, the match stays, and Eclipse types again. */
    @Test
    void aClickIntoTheTextEndsTheCompletion() {
        ctrlKey(widget, 'n');
        listShowing("apple\napricot\n");
        Event click = new Event();
        click.button = 1;
        click.count = 1;
        Point at = widget.getLocationAtOffset(widget.getOffsetAtLine(2));
        click.x = at.x + 1;
        click.y = at.y + 1;
        widget.notifyListeners(SWT.MouseDown, click);
        widget.notifyListeners(SWT.MouseUp, click);
        noList();
        waitUntil("the completion over", TIMEOUT_MS, () -> !session.isCompleting());
        assertTrue(session.isTypingMode(), "still in the insert");
        waitUntil("Eclipse types again", TIMEOUT_MS, session::isEclipseTypingReady);
        expectText("apple\napricot\napple\n");
    }

    /**
     * Eclipse changes the document by itself while Neovim completes (a paste with the mouse, an
     * accepted content-assist proposal): the change is not Neovim's typing, so it must reach
     * Neovim, or the text Neovim sends at the completion's end would take it away again.
     */
    @Test
    void anEditEclipseMakesWhileNeovimCompletesIsKept() throws Exception {
        ctrlKey(widget, 'n');
        listShowing("apple\napricot\n");
        document.replace(0, 0, "pasted ");
        settle();
        type(widget, "(");
        noList();
        waitUntil("Eclipse types again", TIMEOUT_MS, session::isEclipseTypingReady);
        expectText("pasted apple\napricot\napple(\n");
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        settle();
        expectText("pasted apple\napricot\napple(\n");
        assertEquals(List.of("pasted apple", "apricot", "apple("), neovimLines(), "Neovim has the same text");
    }

    /**
     * The same on the line being completed (a proposal accepted at the caret): Neovim's completion
     * remembers that line as it was, so the change ends the completion. Ctrl-N after it starts a
     * new one and takes nothing away.
     */
    @Test
    void anEditOnTheCompletedLineEndsTheCompletionAndIsKept() throws Exception {
        ctrlKey(widget, 'n');
        listShowing("apple\napricot\n");
        int end = document.getLength() - 1; // after "apple" on the third line
        document.replace(end, 0, ".foo()");
        noList();
        waitUntil("the completion over", TIMEOUT_MS, () -> !session.isCompleting());
        expectText("apple\napricot\napple.foo()\n");
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        settle();
        expectText("apple\napricot\napple.foo()\n");
        assertEquals(List.of("apple", "apricot", "apple.foo()"), neovimLines(), "Neovim has the same text");
    }
}
