// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;

/**
 * Neovim's command-line window ({@code q:}) in a real editor: drawn over the editor's bottom
 * ({@link NeovimWindowPanel}), every key Neovim's, Enter running the line, the focus elsewhere
 * closing it without running anything, {@code Ctrl-F} on the {@code :} box opening it.
 */
class CommandWindowEndToEndTest extends EndToEndTestBase {

    @AfterEach
    void closeTheWindow() {
        session.closeCommandWindowAsync(); // a failed test leaves no window for the next one
        waitUntil("the command-line window closed", TIMEOUT_MS, () -> !session.isCommandWindowOpen());
        settle();
        SessionProbe.lua(session, "vim.g.n4e_cmdwin = nil");
    }

    /** The panel's canvas below the editor's widget, or {@code null} when it is closed. */
    private static GridCanvas canvasOf(Composite parent) {
        for (Control child : parent.getChildren()) {
            if (child instanceof GridCanvas canvas && !canvas.isDisposed()) {
                return canvas;
            }
            if (child instanceof Composite composite) {
                GridCanvas found = canvasOf(composite);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Waits for the panel to show a row holding {@code text}, and returns its canvas. */
    private GridCanvas panelShowing(String text) {
        waitUntil("the command-line window showing " + text, TIMEOUT_MS, () -> {
            settle();
            GridCanvas canvas = canvasOf(widget);
            return canvas != null && rowsOf(canvas.grid()).contains(text);
        }, () -> {
            GridCanvas canvas = canvasOf(widget);
            return canvas == null ? "; no panel" : "; it shows " + rowsOf(canvas.grid());
        });
        return canvasOf(widget);
    }

    private static String rowsOf(GridSnapshot grid) {
        if (grid == null) {
            return "";
        }
        StringBuilder rows = new StringBuilder();
        for (int r = 0; r < grid.height(); r++) {
            rows.append(grid.row(r).stripTrailing()).append('\n');
        }
        return rows.toString();
    }

    private static void key(Control control, int keyCode, char character, int stateMask) {
        Event event = new Event();
        event.keyCode = keyCode;
        event.character = character;
        event.stateMask = stateMask;
        event.doit = true;
        control.notifyListeners(SWT.KeyDown, event);
    }

    private static void keys(Control control, String text) {
        for (char c : text.toCharArray()) {
            key(control, Character.isLetter(c) ? Character.toLowerCase(c) : c, c, Character.isUpperCase(c) ? SWT.SHIFT : 0);
        }
    }

    private Object g(String name) {
        return SessionProbe.lua(session, "return vim.g." + name);
    }

    @Test
    void qColonShowsTheHistoryAndEnterRunsTheLineUnderTheCursor() {
        SessionProbe.lua(session, "vim.fn.histadd(':', 'let g:n4e_cmdwin = 1')");
        type(widget, "q:");
        GridCanvas canvas = panelShowing("let g:n4e_cmdwin = 1");
        UiTestSupport.requireKeyboardFocus(canvas, TIMEOUT_MS);
        keys(canvas, "k");
        key(canvas, SWT.CR, SWT.CR, 0);
        waitUntil("the line ran", TIMEOUT_MS, () -> Long.valueOf(1).equals(g("n4e_cmdwin")), () -> "; g:n4e_cmdwin " + g("n4e_cmdwin"));
        waitUntil("the panel closed", TIMEOUT_MS, () -> {
            settle();
            return canvasOf(widget) == null;
        });
        waitUntil("the editor has the focus back", TIMEOUT_MS, widget::isFocusControl);
    }

    @Test
    void insertModeInTheWindowTypesIntoNeovimNotTheEditor() {
        String document = widget.getText();
        type(widget, "q:");
        GridCanvas canvas = panelShowing("");
        UiTestSupport.requireKeyboardFocus(canvas, TIMEOUT_MS);
        keys(canvas, "iabc");
        panelShowing("abc");
        assertEquals("i", session.getMode(), "Neovim's insert, in its window");
        assertEquals(document, widget.getText(), "nothing typed into the editor");
    }

    @Test
    void theFocusInTheEditorClosesTheWindowAndRunsNothing() {
        type(widget, "q:");
        GridCanvas canvas = panelShowing("");
        UiTestSupport.requireKeyboardFocus(canvas, TIMEOUT_MS);
        keys(canvas, "ilet g:n4e_cmdwin = 2");
        panelShowing("let g:n4e_cmdwin = 2");
        UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS); // a click into the editor
        waitUntil("the window closed", TIMEOUT_MS, () -> {
            settle();
            return !session.isCommandWindowOpen() && canvasOf(widget) == null;
        });
        assertEquals(null, g("n4e_cmdwin"), "the line did not run");
        waitUntil("normal mode", TIMEOUT_MS, () -> "n".equals(session.getMode()));
    }

    @Test
    void ctrlFOnTheColonBoxOpensTheWindowWithItsLine() {
        type(widget, ":");
        waitUntil("the : box", TIMEOUT_MS, () -> textChildOf(widget) != null);
        Text box = textChildOf(widget);
        UiTestSupport.requireKeyboardFocus(box, TIMEOUT_MS);
        box.insert("echo 42");
        waitUntil("Neovim has the line", TIMEOUT_MS, () -> {
            settle();
            Text open = textChildOf(widget);
            return open != null && ":echo 42".equals(open.getText());
        });
        // In the box, Vim's Ctrl keys come through Eclipse's key bindings (SendKeyHandler), as here.
        EditorAttachManager.getInstance().getActiveController().sendKey("<C-f>");
        GridCanvas canvas = panelShowing("echo 42");
        waitUntil("the : box closed", TIMEOUT_MS, () -> {
            settle();
            return textChildOf(widget) == null;
        });
        UiTestSupport.requireKeyboardFocus(canvas, TIMEOUT_MS);
    }
}
